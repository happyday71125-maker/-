package app.prolauncher.ai
import android.content.Context
import android.net.Uri
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Html
import android.text.TextPaint
import androidx.compose.runtime.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
internal val CH_TAB = 9.toChar()
internal val CH_NL = 10.toChar()
internal val CH_CR = 13.toChar()
internal val CH_DQ = 34.toChar()
internal val CH_SQ = 39.toChar()
internal val CH_BS = 92.toChar()
internal val STR_NL = CH_NL.toString()
internal val STR_PARA = STR_NL + STR_NL
data class AppFolder(
 val id: Long,
 val name: String,
 val packages: List<String>
)
data class AiChatSession(
 val id: Long,
 val title: String,
 val folder: String,
 val messages: List<ChatMsg>,
 val updatedAt: String
)
data class FootnoteSpan(
 val start: Int,
 val end: Int,
 val isUnicodeSup: Boolean,
 val isLineStartRef: Boolean = false
)
object EbookTextReflower {
 private val sentenceEndChars = setOf('.', '?', '!', '。', '！', '？', '…', ':', ';', '】', '》', '〉', '』', '」')
 private val closingWrapperChars = setOf(CH_DQ, CH_SQ, '”', '’', ')', ']', '}', '』', '」', '〉', '》')
 fun cleanControlChars(raw: String): String {
  if (raw.isEmpty()) return ""
  val sb = StringBuilder(raw.length)
  var i = 0
  while (i < raw.length) {
   val c = raw[i]
   when {
    c == CH_CR -> {
     if (i + 1 < raw.length && raw[i + 1] == CH_NL) i++
     sb.append(CH_NL)
    }
    c.code == 0x00AD || c.code == 0x200B || c.code == 0xFEFF -> {
    }
    c.code == 0x00A0 || c == CH_TAB -> sb.append(' ')
    else -> sb.append(c)
   }
   i++
  }
  return sb.toString()
 }
 private fun isNoisePageMarker(trimmed: String): Boolean {
  if (trimmed.isEmpty()) return false
  if (trimmed.startsWith("— PDF ") && trimmed.endsWith("쪽 —")) return true
  if (trimmed.length <= 16) {
   val stripped = trimmed.trim('-', '—', '–', ' ', '[', ']', '(', ')', '·')
   if (stripped.isNotEmpty() && stripped.all { it.isDigit() }) return true
   val low = stripped.lowercase()
   if (low.startsWith("page ") && low.removePrefix("page ").trim().all { it.isDigit() }) return true
  }
  return false
 }
 private fun isStructuralLineStart(trimmed: String): Boolean {
  if (trimmed.isEmpty()) return true
  val first = trimmed[0]
  if (first in charArrayOf('#', '【', '《', '〈', '■', '●', '◆', '▶', '※', '•', '☐', '☑', '☆', '★')) return true
  if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("— ")) return true
  val low = trimmed.lowercase()
  if (low.startsWith("chapter ") || low.startsWith("part ") || low.startsWith("section ")) return true
  if (trimmed.startsWith("제") && trimmed.length <= 32 && (trimmed.contains("장") || trimmed.contains("편") || trimmed.contains("절") || trimmed.contains("부"))) return true
  if (trimmed.startsWith("[") && trimmed.contains("]") && trimmed.indexOf(']') <= 24) return true
  var dIdx = 0
  if (first == '(' && trimmed.length > 3) dIdx = 1
  var digits = 0
  while (dIdx < trimmed.length && dIdx < 5 && trimmed[dIdx].isDigit()) {
   digits++
   dIdx++
  }
  if (digits > 0 && dIdx < trimmed.length && (trimmed[dIdx] == '.' || trimmed[dIdx] == ')')) {
   if (dIdx + 1 >= trimmed.length || trimmed[dIdx + 1] == ' ') return true
  }
  return false
 }
 private fun isDialogueStart(trimmed: String): Boolean {
  if (trimmed.isEmpty()) return false
  return trimmed[0] in charArrayOf('"', '“', '‘', '「', '『')
 }
 private fun endsCompleteSentence(trimmed: String): Boolean {
  if (trimmed.isEmpty()) return true
  var idx = trimmed.lastIndex
  while (idx >= 0 && trimmed[idx] in closingWrapperChars) idx--
  if (idx < 0) return false
  val last = trimmed[idx]
  return last in sentenceEndChars
 }
 private fun looksLikeStandaloneHeading(trimmed: String): Boolean {
  if (trimmed.isEmpty() || trimmed.length > 28) return false
  if (isStructuralLineStart(trimmed)) return true
  val last = trimmed.last()
  if (last == ',' || last == '·' || last == '-') return false
  val unfinishedSuffixes = listOf(
   "은", "는", "이", "가", "을", "를", "의", "에", "에서", "으로", "로", "과", "와", "도", "만",
   "하고", "하며", "해서", "하여", "지만", "거나", "는데", "으며", "고", "며", "서", "면", "던", "한", "할", "된", "될", "있는", "없는"
  )
  if (unfinishedSuffixes.any { trimmed.endsWith(" $it") || (trimmed.length > 3 && trimmed.endsWith(it)) }) return false
  return false
 }
 private val standaloneSingleHangul = setOf(
  '나', '너', '저', '그', '이', '더', '덜', '잘', '못', '안', '또', '꼭', '참', '좀', '막', '다', '늘', '곧', '왜', '뭐',
  '집', '책', '길', '밤', '낮', '눈', '손', '발', '속', '위', '앞', '뒤', '옆', '밖', '일', '말', '글', '물', '불', '술',
  '밥', '옷', '꽃', '산', '강', '달', '별', '해', '비', '숨', '꿈', '첫', '새', '헌', '온', '한', '두', '세', '네'
 )
 private val boundExactHangul = setOf("다", "요", "죠", "네", "군", "지", "까", "니", "고", "며", "서", "면", "던", "은", "는", "을", "를", "의", "에", "로", "과", "와", "도", "만")
 private val boundPrefixHangul = listOf(
  "니다", "습니다", "어요", "아요", "여요", "네요", "군요", "지요", "니까", "면서", "지만", "는데",
  "아서", "어서", "해서", "으로", "에서", "에게", "한테", "부터", "까지", "처럼", "만큼", "보다", "라도"
 )
 fun joinMidWordWithoutSpace(prev: String, prevEndedSpace: Boolean, nextTrimmed: String): Boolean {
  if (prevEndedSpace || prev.isEmpty() || nextTrimmed.isEmpty()) return false
  if (prev.last().code !in 0xAC00..0xD7A3 || nextTrimmed.first().code !in 0xAC00..0xD7A3) return false
  val prevTok = prev.substringAfterLast(' ')
  val nextRawTok = nextTrimmed.substringBefore(' ')
  val nextTok = nextRawTok.trimEnd('.', ',', '?', '!', '~', '…', ':', ';', CH_DQ, CH_SQ, '”', '’', ')', ']', '」', '』')
  if (nextTok.isEmpty()) return false
  if (prevTok.length == 1 && nextTok.length == 1) return true
  if (prevTok == "어떻" && nextTok.startsWith("게")) return true
  if (nextTok in boundExactHangul || boundPrefixHangul.any { nextTok.startsWith(it) }) return true
  if (prevTok.length == 1 && prevTok[0] !in standaloneSingleHangul) return true
  return false
 }
 fun unwrapNaturalParagraphs(raw: String, preserveStanzas: Boolean = false): String {
  val cleaned = cleanControlChars(raw)
  if (cleaned.isBlank()) return ""
  if (preserveStanzas) {
   return cleaned.split(CH_NL).joinToString(STR_NL) { it.trimEnd() }.trim()
  }
  val rawLines = cleaned.split(CH_NL)
  val nonEmptyTrimmed = ArrayList<String>(rawLines.size)
  var blankTransitions = 0
  var sawAnyContent = false
  var prevWasBlank = false
  for (ln in rawLines) {
   val t = ln.trim()
   if (t.isEmpty()) {
    if (sawAnyContent) prevWasBlank = true
   } else if (!isNoisePageMarker(t)) {
    if (sawAnyContent && prevWasBlank) blankTransitions++
    sawAnyContent = true
    prevWasBlank = false
    nonEmptyTrimmed.add(t)
   }
  }
  if (nonEmptyTrimmed.isEmpty()) return ""
  val sortedLens = nonEmptyTrimmed.map { it.length }.sorted()
  val wrapRefLen = sortedLens[(sortedLens.size * 85 / 100).coerceIn(0, sortedLens.lastIndex)]
  val incompleteCnt = nonEmptyTrimmed.count { !endsCompleteSentence(it) && !looksLikeStandaloneHeading(it) }
  val brokenParaArtifact = nonEmptyTrimmed.size >= 4 &&
   blankTransitions >= (nonEmptyTrimmed.size * 45 / 100) &&
   incompleteCnt >= (nonEmptyTrimmed.size * 22 / 100)
  val out = StringBuilder(cleaned.length)
  val curPara = StringBuilder()
  var lastRawTrimLen = 0
  var lastRawEndedSpace = false
  var sawBlankLine = false
  var blankLineCount = 0
  fun flushPara() {
   if (curPara.isNotEmpty()) {
    if (out.isNotEmpty()) out.append(STR_PARA)
    out.append(curPara.toString().trim())
    curPara.setLength(0)
   }
  }
  for (rawLn in rawLines) {
   val endedSpace = rawLn.isNotEmpty() && rawLn.last().isWhitespace()
   val nextIndent = rawLn.startsWith(" ") || rawLn.startsWith("　") || rawLn.startsWith(CH_TAB.toString())
   val trimmed = rawLn.trim().replace(Regex(" {2,}"), " ")
   if (trimmed.isEmpty()) {
    if (curPara.isNotEmpty()) {
     sawBlankLine = true
     blankLineCount++
    }
    continue
   }
   if (isNoisePageMarker(trimmed)) continue
   if (curPara.isEmpty()) {
    curPara.append(trimmed)
    lastRawTrimLen = trimmed.length
    lastRawEndedSpace = endedSpace
    sawBlankLine = false
    blankLineCount = 0
    continue
   }
   val prevStr = curPara.toString()
   val prevComplete = endsCompleteSentence(prevStr)
   val nextStruct = isStructuralLineStart(trimmed)
   val prevHeading = looksLikeStandaloneHeading(prevStr)
   val shouldFlush = when {
    nextStruct || prevHeading -> true
    !prevComplete -> blankLineCount >= 2 && lastRawTrimLen <= 14
    isDialogueStart(trimmed) -> true
    sawBlankLine -> {
     if (brokenParaArtifact && blankLineCount == 1 && lastRawTrimLen >= (wrapRefLen * 0.82f).toInt()) false
     else true
    }
    else -> wrapRefLen > 75 || lastRawTrimLen < (wrapRefLen * 0.78f).toInt() || nextIndent
   }
   if (shouldFlush) {
    flushPara()
    curPara.append(trimmed)
   } else {
    if (prevStr.endsWith("-") && prevStr.length >= 2 && prevStr[prevStr.length - 2].isLetter() && trimmed.first().isLetter()) {
     curPara.setLength(curPara.length - 1)
     curPara.append(trimmed)
    } else if (!sawBlankLine && joinMidWordWithoutSpace(prevStr, lastRawEndedSpace, trimmed)) {
     curPara.append(trimmed)
    } else if (sawBlankLine && brokenParaArtifact && joinMidWordWithoutSpace(prevStr, false, trimmed)) {
     curPara.append(trimmed)
    } else {
     curPara.append(' ').append(trimmed)
    }
   }
   lastRawTrimLen = trimmed.length
   lastRawEndedSpace = endedSpace
   sawBlankLine = false
   blankLineCount = 0
  }
  flushPara()
  return out.toString().trim()
 }
 fun createBookPaint(fontSizePx: Float, useSerif: Boolean): TextPaint {
  return TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
   textSize = fontSizePx.coerceAtLeast(18f)
   typeface = if (useSerif) Typeface.SERIF else Typeface.SANS_SERIF
  }
 }
 private val unicodeSupChars = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁽⁾"
 private val numPrefixWords = setOf(
  "제", "약", "만", "총", "각", "전", "후", "상", "하", "초", "중", "고", "대",
  "시즌", "파트", "챕터", "레벨", "버전", "채널", "트랙", "페이지", "그림", "표", "식",
  "부록", "예제", "문제", "단계", "유형", "코로나", "오미크론", "비타민", "아이폰", "갤럭시",
  "윈도우", "안드로이드", "플레이스테이션", "오피스", "모델", "타입", "코드", "번호", "항목",
  "규칙", "원칙", "법칙", "조항", "영문", "국도", "호선", "티어", "그룹", "클래스", "카테고리",
  "시리즈", "에피소드", "라운드", "세트", "게임", "매치", "코어", "스레드", "섹션", "포인트", "슬롯"
 )
 private val josaSuffixes = listOf(
  "에서는", "에서도", "에게는", "에게서", "으로부터", "로부터", "에서", "에게", "으로",
  "까지", "부터", "조차", "마저", "처럼", "보다", "이나", "이란",
  "은", "는", "이", "가", "을", "를", "의", "에", "로", "과", "와", "도", "만"
 )
 private val counterHeadChars = "살세년월일시분초장절편부권쪽화회차번개명원배위층호기대점"
 private fun isHangulSyl(c: Char): Boolean = c in '\uAC00'..'\uD7A3'
 private fun isClosingPunct(c: Char): Boolean =
  c == '.' || c == '?' || c == '!' || c == '。' || c == '！' || c == '？' ||
   c == '”' || c == '’' || c == CH_DQ || c == CH_SQ || c == '」' || c == '』' || c == ')'
 private fun isPostFootnoteTerm(c: Char): Boolean =
  c.isWhitespace() || c == ',' || c == '.' || c == ';' || c == ':' || c == '?' ||
   c == '!' || c == ')' || c == ']' || c == '”' || c == '’' || c == '」' || c == '』'
 private fun isBoundaryAfterFootnote(text: String, afterIdx: Int): Boolean {
  val n = text.length
  if (afterIdx >= n) return true
  val c = text[afterIdx]
  if (isPostFootnoteTerm(c)) return true
  if (c.isDigit() || c == '-' || c == '~' || c == '/' || c == '%') return false
  if (counterHeadChars.indexOf(c) >= 0) return false
  for (josa in josaSuffixes) {
   if (text.regionMatches(afterIdx, josa, 0, josa.length)) {
    val endJ = afterIdx + josa.length
    if (endJ >= n || isPostFootnoteTerm(text[endJ])) return true
   }
  }
  return false
 }
 private fun hasUnclosedParenOnLine(text: String, pos: Int): Boolean {
  val stop = (pos - 40).coerceAtLeast(0)
  var depth = 0
  var k = pos - 1
  while (k >= stop) {
   val ch = text[k]
   if (ch == CH_NL) break
   if (ch == ')') depth++
   else if (ch == '(') {
    if (depth == 0) return true
    depth--
   }
   k--
  }
  return false
 }
 fun findFootnoteSpans(text: String): List<FootnoteSpan> {
  val n = text.length
  if (n == 0) return emptyList()
  var out: ArrayList<FootnoteSpan>? = null
  fun emit(s: Int, e: Int, uni: Boolean, lineRef: Boolean = false) {
   if (out == null) out = ArrayList(4)
   out!!.add(FootnoteSpan(s, e, uni, lineRef))
  }
  var i = 0
  while (i < n) {
   val c = text[i]
   if (unicodeSupChars.indexOf(c) >= 0) {
    var j = i + 1
    while (j < n && unicodeSupChars.indexOf(text[j]) >= 0) j++
    val atStart = (i == 0 || text[i - 1] == CH_NL)
    emit(i, j, uni = true, lineRef = atStart)
    i = j
    continue
   }
   if (c == '[' || c == '〔') {
    val closeCh = if (c == '[') ']' else '〕'
    var p = i + 1
    if (text.startsWith("각주", p)) p += 2
    else if (text.startsWith("주", p)) p += 1
    while (p < n && text[p] == ' ') p++
    val dStart = p
    while (p < n && text[p].isDigit() && p - dStart < 4) p++
    if (p > dStart && (p - dStart) in 1..3 && p < n && text[p] == closeCh) {
     val endIdx = p + 1
     val atStart = (i == 0 || text[i - 1] == CH_NL)
     emit(i, endIdx, uni = false, lineRef = atStart)
     i = endIdx
     continue
    }
   }
   if (c.isDigit()) {
    val dStart = i
    var dEnd = i + 1
    while (dEnd < n && text[dEnd].isDigit()) dEnd++
    val dLen = dEnd - dStart
    if (dLen in 1..3 && (dStart == 0 || !text[dStart - 1].isDigit())) {
     val hasCloseParen = dEnd < n && text[dEnd] == ')'
     if (hasCloseParen && dStart > 0 && (isHangulSyl(text[dStart - 1]) || isClosingPunct(text[dStart - 1]) || text[dStart - 1].isLetter()) && !hasUnclosedParenOnLine(text, dStart)) {
      val spanEnd = dEnd + 1
      if (isBoundaryAfterFootnote(text, spanEnd)) {
       emit(dStart, spanEnd, uni = false)
       i = spanEnd
       continue
      }
     } else if (!hasCloseParen && dStart > 0) {
      val prev = text[dStart - 1]
      val validPunct = when (prev) {
       '.' -> dStart >= 2 && (isHangulSyl(text[dStart - 2]) || "”’」』)".indexOf(text[dStart - 2]) >= 0)
       '?', '!', '。', '！', '？', '”', '’', '」', '』', ')' -> true
       else -> false
      }
      if (validPunct && !hasUnclosedParenOnLine(text, dStart) && isBoundaryAfterFootnote(text, dEnd)) {
       emit(dStart, dEnd, uni = false)
       i = dEnd
       continue
      }
      if (dLen in 1..2 && dStart >= 2 && isHangulSyl(prev) && !hasUnclosedParenOnLine(text, dStart)) {
       var wStart = dStart - 1
       while (wStart > 0 && isHangulSyl(text[wStart - 1])) wStart--
       val word = text.substring(wStart, dStart)
       val numVal = text.substring(dStart, dEnd).toIntOrNull() ?: 999
       val blockedPrefix = word in numPrefixWords || numPrefixWords.any { it.length >= 2 && word.endsWith(it) }
       if (word.length >= 2 && !blockedPrefix && numVal in 1..99 && isBoundaryAfterFootnote(text, dEnd)) {
        emit(dStart, dEnd, uni = false)
        i = dEnd
        continue
       }
      }
     }
    }
    i = dEnd
    continue
   }
   i++
  }
  return out ?: emptyList()
 }
 fun wrapPageKeepAll(pageTx: String, colWPx: Int, fontSizePx: Float, useSerif: Boolean): String {
  if (pageTx.isEmpty() || colWPx <= 60) return pageTx
  val paint = createBookPaint(fontSizePx, useSerif)
  val maxW = (colWPx.toFloat() - fontSizePx * 0.36f).coerceAtLeast(60f)
  val chars = pageTx.toCharArray()
  val n = chars.size
  var segStart = 0
  var i = 0
  while (i <= n) {
   if (i == n || chars[i] == CH_NL) {
    var cur = segStart
    while (cur < i) {
     var scanStart = cur
     while (scanStart < i && chars[scanStart] == ' ') scanStart++
     if (scanStart >= i) break
     var bestEnd = cur
     var j = scanStart + 1
     while (j <= i) {
      if (j == i || chars[j] == ' ') {
       val w = paint.measureText(chars, cur, j - cur)
       if (w <= maxW) {
        bestEnd = j
        if (j == i) break
       } else {
        break
       }
      }
      j++
     }
     if (bestEnd == i) {
      break
     } else if (bestEnd > cur) {
      chars[bestEnd] = CH_NL
      cur = bestEnd + 1
     } else {
      var sp = -1
      for (k in scanStart + 1 until i) {
       if (chars[k] == ' ') {
        sp = k
        break
       }
      }
      if (sp != -1) {
       chars[sp] = CH_NL
       cur = sp + 1
      } else {
       break
      }
     }
    }
    segStart = i + 1
   }
   i++
  }
  return String(chars)
 }
 fun paginateBookKeepAll(
  full: String,
  colWPx: Int,
  textHPx: Float,
  fontSizePx: Float,
  lineHeightPx: Float,
  useSerif: Boolean
 ): List<PageSlice> {
  if (full.isBlank()) return listOf(PageSlice(0, full.length))
  val n = full.length
  if (colWPx <= 60 || textHPx <= 60f) {
   val fb = ArrayList<PageSlice>()
   var p = 0
   while (p < n) {
    val nx = minOf(n, p + 550)
    fb.add(PageSlice(p, nx))
    p = nx
   }
   return if (fb.isEmpty()) listOf(PageSlice(0, n)) else fb
  }
  val paint = createBookPaint(fontSizePx, useSerif)
  val maxW = (colWPx.toFloat() - fontSizePx * 0.36f).coerceAtLeast(60f)
  val safeLineH = lineHeightPx.coerceAtLeast(fontSizePx * 1.2f)
  val maxLines = (((textHPx - fontSizePx * 1.16f).coerceAtLeast(0f) / safeLineH).toInt() + 1).coerceAtLeast(3)
  val out = ArrayList<PageSlice>((n / 420).coerceAtLeast(8))
  var pos = 0
  while (pos < n && (full[pos] == CH_NL || full[pos] == CH_CR)) pos++
  while (pos < n) {
   var cur = pos
   var usedLines = 0
   while (cur < n && usedLines < maxLines) {
    val ch0 = full[cur]
    if (ch0 == CH_CR) {
     cur++
     continue
    }
    if (ch0 == CH_NL) {
     usedLines++
     cur++
     continue
    }
    val searchLim = minOf(n, cur + 2400)
    var segEnd = cur
    while (segEnd < searchLim && full[segEnd] != CH_NL) segEnd++
    var scanStart = cur
    while (scanStart < segEnd && full[scanStart] == ' ') scanStart++
    var bestEnd = cur
    if (scanStart < segEnd) {
     var j = scanStart + 1
     while (j <= segEnd) {
      if (j == segEnd || full[j] == ' ') {
       val w = paint.measureText(full, cur, j)
       if (w <= maxW) {
        bestEnd = j
        if (j == segEnd) break
       } else {
        break
       }
      }
      j++
     }
    } else {
     bestEnd = segEnd
    }
    if (bestEnd > cur) {
     cur = bestEnd
     if (cur < n && full[cur] == ' ') {
      cur++
     } else if (cur < n && full[cur] == CH_NL) {
      cur++
     }
     usedLines++
    } else {
     val broken = paint.breakText(full, cur, segEnd, true, maxW, null).coerceAtLeast(1)
     var nextCur = (cur + broken).coerceAtMost(segEnd)
     if (nextCur < segEnd && Character.isLowSurrogate(full[nextCur]) && nextCur > cur + 1) nextCur--
     cur = nextCur
     if (cur < n && full[cur] == CH_NL) cur++
     usedLines++
    }
   }
   if (cur <= pos) cur = (pos + 1).coerceAtMost(n)
   out.add(PageSlice(pos, cur))
   pos = cur
   while (pos < n && (full[pos] == CH_NL || full[pos] == CH_CR)) pos++
  }
  return if (out.isEmpty()) listOf(PageSlice(0, n)) else out
 }

 fun extractCleanPdf(context: Context, uri: Uri): String {
  PDFBoxResourceLoader.init(context)
  context.contentResolver.openInputStream(uri)?.use { input ->
   PDDocument.load(input).use { doc ->
    val stripper = PDFTextStripper().apply {
     sortByPosition = true
    }
    val sb = StringBuilder()
    for (p in 1..doc.numberOfPages) {
     stripper.startPage = p
     stripper.endPage = p
     val pageRaw = cleanControlChars(stripper.getText(doc))
     val pageLines = pageRaw.split(CH_NL).toMutableList()
     while (pageLines.isNotEmpty() && (pageLines.first().trim().isEmpty() || isNoisePageMarker(pageLines.first().trim()))) {
      pageLines.removeAt(0)
     }
     while (pageLines.isNotEmpty() && (pageLines.last().trim().isEmpty() || isNoisePageMarker(pageLines.last().trim()))) {
      pageLines.removeAt(pageLines.lastIndex)
     }
     if (pageLines.isNotEmpty()) {
      if (sb.isNotEmpty()) sb.append(CH_NL)
      sb.append(pageLines.joinToString(STR_NL))
     }
    }
    return unwrapNaturalParagraphs(sb.toString(), preserveStanzas = false)
   }
  }
  return ""
 }
 fun htmlToNaturalParagraphs(htmlRaw: String): String {
  if (htmlRaw.isBlank()) return ""
  var s = htmlRaw
  s = s.replace(Regex("(?is)<(head|style|script|svg|nav|noscript)[^>]*>.*?</(head|style|script|svg|nav|noscript)>"), "")
  s = s.replace(Regex("(?is)<!--.*?-->"), "")
  s = s.replace(Regex("(?i)<br[^>]*>"), STR_NL)
  s = s.replace(Regex("(?i)</?(p|div|h1|h2|h3|h4|h5|h6|li|blockquote|section|article|tr|hr|dt|dd|aside)[^>]*>"), STR_PARA)
  s = s.replace(Regex("(?is)<sup[^>]*>\\s*(?:<[^>]+>\\s*)*\\[?(\\d{1,3})\\]?\\)?\\s*(?:</[^>]+>\\s*)*</sup>"), "[$1]")
  s = s.replace(Regex("(?is)<a[^>]+(?:noteref|footnote|fnref|doc-noteref)[^>]*>\\s*(?:<[^>]+>\\s*)*\\[?(\\d{1,3})\\]?\\)?\\s*(?:</[^>]+>\\s*)*</a>"), "[$1]")
  s = s.replace(Regex("<[^>]+>"), "")
  val markerP = "[[PL_PARA_BRK]]"
  val markerL = "[[PL_LINE_BRK]]"
  val marked = cleanControlChars(s)
   .replace(Regex(STR_NL + "{2,}"), markerP)
   .replace(STR_NL, markerL)
  val decoded = runCatching {
   Html.fromHtml(marked, Html.FROM_HTML_MODE_LEGACY).toString()
  }.getOrElse { marked }
  val restored = decoded
   .replace(markerP, STR_PARA)
   .replace(markerL, STR_NL)
  return unwrapNaturalParagraphs(restored, preserveStanzas = false)
 }
 fun extractCleanEpub(context: Context, uri: Uri): String {
  val entries = LinkedHashMap<String, ByteArray>()
  context.contentResolver.openInputStream(uri)?.use { raw ->
   ZipInputStream(BufferedInputStream(raw)).use { zis ->
    var entry = zis.nextEntry
    while (entry != null) {
     if (!entry.isDirectory) {
      val name = entry.name.replace(CH_BS, '/').trimStart('/')
      val low = name.lowercase()
      if (low.endsWith(".xml") || low.endsWith(".opf") || low.endsWith(".xhtml") || low.endsWith(".html") || low.endsWith(".htm")) {
       entries[name] = zis.readBytes()
      }
     }
     entry = zis.nextEntry
    }
   }
  }
  if (entries.isEmpty()) return ""
  fun extractXmlAttr(tag: String, attrName: String): String? {
   val lowTag = tag.lowercase()
   val target = attrName.lowercase()
   var searchFrom = 0
   while (searchFrom < lowTag.length) {
    val idx = lowTag.indexOf(target, searchFrom)
    if (idx < 0) return null
    val prevOk = idx == 0 || !lowTag[idx - 1].let { it.isLetterOrDigit() || it == '-' || it == '_' || it == ':' }
    var p = idx + target.length
    while (p < tag.length && tag[p].isWhitespace()) p++
    if (prevOk && p < tag.length && tag[p] == '=') {
     p++
     while (p < tag.length && tag[p].isWhitespace()) p++
     if (p < tag.length && (tag[p] == CH_DQ || tag[p] == CH_SQ)) {
      val q = tag[p]
      val end = tag.indexOf(q, p + 1)
      if (end > p) return tag.substring(p + 1, end)
     }
    }
    searchFrom = idx + target.length
   }
   return null
  }
  val orderedPaths = mutableListOf<String>()
  runCatching {
   val containerXml = entries.entries.firstOrNull { it.key.equals("META-INF/container.xml", ignoreCase = true) }?.value?.toString(Charsets.UTF_8)
   val opfPath = containerXml?.let { extractXmlAttr(it, "full-path") }
    ?: entries.keys.firstOrNull { it.lowercase().endsWith(".opf") }
   if (opfPath != null) {
    val opfNorm = opfPath.replace(CH_BS, '/').trimStart('/')
    val opfDir = if ('/' in opfNorm) opfNorm.substringBeforeLast('/') + "/" else ""
    val opfXml = entries.entries.firstOrNull { it.key.equals(opfNorm, ignoreCase = true) }?.value?.toString(Charsets.UTF_8)
    if (opfXml != null) {
     val manifest = HashMap<String, String>()
     Regex("<item[ >]([^>]+)>", RegexOption.IGNORE_CASE).findAll(opfXml).forEach { m ->
      val attrs = m.groupValues[1]
      val id = extractXmlAttr(attrs, "id")
      val href = extractXmlAttr(attrs, "href")
      val props = extractXmlAttr(attrs, "properties") ?: ""
      if (!id.isNullOrBlank() && !href.isNullOrBlank() && !props.lowercase().contains("nav")) {
       val cleanHref = Uri.decode(href.substringBefore('#'))
       val resolved = normalizeZipPath(opfDir + cleanHref)
       manifest[id] = resolved
      }
     }
     Regex("<itemref[ >]([^>]+)>", RegexOption.IGNORE_CASE).findAll(opfXml).forEach { m ->
      val idref = extractXmlAttr(m.groupValues[1], "idref")
      val target = idref?.let { manifest[it] }
      if (!target.isNullOrBlank()) orderedPaths.add(target)
     }
    }
   }
  }
  val chapterKeys = if (orderedPaths.isNotEmpty()) {
   orderedPaths.mapNotNull { want ->
    entries.keys.firstOrNull { it.equals(want, ignoreCase = true) }
   }.distinct()
  } else {
   entries.keys.filter {
    val low = it.lowercase()
    (low.endsWith(".xhtml") || low.endsWith(".html") || low.endsWith(".htm")) &&
     !low.contains("toc") && !low.contains("nav")
   }
  }
  val sb = StringBuilder()
  for (k in chapterKeys) {
   val html = entries[k]?.toString(Charsets.UTF_8) ?: continue
   val cleanChapter = htmlToNaturalParagraphs(html)
   if (cleanChapter.length >= 12) {
    if (sb.isNotEmpty()) sb.append(STR_PARA)
    sb.append(cleanChapter)
   }
  }
  return sb.toString().trim()
 }
 private fun normalizeZipPath(path: String): String {
  val parts = ArrayList<String>()
  for (seg in path.replace(CH_BS, '/').split('/')) {
   when (seg) {
    "", "." -> {}
    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
    else -> parts.add(seg)
   }
  }
  return parts.joinToString("/")
 }
}
object FolderRegistry {
 private val DEF_WRITER_FOLDERS = listOf("기본 폴더", "기획·초안", "독서·학습", "업무 문서")
 private val DEF_AI_FOLDERS = listOf("기본 대화", "업무·아이디어", "독서·학습", "일상 질문")
 private fun customPrefs(ctx: Context) = ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE)
 fun loadAppFolders(ctx: Context, installedApps: List<AppEntry> = emptyList()): List<AppFolder> {
  val raw = customPrefs(ctx).getString("home_app_folders_v1", null)
  if (!raw.isNullOrBlank()) {
   return runCatching {
    val arr = JSONArray(raw)
    buildList {
     for (i in 0 until arr.length()) {
      val o = arr.getJSONObject(i)
      val pArr = o.optJSONArray("packages") ?: JSONArray()
      val pkgs = buildList { for (j in 0 until pArr.length()) add(pArr.getString(j)) }.distinct()
      val nm = o.optString("name", "").trim()
      if (nm.isNotEmpty()) add(AppFolder(o.optLong("id", i + 1L), nm, pkgs))
     }
    }
   }.getOrElse { emptyList() }
  }
  val commPkgs = installedApps.filter {
   val k = (it.label + " " + it.packageName).lowercase()
   listOf("카카오", "kakao", "메시지", "message", "전화", "dialer", "메일", "gmail").any { w -> k.contains(w) }
  }.map { it.packageName }.take(8)
  val workPkgs = installedApps.filter {
   val k = (it.label + " " + it.packageName).lowercase()
   listOf("캘린더", "calendar", "노트", "note", "크롬", "chrome", "브라우저", "browser", "문서", "docs", "파일").any { w -> k.contains(w) }
  }.map { it.packageName }.take(8)
  val seeded = listOf(
   AppFolder(101L, "소통·연락", commPkgs),
   AppFolder(102L, "업무·도구", workPkgs)
  )
  if (installedApps.isNotEmpty()) saveAppFolders(ctx, seeded)
  return seeded
 }
 fun saveAppFolders(ctx: Context, list: List<AppFolder>) {
  val arr = JSONArray()
  list.forEach { f ->
   arr.put(JSONObject().apply {
    put("id", f.id)
    put("name", f.name.trim())
    put("packages", JSONArray(f.packages.distinct()))
   })
  }
  customPrefs(ctx).edit().putString("home_app_folders_v1", arr.toString()).apply()
 }
 fun loadWriterFolders(ctx: Context): List<String> =
  customPrefs(ctx).getString("writer_folders_v1", null)
   ?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.ifEmpty { DEF_WRITER_FOLDERS }
   ?: DEF_WRITER_FOLDERS
 fun saveWriterFolders(ctx: Context, folders: List<String>) {
  val clean = folders.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf("기본 폴더") }
  customPrefs(ctx).edit().putString("writer_folders_v1", clean.joinToString("|")).apply()
 }
 fun renameWriterFolder(ctx: Context, oldName: String, newName: String, docs: List<WriterDoc>): Pair<List<String>, List<WriterDoc>> {
  val nv = newName.trim()
  if (nv.isEmpty() || nv == oldName) return loadWriterFolders(ctx) to docs
  val nf = loadWriterFolders(ctx).map { if (it == oldName) nv else it }.distinct()
  saveWriterFolders(ctx, nf)
  val nd = docs.map { if (it.folder == oldName) it.copy(folder = nv) else it }
  saveWriterDocs(ctx, nd)
  return nf to nd
 }
 fun renameBookFolder(ctx: Context, repo: LauncherRepository, oldName: String, newName: String, books: List<BookItem>): Pair<List<String>, List<BookItem>> {
  val nv = newName.trim()
  if (nv.isEmpty() || nv == oldName) return SmartMemoClassifier.loadBookCats(ctx) to books
  val nf = SmartMemoClassifier.loadBookCats(ctx).map { if (it == oldName) nv else it }.distinct()
  SmartMemoClassifier.saveBookCats(ctx, nf)
  val nb = books.map { if ((it.category.ifBlank { "기타" }) == oldName) it.copy(category = nv) else it }
  repo.saveBooks(nb)
  return nf to nb
 }
 fun renameMemoFolder(ctx: Context, repo: LauncherRepository, oldName: String, newName: String, notes: List<NoteItem>): Pair<List<String>, List<NoteItem>> {
  val nv = newName.trim()
  if (nv.isEmpty() || nv == oldName) return SmartMemoClassifier.loadMemoCats(ctx) to notes
  val nf = SmartMemoClassifier.loadMemoCats(ctx).map { if (it == oldName) nv else it }.distinct()
  SmartMemoClassifier.saveMemoCats(ctx, nf)
  val nn = notes.map { n ->
   if (n.tags.contains(oldName)) n.copy(tags = n.tags.map { if (it == oldName) nv else it }.distinct())
   else n
  }
  repo.saveNotes(nn)
  return nf to nn
 }
 fun loadAiFolders(ctx: Context): List<String> =
  customPrefs(ctx).getString("ai_chat_folders_v1", null)
   ?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.ifEmpty { DEF_AI_FOLDERS }
   ?: DEF_AI_FOLDERS
 fun saveAiFolders(ctx: Context, folders: List<String>) {
  val clean = folders.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf("기본 대화") }
  customPrefs(ctx).edit().putString("ai_chat_folders_v1", clean.joinToString("|")).apply()
 }
 fun loadAiSessions(ctx: Context): List<AiChatSession> = runCatching {
  val raw = customPrefs(ctx).getString("ai_chat_sessions_v1", "[]") ?: "[]"
  val arr = JSONArray(raw)
  buildList {
   for (i in 0 until arr.length()) {
    val o = arr.getJSONObject(i)
    val mArr = o.optJSONArray("messages") ?: JSONArray()
    val msgs = buildList {
     for (j in 0 until mArr.length()) {
      val mo = mArr.getJSONObject(j)
      add(ChatMsg(mo.optLong("id", j + 1L), mo.optBoolean("isUser", false), mo.optString("text", ""), mo.optBoolean("usedMemo", false)))
     }
    }
    add(
     AiChatSession(
      id = o.optLong("id", System.currentTimeMillis() + i),
      title = o.optString("title", "AI 대화"),
      folder = o.optString("folder", "기본 대화").ifBlank { "기본 대화" },
      messages = msgs,
      updatedAt = o.optString("updatedAt", "")
     )
    )
   }
  }
 }.getOrElse { emptyList() }
 fun saveAiSessions(ctx: Context, list: List<AiChatSession>) {
  val arr = JSONArray()
  list.forEach { s ->
   val mArr = JSONArray()
   s.messages.forEach { m ->
    mArr.put(JSONObject().apply {
     put("id", m.id)
     put("isUser", m.isUser)
     put("text", m.text)
     put("usedMemo", m.usedMemo)
    })
   }
   arr.put(JSONObject().apply {
    put("id", s.id)
    put("title", s.title)
    put("folder", s.folder.ifBlank { "기본 대화" })
    put("updatedAt", s.updatedAt)
    put("messages", mArr)
   })
  }
  customPrefs(ctx).edit().putString("ai_chat_sessions_v1", arr.toString()).apply()
 }
 fun renameAiFolder(ctx: Context, oldName: String, newName: String, sessions: List<AiChatSession>): Pair<List<String>, List<AiChatSession>> {
  val nv = newName.trim()
  if (nv.isEmpty() || nv == oldName) return loadAiFolders(ctx) to sessions
  val nf = loadAiFolders(ctx).map { if (it == oldName) nv else it }.distinct()
  saveAiFolders(ctx, nf)
  val ns = sessions.map { if (it.folder == oldName) it.copy(folder = nv) else it }
  saveAiSessions(ctx, ns)
  return nf to ns
 }
}
object LauncherBackupManager {
 var backupRev by mutableStateOf(0)
 var lastReport by mutableStateOf<String?>(null)
 private fun bookFingerprint(b: BookItem): String {
  val t = b.title.trim().lowercase()
  val pCnt = b.pages.size
  val head = b.pages.firstOrNull()?.trim()?.take(80) ?: ""
  val tail = b.pages.lastOrNull()?.trim()?.takeLast(80) ?: ""
  return "$t|$pCnt|${head.hashCode()}|${tail.hashCode()}"
 }
 private fun noteFingerprint(n: NoteItem): String =
  n.content.lines().filter { !it.trim().startsWith("#") }.joinToString(STR_NL).trim()
 private fun writerFingerprint(d: WriterDoc): String =
  "${d.title.trim().lowercase()}|${d.content.trim().length}|${d.content.trim().hashCode()}"
 private fun aiSessionFingerprint(s: AiChatSession): String {
  val lastT = s.messages.lastOrNull()?.text?.trim()?.take(100) ?: ""
  return "${s.title.trim().lowercase()}|${s.messages.size}|${lastT.hashCode()}"
 }
 private fun packReaderSection(ctx: Context, repo: LauncherRepository, folderFilter: String? = null): JSONObject {
  val allBooks = repo.loadBooks()
  val books = if (folderFilter.isNullOrBlank() || folderFilter == "전체") allBooks else allBooks.filter { (it.category.ifBlank { "기타" }) == folderFilter }
  val bookIds = books.map { it.id }.toSet()
  val bms = loadBookmarks(ctx).filter { it.bookId in bookIds }
  val metaMap = loadBookMetaMap(ctx).filterKeys { it in bookIds }
  val folders = if (folderFilter.isNullOrBlank() || folderFilter == "전체") SmartMemoClassifier.loadBookCats(ctx) else listOf(folderFilter)
  val bArr = JSONArray()
  books.forEach { b ->
   bArr.put(JSONObject().apply {
    put("id", b.id)
    put("title", b.title)
    put("author", b.author)
    put("category", b.category)
    put("currentPage", b.currentPage)
    put("pages", JSONArray(b.pages))
   })
  }
  val bmArr = JSONArray()
  bms.forEach { bm ->
   bmArr.put(JSONObject().apply {
    put("bookId", bm.bookId)
    put("bookTitle", bm.bookTitle)
    put("pageIndex", bm.pageIndex)
    put("snippet", bm.snippet)
    put("charOffset", bm.charOffset)
   })
  }
  val metaObj = JSONObject()
  metaMap.forEach { (id, m) ->
   metaObj.put(id.toString(), JSONObject().apply {
    put("ord", m.sortOrder)
    put("last", m.lastReadAt)
    put("pin", m.pinned)
    put("off", m.charOffset)
   })
  }
  val allNotes = repo.loadNotes()
  val linkedNotes = allNotes.filter { n ->
   books.any { bk -> n.content.trimStart().startsWith("📚 『${bk.title.trim()}』 독서노트") }
  }
  val rnArr = JSONArray()
  linkedNotes.forEach { n ->
   rnArr.put(JSONObject().apply {
    put("id", n.id)
    put("content", n.content)
    put("summary", n.summary)
    put("tags", JSONArray(n.tags))
    put("createdAt", n.createdAt)
    put("aiEngine", n.aiEngine)
   })
  }
  return JSONObject().apply {
   put("folders", JSONArray(folders))
   put("books", bArr)
   put("bookmarks", bmArr)
   put("bookMeta", metaObj)
   put("bookNotes", rnArr)
  }
 }
 private fun packMemoSection(ctx: Context, repo: LauncherRepository, folderFilter: String? = null): JSONObject {
  val cats = SmartMemoClassifier.loadMemoCats(ctx)
  val allNotes = repo.loadNotes()
  val notes = if (folderFilter.isNullOrBlank() || folderFilter in listOf("전체", "★", "☑")) {
   when (folderFilter) {
    "★" -> allNotes.filter { SmartMemoClassifier.isImportant(it) }
    "☑" -> allNotes.filter { it.content.contains("☐") || it.content.contains("☑") }
    else -> allNotes
   }
  } else {
   allNotes.filter { SmartMemoClassifier.categoryOf(it, cats) == folderFilter }
  }
  val nArr = JSONArray()
  notes.forEach { n ->
   nArr.put(JSONObject().apply {
    put("id", n.id)
    put("content", n.content)
    put("summary", n.summary)
    put("tags", JSONArray(n.tags))
    put("createdAt", n.createdAt)
    put("aiEngine", n.aiEngine)
   })
  }
  val outCats = if (folderFilter.isNullOrBlank() || folderFilter in listOf("전체", "★", "☑")) cats else listOf(folderFilter)
  return JSONObject().apply {
   put("folders", JSONArray(outCats))
   put("notes", nArr)
  }
 }
 private fun packWriterSection(ctx: Context, folderFilter: String? = null): JSONObject {
  val folders = FolderRegistry.loadWriterFolders(ctx)
  val allDocs = loadWriterDocs(ctx)
  val docs = if (folderFilter.isNullOrBlank() || folderFilter == "전체") allDocs else allDocs.filter { it.folder == folderFilter }
  val dArr = JSONArray()
  docs.forEach { d ->
   dArr.put(JSONObject().apply {
    put("id", d.id)
    put("title", d.title)
    put("folder", d.folder)
    put("content", d.content)
    put("updatedAt", d.updatedAt)
   })
  }
  val outFolders = if (folderFilter.isNullOrBlank() || folderFilter == "전체") folders else listOf(folderFilter)
  return JSONObject().apply {
   put("folders", JSONArray(outFolders))
   put("docs", dArr)
  }
 }
 private fun packHomeSection(ctx: Context, repo: LauncherRepository, folderFilter: String? = null): JSONObject {
  val allFolders = FolderRegistry.loadAppFolders(ctx)
  val folders = if (folderFilter.isNullOrBlank() || folderFilter == "전체") allFolders else allFolders.filter { it.name == folderFilter }
  val fArr = JSONArray()
  folders.forEach { f ->
   fArr.put(JSONObject().apply {
    put("id", f.id)
    put("name", f.name)
    put("packages", JSONArray(f.packages))
   })
  }
  val cfg = repo.loadConfig()
  val p2 = ctx.getSharedPreferences("pl_ai_v2_prefs", Context.MODE_PRIVATE)
  return JSONObject().apply {
   put("appFolders", fArr)
   put("favorites", JSONArray(repo.getFavoritePackages().toList()))
   put("interrupts", JSONArray(repo.getInterruptPackages().toList()))
   put("homeColMode", p2.getInt("home_app_col_mode", 1))
   put("homeGapMode", p2.getInt("home_app_gap_mode", 0))
   put("themeName", cfg.themeName)
   put("textAlign", cfg.textAlign)
   put("readerTheme", cfg.readerTheme)
   put("readerFontSizeSp", cfg.readerFontSizeSp)
   put("readerUseSerif", cfg.readerUseSerif)
   put("briefCfgRaw", p2.getString("brief_cfg_v2", "") ?: "")
  }
 }
 private fun packAiSection(ctx: Context, folderFilter: String? = null): JSONObject {
  val folders = FolderRegistry.loadAiFolders(ctx)
  val allSessions = FolderRegistry.loadAiSessions(ctx)
  val sessions = if (folderFilter.isNullOrBlank() || folderFilter == "전체") allSessions else allSessions.filter { it.folder == folderFilter }
  val sArr = JSONArray()
  sessions.forEach { s ->
   val mArr = JSONArray()
   s.messages.forEach { m ->
    mArr.put(JSONObject().apply {
     put("id", m.id)
     put("isUser", m.isUser)
     put("text", m.text)
     put("usedMemo", m.usedMemo)
    })
   }
   sArr.put(JSONObject().apply {
    put("id", s.id)
    put("title", s.title)
    put("folder", s.folder)
    put("updatedAt", s.updatedAt)
    put("messages", mArr)
   })
  }
  val outFolders = if (folderFilter.isNullOrBlank() || folderFilter == "전체") folders else listOf(folderFilter)
  return JSONObject().apply {
   put("folders", JSONArray(outFolders))
   put("sessions", sArr)
  }
 }
 fun exportBackupJson(ctx: Context, repo: LauncherRepository, scope: String, folderFilter: String? = null): String {
  val root = JSONObject().apply {
   put("schema", "PRO_LAUNCHER_BACKUP_V10_5")
   put("scope", scope)
   put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date()))
   if (!folderFilter.isNullOrBlank()) put("folderFilter", folderFilter)
   when (scope) {
    "FULL" -> {
     put("home", packHomeSection(ctx, repo))
     put("writer", packWriterSection(ctx))
     put("reader", packReaderSection(ctx, repo))
     put("memo", packMemoSection(ctx, repo))
     put("ai", packAiSection(ctx))
    }
    "HOME" -> put("home", packHomeSection(ctx, repo, folderFilter))
    "WRITER" -> put("writer", packWriterSection(ctx, folderFilter))
    "READER" -> put("reader", packReaderSection(ctx, repo, folderFilter))
    "MEMO" -> put("memo", packMemoSection(ctx, repo, folderFilter))
    "AI" -> put("ai", packAiSection(ctx, folderFilter))
   }
  }
  return root.toString(2)
 }
 private fun mergeNotesJsonArray(ctx: Context, repo: LauncherRepository, nArr: JSONArray, incomingFolders: List<String> = emptyList()): Pair<Int, Int> {
  val curNotes = repo.loadNotes().toMutableList()
  val seenFp = curNotes.associateBy { noteFingerprint(it) }.toMutableMap()
  val usedIds = curNotes.map { it.id }.toMutableSet()
  var nextSafeId = (usedIds.maxOrNull() ?: System.currentTimeMillis()).coerceAtLeast(System.currentTimeMillis()) + 1L
  var added = 0
  var kept = 0
  for (i in 0 until nArr.length()) {
   val o = nArr.optJSONObject(i) ?: continue
   val content = o.optString("content", "")
   if (content.isBlank()) continue
   val tArr = o.optJSONArray("tags") ?: JSONArray()
   val tags = buildList { for (j in 0 until tArr.length()) add(tArr.getString(j)) }.ifEmpty { listOf("일상", "일반") }
   val rawId = o.optLong("id", nextSafeId++)
   val candidate = NoteItem(rawId, content, o.optString("summary", ""), tags, o.optString("createdAt", "복원"), o.optString("aiEngine", "LocalAI"))
   val fp = noteFingerprint(candidate)
   val firstLine = content.lineSequence().firstOrNull()?.trim() ?: ""
   if (firstLine.startsWith("📚 『") && firstLine.contains("』 독서노트")) {
    val existingIdx = curNotes.indexOfFirst { it.content.lineSequence().firstOrNull()?.trim() == firstLine }
    if (existingIdx >= 0) {
     val ex = curNotes[existingIdx]
     if (!ex.content.contains(fp) && fp != noteFingerprint(ex)) {
      val incBody = content.substringAfter(CH_NL, "").lines().filter { !it.trim().startsWith("#") }.joinToString(STR_NL).trim()
      if (incBody.isNotEmpty() && !ex.content.contains(incBody)) {
       val mergedRaw = noteFingerprint(ex) + STR_PARA + "────────────" + STR_NL + incBody
       curNotes[existingIdx] = ex.copy(content = makeHashtags(mergedRaw, "독서"))
       added++
      } else kept++
     } else kept++
     continue
    }
   }
   if (fp.isNotEmpty() && seenFp.containsKey(fp)) {
    kept++
   } else {
    val finalId = if (rawId in usedIds) nextSafeId++ else rawId
    usedIds.add(finalId)
    val fixed = candidate.copy(id = finalId)
    curNotes.add(fixed)
    if (fp.isNotEmpty()) seenFp[fp] = fixed
    added++
   }
  }
  val curCats = SmartMemoClassifier.loadMemoCats(ctx)
  val noteDiscoveredCats = curNotes.mapNotNull { n ->
   n.tags.firstOrNull { it !in listOf("중요", "일반", "메모", "할일") }
  }
  val mergedCats = (curCats + incomingFolders + noteDiscoveredCats).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
  SmartMemoClassifier.saveMemoCats(ctx, mergedCats)
  repo.saveNotes(curNotes)
  ScheduleBriefingManager.syncNoteAlarms(ctx, curNotes)
  return added to kept
 }
 private fun mergeReaderSection(ctx: Context, repo: LauncherRepository, rObj: JSONObject): String {
  val curBooks = repo.loadBooks().toMutableList()
  val fpToExisting = curBooks.associateBy { bookFingerprint(it) }.toMutableMap()
  val usedIds = curBooks.map { it.id }.toMutableSet()
  var nextSafeId = (usedIds.maxOrNull() ?: System.currentTimeMillis()).coerceAtLeast(System.currentTimeMillis()) + 1L
  val idRemap = HashMap<Long, Long>()
  var addedBooks = 0
  var mergedExisting = 0
  val bArr = rObj.optJSONArray("books") ?: JSONArray()
  for (i in 0 until bArr.length()) {
   val o = bArr.optJSONObject(i) ?: continue
   val pArr = o.optJSONArray("pages") ?: continue
   val pages = buildList { for (j in 0 until pArr.length()) add(pArr.getString(j)) }.filter { it.isNotBlank() }
   if (pages.isEmpty()) continue
   val oldId = o.optLong("id", nextSafeId++)
   val incoming = BookItem(
    id = oldId,
    title = o.optString("title", "복원 도서"),
    author = o.optString("author", "내 서재"),
    category = o.optString("category", "기타").ifBlank { "기타" },
    pages = pages,
    currentPage = o.optInt("currentPage", 0)
   )
   val fp = bookFingerprint(incoming)
   val match = fpToExisting[fp]
   if (match != null) {
    idRemap[oldId] = match.id
    if (incoming.currentPage > match.currentPage) {
     val idx = curBooks.indexOfFirst { it.id == match.id }
     if (idx >= 0) curBooks[idx] = match.copy(currentPage = incoming.currentPage.coerceAtMost(match.pages.lastIndex))
    }
    mergedExisting++
   } else {
    val safeId = if (oldId in usedIds) nextSafeId++ else oldId
    usedIds.add(safeId)
    idRemap[oldId] = safeId
    val savedBook = incoming.copy(id = safeId)
    curBooks.add(savedBook)
    fpToExisting[fp] = savedBook
    addedBooks++
   }
  }
  repo.saveBooks(curBooks)
  val fArr = rObj.optJSONArray("folders") ?: JSONArray()
  val incFolders = buildList { for (i in 0 until fArr.length()) add(fArr.getString(i)) }
  val mergedCats = (SmartMemoClassifier.loadBookCats(ctx) + incFolders + curBooks.map { it.category.ifBlank { "기타" } })
   .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
  SmartMemoClassifier.saveBookCats(ctx, mergedCats)
  val curBms = loadBookmarks(ctx).toMutableList()
  val bmArr = rObj.optJSONArray("bookmarks") ?: JSONArray()
  var addedBms = 0
  for (i in 0 until bmArr.length()) {
   val bo = bmArr.optJSONObject(i) ?: continue
   val origBid = bo.optLong("bookId", -1L)
   val mappedBid = idRemap[origBid] ?: origBid
   if (curBooks.none { it.id == mappedBid }) continue
   val off = bo.optInt("charOffset", -1)
   val pg = bo.optInt("pageIndex", 0)
   val dup = curBms.any { it.bookId == mappedBid && ((off >= 0 && kotlin.math.abs(it.charOffset - off) < 12) || (off < 0 && it.pageIndex == pg)) }
   if (!dup) {
    curBms.add(BookmarkEntry(mappedBid, bo.optString("bookTitle", "도서"), pg, bo.optString("snippet", ""), off))
    addedBms++
   }
  }
  saveBookmarks(ctx, curBms)
  val curMeta = loadBookMetaMap(ctx).toMutableMap()
  val metaObj = rObj.optJSONObject("bookMeta")
  if (metaObj != null) {
   val keys = metaObj.keys()
   while (keys.hasNext()) {
    val k = keys.next()
    val origBid = k.toLongOrNull() ?: continue
    val mappedBid = idRemap[origBid] ?: origBid
    val mo = metaObj.optJSONObject(k) ?: continue
    val incMeta = BookSortMeta(
     sortOrder = mo.optInt("ord", curMeta.size),
     lastReadAt = mo.optLong("last", 0L),
     pinned = mo.optBoolean("pin", false),
     charOffset = mo.optInt("off", -1)
    )
    val prev = curMeta[mappedBid]
    curMeta[mappedBid] = if (prev == null) incMeta else BookSortMeta(
     sortOrder = prev.sortOrder,
     lastReadAt = maxOf(prev.lastReadAt, incMeta.lastReadAt),
     pinned = prev.pinned || incMeta.pinned,
     charOffset = maxOf(prev.charOffset, incMeta.charOffset)
    )
   }
   saveBookMetaMap(ctx, curMeta)
  }
  val rnArr = rObj.optJSONArray("bookNotes")
  if (rnArr != null && rnArr.length() > 0) {
   mergeNotesJsonArray(ctx, repo, rnArr, listOf("독서"))
  }
  return "도서 +${addedBooks}권 추가(기존 ${mergedExisting}권 진도보존, 책갈피 +${addedBms}개)"
 }
 private fun mergeWriterSection(ctx: Context, wObj: JSONObject): String {
  val curDocs = loadWriterDocs(ctx).toMutableList()
  val seenFp = curDocs.associateBy { writerFingerprint(it) }.toMutableMap()
  val usedIds = curDocs.map { it.id }.toMutableSet()
  var nextSafeId = (usedIds.maxOrNull() ?: System.currentTimeMillis()).coerceAtLeast(System.currentTimeMillis()) + 1L
  var added = 0
  var kept = 0
  val dArr = wObj.optJSONArray("docs") ?: JSONArray()
  for (i in 0 until dArr.length()) {
   val o = dArr.optJSONObject(i) ?: continue
   val rawId = o.optLong("id", nextSafeId++)
   val cand = WriterDoc(
    id = rawId,
    title = o.optString("title", "복원 문서"),
    content = o.optString("content", ""),
    updatedAt = o.optString("updatedAt", "복원"),
    folder = o.optString("folder", "기본 폴더").ifBlank { "기본 폴더" }
   )
   if (cand.content.isBlank() && cand.title.isBlank()) continue
   val fp = writerFingerprint(cand)
   if (seenFp.containsKey(fp)) {
    kept++
   } else {
    val safeId = if (rawId in usedIds) nextSafeId++ else rawId
    usedIds.add(safeId)
    val fixed = cand.copy(id = safeId)
    curDocs.add(fixed)
    seenFp[fp] = fixed
    added++
   }
  }
  saveWriterDocs(ctx, curDocs)
  val fArr = wObj.optJSONArray("folders") ?: JSONArray()
  val incFolders = buildList { for (i in 0 until fArr.length()) add(fArr.getString(i)) }
  val mergedFolders = (FolderRegistry.loadWriterFolders(ctx) + incFolders + curDocs.map { it.folder }).distinct()
  FolderRegistry.saveWriterFolders(ctx, mergedFolders)
  return "문서 +${added}건 추가(중복 ${kept}건 유지)"
 }
 private fun mergeHomeSection(ctx: Context, repo: LauncherRepository, hObj: JSONObject): String {
  val curFolders = FolderRegistry.loadAppFolders(ctx).toMutableList()
  var nextId = (curFolders.maxOfOrNull { it.id } ?: 200L) + 1L
  val fArr = hObj.optJSONArray("appFolders") ?: JSONArray()
  var addedF = 0
  var mergedF = 0
  for (i in 0 until fArr.length()) {
   val o = fArr.optJSONObject(i) ?: continue
   val nm = o.optString("name", "").trim()
   if (nm.isEmpty()) continue
   val pArr = o.optJSONArray("packages") ?: JSONArray()
   val pkgs = buildList { for (j in 0 until pArr.length()) add(pArr.getString(j)) }
   val idx = curFolders.indexOfFirst { it.name == nm }
   if (idx >= 0) {
    val ex = curFolders[idx]
    curFolders[idx] = ex.copy(packages = (ex.packages + pkgs).distinct())
    mergedF++
   } else {
    curFolders.add(AppFolder(nextId++, nm, pkgs.distinct()))
    addedF++
   }
  }
  FolderRegistry.saveAppFolders(ctx, curFolders)
  val p2 = ctx.getSharedPreferences("pl_ai_v2_prefs", Context.MODE_PRIVATE)
  val favArr = hObj.optJSONArray("favorites")
  if (favArr != null) {
   val incFav = buildList { for (i in 0 until favArr.length()) add(favArr.getString(i)) }
   p2.edit().putStringSet("fav_pkgs", (repo.getFavoritePackages() + incFav).toSet()).apply()
  }
  val intArr = hObj.optJSONArray("interrupts")
  if (intArr != null) {
   val incInt = buildList { for (i in 0 until intArr.length()) add(intArr.getString(i)) }
   p2.edit().putStringSet("int_pkgs", (repo.getInterruptPackages() + incInt).toSet()).apply()
  }
  return "앱폴더 +${addedF}개 신설(기존 ${mergedF}개 병합)"
 }
 private fun mergeAiSection(ctx: Context, aObj: JSONObject): String {
  val curSessions = FolderRegistry.loadAiSessions(ctx).toMutableList()
  val seenFp = curSessions.associateBy { aiSessionFingerprint(it) }.toMutableMap()
  val usedIds = curSessions.map { it.id }.toMutableSet()
  var nextSafeId = (usedIds.maxOrNull() ?: System.currentTimeMillis()).coerceAtLeast(System.currentTimeMillis()) + 1L
  var added = 0
  var kept = 0
  val sArr = aObj.optJSONArray("sessions") ?: JSONArray()
  for (i in 0 until sArr.length()) {
   val o = sArr.optJSONObject(i) ?: continue
   val mArr = o.optJSONArray("messages") ?: JSONArray()
   val msgs = buildList {
    for (j in 0 until mArr.length()) {
     val mo = mArr.optJSONObject(j) ?: continue
     add(ChatMsg(mo.optLong("id", j + 1L), mo.optBoolean("isUser", false), mo.optString("text", ""), mo.optBoolean("usedMemo", false)))
    }
   }
   if (msgs.isEmpty()) continue
   val rawId = o.optLong("id", nextSafeId++)
   val cand = AiChatSession(
    id = rawId,
    title = o.optString("title", "복원된 대화"),
    folder = o.optString("folder", "기본 대화").ifBlank { "기본 대화" },
    messages = msgs,
    updatedAt = o.optString("updatedAt", "복원")
   )
   val fp = aiSessionFingerprint(cand)
   if (seenFp.containsKey(fp)) {
    kept++
   } else {
    val safeId = if (rawId in usedIds) nextSafeId++ else rawId
    usedIds.add(safeId)
    val fixed = cand.copy(id = safeId)
    curSessions.add(fixed)
    seenFp[fp] = fixed
    added++
   }
  }
  FolderRegistry.saveAiSessions(ctx, curSessions)
  val fArr = aObj.optJSONArray("folders") ?: JSONArray()
  val incFolders = buildList { for (i in 0 until fArr.length()) add(fArr.getString(i)) }
  val mergedFolders = (FolderRegistry.loadAiFolders(ctx) + incFolders + curSessions.map { it.folder }).distinct()
  FolderRegistry.saveAiFolders(ctx, mergedFolders)
  return "AI대화 +${added}건 추가(중복 ${kept}건 유지)"
 }
 fun importAndMergeBackupJson(ctx: Context, repo: LauncherRepository, rawJson: String): String {
  val trimmed = rawJson.trim()
  if (trimmed.isEmpty()) return "⚠️ 비어 있는 백업 파일입니다."
  return runCatching {
   val parts = ArrayList<String>()
   if (trimmed.startsWith("[")) {
    val arr = JSONArray(trimmed)
    val firstObj = arr.optJSONObject(0)
    if (firstObj != null && firstObj.has("pages")) {
     val msg = mergeReaderSection(ctx, repo, JSONObject().put("books", arr))
     parts.add(msg)
    } else {
     val (added, kept) = mergeNotesJsonArray(ctx, repo, arr)
     parts.add("메모 +${added}건 추가(중복 ${kept}건 유지)")
    }
   } else {
    val root = JSONObject(trimmed)
    root.optJSONObject("home")?.let { parts.add(mergeHomeSection(ctx, repo, it)) }
    root.optJSONObject("writer")?.let { parts.add(mergeWriterSection(ctx, it)) }
    root.optJSONObject("reader")?.let { parts.add(mergeReaderSection(ctx, repo, it)) }
    root.optJSONObject("memo")?.let { mo ->
     val fArr = mo.optJSONArray("folders") ?: JSONArray()
     val incF = buildList { for (i in 0 until fArr.length()) add(fArr.getString(i)) }
     val (added, kept) = mergeNotesJsonArray(ctx, repo, mo.optJSONArray("notes") ?: JSONArray(), incF)
     parts.add("메모 +${added}건 추가(중복 ${kept}건 유지)")
    }
    root.optJSONObject("ai")?.let { parts.add(mergeAiSection(ctx, it)) }
   }
   val summary = if (parts.isEmpty()) "⚠️ 복원할 항목을 찾지 못했습니다." else "♻️ 무충돌 병합 복원 완료: " + parts.joinToString(" · ")
   lastReport = summary
   backupRev += 1
   summary
  }.getOrElse { e ->
   "⚠️ 백업 파일 형식을 읽지 못했습니다: ${e.message ?: "오류"}"
  }
 }
}
fun findBookDedicatedNoteBody(notes: List<NoteItem>, bookTitle: String): String {
 val cleanTitle = bookTitle.trim().ifEmpty { "도서" }
 val header = "📚 『$cleanTitle』 독서노트"
 val found = notes.firstOrNull { it.content.lineSequence().firstOrNull()?.trim() == header } ?: return ""
 return found.content.lineSequence()
  .drop(1)
  .filter { !it.trim().startsWith("#") }
  .joinToString(STR_NL)
  .trim()
}
fun upsertBookDedicatedNote(notes: List<NoteItem>, bookTitle: String, fullBody: String): List<NoteItem> {
 val cleanTitle = bookTitle.trim().ifEmpty { "도서" }
 val header = "📚 『$cleanTitle』 독서노트"
 val stamp = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
 val bodyTrim = fullBody.trim()
 val idx = notes.indexOfFirst { it.content.lineSequence().firstOrNull()?.trim() == header }
 if (bodyTrim.isEmpty()) {
  return if (idx >= 0) notes.filterIndexed { i, _ -> i != idx } else notes
 }
 val rawFull = header + STR_PARA + bodyTrim
 val formatted = makeHashtags(rawFull, "독서")
 return if (idx >= 0) {
  val target = notes[idx]
  val updated = target.copy(
   content = formatted,
   summary = "",
   tags = (listOf("독서") + target.tags).distinct(),
   createdAt = stamp
  )
  listOf(updated) + notes.filterIndexed { i, _ -> i != idx }
 } else {
  val created = NoteItem(nextId(), formatted, "", listOf("독서", "일반"), stamp, "LocalAI")
  listOf(created) + notes
 }
}
data class BookSearchHit(
 val bookId: Long,
 val bookTitle: String,
 val category: String,
 val charOffset: Int,
 val matchEndOffset: Int,
 val approxPct: Int,
 val snippet: String,
 val isTitleMatch: Boolean = false
)
object BookPassageSearcher {
 fun findMatchRangesInText(fullText: String, rawQuery: String, maxHits: Int = 80): List<IntRange> {
  val q = rawQuery.trim()
  if (q.isEmpty() || fullText.isEmpty()) return emptyList()
  val tokens = q.split(' ', CH_NL, CH_CR, CH_TAB).map { it.trim() }.filter { it.isNotEmpty() }
  if (tokens.isEmpty()) return emptyList()
  val out = ArrayList<IntRange>()
  val n = fullText.length
  var searchFrom = 0
  while (searchFrom < n && out.size < maxHits) {
   val firstIdx = fullText.indexOf(tokens[0], searchFrom, ignoreCase = true)
   if (firstIdx < 0) break
   var cur = firstIdx + tokens[0].length
   var allOk = true
   for (tIdx in 1 until tokens.size) {
    var wsCount = 0
    while (cur < n && (fullText[cur].isWhitespace() || fullText[cur] == CH_NL || fullText[cur] == CH_CR)) {
     cur++
     wsCount++
    }
    val tk = tokens[tIdx]
    if (wsCount == 0 || wsCount > 16 || cur + tk.length > n || !fullText.regionMatches(cur, tk, 0, tk.length, ignoreCase = true)) {
     allOk = false
     break
    }
    cur += tk.length
   }
   if (allOk) {
    out.add(firstIdx until cur)
    searchFrom = (firstIdx + tokens[0].length).coerceAtLeast(firstIdx + 1)
   } else {
    searchFrom = firstIdx + 1
   }
  }
  return out
 }
 fun buildContextSnippet(fullText: String, range: IntRange, radius: Int = 32): String {
  if (fullText.isEmpty()) return ""
  val s = (range.first - radius).coerceAtLeast(0)
  val e = (range.last + 1 + radius).coerceAtMost(fullText.length)
  val pre = fullText.substring(s, range.first).replace(CH_NL, ' ').replace(CH_CR, ' ').trimStart()
  val mid = fullText.substring(range.first, (range.last + 1).coerceAtMost(fullText.length)).replace(CH_NL, ' ')
  val post = fullText.substring((range.last + 1).coerceAtMost(fullText.length), e).replace(CH_NL, ' ').replace(CH_CR, ' ').trimEnd()
  val prefixEllip = if (s > 0) "…" else ""
  val suffixEllip = if (e < fullText.length) "…" else ""
  return "$prefixEllip$pre 【$mid】 $post$suffixEllip"
 }
 fun searchBookBody(book: BookItem, fullText: String, rawQuery: String, maxHits: Int = 60): List<BookSearchHit> {
  val ranges = findMatchRangesInText(fullText, rawQuery, maxHits)
  if (ranges.isEmpty()) return emptyList()
  val totalLen = fullText.length.coerceAtLeast(1)
  return ranges.map { r ->
   val pct = (((r.first + 1) * 100) / totalLen).coerceIn(1, 100)
   BookSearchHit(
    bookId = book.id,
    bookTitle = book.title,
    category = book.category.ifBlank { "기타" },
    charOffset = r.first,
    matchEndOffset = r.last + 1,
    approxPct = pct,
    snippet = buildContextSnippet(fullText, r),
    isTitleMatch = false
   )
  }
 }
 fun searchAllBooks(books: List<BookItem>, rawQuery: String, smartReflow: Boolean = true, maxTotalHits: Int = 80): List<BookSearchHit> {
  val q = rawQuery.trim()
  if (q.isEmpty() || books.isEmpty()) return emptyList()
  val out = ArrayList<BookSearchHit>()
  for (b in books) {
   if (out.size >= maxTotalHits) break
   val isPoetry = b.category.contains("시")
   val src = buildBookReflowSource(b.pages, smartReflow = smartReflow, preservePoetry = isPoetry)
   val remain = (maxTotalHits - out.size).coerceAtLeast(1)
   val bodyHits = searchBookBody(b, src.fullText, q, maxHits = minOf(15, remain))
   if (bodyHits.isNotEmpty()) {
    out.addAll(bodyHits)
   } else if (KoreanChosungMatcher.matches(b.title + " " + b.author, q)) {
    val preview = src.fullText.take(64).replace(CH_NL, ' ').trim()
    out.add(
     BookSearchHit(
      bookId = b.id,
      bookTitle = b.title,
      category = b.category.ifBlank { "기타" },
      charOffset = 0,
      matchEndOffset = 0,
      approxPct = (((b.currentPage + 1) * 100) / b.pages.size.coerceAtLeast(1)).coerceIn(1, 100),
      snippet = "📕 제목·저자 일치 (${b.author}) · $preview…",
      isTitleMatch = true
     )
    )
   }
  }
  return out
 }
}
