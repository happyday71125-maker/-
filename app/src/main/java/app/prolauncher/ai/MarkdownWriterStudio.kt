package app.prolauncher.ai
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.speech.RecognizerIntent
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

data class WriterDoc(
    val id: Long,
    val title: String,
    val content: String,
    val updatedAt: String,
    val folder: String = "기본 폴더"
)

fun loadWriterDocs(ctx: Context): List<WriterDoc> = runCatching {
    val raw = ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).getString("writer_docs_v1", null) ?: return emptyList()
    val arr = JSONArray(raw)
    buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(WriterDoc(o.getLong("id"), o.optString("title", "제목 없음"), o.optString("content", ""), o.optString("updatedAt", ""), o.optString("folder", "기본 폴더").ifBlank { "기본 폴더" }))
        }
    }
}.getOrElse { emptyList() }

fun saveWriterDocs(ctx: Context, docs: List<WriterDoc>) {
    val arr = JSONArray()
    docs.forEach { d ->
        arr.put(JSONObject().put("id", d.id).put("title", d.title).put("content", d.content).put("updatedAt", d.updatedAt).put("folder", d.folder.ifBlank { "기본 폴더" }))
    }
    ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).edit().putString("writer_docs_v1", arr.toString()).apply()
}

@Composable
fun MarkdownGuideContent(onInsertSample: ((String) -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("✍️ 기본 마크다운(Markdown) 작성 문법 안내", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CG)
        Text(
            "• 제목 단계: 줄 맨 앞에 # 대제목 / ## 중제목 / ### 소제목 (샵 뒤에 공백 1칸)\n" +
                "• 굵은 글씨: **강조할 문구** 양옆을 별표 2개로 감쌉니다.\n" +
                "• 기울임꼴: *기울일 문구* 양옆을 별표 1개로 감쌉니다.\n" +
                "• 취소선: ~~지울 문구~~ 양옆을 물결표 2개로 감쌉니다.\n" +
                "• 글머리 목록: 줄 앞에 - 항목 또는 * 항목 입력 (엔터 시 자동 이어짐)\n" +
                "• 번호 목록: 줄 앞에 1. 첫째 항목 / 2. 둘째 항목 입력\n" +
                "• 체크리스트: 줄 앞에 - [ ] 할 일 (미완료) / - [x] 완료 항목\n" +
                "• 인용문 박스: 줄 앞에 > 인용할 문장 입력\n" +
                "• 인라인 코드: `변수명` 처럼 백틱(`) 1개로 감쌉니다.\n" +
                "• 코드 블록: 위아래 줄에 ``` (백틱 3개)를 넣어 여러 줄 코드를 감쌉니다.\n" +
                "• 가로 구분선: 빈 줄에 --- (하이픈 3개) 입력\n" +
                "• 글자 크기: 상단 [🔤포인트] 또는 툴바 [🔤-/🔤+]로 12~32pt 조절",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = CW
        )
        if (onInsertSample != null) {
            OutlinedButton(
                onClick = {
                    onInsertSample(
                        "# 문서 제목\n\n## 1. 핵심 요약\n여기에 **중요한 내용**과 *보조 설명*, `핵심 키워드`를 작성합니다.\n\n" +
                            "- 첫 번째 요점\n- 두 번째 요점\n- [ ] 점검할 체크리스트\n- [x] 완료된 항목\n\n" +
                            "> 인용문이나 기억할 문장을 깔끔하게 강조할 수 있습니다.\n\n---\n\n## 2. 세부 메모\n자유롭게 이어서 작성해 보세요."
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📋 마크다운 예시 템플릿 본문에 넣기", fontSize = 12.sp, color = CA)
            }
        }
    }
}

fun buildInlineMarkdownAnnotated(raw: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = raw.length
    while (i < n) {
        when {
            raw.startsWith("**", i) -> {
                val end = raw.indexOf("**", i + 2)
                if (end > i + 2) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFFBBF24)))
                    append(raw.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else { append(raw[i]); i++ }
            }
            raw.startsWith("~~", i) -> {
                val end = raw.indexOf("~~", i + 2)
                if (end > i + 2) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = CM))
                    append(raw.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else { append(raw[i]); i++ }
            }
            raw[i] == '`' -> {
                val end = raw.indexOf('`', i + 1)
                if (end > i + 1) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF1E293B), color = Color(0xFF6EE7B7)))
                    append(" " + raw.substring(i + 1, end) + " ")
                    pop()
                    i = end + 1
                } else { append(raw[i]); i++ }
            }
            raw[i] == '*' && (i + 1 < n && raw[i + 1] != ' ') -> {
                val end = raw.indexOf('*', i + 1)
                if (end > i + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFFBAE6FD)))
                    append(raw.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else { append(raw[i]); i++ }
            }
            else -> {
                pushStyle(SpanStyle(color = baseColor))
                append(raw[i])
                pop()
                i++
            }
        }
    }
}

@Composable
fun MarkdownRenderedView(
    markdown: String,
    bodySp: Float = 16f,
    onToggleTaskLine: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val lines = remember(markdown) { markdown.lines() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (markdown.isBlank()) {
            Text("작성된 내용이 없습니다. 편집 탭에서 마크다운 문서를 작성해 보세요.", fontSize = bodySp.sp, color = CM)
            return@Column
        }
        var idx = 0
        while (idx < lines.size) {
            val ln = lines[idx]
            val tr = ln.trim()
            if (tr.startsWith("```")) {
                val lang = tr.removePrefix("```").trim()
                val codeBuf = mutableListOf<String>()
                idx++
                while (idx < lines.size && !lines[idx].trim().startsWith("```")) {
                    codeBuf.add(lines[idx])
                    idx++
                }
                Surface(
                    color = Color(0xFF090D16),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.8.dp, Color(0xFF24324B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (lang.isNotEmpty()) Text(lang, fontSize = (bodySp - 3f).coerceAtLeast(10f).sp, fontWeight = FontWeight.Bold, color = CG)
                        Text(
                            codeBuf.joinToString("\n"),
                            fontSize = (bodySp - 1.5f).coerceAtLeast(11f).sp,
                            lineHeight = ((bodySp - 1.5f).coerceAtLeast(11f) * 1.45f).sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFE2E8F0)
                        )
                    }
                }
                idx++
                continue
            }
            when {
                tr.isEmpty() -> Spacer(Modifier.height(2.dp))
                tr == "---" || tr == "***" || tr == "___" -> {
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Color(0xFF2B3B55)))
                }
                tr.startsWith("# ") -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(buildInlineMarkdownAnnotated(tr.removePrefix("# ").trim(), CW), fontSize = (bodySp + 7f).sp, lineHeight = ((bodySp + 7f) * 1.32f).sp, fontWeight = FontWeight.ExtraBold)
                        Box(Modifier.fillMaxWidth().height(1.2.dp).background(CG.copy(alpha = 0.45f)))
                    }
                }
                tr.startsWith("## ") -> {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(buildInlineMarkdownAnnotated(tr.removePrefix("## ").trim(), CW), fontSize = (bodySp + 4f).sp, lineHeight = ((bodySp + 4f) * 1.35f).sp, fontWeight = FontWeight.Bold)
                        Box(Modifier.fillMaxWidth().height(0.8.dp).background(Color(0xFF25344E)))
                    }
                }
                tr.startsWith("### ") -> {
                    Text(buildInlineMarkdownAnnotated(tr.removePrefix("### ").trim(), Color(0xFFA7F3D0)), fontSize = (bodySp + 2f).sp, lineHeight = ((bodySp + 2f) * 1.38f).sp, fontWeight = FontWeight.Bold)
                }
                tr.startsWith("#### ") -> {
                    Text(buildInlineMarkdownAnnotated(tr.removePrefix("#### ").trim(), CA), fontSize = (bodySp + 0.5f).sp, lineHeight = ((bodySp + 0.5f) * 1.4f).sp, fontWeight = FontWeight.SemiBold)
                }
                tr.startsWith("> ") || tr == ">" -> {
                    Row(Modifier.fillMaxWidth().background(Color(0xFF10192B), RoundedCornerShape(8.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height((bodySp + 6f).dp).background(CG))
                        Spacer(Modifier.width(8.dp))
                        Text(buildInlineMarkdownAnnotated(tr.removePrefix(">").trim(), Color(0xFFD1FAE5)), fontSize = bodySp.sp, lineHeight = (bodySp * 1.5f).sp)
                    }
                }
                tr.startsWith("- [ ] ") || tr.startsWith("- [x] ") || tr.startsWith("- [X] ") -> {
                    val done = !tr.startsWith("- [ ] ")
                    val itemTx = tr.substring(6).trim()
                    val lineCopy = idx
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (onToggleTaskLine != null) Modifier.clickable { onToggleTaskLine(lineCopy) } else Modifier)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(if (done) "☑" else "☐", fontSize = bodySp.sp, fontWeight = FontWeight.Bold, color = if (done) CG else CA)
                        Text(
                            buildInlineMarkdownAnnotated(itemTx, if (done) CM else CW),
                            fontSize = bodySp.sp,
                            lineHeight = (bodySp * 1.48f).sp,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                tr.startsWith("- ") || tr.startsWith("* ") -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", fontSize = bodySp.sp, fontWeight = FontWeight.Bold, color = CG)
                        Text(buildInlineMarkdownAnnotated(tr.substring(2).trim(), CW), fontSize = bodySp.sp, lineHeight = (bodySp * 1.48f).sp, modifier = Modifier.weight(1f))
                    }
                }
                Regex("^\\d+\\.\\s+.*").matches(tr) -> {
                    val num = tr.substringBefore(".") + "."
                    val rest = tr.substringAfter(".").trim()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(num, fontSize = bodySp.sp, fontWeight = FontWeight.Bold, color = CA)
                        Text(buildInlineMarkdownAnnotated(rest, CW), fontSize = bodySp.sp, lineHeight = (bodySp * 1.48f).sp, modifier = Modifier.weight(1f))
                    }
                }
                else -> {
                    Text(buildInlineMarkdownAnnotated(ln, CW), fontSize = bodySp.sp, lineHeight = (bodySp * 1.52f).sp)
                }
            }
            idx++
        }
    }
}

