package app.prolauncher.ai
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object KoreanChosungMatcher {
private val CHO = charArrayOf('ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ','ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ')
fun extractChosung(text: String): String {
val sb = StringBuilder(text.length)
for (ch in text) {
if (ch in '\uAC00'..'\uD7A3') sb.append(CHO[(ch.code - 0xAC00) / (21 * 28)])
else sb.append(ch.lowercaseChar())
}
return sb.toString()
}
fun matches(target: String, query: String): Boolean {
val q = query.trim().lowercase().replace(" ", "")
if (q.isEmpty()) return true
val clean = target.lowercase().replace(" ", "")
return clean.contains(q) || extractChosung(clean).contains(q)
}
}

data class ResponsiveUi(
val hPad: Dp, val vPad: Dp, val cardPad: Dp,
val titleSp: TextUnit, val bodySp: TextUnit, val subSp: TextUnit,
val panelMaxH: Dp, val isCompact: Boolean
)

@Composable
fun rememberUi(scaleMode: String = "AUTO"): ResponsiveUi {
val conf = LocalConfiguration.current
val w = conf.screenWidthDp; val h = conf.screenHeightDp
val mult = when (scaleMode) {
"COMPACT" -> 0.88f
"LARGE" -> 1.12f
else -> if (h < 700 || w < 350) 0.90f else if (h > 850 || w > 410) 1.06f else 1.0f
}
val compact = h < 700 || w < 350 || scaleMode == "COMPACT"
return ResponsiveUi(
hPad = ((if (compact) 11f else 15f) * mult).dp,
vPad = ((if (compact) 8f else 12f) * mult).dp,
cardPad = ((if (compact) 9f else 12f) * mult).dp,
titleSp = ((if (compact) 15f else 17f) * mult).sp,
bodySp = ((if (compact) 13f else 14f) * mult).sp,
subSp = ((if (compact) 10f else 11f) * mult).sp,
panelMaxH = (h * 0.36f).coerceIn(180f, 320f).dp,
isCompact = compact
)
}

const val DEF_SYS_PROMPT = "당신은 사용자의 질문 의도를 정확히 파악해 핵심만 명쾌하고 논리적으로 답하는 스마트 AI 챗봇입니다. 동문서답 없이 사용자의 질문에 직접적으로, 구체적이고 실용적인 한국어로 답변하세요."

val PROMPT_PRESETS = listOf(
"🤖 기본 비서" to DEF_SYS_PROMPT,
"📌 핵심 요약" to "당신은 요약 전문가입니다. 핵심 결론을 먼저 1줄로 제시하고 세부 요점을 글머리(•)로 간결하게 정리하세요.",
"💡 기획 코치" to "당신은 아이디어 코치입니다. 주제에 대해 실행 가능한 아이디어 3가지와 구체적인 첫 단계를 제안하세요.",
"✍️ 문장 교정" to "당신은 전문 에디터입니다. 사용자의 문장을 자연스럽고 세련된 한국어 표현으로 다듬어 주세요."
)

data class AiRagConfig(
val sysPrompt: String = DEF_SYS_PROMPT,
val ragOn: Boolean = false,
val ragScope: String = "ALL",
val ragTopK: Int = 5,
val ragBooks: Boolean = true,
val ragImpOnly: Boolean = false,
val uiScale: String = "AUTO"
)

fun loadAiCfg(ctx: Context): AiRagConfig {
val p = ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE)
return AiRagConfig(
sysPrompt = p.getString("ai_sys_prompt", DEF_SYS_PROMPT)?.ifBlank { DEF_SYS_PROMPT } ?: DEF_SYS_PROMPT,
ragOn = p.getBoolean("ai_rag_on", false),
ragScope = p.getString("ai_rag_scope", "ALL") ?: "ALL",
ragTopK = p.getInt("ai_rag_topk", 5),
ragBooks = p.getBoolean("ai_rag_books", true),
ragImpOnly = p.getBoolean("ai_rag_imp", false),
uiScale = p.getString("ui_scale_mode", "AUTO") ?: "AUTO"
)
}

fun saveAiCfg(ctx: Context, c: AiRagConfig) {
ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).edit()
.putString("ai_sys_prompt", c.sysPrompt).putBoolean("ai_rag_on", c.ragOn)
.putString("ai_rag_scope", c.ragScope).putInt("ai_rag_topk", c.ragTopK)
.putBoolean("ai_rag_books", c.ragBooks).putBoolean("ai_rag_imp", c.ragImpOnly)
.putString("ui_scale_mode", c.uiScale).apply()
}