fun buildPdfSpannable(raw: String): SpannableStringBuilder {
    val sb = SpannableStringBuilder()
    var i = 0
    val n = raw.length
    while (i < n) {
        when {
            raw.startsWith("**", i) -> {
                val end = raw.indexOf("**", i + 2)
                if (end > i + 2) {
                    val st = sb.length
                    sb.append(raw.substring(i + 2, end))
                    sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                } else { sb.append(raw[i]); i++ }
            }
            raw.startsWith("~~", i) -> {
                val end = raw.indexOf("~~", i + 2)
                if (end > i + 2) {
                    val st = sb.length
                    sb.append(raw.substring(i + 2, end))
                    sb.setSpan(StrikethroughSpan(), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                } else { sb.append(raw[i]); i++ }
            }
            raw[i] == '`' -> {
                val end = raw.indexOf('`', i + 1)
                if (end > i + 1) {
                    val st = sb.length
                    sb.append(" " + raw.substring(i + 1, end) + " ")
                    sb.setSpan(TypefaceSpan("monospace"), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(0xFFEFF3F8.toInt()), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                } else { sb.append(raw[i]); i++ }
            }
            raw[i] == '*' && (i + 1 < n && raw[i + 1] != ' ') -> {
                val end = raw.indexOf('*', i + 1)
                if (end > i + 1) {
                    val st = sb.length
                    sb.append(raw.substring(i + 1, end))
                    sb.setSpan(StyleSpan(Typeface.ITALIC), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                } else { sb.append(raw[i]); i++ }
            }
            else -> {
                sb.append(raw[i])
                i++
            }
        }
    }
    return sb
}

suspend fun exportMarkdownToPdf(ctx: Context, uri: Uri, title: String, markdown: String, writerFontSp: Int = 16): String = withContext(Dispatchers.IO) {
    runCatching {
        val scale = (writerFontSp / 16f).coerceIn(0.75f, 1.75f)
        val pageW = 595
        val pageH = 842
        val mLeft = 46f
        val mRight = 46f
        val mTop = 50f
        val mBottom = 52f
        val contentW = (pageW - mLeft - mRight).toInt()
        val maxY = pageH - mBottom
        val pdf = PdfDocument()
        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
        var page = pdf.startPage(pageInfo)
        var canvas: Canvas = page.canvas
        var curY = mTop
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCBD5E1.toInt(); strokeWidth = 1f }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF10B981.toInt(); strokeWidth = 3f }
        val codeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1F5F9.toInt(); style = Paint.Style.FILL }
        val footPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF64748B.toInt(); textSize = 9.5f; textAlign = Paint.Align.CENTER }
        fun drawFooter(c: Canvas, pNo: Int) {
            c.drawText("- $pNo -", pageW / 2f, pageH - 24f, footPaint)
        }
        fun nextPage() {
            drawFooter(canvas, pageNum)
            pdf.finishPage(page)
            pageNum++
            pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
            page = pdf.startPage(pageInfo)
            canvas = page.canvas
            curY = mTop
        }
        fun drawStyledBlock(charSeq: CharSequence, textSize: Float, bold: Boolean = false, colorInt: Int = 0xFF111827.toInt(), indent: Float = 0f, isQuote: Boolean = false, isCode: Boolean = false, underlineRule: Boolean = false, gapAfter: Float = 8f) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorInt
                this.textSize = textSize
                typeface = when {
                    isCode -> Typeface.MONOSPACE
                    bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    else -> Typeface.DEFAULT
                }
            }
            val availW = (contentW - indent.toInt() - (if (isCode) 16 else 0)).coerceAtLeast(120)
            val sl = StaticLayout.Builder.obtain(charSeq, 0, charSeq.length, tp, availW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.28f)
                .setIncludePad(false)
                .build()
            val padV = if (isCode) 8f else 0f
            val totalBlockH = sl.height + padV * 2f
            if (curY + totalBlockH > maxY && curY > mTop + 10f) {
                nextPage()
            }
            if (totalBlockH <= (maxY - mTop)) {
                if (isCode) {
                    val rf = RectF(mLeft + indent, curY, pageW - mRight, curY + totalBlockH)
                    canvas.drawRoundRect(rf, 6f, 6f, codeBgPaint)
                }
                if (isQuote) {
                    canvas.drawLine(mLeft + 4f, curY + 2f, mLeft + 4f, curY + sl.height - 2f, accentPaint)
                }
                canvas.save()
                canvas.translate(mLeft + indent + (if (isCode) 8f else 0f), curY + padV)
                sl.draw(canvas)
                canvas.restore()
                curY += totalBlockH
                if (underlineRule) {
                    curY += 4f
                    canvas.drawLine(mLeft, curY, pageW - mRight, curY, rulePaint)
                }
                curY += gapAfter
            } else {
                for (lnIdx in 0 until sl.lineCount) {
                    val lTop = sl.getLineTop(lnIdx).toFloat()
                    val lBot = sl.getLineBottom(lnIdx).toFloat()
                    val lh = (lBot - lTop).coerceAtLeast(textSize * 1.25f)
                    if (curY + lh > maxY) nextPage()
                    val st = sl.getLineStart(lnIdx)
                    val ed = sl.getLineEnd(lnIdx)
                    val sub = charSeq.subSequence(st, ed)
                    val subSl = StaticLayout.Builder.obtain(sub, 0, sub.length, tp, availW).setIncludePad(false).build()
                    if (isCode) {
                        canvas.drawRect(mLeft + indent, curY, pageW - mRight, curY + lh + 2f, codeBgPaint)
                    }
                    canvas.save()
                    canvas.translate(mLeft + indent + (if (isCode) 8f else 0f), curY)
                    subSl.draw(canvas)
                    canvas.restore()
                    curY += lh + 2f
                }
                curY += gapAfter
            }
        }
        val safeTitle = title.trim().ifEmpty { "마크다운 문서" }
        val dateStr = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date())
        drawStyledBlock(safeTitle, 20f * scale, bold = true, colorInt = 0xFF0F172A.toInt(), underlineRule = false, gapAfter = 3f)
        drawStyledBlock("작성·내보내기: $dateStr · 글자 기준 ${writerFontSp}pt", 9.5f, bold = false, colorInt = 0xFF64748B.toInt(), underlineRule = true, gapAfter = 14f)
        val lines = markdown.lines()
        var idx = 0
        while (idx < lines.size) {
            val ln = lines[idx]
            val tr = ln.trim()
            if (tr.startsWith("```")) {
                val codeLines = mutableListOf<String>()
                idx++
                while (idx < lines.size && !lines[idx].trim().startsWith("```")) {
                    codeLines.add(lines[idx])
                    idx++
                }
                drawStyledBlock(codeLines.joinToString("\n"), 10f * scale, isCode = true, colorInt = 0xFF1E293B.toInt(), gapAfter = 10f)
                idx++
                continue
            }
            when {
                tr.isEmpty() -> { curY += 5f }
                tr == "---" || tr == "***" || tr == "___" -> {
                    if (curY + 14f > maxY) nextPage()
                    curY += 6f
                    canvas.drawLine(mLeft, curY, pageW - mRight, curY, rulePaint)
                    curY += 10f
                }
                tr.startsWith("# ") -> drawStyledBlock(buildPdfSpannable(tr.removePrefix("# ").trim()), 17.5f * scale, bold = true, underlineRule = true, gapAfter = 10f)
                tr.startsWith("## ") -> drawStyledBlock(buildPdfSpannable(tr.removePrefix("## ").trim()), 15f * scale, bold = true, underlineRule = true, gapAfter = 8f)
                tr.startsWith("### ") -> drawStyledBlock(buildPdfSpannable(tr.removePrefix("### ").trim()), 13.2f * scale, bold = true, colorInt = 0xFF065F46.toInt(), gapAfter = 6f)
                tr.startsWith("#### ") -> drawStyledBlock(buildPdfSpannable(tr.removePrefix("#### ").trim()), 12f * scale, bold = true, gapAfter = 5f)
                tr.startsWith("> ") || tr == ">" -> drawStyledBlock(buildPdfSpannable(tr.removePrefix(">").trim()), 11.2f * scale, colorInt = 0xFF334155.toInt(), indent = 14f, isQuote = true, gapAfter = 7f)
                tr.startsWith("- [ ] ") || tr.startsWith("- [x] ") || tr.startsWith("- [X] ") -> {
                    val mark = if (tr.startsWith("- [ ] ")) "☐ " else "☑ "
                    val sp = SpannableStringBuilder(mark).append(buildPdfSpannable(tr.substring(6).trim()))
                    drawStyledBlock(sp, 11.2f * scale, indent = 10f, gapAfter = 5f)
                }
                tr.startsWith("- ") || tr.startsWith("* ") -> {
                    val sp = SpannableStringBuilder("• ").append(buildPdfSpannable(tr.substring(2).trim()))
                    drawStyledBlock(sp, 11.2f * scale, indent = 10f, gapAfter = 5f)
                }
                else -> drawStyledBlock(buildPdfSpannable(ln), 11.4f * scale, gapAfter = 6f)
            }
            idx++
        }
        drawFooter(canvas, pageNum)
        pdf.finishPage(page)
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            pdf.writeTo(out)
        } ?: error("출력 스트림을 열 수 없습니다.")
        pdf.close()
        "✅ PDF 내보내기 완료! (${safeTitle}.pdf · 총 ${pageNum}쪽 · ${writerFontSp}pt 비율)"
    }.getOrElse { e ->
        "⚠️ PDF 내보내기 오류: ${e.message ?: "알 수 없는 오류"}"
    }
}