data class ChatMsg(val id: Long, val isUser: Boolean, val text: String, val usedMemo: Boolean = false)

fun callLlmHttp(sysPrompt: String, ragCtx: String, history: List<ChatMsg>, userMsg: String): String? = runCatching {
val conn = (URL("https://text.pollinations.ai/").openConnection() as HttpURLConnection).apply {
requestMethod = "POST"; connectTimeout = 9000; readTimeout = 15000; doOutput = true
setRequestProperty("Content-Type", "application/json; charset=UTF-8")
}
val fullSys = if (ragCtx.isBlank()) sysPrompt else "$sysPrompt\n\n[사용자의 메모장 및 독서대 참고 데이터]\n$ragCtx\n위 데이터를 바탕으로 질문에 정확하고 구체적으로 답변하세요."
val msgs = JSONArray().apply {
put(JSONObject().apply { put("role", "system"); put("content", fullSys) })
history.takeLast(6).forEach { m ->
put(JSONObject().apply { put("role", if (m.isUser) "user" else "assistant"); put("content", m.text) })
}
put(JSONObject().apply { put("role", "user"); put("content", userMsg) })
}
val payload = JSONObject().apply { put("messages", msgs); put("model", "openai"); put("seed", 42) }.toString()
OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
if (conn.responseCode in 200..299) {
val r = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }.trim()
if (r.isNotEmpty() && !r.startsWith("<!DOCTYPE")) r else null
} else null
}.getOrNull()

suspend fun buildAiChatReply(
qRaw: String, aiCfg: AiRagConfig, history: List<ChatMsg>,
notes: List<NoteItem>, books: List<BookItem>, cats: List<String>, ai: LocalAiNoteEngine
): String = withContext(Dispatchers.IO) {
val q = qRaw.trim(); val low = q.lowercase()
val useRag = aiCfg.ragOn || (q.contains("내 메모") || q.contains("전체 메모"))
val ragContext = if (useRag && notes.isNotEmpty()) {
val baseNotes = if (aiCfg.ragImpOnly) notes.filter { SMC.isImportant(it) } else notes
val targetNotes = if (aiCfg.ragScope == "ALL") baseNotes else {
val kws = q.split(" ").filter { it.length >= 2 }
baseNotes.sortedByDescending { n -> kws.count { k -> n.content.contains(k, true) || n.tags.any { t -> t.contains(k, true) } } }.take(aiCfg.ragTopK.coerceAtLeast(1))
}
buildString {
append("총 메모 ${notes.size}건 중 참조된 메모 (${targetNotes.size}건):\n")
targetNotes.forEachIndexed { idx, n ->
val impStr = if (SMC.isImportant(n)) "[★중요] " else ""
append("${idx + 1}. $impStr[${SMC.categoryOf(n, cats)} · ${n.createdAt}] ${n.content.replace("\n", " / ")}\n")
}
if (aiCfg.ragBooks && books.isNotEmpty()) {
append("\n독서대 도서: ")
books.take(3).forEach { b -> append("『${b.title}』(${b.category}) ") }
}
}
} else ""

val liveResp = callLlmHttp(aiCfg.sysPrompt, ragContext, history, q)
if (!liveResp.isNullOrBlank()) return@withContext liveResp

if (!useRag) {
if (low.contains("요약") || q.lines().size >= 3 || q.length >= 80) return@withContext "✨ [핵심 요약]\n${SMC.summarizeByLength(q, true)}"
return@withContext "🤖 [오프라인 AI 답변]\n질문하신 '$q'에 대한 답변입니다:\n• 현재 오프라인 상태여서 내장 엔진이 응답했습니다. 네트워크가 연결되면 설정하신 프롬프트('${aiCfg.sysPrompt.take(30)}…')를 바탕으로 LLM이 모든 일반 질문에 답변합니다."
}
if (notes.isEmpty()) return@withContext "현재 저장된 메모가 없습니다. [📝 메모] 탭에서 메모를 먼저 작성해 주세요."
if (low.contains("요약") || low.contains("정리") || low.contains("브리핑") || low.contains("전체")) {
val imps = notes.filter { SMC.isImportant(it) }
return@withContext buildString {
append("🗂️ [전체 메모 RAG 분석 브리핑 · 총 ${notes.size}건]\n")
if (imps.isNotEmpty()) { append("★ 중요 메모 (${imps.size}건):\n"); imps.forEach { n -> append("• [${SMC.categoryOf(n, cats)}] ${n.content.replace("\n", " ").take(65)}\n") } }
append("📌 전체 메모 요약:\n")
notes.take(8).forEach { n ->
val s = SMC.summarizeByLength(n.content, false).ifBlank { n.content.replace("\n", " ").take(60) }
append("• [${SMC.categoryOf(n, cats)} · ${n.createdAt}] $s\n")
}
}.trim()
}
if (low.contains("할일") || low.contains("할 일") || low.contains("체크") || low.contains("일정")) {
val unc = notes.flatMap { n -> n.content.lines().filter { it.trimStart().startsWith("☐ ") }.map { "[${SMC.categoryOf(n, cats)}] $it" } }
return@withContext if (unc.isNotEmpty()) "✅ 미완료 체크리스트 (${unc.size}건):\n" + unc.joinToString("\n") else "미완료된 체크리스트(☐)가 없습니다."
}
ai.askRag(q, notes, books, aiCfg.ragBooks)
}