@Composable
fun WriterAiAssistantContent(
    ctx: Context,
    ui: ResponsiveUi,
    docTitle: String,
    docText: String,
    aiAns: String,
    aiBusy: Boolean,
    aiPromptIn: String,
    onPromptChange: (String) -> Unit,
    onAsk: (String) -> Unit,
    onStop: () -> Unit,
    onInsertBottom: (String) -> Unit,
    onReplaceDoc: (String) -> Unit,
    compact: Boolean
) {
    Column(Modifier.fillMaxSize().padding(if (compact) 8.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
            ChipBtn("✨이어쓰기", false, CG) { onAsk("현재 작성 중인 글의 흐름과 문체를 살려 자연스럽게 다음 내용을 마크다운 형식으로 이어서 작성해줘.") }
            ChipBtn("🪄문장다듬기", false, CA) { onAsk("현재 작성된 문서를 더 매끄럽고 가독성 좋은 한국어 마크다운 문서로 다듬어줘.") }
            ChipBtn("📑구조화", false, CW) { onAsk("현재 내용을 제목(#, ##), 목록(-), 강조(**)를 활용한 깔끔한 마크다운 구조로 정리해줘.") }
            ChipBtn("📌요약", false, CG) { onAsk("현재 작성된 문서의 핵심 요점을 3~5줄 마크다운 목록으로 요약해줘.") }
        }
        Surface(
            color = Color(0xFF080D17),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(0.8.dp, Color(0xFF1F2D46)),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                Text(
                    aiAns.ifBlank { "💡 위 버튼([✨이어쓰기/🪄다듬기/📑구조화])을 누르거나 아래에 요청사항을 입력하면 현재 문서(${docText.length}자)를 바탕으로 AI가 작성·첨삭해 드립니다." },
                    fontSize = if (compact) 12.sp else ui.bodySp,
                    lineHeight = if (compact) 17.sp else (ui.bodySp.value + 5f).sp,
                    color = CW
                )
            }
        }
        if (aiAns.isNotBlank() && !aiBusy) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                ChipBtn("➕ 본문 끝에 추가", false, CG) { onInsertBottom(aiAns.removeSuffix(" ▍").trim()) }
                ChipBtn("🔄 본문 교체", false, CA) { onReplaceDoc(aiAns.removeSuffix(" ▍").trim()) }
                ChipBtn("📋 복사", false, CW) { copyToClip(ctx, aiAns.removeSuffix(" ▍").trim()) }
                ChipBtn("🔈 낭독", false, CG) { speakOneShot(ctx, aiAns.removeSuffix(" ▍").trim()) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = aiPromptIn,
                onValueChange = onPromptChange,
                placeholder = { Text("초안 작성·문장 수정·아이디어 요청...", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            if (aiBusy) {
                Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))) { Text("⏹️", fontSize = 12.sp) }
            } else {
                Button(
                    onClick = { val q = aiPromptIn.trim(); if (q.isNotEmpty()) { onPromptChange(""); onAsk(q) } },
                    colors = ButtonDefaults.buttonColors(containerColor = CG, contentColor = Color(0xFF062019))
                ) { Text("↑", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun WriterEditorWorkspacePane(
    mod: Modifier,
    ui: ResponsiveUi,
    writerFontSp: Int,
    fontBarOpen: Boolean,
    onToggleFontBar: () -> Unit,
    onChangeFontSp: (Int) -> Unit,
    viewMode: Int,
    onSetViewMode: (Int) -> Unit,
    writerDictOn: Boolean,
    onToggleDict: () -> Unit,
    onInsertSyntax: (String, String, Boolean) -> Unit,
    bodyTfv: TextFieldValue,
    onBodyTfvChange: (TextFieldValue) -> Unit,
    onSetBodyRaw: (String) -> Unit
) {
    val editTextStyle = remember(writerFontSp) {
        TextStyle(
            fontSize = writerFontSp.sp,
            lineHeight = (writerFontSp * 1.54f).sp,
            color = CW
        )
    }
    Column(mod, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
            ChipBtn("✍️편집", viewMode == 0, CG) { onSetViewMode(0) }
            ChipBtn("👁️미리보기", viewMode == 1, CA) { onSetViewMode(1) }
            ChipBtn("◫동시보기", viewMode == 2, CW) { onSetViewMode(2) }
            ChipBtn("🔤${writerFontSp}pt", fontBarOpen, CW) { onToggleFontBar() }
            ChipBtn("🔤-", false, CM) { onChangeFontSp(writerFontSp - 1) }
            ChipBtn("🔤+", false, CA) { onChangeFontSp(writerFontSp + 1) }
            ChipBtn(if (writerDictOn) "🔴전사중" else "🎙️음성전사", writerDictOn, CA) { onToggleDict() }
            ChipBtn("# H1", false, CW) { onInsertSyntax("# ", "", true) }
            ChipBtn("## H2", false, CW) { onInsertSyntax("## ", "", true) }
            ChipBtn("**굵게**", false, CA) { onInsertSyntax("**", "**", false) }
            ChipBtn("*기울임*", false, CW) { onInsertSyntax("*", "*", false) }
            ChipBtn("- 목록", false, CG) { onInsertSyntax("- ", "", true) }
            ChipBtn("☑ 할일", false, CG) { onInsertSyntax("- [ ] ", "", true) }
            ChipBtn("> 인용", false, CW) { onInsertSyntax("> ", "", true) }
            ChipBtn("`코드`", false, CW) { onInsertSyntax("`", "`", false) }
            ChipBtn("---선", false, CM) { onInsertSyntax("\n---\n", "", false) }
        }
        when (viewMode) {
            0 -> {
                OutlinedTextField(
                    value = bodyTfv,
                    onValueChange = { nv -> onBodyTfvChange(nv) },
                    placeholder = { Text("# 제목을 입력하고 마크다운으로 글을 작성하세요...", fontSize = writerFontSp.sp, color = CD) },
                    textStyle = editTextStyle,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
            1 -> {
                Surface(
                    color = Color(0xFF0C121E),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.8.dp, Color(0xFF23324B)),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    MarkdownRenderedView(
                        markdown = bodyTfv.text,
                        bodySp = writerFontSp.toFloat(),
                        onToggleTaskLine = { lineIdx ->
                            val ls = bodyTfv.text.lines().toMutableList()
                            if (lineIdx in ls.indices) {
                                val t = ls[lineIdx].trimStart()
                                ls[lineIdx] = if (t.startsWith("- [ ] ")) ls[lineIdx].replaceFirst("- [ ] ", "- [x] ")
                                else ls[lineIdx].replaceFirst("- [x] ", "- [ ] ").replaceFirst("- [X] ", "- [ ] ")
                                onSetBodyRaw(ls.joinToString("\n"))
                            }
                        },
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
                    )
                }
            }
            else -> {
                Surface(
                    color = Color(0xFF0C121E),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.8.dp, Color(0xFF23324B)),
                    modifier = Modifier.weight(0.45f).fillMaxWidth()
                ) {
                    MarkdownRenderedView(
                        markdown = bodyTfv.text,
                        bodySp = (writerFontSp - 1f).coerceAtLeast(12f),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)
                    )
                }
                OutlinedTextField(
                    value = bodyTfv,
                    onValueChange = { nv -> onBodyTfvChange(nv) },
                    textStyle = editTextStyle,
                    modifier = Modifier.weight(0.55f).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun MarkdownWriterView(
    onGoReader: () -> Unit,
    onGoHome: () -> Unit,
    onSaveToMemo: (String) -> Unit
) {
    val ctx = LocalContext.current
    val aiCfg = remember { loadAiCfg(ctx) }
    val ui = rememberUi(aiCfg.uiScale)
    val scope = rememberCoroutineScope()
    EmojiHelpDialog()
    val sp = remember { ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE) }
    val writerRepo = remember { LauncherRepository(ctx) }
    var docs by remember { mutableStateOf(loadWriterDocs(ctx)) }
    var writerFolders by remember { mutableStateOf(FolderRegistry.loadWriterFolders(ctx)) }
    var curDocFolder by remember { mutableStateOf(sp.getString("writer_active_folder_v1", "기본 폴더") ?: "기본 폴더") }
    var shelfFolderFilter by remember { mutableStateOf("전체") }
    var writerFolderDlgOpen by remember { mutableStateOf(false) }
    var pendingWriterBackupFlt by remember { mutableStateOf<String?>(null) }
    var curDocId by remember { mutableStateOf(sp.getLong("writer_active_id_v1", 0L)) }
    LaunchedEffect(LauncherBackupManager.backupRev) {
        if (LauncherBackupManager.backupRev > 0) {
            docs = loadWriterDocs(ctx)
            writerFolders = FolderRegistry.loadWriterFolders(ctx)
        }
    }
    var writerFontSp by remember { mutableStateOf(sp.getInt("writer_font_sp_v1", 16).coerceIn(12, 32)) }
    var fontBarOpen by remember { mutableStateOf(false) }
    var titleTfv by remember {
        val initT = sp.getString("writer_active_title_v1", "새 마크다운 문서") ?: "새 마크다운 문서"
        mutableStateOf(TextFieldValue(initT, TextRange(initT.length)))
    }
    var bodyTfv by remember {
        val initB = sp.getString("writer_active_draft_v1", "# 새 문서\n\n여기에 마크다운 형식으로 자유롭게 글을 작성해 보세요.\n") ?: ""
        mutableStateOf(TextFieldValue(initB, TextRange(initB.length)))
    }
    var statusMsg by remember { mutableStateOf("✍️ 마크다운 스튜디오 · 글자크기(${writerFontSp}pt) · 저장/PDF · AI") }
    fun setWriterFont(newPt: Int) {
        val c = newPt.coerceIn(12, 32)
        writerFontSp = c
        sp.edit().putInt("writer_font_sp_v1", c).apply()
        statusMsg = "🔤 문서 작성 글자 크기: ${c}pt 적용됨 (편집·미리보기·PDF 연동)"
    }
    var writerDictOn by remember { mutableStateOf(false) }
    fun setBodyText(s: String) {
        bodyTfv = TextFieldValue(s, TextRange(s.length))
        sp.edit().putString("writer_active_draft_v1", s).apply()
        if (writerDictOn) syncDictationBuffer(ctx, s)
    }
    fun setBodyFromDict(s: String) {
        bodyTfv = TextFieldValue(s, TextRange(s.length))
        sp.edit().putString("writer_active_draft_v1", s).apply()
    }
    var viewMode by remember { mutableStateOf(0) } // 0: 편집, 1: 미리보기, 2: 동시보기
    var aiMode by remember { mutableStateOf(0) }   // 0: OFF, 1: 화면분할, 2: 플로팅(드래그 창이동)
    var splitHorizontal by remember { mutableStateOf(false) } // false: 상하분할, true: 좌우분할
    var floatMin by remember { mutableStateOf(false) }
    var floatX by remember { mutableStateOf(16f) }
    var floatY by remember { mutableStateOf(96f) }
    var docShelfOpen by remember { mutableStateOf(false) }
    var mdHelpOpen by remember { mutableStateOf(false) }
    var aiPromptIn by remember { mutableStateOf("") }
    var aiAns by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiJob by remember { mutableStateOf<Job?>(null) }
    
    fun nowLabel() = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
    fun saveToInternalShelf() {
        val t = titleTfv.text.trim().ifEmpty {
            bodyTfv.text.lines().firstOrNull { it.trim().startsWith("#") }?.replace("#", "")?.trim()?.take(28)?.ifEmpty { "제목 없는 문서" } ?: "제목 없는 문서"
        }
        if (titleTfv.text.isBlank()) titleTfv = TextFieldValue(t, TextRange(t.length))
        val id = if (curDocId > 0L && docs.any { it.id == curDocId }) curDocId else System.currentTimeMillis()
        curDocId = id
        val fld = curDocFolder.ifBlank { "기본 폴더" }
        if (!writerFolders.contains(fld)) {
            writerFolders = writerFolders + fld
            FolderRegistry.saveWriterFolders(ctx, writerFolders)
        }
        val item = WriterDoc(id, t, bodyTfv.text, nowLabel(), fld)
        val updated = listOf(item) + docs.filterNot { it.id == id }
        docs = updated
        saveWriterDocs(ctx, updated)
        sp.edit().putLong("writer_active_id_v1", id).putString("writer_active_title_v1", t).putString("writer_active_folder_v1", fld).putString("writer_active_draft_v1", bodyTfv.text).apply()
        statusMsg = "💾 [📁$fld] 문서함에 [$t] 저장 완료 (${bodyTfv.text.length}자)"
    }
    val mdSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bodyTfv.text.toByteArray(Charsets.UTF_8)) }
                saveToInternalShelf()
                statusMsg = "💾 외부 마크다운(.md) 파일 저장 완료!"
            }.onFailure { statusMsg = "⚠️ 파일 저장 실패: ${it.message}" }
        }
    }
    val mdLoadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val txt = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val guessedTitle = txt.lines().firstOrNull { it.trim().startsWith("#") }?.replace("#", "")?.trim()?.take(28)?.ifEmpty { "불러온 문서" } ?: "불러온 문서"
                curDocId = 0L
                titleTfv = TextFieldValue(guessedTitle, TextRange(guessedTitle.length))
                setBodyText(txt)
                sp.edit().putLong("writer_active_id_v1", 0L).putString("writer_active_title_v1", guessedTitle).apply()
                docShelfOpen = false
                statusMsg = "📂 외부 파일 불러오기 완료 (${txt.length}자)"
            }.onFailure { statusMsg = "⚠️ 파일 불러오기 실패: ${it.message}" }
        }
    }
    val pdfExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            statusMsg = "⏳ 마크다운 서식을 A4 PDF(${writerFontSp}pt 비율)로 조판·변환 중..."
            scope.launch {
                val res = exportMarkdownToPdf(ctx, uri, titleTfv.text, bodyTfv.text, writerFontSp)
                statusMsg = res
            }
        }
    }
    val writerBackupExp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val flt = pendingWriterBackupFlt
            runCatching {
                val json = LauncherBackupManager.exportBackupJson(ctx, writerRepo, "WRITER", flt)
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                statusMsg = "✅ 문서함 백업 완료 (${if (flt == null) "전체 문서" else "[$flt] 폴더"})"
            }.onFailure { statusMsg = "⚠️ 문서 백업 실패: ${it.message}" }
        }
    }
    val writerBackupImp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val raw = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                statusMsg = LauncherBackupManager.importAndMergeBackupJson(ctx, writerRepo, raw)
                docs = loadWriterDocs(ctx)
                writerFolders = FolderRegistry.loadWriterFolders(ctx)
            }.onFailure { statusMsg = "⚠️ 문서 복원 실패: ${it.message}" }
        }
    }
    val writerDictFallback = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val spk = if (res.resultCode == android.app.Activity.RESULT_OK) res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim() ?: "" else ""
        if (spk.isNotEmpty()) {
            val cur = bodyTfv.text
            setBodyText(if (cur.isBlank()) spk else "$cur $spk")
            statusMsg = "✅ 음성 입력됨: $spk"
        }
        writerDictOn = false
    }
    fun startWriterDict() {
        if (writerDictOn) {
            writerDictOn = false
            stopOneShotDictation()
            statusMsg = "⏹️ 음성 전사 중지"
            return
        }
        if (JarvisRuntime.on) JarvisRuntime.stop(ctx)
        writerDictOn = true
        val initTx = bodyTfv.text
        startOneShotDictation(
            ctx,
            initTx,
            onPart = { pt -> setBodyFromDict(pt) },
            onDone = { fn -> writerDictOn = false; setBodyFromDict(fn) },
            onStatus = { st -> statusMsg = st },
            onFallback = {
                writerDictOn = false
                runCatching {
                    writerDictFallback.launch(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR"))
                }
            },
            onCmd = { cmd, tx ->
                writerDictOn = false
                setBodyFromDict(tx)
                if (cmd == "SAVE_MEMO") saveToInternalShelf()
            }
        )
    }
    val writerMicPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startWriterDict() else { writerDictOn = false; statusMsg = "⚠️ 마이크 권한이 필요합니다." }
    }
    fun insertMdSyntax(prefix: String, suffix: String = "", lineStart: Boolean = false) {
        val txt = bodyTfv.text
        val s = bodyTfv.selection.min.coerceIn(0, txt.length)
        val e = bodyTfv.selection.max.coerceIn(0, txt.length)
        if (lineStart) {
            val ls = txt.lastIndexOf('\n', (s - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val newTx = txt.substring(0, ls) + prefix + txt.substring(ls)
            val newCur = (s + prefix.length).coerceIn(0, newTx.length)
            bodyTfv = TextFieldValue(newTx, TextRange(newCur))
            sp.edit().putString("writer_active_draft_v1", newTx).apply()
        } else if (s != e) {
            val sel = txt.substring(s, e)
            val newTx = txt.substring(0, s) + prefix + sel + suffix + txt.substring(e)
            val newCur = (e + prefix.length + suffix.length).coerceIn(0, newTx.length)
            bodyTfv = TextFieldValue(newTx, TextRange(newCur))
            sp.edit().putString("writer_active_draft_v1", newTx).apply()
        } else {
            val newTx = txt.substring(0, s) + prefix + suffix + txt.substring(s)
            val newCur = (s + prefix.length).coerceIn(0, newTx.length)
            bodyTfv = TextFieldValue(newTx, TextRange(newCur))
            sp.edit().putString("writer_active_draft_v1", newTx).apply()
        }
    }
    fun onWriterBodyChange(oldV: TextFieldValue, newV: TextFieldValue): TextFieldValue {
        sp.edit().putString("writer_active_draft_v1", newV.text).apply()
        if (writerDictOn && newV.text != oldV.text) syncDictationBuffer(ctx, newV.text)
        if (newV.composition != null) return newV
        val oldTx = oldV.text
        val newTx = newV.text
        val cur = newV.selection.end.coerceIn(0, newTx.length)
        if (cur > 0 && newTx[cur - 1] == '\n' && newTx.count { it == '\n' } == oldTx.count { it == '\n' } + 1) {
            val ls = newTx.lastIndexOf('\n', (cur - 2).coerceAtLeast(0)) + 1
            val prevLn = if (cur - 1 >= ls) newTx.substring(ls, cur - 1).trimStart() else ""
            when {
                prevLn == "- [ ]" || prevLn == "-" || prevLn == "*" || prevLn == ">" -> {
                    val cl = newTx.substring(0, ls) + newTx.substring(cur)
                    return TextFieldValue(cl, TextRange(ls.coerceIn(0, cl.length)))
                }
                prevLn.startsWith("- [ ] ") || prevLn.startsWith("- [x] ") -> {
                    val ins = "- [ ] "
                    val res = newTx.substring(0, cur) + ins + newTx.substring(cur)
                    return TextFieldValue(res, TextRange(cur + ins.length))
                }
                prevLn.startsWith("- ") -> {
                    val res = newTx.substring(0, cur) + "- " + newTx.substring(cur)
                    return TextFieldValue(res, TextRange(cur + 2))
                }
                prevLn.startsWith("> ") -> {
                    val res = newTx.substring(0, cur) + "> " + newTx.substring(cur)
                    return TextFieldValue(res, TextRange(cur + 2))
                }
            }
        }
        return newV
    }
    fun askWriterAi(reqPrompt: String) {
        aiJob?.cancel()
        if (ProLlamaJni.ready) runCatching { ProLlamaJni.abortGeneration() }
        val g = ++activeGenId
        aiBusy = true
        aiAns = "⚡ 마크다운 작성 AI 생성 중... ▍"
        aiJob = scope.launch {
            val cc = loadAiCfg(ctx)
            val docCtx = "문서 제목: ${titleTfv.text.ifBlank { "제목 없음" }}\n[현재 작성 중인 마크다운 본문]\n${bodyTfv.text.takeLast(2400)}"
            val ans = withContext(Dispatchers.IO) {
                callLlmHttp(
                    "당신은 한국어 마크다운 문서 작성 및 편집 도우미입니다. 깔끔한 마크다운 문법(#, ##, -, **, >)을 활용해 실용적인 문장으로 답하세요.",
                    "",
                    emptyList(),
                    "$docCtx\n\n[작성 요청]\n$reqPrompt",
                    cc.apiUrl, cc.apiModel, cc.apiKey,
                    { lv -> if (g == activeGenId && lv.isNotBlank()) uiP { aiAns = lv + " ▍" } },
                    g, cc.temp, cc.ctxSize, cc.maxTok.coerceAtLeast(768), cc.cpuMode, cc.histTurns
                )
            }
            if (g == activeGenId) {
                aiAns = (ans ?: "").ifBlank { aiAns.removeSuffix(" ▍").ifBlank { "⚠️ 응답을 생성하지 못했습니다. [⚙️ 설정]에서 GGUF 모델 또는 API를 확인해 주세요." } }
                aiBusy = false
            }
        }
    }
    fun stopWriterAi() {
        activeGenId++
        if (ProLlamaJni.ready) runCatching { ProLlamaJni.abortGeneration() }
        aiJob?.cancel()
        aiBusy = false
        aiAns = aiAns.removeSuffix(" ▍")
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF07090E))) {
        val boxW = this.maxWidth
        val boxH = this.maxHeight
        val density = LocalDensity.current
        val maxWPx = with(density) { boxW.toPx() }
        val maxHPx = with(density) { boxH.toPx() }
        val floatW = if (floatMin) 190.dp else (boxW * 0.86f).coerceIn(250.dp, 360.dp)
        val floatH = if (floatMin) 44.dp else (boxH * 0.46f).coerceIn(230.dp, 340.dp)
        val floatWPx = with(density) { floatW.toPx() }
        val floatHPx = with(density) { floatH.toPx() }
        Column(Modifier.fillMaxSize().padding(horizontal = ui.hPad, vertical = ui.vPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), SB, CV) {
                Column(Modifier.weight(1f)) {
                    Text("✍️ 마크다운 작성 스튜디오", fontSize = ui.titleSp, fontWeight = FontWeight.Bold, color = CW, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(statusMsg, fontSize = ui.subSp, color = CG, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = CV) {
                    OutlinedButton(
                        onClick = { fontBarOpen = !fontBarOpen },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("🔤${writerFontSp}pt", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = if (fontBarOpen) CG else CW)
                    }
                    TextButton(onClick = { mdHelpOpen = true }) { Text("❓문법", fontSize = ui.subSp, color = CA) }
                    TextButton(onClick = onGoReader) { Text("📖", fontSize = ui.bodySp, color = CW) }
                    TextButton(onClick = onGoHome) { Text("🏠", fontSize = ui.bodySp, color = CM) }
                }
            }
            if (fontBarOpen) {
                Surface(
                    color = Color(0xFF0E1524),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CG.copy(alpha = 0.75f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), SB, CV) {
                            Text("🔤 문서 작성 글자 크기: ${writerFontSp}pt (12~32pt)", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG)
                            Text("✕ 닫기", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { fontBarOpen = false }.padding(2.dp))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                            ReaderPointBtn("➖ 2pt", false, CW, Modifier.weight(1f)) { setWriterFont(writerFontSp - 2) }
                            ReaderPointBtn("➖ 1pt", false, CW, Modifier.weight(1f)) { setWriterFont(writerFontSp - 1) }
                            ReaderPointBtn("➕ 1pt", false, CA, Modifier.weight(1f)) { setWriterFont(writerFontSp + 1) }
                            ReaderPointBtn("➕ 2pt", false, CA, Modifier.weight(1f)) { setWriterFont(writerFontSp + 2) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                            listOf(12, 14, 16, 18).forEach { pt ->
                                ReaderPointBtn("${pt}pt", writerFontSp == pt, CG, Modifier.weight(1f)) { setWriterFont(pt) }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                            listOf(20, 22, 25, 28).forEach { pt ->
                                ReaderPointBtn("${pt}pt", writerFontSp == pt, CG, Modifier.weight(1f)) { setWriterFont(pt) }
                            }
                        }
                        Slider(
                            value = writerFontSp.toFloat(),
                            onValueChange = { setWriterFont(it.roundToInt()) },
                            valueRange = 12f..32f,
                            steps = 19,
                            modifier = Modifier.fillMaxWidth().height(24.dp)
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                OutlinedTextField(
                    value = titleTfv,
                    onValueChange = { titleTfv = it; sp.edit().putString("writer_active_title_v1", it.text).apply() },
                    placeholder = { Text("문서 제목 입력", fontSize = ui.subSp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                ChipBtn("📁$curDocFolder", false, CA) {
                    val idx = writerFolders.indexOf(curDocFolder)
                    val nxF = if (idx >= 0 && writerFolders.isNotEmpty()) writerFolders[(idx + 1) % writerFolders.size] else (writerFolders.firstOrNull() ?: "기본 폴더")
                    curDocFolder = nxF
                    sp.edit().putString("writer_active_folder_v1", nxF).apply()
                    statusMsg = "📁 현재 문서 폴더: [$nxF] (길게 관리하려면 [📁폴더·백업] 버튼 터치)"
                }
                ChipBtn("📄새문서", false, CM) {
                    saveToInternalShelf()
                    curDocId = 0L
                    titleTfv = TextFieldValue("새 마크다운 문서", TextRange(9))
                    setBodyText("# 새 문서" + 10.toChar() + 10.toChar())
                    statusMsg = "📄 이전 문서를 보관하고 새 문서를 열었습니다."
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                ChipBtn("💾저장", false, CG) { saveToInternalShelf() }
                ChipBtn("📂문서함(${docs.size})", docShelfOpen, CA) { docShelfOpen = !docShelfOpen }
                ChipBtn("📁폴더·백업", writerFolderDlgOpen, CG) { writerFolderDlgOpen = true }
                ChipBtn("📂파일열기", false, CW) { mdLoadLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }
                ChipBtn("💾.md저장", false, CG) {
                    val fn = (titleTfv.text.trim().ifEmpty { "markdown_note" }) + ".md"
                    mdSaveLauncher.launch(fn)
                }
                ChipBtn("📕PDF내보내기", false, CA) {
                    val fn = (titleTfv.text.trim().ifEmpty { "markdown_doc" }) + ".pdf"
                    pdfExportLauncher.launch(fn)
                }
                ChipBtn("📝메모보내기", false, CW) {
                    if (bodyTfv.text.isNotBlank()) {
                        onSaveToMemo("# ${titleTfv.text.trim().ifEmpty { "작성 문서" }}\n${bodyTfv.text.trim()}")
                        statusMsg = "✅ 작성한 문서를 메모장에도 저장했습니다!"
                    }
                }
                ChipBtn("◫분할AI", aiMode == 1, CG) { aiMode = if (aiMode == 1) 0 else 1 }
                ChipBtn("🪟플로팅AI", aiMode == 2, CA) { aiMode = if (aiMode == 2) 0 else 2; floatMin = false }
            }
            if (docShelfOpen) {
                val visibleDocs = remember(docs, shelfFolderFilter) {
                    if (shelfFolderFilter == "전체") docs else docs.filter { it.folder == shelfFolderFilter }
                }
                Surface(
                    color = Color(0xFF0E1524),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CA.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp)
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), SB, CV) {
                            Text("📂 마크다운 문서함 (${visibleDocs.size}/${docs.size}건)", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CA)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = CV) {
                                Text("📤백업", fontSize = ui.subSp, color = CG, modifier = Modifier.clickable {
                                    pendingWriterBackupFlt = if (shelfFolderFilter == "전체") null else shelfFolderFilter
                                    writerBackupExp.launch("prolauncher_writer_backup.json")
                                })
                                Text("♻️복원", fontSize = ui.subSp, color = CA, modifier = Modifier.clickable { writerBackupImp.launch(arrayOf("*/*")) })
                                Text("✕ 닫기", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { docShelfOpen = false })
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
                            val allWF = (listOf("전체") + (writerFolders + docs.map { it.folder }).distinct())
                            allWF.forEach { wf ->
                                val cnt = if (wf == "전체") docs.size else docs.count { it.folder == wf }
                                ChipBtn("📁$wf($cnt)", shelfFolderFilter == wf, CG) { shelfFolderFilter = wf }
                            }
                            ChipBtn("➕폴더", false, CA) { writerFolderDlgOpen = true }
                        }
                        if (visibleDocs.isEmpty()) {
                            Text("이 폴더에 저장된 문서가 없습니다. 상단 [💾저장]을 눌러 현재 글을 보관해 보세요.", fontSize = ui.subSp, color = CM)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(visibleDocs, key = { it.id }) { d ->
                                    Surface(
                                        color = if (d.id == curDocId) Color(0xFF132924) else CS,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            curDocId = d.id
                                            curDocFolder = d.folder
                                            titleTfv = TextFieldValue(d.title, TextRange(d.title.length))
                                            setBodyText(d.content)
                                            sp.edit().putLong("writer_active_id_v1", d.id).putString("writer_active_title_v1", d.title).putString("writer_active_folder_v1", d.folder).apply()
                                            docShelfOpen = false
                                            statusMsg = "📂 [📁${d.folder}] ${d.title} 문서를 불러왔습니다."
                                        }
                                    ) {
                                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), SB, CV) {
                                            Column(Modifier.weight(1f)) {
                                                Text("${d.title} (${d.content.length}자)", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CW, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("📁${d.folder} · 수정: ${d.updatedAt}", fontSize = 10.sp, color = CM)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
                                                Text("📁이동", fontSize = 10.sp, color = CA, modifier = Modifier.clickable {
                                                    val idx = writerFolders.indexOf(d.folder)
                                                    val nxF = if (idx >= 0 && writerFolders.isNotEmpty()) writerFolders[(idx + 1) % writerFolders.size] else "기본 폴더"
                                                    val nx = docs.map { if (it.id == d.id) it.copy(folder = nxF) else it }
                                                    docs = nx; saveWriterDocs(ctx, nx)
                                                    if (curDocId == d.id) curDocFolder = nxF
                                                }.padding(4.dp))
                                                Text("🗑️", fontSize = ui.subSp, color = Color(0xFFEF4444), modifier = Modifier.clickable {
                                                    val nx = docs.filterNot { it.id == d.id }
                                                    docs = nx; saveWriterDocs(ctx, nx)
                                                    if (curDocId == d.id) curDocId = 0L
                                                }.padding(4.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (aiMode == 1) {
                if (splitHorizontal) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WriterEditorWorkspacePane(
                            mod = Modifier.weight(0.54f).fillMaxHeight(),
                            ui = ui,
                            writerFontSp = writerFontSp,
                            fontBarOpen = fontBarOpen,
                            onToggleFontBar = { fontBarOpen = !fontBarOpen },
                            onChangeFontSp = ::setWriterFont,
                            viewMode = viewMode,
                            onSetViewMode = { viewMode = it },
                            writerDictOn = writerDictOn,
                            onToggleDict = { if (ctx.checkSelfPermission("android.permission.RECORD_AUDIO") == 0) startWriterDict() else writerMicPerm.launch("android.permission.RECORD_AUDIO") },
                            onInsertSyntax = { p, s, ls -> insertMdSyntax(p, s, ls) },
                            bodyTfv = bodyTfv,
                            onBodyTfvChange = { nv -> bodyTfv = onWriterBodyChange(bodyTfv, nv) },
                            onSetBodyRaw = ::setBodyText
                        )
                        Surface(
                            color = Color(0xFF0E1626),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, CG.copy(alpha = 0.65f)),
                            modifier = Modifier.weight(0.46f).fillMaxHeight()
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                Row(Modifier.fillMaxWidth().background(Color(0xFF132036)).padding(horizontal = 10.dp, vertical = 6.dp), SB, CV) {
                                    Text("🦙 화면분할 AI (좌우)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CG)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
                                        Text("↕상하", fontSize = 11.sp, color = CW, modifier = Modifier.clickable { splitHorizontal = false })
                                        Text("🪟플로팅", fontSize = 11.sp, color = CA, modifier = Modifier.clickable { aiMode = 2 })
                                        Text("✕", fontSize = 11.sp, color = CM, modifier = Modifier.clickable { aiMode = 0 })
                                    }
                                }
                                WriterAiAssistantContent(
                                    ctx = ctx, ui = ui, docTitle = titleTfv.text, docText = bodyTfv.text,
                                    aiAns = aiAns, aiBusy = aiBusy, aiPromptIn = aiPromptIn,
                                    onPromptChange = { aiPromptIn = it }, onAsk = ::askWriterAi, onStop = ::stopWriterAi,
                                    onInsertBottom = { ins -> setBodyText(if (bodyTfv.text.isBlank()) ins else bodyTfv.text.trimEnd() + "\n\n" + ins); statusMsg = "➕ AI 작성 내용을 본문 하단에 추가했습니다." },
                                    onReplaceDoc = { rep -> setBodyText(rep); statusMsg = "🔄 본문을 AI 결과로 교체했습니다." },
                                    compact = true
                                )
                            }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WriterEditorWorkspacePane(
                            mod = Modifier.weight(0.55f).fillMaxWidth(),
                            ui = ui,
                            writerFontSp = writerFontSp,
                            fontBarOpen = fontBarOpen,
                            onToggleFontBar = { fontBarOpen = !fontBarOpen },
                            onChangeFontSp = ::setWriterFont,
                            viewMode = viewMode,
                            onSetViewMode = { viewMode = it },
                            writerDictOn = writerDictOn,
                            onToggleDict = { if (ctx.checkSelfPermission("android.permission.RECORD_AUDIO") == 0) startWriterDict() else writerMicPerm.launch("android.permission.RECORD_AUDIO") },
                            onInsertSyntax = { p, s, ls -> insertMdSyntax(p, s, ls) },
                            bodyTfv = bodyTfv,
                            onBodyTfvChange = { nv -> bodyTfv = onWriterBodyChange(bodyTfv, nv) },
                            onSetBodyRaw = ::setBodyText
                        )
                        Surface(
                            color = Color(0xFF0E1626),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, CG.copy(alpha = 0.65f)),
                            modifier = Modifier.weight(0.45f).fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                Row(Modifier.fillMaxWidth().background(Color(0xFF132036)).padding(horizontal = 10.dp, vertical = 6.dp), SB, CV) {
                                    Text("🦙 화면분할 AI 도우미 (상하)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CG)
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = CV) {
                                        Text("↔좌우분할", fontSize = 11.sp, color = CW, modifier = Modifier.clickable { splitHorizontal = true })
                                        Text("🪟플로팅전환", fontSize = 11.sp, color = CA, modifier = Modifier.clickable { aiMode = 2 })
                                        Text("✕닫기", fontSize = 11.sp, color = CM, modifier = Modifier.clickable { aiMode = 0 })
                                    }
                                }
                                WriterAiAssistantContent(
                                    ctx = ctx, ui = ui, docTitle = titleTfv.text, docText = bodyTfv.text,
                                    aiAns = aiAns, aiBusy = aiBusy, aiPromptIn = aiPromptIn,
                                    onPromptChange = { aiPromptIn = it }, onAsk = ::askWriterAi, onStop = ::stopWriterAi,
                                    onInsertBottom = { ins -> setBodyText(if (bodyTfv.text.isBlank()) ins else bodyTfv.text.trimEnd() + "\n\n" + ins); statusMsg = "➕ AI 작성 내용을 본문 하단에 추가했습니다." },
                                    onReplaceDoc = { rep -> setBodyText(rep); statusMsg = "🔄 본문을 AI 결과로 교체했습니다." },
                                    compact = true
                                )
                            }
                        }
                    }
                }
            } else {
                WriterEditorWorkspacePane(
                    mod = Modifier.weight(1f).fillMaxWidth(),
                    ui = ui,
                    writerFontSp = writerFontSp,
                    fontBarOpen = fontBarOpen,
                    onToggleFontBar = { fontBarOpen = !fontBarOpen },
                    onChangeFontSp = ::setWriterFont,
                    viewMode = viewMode,
                    onSetViewMode = { viewMode = it },
                    writerDictOn = writerDictOn,
                    onToggleDict = { if (ctx.checkSelfPermission("android.permission.RECORD_AUDIO") == 0) startWriterDict() else writerMicPerm.launch("android.permission.RECORD_AUDIO") },
                    onInsertSyntax = { p, s, ls -> insertMdSyntax(p, s, ls) },
                    bodyTfv = bodyTfv,
                    onBodyTfvChange = { nv -> bodyTfv = onWriterBodyChange(bodyTfv, nv) },
                    onSetBodyRaw = ::setBodyText
                )
            }
        }
        if (aiMode == 2) {
            val clampedX = floatX.coerceIn(4f, (maxWPx - floatWPx - 4f).coerceAtLeast(4f))
            val clampedY = floatY.coerceIn(8f, (maxHPx - floatHPx - 8f).coerceAtLeast(8f))
            Surface(
                color = Color(0xF50E1628),
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 12.dp,
                border = BorderStroke(1.2.dp, CA.copy(alpha = 0.85f)),
                modifier = Modifier
                    .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                    .width(floatW)
                    .height(floatH)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF17253E))
                            .pointerInput(maxWPx, maxHPx, floatWPx, floatHPx) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    floatX = (floatX + dragAmount.x).coerceIn(4f, (maxWPx - floatWPx - 4f).coerceAtLeast(4f))
                                    floatY = (floatY + dragAmount.y).coerceIn(8f, (maxHPx - floatHPx - 8f).coerceAtLeast(8f))
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        SB,
                        CV
                    ) {
                        Text("✥ 이동 · 🦙 플로팅 AI", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CA, maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
                            if (!floatMin) Text("◫분할", fontSize = 11.sp, color = CG, modifier = Modifier.clickable { aiMode = 1 })
                            Text(if (floatMin) "🔼펼침" else "🗕접기", fontSize = 11.sp, color = CW, modifier = Modifier.clickable { floatMin = !floatMin })
                            Text("✕", fontSize = 11.sp, color = Color(0xFFF87171), modifier = Modifier.clickable { aiMode = 0 })
                        }
                    }
                    if (!floatMin) {
                        WriterAiAssistantContent(
                            ctx = ctx, ui = ui, docTitle = titleTfv.text, docText = bodyTfv.text,
                            aiAns = aiAns, aiBusy = aiBusy, aiPromptIn = aiPromptIn,
                            onPromptChange = { aiPromptIn = it }, onAsk = ::askWriterAi, onStop = ::stopWriterAi,
                            onInsertBottom = { ins -> setBodyText(if (bodyTfv.text.isBlank()) ins else bodyTfv.text.trimEnd() + "\n\n" + ins); statusMsg = "➕ 플로팅 AI 내용을 본문에 추가했습니다." },
                            onReplaceDoc = { rep -> setBodyText(rep); statusMsg = "🔄 본문을 플로팅 AI 결과로 교체했습니다." },
                            compact = true
                        )
                    }
                }
            }
        }
        if (writerFolderDlgOpen) {
            FolderManageDialog(
                title = "📁 문서 스튜디오 폴더 관리 및 백업",
                folders = writerFolders,
                selectedFolder = shelfFolderFilter,
                ui = ui,
                onAddFolder = { t ->
                    if (!writerFolders.contains(t)) {
                        val nx = writerFolders + t
                        writerFolders = nx
                        FolderRegistry.saveWriterFolders(ctx, nx)
                    }
                },
                onRenameFolder = { oldN, newN ->
                    val (nf, nd) = FolderRegistry.renameWriterFolder(ctx, oldN, newN, docs)
                    writerFolders = nf
                    docs = nd
                    if (curDocFolder == oldN) curDocFolder = newN
                    if (shelfFolderFilter == oldN) shelfFolderFilter = newN
                },
                onDeleteFolder = { delN ->
                    if (writerFolders.size > 1) {
                        val nx = writerFolders.filterNot { it == delN }
                        writerFolders = nx
                        FolderRegistry.saveWriterFolders(ctx, nx)
                        if (curDocFolder == delN) curDocFolder = nx.first()
                        if (shelfFolderFilter == delN) shelfFolderFilter = "전체"
                    }
                },
                onBackupAllInTab = {
                    pendingWriterBackupFlt = null
                    writerBackupExp.launch("prolauncher_writer_backup.json")
                },
                onBackupCurrentFolder = {
                    if (shelfFolderFilter != "전체") {
                        pendingWriterBackupFlt = shelfFolderFilter
                        writerBackupExp.launch("prolauncher_writer_${shelfFolderFilter}.json")
                    }
                },
                onRestoreTab = { writerBackupImp.launch(arrayOf("*/*")) },
                statusText = statusMsg,
                onDismiss = { writerFolderDlgOpen = false }
            )
        }
        if (mdHelpOpen) {
            AlertDialog(
                onDismissRequest = { mdHelpOpen = false },
                title = { Text("❓ 마크다운 작성 가이드", fontSize = ui.titleSp, fontWeight = FontWeight.Bold, color = CG) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MarkdownGuideContent(onInsertSample = { sample ->
                            setBodyText(if (bodyTfv.text.isBlank()) sample else bodyTfv.text.trimEnd() + "\n\n" + sample)
                            mdHelpOpen = false
                            statusMsg = "📋 마크다운 예시 문법을 본문에 추가했습니다."
                        })
                    }
                },
                confirmButton = { Button(onClick = { mdHelpOpen = false }) { Text("확인") } }
            )
        }
    }
}