@Composable
fun AiPromptRagPanel(
ctx: Context, aiCfg: AiRagConfig, ui: ResponsiveUi,
onUpdate: (AiRagConfig) -> Unit, onPickModel: () -> Unit
) {
Surface(color = Color(0xFF14161F), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CA), modifier = Modifier.fillMaxWidth().heightIn(max = ui.panelMaxH)) {
Column(Modifier.verticalScroll(rememberScrollState()).padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("⚙️ 기본 프롬프트 수정 & RAG 세부 설정", fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold, color = CA)
Text("기본값 복원", fontSize = ui.subSp, color = CG, modifier = Modifier.clickable { val u = aiCfg.copy(sysPrompt = DEF_SYS_PROMPT); onUpdate(u); saveAiCfg(ctx, u) })
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
PROMPT_PRESETS.forEach { (label, promptTxt) ->
ChipBtn(label, aiCfg.sysPrompt == promptTxt, CA) { val u = aiCfg.copy(sysPrompt = promptTxt); onUpdate(u); saveAiCfg(ctx, u) }
}
}
OutlinedTextField(
value = aiCfg.sysPrompt,
onValueChange = { val u = aiCfg.copy(sysPrompt = it); onUpdate(u); saveAiCfg(ctx, u) },
label = { Text("시스템 프롬프트 (AI 역할·말투·규칙 직접 수정)") },
singleLine = false, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
)
Text("RAG 작동 범위 및 옵션 설정", fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("전체 메모 모두 읽기 (기본)", aiCfg.ragScope == "ALL") { val u = aiCfg.copy(ragScope = "ALL"); onUpdate(u); saveAiCfg(ctx, u) }
ChipBtn("연관 상위 3건", aiCfg.ragScope == "TOPK" && aiCfg.ragTopK == 3) { val u = aiCfg.copy(ragScope = "TOPK", ragTopK = 3); onUpdate(u); saveAiCfg(ctx, u) }
ChipBtn("연관 상위 5건", aiCfg.ragScope == "TOPK" && aiCfg.ragTopK == 5) { val u = aiCfg.copy(ragScope = "TOPK", ragTopK = 5); onUpdate(u); saveAiCfg(ctx, u) }
ChipBtn("연관 상위 10건", aiCfg.ragScope == "TOPK" && aiCfg.ragTopK == 10) { val u = aiCfg.copy(ragScope = "TOPK", ragTopK = 10); onUpdate(u); saveAiCfg(ctx, u) }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("📚 독서대 본문 포함", aiCfg.ragBooks) { val u = aiCfg.copy(ragBooks = !aiCfg.ragBooks); onUpdate(u); saveAiCfg(ctx, u) }
ChipBtn("★ 중요 메모만 참조", aiCfg.ragImpOnly) { val u = aiCfg.copy(ragImpOnly = !aiCfg.ragImpOnly); onUpdate(u); saveAiCfg(ctx, u) }
ChipBtn("📂 로컬 모델(.litertlm) 연결") { onPickModel() }
}
}
}
}
