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
import kotlinx.coroutines.*
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
panelMaxH = (h * 0.54f).coerceIn(260f, 460f).dp,
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
val uiScale: String = "AUTO",
val apiUrl: String = "local_gguf",
val apiModel: String = "",
val apiKey: String = "",
val temp: Float = 0.5f,
val ctxSize: Int = 2048,
val maxTok: Int = 512, val cpuMode: Int = 2, val histTurns: Int = 2, val idleMin: Int = 30)

fun loadAiCfg(ctx: Context): AiRagConfig {
appCtxRef = ctx.applicationContext; val sp0 = ctx.getSharedPreferences("pl_ai_v2_prefs", 0); thinkOn = sp0.getBoolean("ai_think_on", false); streamOn = sp0.getBoolean("ai_stream_on", true); wakeWord = sp0.getString("jarvis_wake_word", "자비스")?.trim()?.ifEmpty { "자비스" } ?: "자비스"; sleepWord = sp0.getString("jarvis_sleep_word", "쉬어")?.trim()?.ifEmpty { "쉬어" } ?: "쉬어"; wakeOnly = sp0.getBoolean("jarvis_wake_only", false); ddgWebOn = sp0.getBoolean("ai_ddg_web_on", false); refreshDetectedModelNames()
val p = ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE)
return AiRagConfig(
sysPrompt = p.getString("ai_sys_prompt", DEF_SYS_PROMPT)?.ifBlank { DEF_SYS_PROMPT } ?: DEF_SYS_PROMPT,
ragOn = p.getBoolean("ai_rag_on", false),
ragScope = p.getString("ai_rag_scope", "ALL") ?: "ALL",
ragTopK = p.getInt("ai_rag_topk", 5),
ragBooks = p.getBoolean("ai_rag_books", true),
ragImpOnly = p.getBoolean("ai_rag_imp", p.getBoolean("ai_rag_imp_only", false)),
uiScale = p.getString("ui_scale_mode", "AUTO") ?: "AUTO",
apiUrl = p.getString("ai_host_url", "local_gguf")?.ifBlank { "local_gguf" } ?: "local_gguf",
apiModel = p.getString("ai_host_model", "") ?: "",
apiKey = p.getString("ai_host_key", "") ?: "",
temp = p.getFloat("ai_temp", 0.5f),
ctxSize = p.getInt("ai_ctx_size", 2048),
maxTok = p.getInt("ai_max_tok", 512).coerceAtLeast(512), cpuMode = p.getInt("ai_cpu_mode", 2), histTurns = p.getInt("ai_hist_turns", 2), idleMin = p.getInt("ai_idle_min", 30))
}

fun saveAiCfg(ctx: Context, c: AiRagConfig) {
ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).edit()
.putString("ai_sys_prompt", c.sysPrompt).putBoolean("ai_rag_on", c.ragOn)
.putString("ai_rag_scope", c.ragScope).putInt("ai_rag_topk", c.ragTopK)
.putBoolean("ai_rag_books", c.ragBooks).putBoolean("ai_rag_imp", c.ragImpOnly)
.putString("ui_scale_mode", c.uiScale).putString("ai_host_url", c.apiUrl.trim()).putString("ai_host_model", c.apiModel.trim()).putString("ai_host_key", c.apiKey.trim()).putFloat("ai_temp", c.temp).putInt("ai_ctx_size", c.ctxSize).putInt("ai_max_tok", c.maxTok.coerceAtLeast(512)).putInt("ai_cpu_mode", c.cpuMode).putInt("ai_hist_turns", c.histTurns).putInt("ai_idle_min", c.idleMin).apply()
}

data class ChatMsg(val id: Long, val isUser: Boolean, val text: String, val usedMemo: Boolean = false)
fun interface TokenCallback { fun onToken(bytes: ByteArray): Boolean }
object ProLlamaJni {
val ready: Boolean = runCatching { System.loadLibrary("prollama_jni"); true }.getOrDefault(false)
external fun loadModelBytes(pathUtf8: ByteArray): Long
external fun freeModel(modelPtr: Long)
external fun abortGeneration()
external fun resetAbort()
external fun generateStreamBytes(modelPtr: Long, sysUtf8: ByteArray, userUtf8: ByteArray, maxTokens: Int, temp: Float, ctxSize: Int, threads: Int, cb: TokenCallback?): ByteArray
external fun embedTextBytes(modelPtr: Long, textUtf8: ByteArray, threads: Int): FloatArray?
}
var appCtxRef: Context? = null
var mainActRef: android.app.Activity? = null
var appInFg: Boolean = true
var srFailStreak: Int = 0
val llmMutex = kotlinx.coroutines.sync.Mutex()
var ggufModelPtr: Long = 0L; var ggufLoadedPath: String = ""
var embModelPtr: Long = 0L; var embLoadedPath: String = ""
val embedVecCache = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()
var activeGenId: Long = 0L
var thinkOn by androidx.compose.runtime.mutableStateOf(false)
var streamOn by androidx.compose.runtime.mutableStateOf(true)
var showEmojiHelp by androidx.compose.runtime.mutableStateOf(false)
var wakeWord by androidx.compose.runtime.mutableStateOf("자비스")
var sleepWord by androidx.compose.runtime.mutableStateOf("쉬어")
var wakeOnly by androidx.compose.runtime.mutableStateOf(false)
var ddgWebOn by androidx.compose.runtime.mutableStateOf(false)
var wakeFollowUpUntil: Long = 0L
var lastModelUseMs: Long = System.currentTimeMillis()
fun optThreads(cpuMode: Int = 2): Int = when (cpuMode) { 1 -> 2; 3 -> 4; else -> 3 }
fun setThinkOn(ctx: Context, on: Boolean) { thinkOn = on; ctx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putBoolean("ai_think_on", on).apply() }
fun setStreamOn(ctx: Context, on: Boolean) { streamOn = on; ctx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putBoolean("ai_stream_on", on).apply() }
fun setWakeWord(ctx: Context, w: String) { val c = w.trim().ifEmpty { "자비스" }; wakeWord = c; ctx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putString("jarvis_wake_word", c).apply() }
fun setSleepWord(ctx: Context, w: String) { val c = w.trim().ifEmpty { "쉬어" }; sleepWord = c; ctx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putString("jarvis_sleep_word", c).apply() }
fun matchSleepPhrase(raw: String, customS: String = sleepWord): Boolean { val t = raw.trim(); if (t.isEmpty()) return false; val cs = customS.trim().ifEmpty { "쉬어" }; val nsT = t.replace(" ", ""); val cands = listOf(cs, "잠깐 $cs", "자비스 $cs", "$wakeWord $cs", "자비스 쉬어", "잠깐 쉬어", "쉬어", "대기 모드", "대기모드", "조용히 해", "스탠바이"); return cands.any { c -> val nsC = c.replace(" ", ""); t.equals(c, true) || nsT.equals(nsC, true) || (nsC.length >= 2 && nsT.endsWith(nsC, true) && nsT.length <= nsC.length + 6) } }
fun setWakeOnly(ctx: Context, on: Boolean) { wakeOnly = on; if (!on) wakeFollowUpUntil = 0L; ctx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putBoolean("jarvis_wake_only", on).apply() }
fun setDdgWebOn(ctx: Context, on: Boolean) { ddgWebOn = on; ctx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putBoolean("ai_ddg_web_on", on).apply() }
fun wantsMemoRagLookup(q: String): Boolean { val t = q.replace(" ", ""); return listOf("내메모", "음성메모", "메모에서", "메모찾", "메모조회", "메모알려", "메모보여", "메모읽어", "메모검색", "저장한메모", "적어둔", "기록한").any { t.contains(it) } }
suspend fun fetchDuckDuckGoContext(query: String): String = withContext(Dispatchers.IO) {
 val q = query.trim(); if (q.isEmpty()) return@withContext ""
 val enc = java.net.URLEncoder.encode(q, "UTF-8")
 val pr = "ht" + "tps:" + "/" + "/"
 val dom = "duckduckgo" + ".c" + "om"
 val sb = StringBuilder()
 runCatching {
  val c = (URL(pr + "api." + dom + "/?q=$enc&format=json&no_html=1&skip_disambig=1").openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 4500; readTimeout = 4500; setRequestProperty("User-Agent", "Mozilla/5.0") }
  val body = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { it.readText() }
  val jo = JSONObject(body)
  val abs = jo.optString("AbstractText", "").trim()
  if (abs.isNotEmpty()) sb.append("• 요약: ").append(abs.take(360)).append(10.toChar())
  val rel = jo.optJSONArray("RelatedTopics")
  if (rel != null) { var cnt = 0; for (i in 0 until rel.length()) { val tx = rel.optJSONObject(i)?.optString("Text", "")?.trim() ?: ""; if (tx.isNotEmpty() && cnt < 3) { sb.append("• ").append(tx.take(180)).append(10.toChar()); cnt++ } } }
 }
 if (sb.isEmpty()) {
  runCatching {
   val c = (URL(pr + "html." + dom + "/lite/?q=$enc").openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 5000; readTimeout = 5000; setRequestProperty("User-Agent", "Mozilla/5.0") }
   val html = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { it.readText() }
   val snips = Regex("class=['\"]result-snippet['\"][^>]*>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html).map { m -> m.groupValues[1].replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace("&quot;", 34.toChar().toString()).replace("&#x27;", 39.toChar().toString()).replace("&lt;", "<").replace("&gt;", ">").replace(Regex("[ ]{2,}"), " ").trim() }.filter { it.length >= 12 }.take(4).toList()
   snips.forEachIndexed { idx, s -> sb.append("${idx + 1}. ").append(s.take(220)).append(10.toChar()) }
  }
 }
 val out = sb.toString().trim()
 if (out.isEmpty()) "" else "[DuckDuckGo 실시간 웹 검색 결과]" + 10.toChar() + out
} }
fun matchWakePhrase(raw: String, customW: String = wakeWord): Pair<Boolean, String> { val t = raw.trim(); if (t.isEmpty()) return false to ""; val cw = customW.trim().ifEmpty { "자비스" }; val cands = linkedSetOf(cw, "헤이 $cw", "${cw}야", "${cw}아", "자비스", "헤이 자비스", "자비스야", "헤이 구글", "오케이 구글", "비서야", "프로야").filter { it.isNotBlank() }.sortedByDescending { it.length }; val nsT = t.replace(" ", ""); for (w in cands) { val idx = t.indexOf(w, ignoreCase = true); if (idx >= 0 && idx <= 8) { val rem = (t.substring(0, idx) + " " + t.substring(idx + w.length)).replace(Regex("^[,.!?~ ]+"), "").trim(); return true to rem }; val nsW = w.replace(" ", ""); if (nsW.length >= 2 && nsT.startsWith(nsW, ignoreCase = true)) { var consumed = 0; var rIdx = 0; while (rIdx < t.length && consumed < nsW.length) { if (t[rIdx] != ' ') consumed++; rIdx++ }; val rem = t.substring(rIdx).replace(Regex("^[,.!?~ ]+"), "").trim(); return true to rem } }; return false to t }
fun isGgufFile(f: java.io.File): Boolean = f.isFile && f.length() > 1048576L && (f.name.endsWith(".gguf", true) || runCatching { val b = ByteArray(4); java.io.FileInputStream(f).use { it.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF" } }.getOrDefault(false))
fun isEmbedModelName(nm: String): Boolean = nm.lowercase().let { it.contains("embed") || it.contains("bge") || it.contains("e5-") || it.contains("nomic") || it.contains("minilm") }
fun baseFilesDir(): java.io.File = appCtxRef?.filesDir ?: java.io.File("/data/data/app.prolauncher.ai.v3/files")
fun findEmbedGguf(): java.io.File? { val base = baseFilesDir(); java.io.File(base, "embed_models").listFiles()?.filter { isGgufFile(it) }?.maxByOrNull { it.lastModified() }?.let { return it }; return listOf(java.io.File(base, "models"), java.io.File(base, "llm_models"), base).flatMap { it.listFiles()?.toList() ?: emptyList() }.filter { isGgufFile(it) && isEmbedModelName(it.name) }.maxByOrNull { it.lastModified() } }
fun findLocalGguf(): java.io.File? { val embP = findEmbedGguf()?.absolutePath ?: ""; val base = baseFilesDir(); return listOf(java.io.File(base, "llm_models"), java.io.File(base, "models"), base).flatMap { it.listFiles()?.toList() ?: emptyList() }.filter { isGgufFile(it) && it.absolutePath != embP && !isEmbedModelName(it.name) }.maxByOrNull { it.lastModified() } }
fun refreshDetectedModelNames() = runCatching { findLocalGguf()?.let { f -> AiResidentManager.gemmaName = f.name; AiResidentManager.gemmaMb = f.length() / 1048576L }; findEmbedGguf()?.let { f -> AiResidentManager.embedName = f.name; AiResidentManager.embedMb = f.length() / 1048576L } }
fun releaseGguf() { if (ProLlamaJni.ready) runCatching { ProLlamaJni.abortGeneration() }; val p = ggufModelPtr; ggufModelPtr = 0L; ggufLoadedPath = ""; if (p != 0L && ProLlamaJni.ready) runCatching { ProLlamaJni.freeModel(p) } }
fun releaseEmbedGguf() { val p = embModelPtr; embModelPtr = 0L; embLoadedPath = ""; if (p != 0L && ProLlamaJni.ready) runCatching { ProLlamaJni.freeModel(p) } }
fun releaseAllLocalModels() { releaseGguf(); releaseEmbedGguf(); embedVecCache.clear() }
fun checkIdleUnload(idleMin: Int): Boolean { if (idleMin <= 0 || (ggufModelPtr == 0L && embModelPtr == 0L) || llmMutex.isLocked) return false; if (System.currentTimeMillis() - lastModelUseMs >= idleMin * 60000L) { releaseAllLocalModels(); AiResidentManager.gemmaName = findLocalGguf()?.name?.let { "$it (절전대기)" } ?: "미감지"; System.gc(); return true }; return false }
fun normVec(v: FloatArray): FloatArray { var s = 0f; for (x in v) s += x * x; val n = kotlin.math.sqrt(s).coerceAtLeast(1e-6f); return FloatArray(v.size) { i -> v[i] / n } }
fun dotCos(a: FloatArray, b: FloatArray): Float { val n = minOf(a.size, b.size); if (n == 0) return 0f; var d = 0f; for (i in 0 until n) d += a[i] * b[i]; return d.coerceIn(-1f, 1f) }
suspend fun embedWithGguf(rawText: String, isQuery: Boolean, docTitle: String = "메모"): FloatArray? { if (!ProLlamaJni.ready) return null; val ef = findEmbedGguf() ?: return null; val clean = rawText.replace("\n", " ").trim().take(if (isQuery) 240 else 360); if (clean.isEmpty()) return null; val formatted = if (isQuery) "task: search result | query: $clean" else "title: ${docTitle.ifBlank { "none" }} | text: $clean"; val cKey = "${ef.name}_${ef.length()}_${isQuery}_${formatted.hashCode()}"; embedVecCache[cKey]?.let { return it }; lastModelUseMs = System.currentTimeMillis(); llmMutex.lock(); try { return runCatching { val path = ef.absolutePath; if (embModelPtr == 0L || embLoadedPath != path) { releaseEmbedGguf(); val ptr = ProLlamaJni.loadModelBytes(path.toByteArray(Charsets.UTF_8)); if (ptr == 0L) return@runCatching null; embModelPtr = ptr; embLoadedPath = path; AiResidentManager.embedName = ef.name; AiResidentManager.embedMb = ef.length() / 1048576L }; val rawV = ProLlamaJni.embedTextBytes(embModelPtr, formatted.toByteArray(Charsets.UTF_8), 2); if (rawV != null && rawV.isNotEmpty()) { val nv = normVec(rawV); if (embedVecCache.size > 512) embedVecCache.clear(); embedVecCache[cKey] = nv; nv } else null }.onFailure { releaseEmbedGguf() }.getOrNull() } finally { llmMutex.unlock() } }
suspend fun importGgufModelUri(ctx: Context, uri: android.net.Uri, forEmbed: Boolean): String = kotlinx.coroutines.withContext(Dispatchers.IO) { appCtxRef = ctx.applicationContext; var name = if (forEmbed) "embeddinggemma2.gguf" else "local_llm.gguf"; runCatching { ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) } }; val low = name.lowercase(); if (low.endsWith(".litertlm") || low.endsWith(".task") || low.endsWith(".tflite")) return@withContext "⚠️ 경량화를 위해 .gguf 텍스트 전용 모델만 지원합니다."; val dir = java.io.File(ctx.applicationContext.filesDir, if (forEmbed) "embed_models" else "llm_models").apply { mkdirs() }; if (forEmbed) releaseEmbedGguf() else releaseGguf(); dir.listFiles()?.forEach { runCatching { it.delete() } }; val safeName = (if (low.endsWith(".gguf")) name else "$name.gguf").replace(Regex("[^a-zA-Z0-9가-힣._-]"), "_"); val target = java.io.File(dir, safeName); val ok = runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> java.io.FileOutputStream(target).use { o -> i.copyTo(o) } }; target.exists() && isGgufFile(target) }.getOrDefault(false); if (!ok) { runCatching { target.delete() }; return@withContext "⚠️ 유효한 GGUF 파일이 아닙니다." }; val mb = target.length() / 1048576L; if (forEmbed) { embedVecCache.clear(); AiResidentManager.embedName = target.name; AiResidentManager.embedMb = mb; val tv = embedWithGguf("임베딩젬마2 점검", true); if (tv != null) "✅ 임베딩젬마2 GGUF 활성! [${target.name.take(20)} · ${mb}MB · ${tv.size}차원]" else "✅ 임베딩 등록됨 [${target.name.take(20)} · ${mb}MB]" } else { AiResidentManager.gemmaName = target.name; AiResidentManager.gemmaMb = mb; "✅ 로컬 채팅 GGUF 등록 완료! [${target.name.take(22)} · ${mb}MB · 초고속 스트리밍]" } }
suspend fun buildRagContextSmart(q: String, aiCfg: AiRagConfig, notes: List<NoteItem>, books: List<BookItem>, cats: List<String>): String { if (notes.isEmpty() && (!aiCfg.ragBooks || books.isEmpty())) return ""; val baseNotes = if (aiCfg.ragImpOnly) notes.filter { SmartMemoClassifier.isImportant(it) } else notes; val kws = q.lowercase().split(" ").filter { it.length >= 2 }; val qCho = KoreanChosungMatcher.extractChosung(q.lowercase()); val qVec = embedWithGguf(q, true); val scored = baseNotes.map { n -> val cat = SmartMemoClassifier.categoryOf(n, cats); val lex = kws.count { k -> n.content.contains(k, true) || cat.contains(k, true) } * 0.16f; val cho = if (qCho.length >= 2 && KoreanChosungMatcher.extractChosung(n.content).contains(qCho)) 0.12f else 0f; val sem = if (qVec != null) (embedWithGguf(n.content, false, cat)?.let { dotCos(qVec, it) } ?: 0f) else 0f; n to (sem + lex + cho) }.sortedByDescending { it.second }; val targetNotes = scored.take(if (aiCfg.ragScope == "ALL") 5 else aiCfg.ragTopK.coerceIn(1, 8)); return buildString { val embTag = if (qVec != null) "EmbeddingGemma2 벡터" else "초성·키워드 하이브리드"; append("메모 ${notes.size}건 중 [$embTag] 참조 (${targetNotes.size}건):\n"); targetNotes.forEachIndexed { idx, (n, _) -> append("${idx + 1}. [${SmartMemoClassifier.categoryOf(n, cats)} · ${n.createdAt}] ${n.content.replace("\n", " / ").take(160)}\n") } }.trim() }
fun cleanThink(s: String): String { val m = Regex("<think>([\\s\\S]*?)</think>").find(s); val body = s.replace(Regex("<think>[\\s\\S]*?</think>"), "").replace("<think>", "").replace("</think>", "").trim(); val th = m?.groupValues?.getOrNull(1)?.trim() ?: ""; return if (thinkOn && th.isNotEmpty()) ("💭 [추론 과정]\n" + th + (if (body.isNotEmpty()) "\n────────────\n" + body else "")) else body.ifBlank { th } }
fun formatStreamText(raw: String, done: Boolean): String { if (done) return cleanThink(raw); val ho = raw.contains("<think>"); val hc = raw.contains("</think>"); return if (!thinkOn) { if (ho && !hc) "💭 답변 정리 중..." else cleanThink(raw) } else { if (ho && !hc) "💭 [추론 중...]\n" + raw.substringAfter("<think>").trimStart() else cleanThink(raw) } }
suspend fun runLocalGguf(f: java.io.File, sysPrompt: String, history: List<ChatMsg>, userMsg: String, onStream: ((String) -> Unit)? = null, genId: Long = 0L, temp: Float = 0.5f, ctxSize: Int = 2048, maxTok: Int = 512, cpuMode: Int = 2, histTurns: Int = 2): String? { if (!ProLlamaJni.ready) return null; lastModelUseMs = System.currentTimeMillis(); llmMutex.lock(); try { return runCatching { val path = f.absolutePath; if (ggufModelPtr == 0L || ggufLoadedPath != path) { releaseGguf(); System.gc(); var ptr = ProLlamaJni.loadModelBytes(path.toByteArray(Charsets.UTF_8)); if (ptr == 0L) { releaseEmbedGguf(); System.gc(); ptr = ProLlamaJni.loadModelBytes(path.toByteArray(Charsets.UTF_8)) }; if (ptr == 0L) return@runCatching null; ggufModelPtr = ptr; ggufLoadedPath = path; AiResidentManager.gemmaName = f.name; AiResidentManager.gemmaMb = f.length() / 1048576L }; val hLim = (histTurns.coerceIn(0, 20)) * 2; val hist = if (hLim <= 0) "" else history.takeLast(hLim).joinToString("\n") { (if (it.isUser) "U: " else "A: ") + it.text.substringBefore("\n\n⏱️").substringBefore("\n⏱️").substringAfter("────────────", it.text.substringBefore("\n\n⏱️")).replace("▍", "").take(110) }; val shortSys = sysPrompt.take(240); val fullS = if (hist.isBlank()) shortSys else "$shortSys\n$hist"; val effMax = maxTok.coerceIn(512, 4096); val dynCtx = maxOf(ctxSize, effMax + 512).coerceIn(1024, 8192); val ths = optThreads(cpuMode); val sleepMs = if (cpuMode == 1) 7L else if (cpuMode == 2) 1L else 0L; val liveSb = StringBuilder(); var lastMs = 0L; var tokCnt = 0; var tStart = System.currentTimeMillis(); ProLlamaJni.resetAbort(); val cb = TokenCallback { chunk -> if (genId != 0L && genId != activeGenId) false else { if (tokCnt == 0) tStart = System.currentTimeMillis(); tokCnt += 1; val pc = String(chunk, Charsets.UTF_8); liveSb.append(pc); if (streamOn && onStream != null) { val now = System.currentTimeMillis(); if (now - lastMs >= 25L || pc.indexOf(10.toChar()) >= 0 || pc.contains('.') || pc.contains('!') || pc.contains('?')) { lastMs = now; val sn = formatStreamText(liveSb.toString(), false); if (sn.isNotBlank()) { val el = ((now - tStart).coerceAtLeast(50L)) / 1000f; val badge = "\n\n⏱️ %.1f tok/s · %d/%d토큰 · 기억 %d대화".format(java.util.Locale.US, tokCnt / el, tokCnt, effMax, histTurns); runCatching { onStream.invoke(sn + badge) } } } }; if (sleepMs > 0L) runCatching { Thread.sleep(sleepMs) }; true } }; val outB = ProLlamaJni.generateStreamBytes(ggufModelPtr, fullS.toByteArray(Charsets.UTF_8), userMsg.toByteArray(Charsets.UTF_8), effMax, temp.coerceIn(0f, 1.2f), dynCtx, ths, cb); lastModelUseMs = System.currentTimeMillis(); val cl = cleanThink(String(outB, Charsets.UTF_8).ifBlank { liveSb.toString() }.trim()).takeIf { it.isNotBlank() } ?: return@runCatching null; val totEl = ((System.currentTimeMillis() - tStart).coerceAtLeast(50L)) / 1000f; val tc = tokCnt.coerceAtLeast(1); val finOut = cl + "\n\n⏱️ %.1f tok/s (%.1f초) · %d/%d토큰 · 기억 %d대화".format(java.util.Locale.US, tc / totEl, totEl, tc, effMax, histTurns); if (streamOn && onStream != null) runCatching { onStream.invoke(finOut) }; finOut }.onFailure { releaseGguf() }.getOrNull() } finally { lastModelUseMs = System.currentTimeMillis(); llmMutex.unlock() } }
fun parseAns(r: String): String? = r.trim().takeIf { it.isNotEmpty() && !it.startsWith("<!DOCTYPE", true) && !it.startsWith("<html", true) }?.let { s ->
val raw = if (!s.startsWith("\u007B")) s else runCatching {
val o = JSONObject(s); val c0 = o.optJSONArray("choices")?.optJSONObject(0); val m0 = c0?.optJSONObject("message")
(m0?.optString("content")?.takeIf { it.isNotBlank() } ?: m0?.optString("reasoning_content")?.takeIf { it.isNotBlank() } ?: c0?.optString("text")?.takeIf { it.isNotBlank() } ?: o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() } ?: o.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() } ?: o.optString("response").takeIf { it.isNotBlank() } ?: o.optString("content").takeIf { it.isNotBlank() })?.trim()
}.getOrNull()
raw?.let { cleanThink(it) }?.takeIf { it.isNotBlank() }
}

fun postApi(u: String, k: String, body: JSONObject, tOut: Int = 28000, onStream: ((String) -> Unit)? = null, genId: Long = 0L): Pair<Int, String> {
var c: HttpURLConnection? = null
return try {
c = (URL(u).openConnection() as HttpURLConnection).apply { requestMethod = "POST"; connectTimeout = 4500; readTimeout = tOut; doOutput = true; setRequestProperty("Content-Type", "application/json; charset=UTF-8"); setRequestProperty("User-Agent", "Mozilla/5.0"); if (k.isNotBlank()) setRequestProperty("Authorization", "Bearer " + k.trim()) }
OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
val code = c.responseCode
if (code in 200..299 && streamOn && onStream != null && body.optBoolean("stream", false)) {
val sb = StringBuilder(); val thSb = StringBuilder(); val rawFallback = StringBuilder()
c.inputStream.bufferedReader(Charsets.UTF_8).use { br ->
var ln: String?
while (br.readLine().also { ln = it } != null) {
if (genId != 0L && genId != activeGenId) break
val s = ln?.trim() ?: continue
if (s.isEmpty()) continue
rawFallback.append(s).append("\n")
val jStr = if (s.startsWith("data:")) s.removePrefix("data:").trim() else s
if (jStr == "[DONE]") break
if (jStr.startsWith("\u007B")) runCatching {
val j = JSONObject(jStr)
val d0 = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
val tok = d0?.optString("content", "")?.takeIf { it.isNotEmpty() } ?: j.optJSONObject("message")?.optString("content", "")?.takeIf { it.isNotEmpty() } ?: j.optString("response", "").takeIf { it.isNotEmpty() } ?: ""
val rTok = d0?.optString("reasoning_content", "")?.takeIf { it.isNotEmpty() } ?: j.optJSONObject("message")?.optString("thinking", "")?.takeIf { it.isNotEmpty() } ?: ""
if (rTok.isNotEmpty()) thSb.append(rTok)
if (tok.isNotEmpty()) sb.append(tok)
if (sb.isNotEmpty() || thSb.isNotEmpty()) {
val comb = if (thSb.isNotEmpty()) "<think>" + thSb.toString() + (if (sb.isNotEmpty()) "</think>" + sb.toString() else "") else sb.toString()
onStream.invoke(formatStreamText(comb, false))
}
}
}
}
val finalComb = if (thSb.isNotEmpty()) "<think>" + thSb.toString() + "</think>" + sb.toString() else sb.toString()
code to (finalComb.takeIf { it.isNotBlank() } ?: rawFallback.toString())
} else {
code to ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
}
} catch (e: Exception) { -1 to "" } finally { runCatching { c?.disconnect() } }
}

fun getApi(u: String, k: String = ""): String = runCatching {
(URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 3000; readTimeout = 4000; setRequestProperty("User-Agent", "Mozilla/5.0"); if (k.isNotBlank()) setRequestProperty("Authorization", "Bearer " + k.trim()) }.inputStream.bufferedReader().use { it.readText() }
}.getOrDefault("")

fun discoverServerModel(base: String, k: String): String {
for (ep in listOf("$base/api/tags", "$base/v1/models")) {
val s = getApi(ep, k); if (s.isNotEmpty()) runCatching {
val j = JSONObject(s)
j.optJSONArray("models")?.optJSONObject(0)?.let { return it.optString("name", it.optString("model", "")).trim() }
j.optJSONArray("data")?.optJSONObject(0)?.optString("id", "")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
}
}
return ""
}

fun fallbackLlmOrChat(sysPrompt: String, ragCtx: String, history: List<ChatMsg>, userMsg: String, onStream: ((String) -> Unit)? = null, genId: Long = 0L): String {
val fullSys = if (ragCtx.isBlank()) sysPrompt else "$sysPrompt\n\n[참고 메모]\n$ragCtx"
val msgs = JSONArray().apply {
put(JSONObject().put("role", "system").put("content", fullSys))
history.takeLast(3).forEach { m -> put(JSONObject().put("role", if (m.isUser) "user" else "assistant").put("content", m.text.replace("▍", "").take(180))) }
put(JSONObject().put("role", "user").put("content", userMsg))
}
for (ep in listOf("ht" + "tps://text.pollinations.ai/openai", "ht" + "tps://text.pollinations.ai/")) {
val jb = JSONObject().put("messages", msgs).put("model", "openai").put("seed", 42).put("stream", streamOn && onStream != null)
parseAns(postApi(ep, "", jb, 14000, onStream, genId).second)?.let { return it }
}
val q = userMsg.trim()
if (q.length <= 10 && (q.contains("안녕") || q.contains("ㅎㅇ") || q.contains("하이") || q.contains("반가") || q.contains("테스트"))) {
val gn = AiResidentManager.gemmaName.takeIf { !it.contains("미연결") } ?: "GGUF 로컬AI"
return "안녕하세요! 🦙 초경량 텍스트 전용 로컬 AI ($gn) 챗봇입니다. 무엇이든 편하게 질문해 주세요!"
}
val kw = q.replace(Regex("(이란|란|은|는|이|가|을|를|의|에|대해|알려줘|뭐야|어떤|나라야|[?？.!])+"), " ").trim().split(" ").lastOrNull { it.length >= 2 } ?: q
val r1 = getApi("ht" + "tps://ko.wikipedia.org/api/rest_v1/page/summary/" + java.net.URLEncoder.encode(kw.ifEmpty { q }, "UTF-8"))
if (r1.isNotEmpty()) runCatching {
val ex = JSONObject(r1).optString("extract", "").trim()
if (ex.isNotEmpty() && !ex.contains("자두야") && !ex.contains("절망선생")) return (if (ragCtx.isNotBlank()) "📌 [메모 참조]\n$ragCtx\n\n" else "") + ex.take(700)
}
return if (ragCtx.isNotBlank()) ragCtx else "안녕하세요! 상단 [⚙️ AI 세팅]에서 텍스트 전용 로컬 AI (.gguf)와 임베딩젬마2 (.gguf) 모델을 올리면 오프라인에서도 실시간 스트리밍으로 답변합니다."
}

suspend fun callLlmHttp(sysPrompt: String, ragCtx: String, history: List<ChatMsg>, userMsg: String, apiUrl: String = "", apiModel: String = "", apiKey: String = "", onStream: ((String) -> Unit)? = null, genId: Long = 0L, temp: Float = 0.5f, ctxSize: Int = 2048, maxTok: Int = 512, cpuMode: Int = 2, histTurns: Int = 2): String? { val fullSys = if (ragCtx.isBlank()) sysPrompt else "$sysPrompt\n\n[참고 메모]\n$ragCtx"; val chatGf = findLocalGguf(); if (chatGf != null && (apiUrl.isBlank() || apiUrl == "local_gguf")) { runLocalGguf(chatGf, fullSys, history, userMsg, onStream, genId, temp, ctxSize, maxTok.coerceAtLeast(512), cpuMode, histTurns)?.let { return it } }; var rawU = apiUrl.trim(); if (rawU.isBlank() || rawU == "local_gguf") return fallbackLlmOrChat(sysPrompt, ragCtx, history, userMsg, onStream, genId); if (!rawU.startsWith("ht" + "tp://") && !rawU.startsWith("ht" + "tps://")) rawU = "ht" + "tp://$rawU"; val cleanU = rawU.removeSuffix("/"); val base = cleanU.removeSuffix("/v1/chat/completions").removeSuffix("/api/chat").removeSuffix("/v1").removeSuffix("/"); val isDirect = cleanU.endsWith("/chat/completions") || cleanU.contains("googleapis"); val mod = apiModel.trim().ifEmpty { if (isDirect) "gemini-2.5-flash" else discoverServerModel(base, apiKey.trim()).ifEmpty { "gemma3:1b" } }; val eps = if (isDirect) linkedSetOf(cleanU, "$base/v1/chat/completions") else linkedSetOf("$base/api/chat", "$base/v1/chat/completions", cleanU); for (ep in eps) { val b = JSONObject().put("model", mod).put("stream", streamOn && onStream != null).put("temperature", temp.toDouble()).put("max_tokens", maxTok).put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", fullSys)).put(JSONObject().put("role", "user").put("content", userMsg))); parseAns(postApi(ep, apiKey.trim(), b, 30000, onStream, genId).second)?.let { return it } }; return fallbackLlmOrChat(sysPrompt, ragCtx, history, userMsg, onStream, genId) }
data class BriefingSlot(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val daysMode: String = "DAILY",
    val enabled: Boolean = true
)

data class SmartBriefingConfig(
    val enabled: Boolean = false,
    val slots: List<BriefingSlot> = emptyList(),
    val forceOnSilent: Boolean = false,
    val skipIfEmpty: Boolean = true
)

data class VoiceBriefingPayload(
    val hasItems: Boolean,
    val visualSummary: String,
    val spokenScript: String
)

object ScheduleBriefingManager {
    private const val CH_ID = "pl_briefing_ch"
    private const val PREF_KEY = "smart_brief_cfg_v1"
    private val mdRx = Regex("([0-9]{1,2})[ ]*월[ ]*([0-9]{1,2})[ ]*일")
    private val tmRx = Regex("(오전|오후|아침|점심|저녁|밤)?[ ]*([0-9]{1,2})[ ]*시(?:[ ]*([0-9]{1,2})[ ]*분|[ ]*(반))?")
    private val monitorScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var monitorJob: kotlinx.coroutines.Job? = null

    fun parseLineSchedule(line: String, nowMs: Long = System.currentTimeMillis()): Pair<Long, String>? {
        val tr = line.trim()
        if (tr.isEmpty() || tr.startsWith("☑") || tr.startsWith("#")) return null
        val tm = tmRx.find(tr) ?: return null
        val ap = tm.groupValues[1]
        var hr = tm.groupValues[2].toIntOrNull() ?: return null
        val mn = if (tm.groupValues[4] == "반") 30 else (tm.groupValues[3].toIntOrNull() ?: 0)
        if (hr !in 0..24 || mn !in 0..59) return null
        if ((ap == "오후" || ap == "저녁" || ap == "밤") && hr in 1..11) hr += 12
        else if (ap == "점심" && hr in 1..3) hr += 12
        else if (ap == "오전" && hr == 12) hr = 0
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val md = mdRx.find(tr)
        var dTag = "오늘"
        if (md != null) {
            val mo = (md.groupValues[1].toIntOrNull() ?: 1) - 1
            val dy = md.groupValues[2].toIntOrNull() ?: 1
            cal.set(java.util.Calendar.MONTH, mo.coerceIn(0, 11))
            cal.set(java.util.Calendar.DAY_OF_MONTH, dy.coerceIn(1, 31))
            dTag = "${mo + 1}/${dy}"
        } else if (tr.contains("모레")) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 2)
            dTag = "모레"
        } else if (tr.contains("내일")) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            dTag = "내일"
        }
        cal.set(java.util.Calendar.HOUR_OF_DAY, hr.coerceIn(0, 23))
        cal.set(java.util.Calendar.MINUTE, mn)
        if (md == null && !tr.contains("내일") && !tr.contains("모레") && !tr.contains("오늘") && cal.timeInMillis <= nowMs) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            dTag = "내일"
        }
        return cal.timeInMillis to "$dTag %02d:%02d".format(hr.coerceIn(0, 23), mn)
    }

    fun detectNoteScheduleLabel(n: NoteItem): String? {
        val now = System.currentTimeMillis()
        for (ln in n.content.lines()) {
            val p = parseLineSchedule(ln, now)
            if (p != null && p.first > now - 3600000L) return p.second
        }
        return null
    }

    fun formatSlotTime(hour: Int, minute: Int): String {
        val ampm = if (hour < 12) "오전" else "오후"
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "%s %d:%02d".format(ampm, h12, minute)
    }

    fun daysLabel(daysMode: String): String = when (daysMode) {
        "WEEKDAY" -> "평일"
        "WEEKEND" -> "주말"
        else -> "매일"
    }

    fun nextDaysMode(cur: String): String = when (cur) {
        "DAILY" -> "WEEKDAY"
        "WEEKDAY" -> "WEEKEND"
        else -> "DAILY"
    }

    fun loadBriefCfg(ctx: Context): SmartBriefingConfig = runCatching {
        val sp = ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE)
        val raw = sp.getString(PREF_KEY, null)
        if (raw.isNullOrBlank()) {
            return SmartBriefingConfig(enabled = false, slots = emptyList(), forceOnSilent = false, skipIfEmpty = true)
        }
        val jo = org.json.JSONObject(raw)
        val arr = jo.optJSONArray("slots") ?: org.json.JSONArray()
        val list = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    BriefingSlot(
                        id = o.optLong("id", i + 1L),
                        hour = o.optInt("h", 8).coerceIn(0, 23),
                        minute = o.optInt("m", 0).coerceIn(0, 59),
                        daysMode = o.optString("days", "DAILY"),
                        enabled = o.optBoolean("on", true)
                    )
                )
            }
        }.sortedBy { it.hour * 60 + it.minute }
        SmartBriefingConfig(
            enabled = jo.optBoolean("enabled", false),
            slots = list,
            forceOnSilent = jo.optBoolean("forceSilent", false),
            skipIfEmpty = jo.optBoolean("skipEmpty", true)
        )
    }.getOrDefault(SmartBriefingConfig())

    fun saveAndSync(ctx: Context, cfg: SmartBriefingConfig): SmartBriefingConfig {
        val sorted = cfg.slots.sortedBy { it.hour * 60 + it.minute }.take(16)
        val normalized = cfg.copy(slots = sorted)
        runCatching {
            val arr = org.json.JSONArray()
            sorted.forEach { s ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", s.id)
                        .put("h", s.hour)
                        .put("m", s.minute)
                        .put("days", s.daysMode)
                        .put("on", s.enabled)
                )
            }
            val jo = org.json.JSONObject()
                .put("enabled", normalized.enabled)
                .put("forceSilent", normalized.forceOnSilent)
                .put("skipEmpty", normalized.skipIfEmpty)
                .put("slots", arr)
            ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY, jo.toString())
                .putInt("brief_morning_hr", -1)
                .apply()
        }
        rescheduleAllBriefingAlarms(ctx, normalized)
        return normalized
    }

    

    private fun matchesDayOfWeek(calDayOfWeek: Int, daysMode: String): Boolean = when (daysMode) {
        "WEEKDAY" -> calDayOfWeek in java.util.Calendar.MONDAY..java.util.Calendar.FRIDAY
        "WEEKEND" -> calDayOfWeek == java.util.Calendar.SATURDAY || calDayOfWeek == java.util.Calendar.SUNDAY
        else -> true
    }

    fun computeNextSlotMillis(slot: BriefingSlot, nowMs: Long = System.currentTimeMillis()): Long? {
        if (!slot.enabled) return null
        for (offset in 0..7) {
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(java.util.Calendar.DAY_OF_YEAR, offset)
                set(java.util.Calendar.HOUR_OF_DAY, slot.hour.coerceIn(0, 23))
                set(java.util.Calendar.MINUTE, slot.minute.coerceIn(0, 59))
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (matchesDayOfWeek(cal.get(java.util.Calendar.DAY_OF_WEEK), slot.daysMode) && cal.timeInMillis > nowMs + 4000L) {
                return cal.timeInMillis
            }
        }
        return null
    }

    fun nextSummaryBadge(cfg: SmartBriefingConfig, nowMs: Long = System.currentTimeMillis()): String {
        if (!cfg.enabled) return "OFF"
        val active = cfg.slots.filter { it.enabled }
        if (active.isEmpty()) return "시간 미지정"
        val nextPair = active.mapNotNull { s -> computeNextSlotMillis(s, nowMs)?.let { ms -> ms to s } }.minByOrNull { it.first }
        val nextSlot = nextPair?.second ?: active.first()
        val extra = if (active.size > 1) " 외 ${active.size - 1}회" else ""
        return "${formatSlotTime(nextSlot.hour, nextSlot.minute)}$extra"
    }

    private fun setSafe(am: android.app.AlarmManager, atMs: Long, pi: android.app.PendingIntent) {
        if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            runCatching { am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, atMs, pi) }
                .onFailure { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, atMs, pi) }
        }
    }

    fun rescheduleAllBriefingAlarms(ctx: Context, cfg: SmartBriefingConfig = loadBriefCfg(ctx)) = runCatching {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        android.app.PendingIntent.getBroadcast(
            ctx, 7700, android.content.Intent(ctx, BriefingReceiver::class.java),
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
        )?.let { am.cancel(it); it.cancel() }
        for (i in 0 until 20) {
            android.app.PendingIntent.getBroadcast(
                ctx, 7710 + i, android.content.Intent(ctx, BriefingReceiver::class.java),
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
            )?.let { am.cancel(it); it.cancel() }
        }
        if (!cfg.enabled) return@runCatching
        val nowMs = System.currentTimeMillis()
        cfg.slots.filter { it.enabled }.take(16).forEachIndexed { idx, slot ->
            val nextMs = computeNextSlotMillis(slot, nowMs) ?: return@forEachIndexed
            val itn = android.content.Intent(ctx, BriefingReceiver::class.java)
                .putExtra("mode", "AUTO_BRIEF")
                .putExtra("slot_id", slot.id)
                .putExtra("slot_label", formatSlotTime(slot.hour, slot.minute))
            val pi = android.app.PendingIntent.getBroadcast(
                ctx, 7710 + idx, itn,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            setSafe(am, nextMs, pi)
        }
    }

    

    fun syncNoteAlarms(ctx: Context, notes: List<NoteItem>): Int = runCatching {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        for (s in 0 until 12) {
            android.app.PendingIntent.getBroadcast(
                ctx, 7800 + s, android.content.Intent(ctx, BriefingReceiver::class.java),
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
            )?.let { am.cancel(it) }
        }
        val now = System.currentTimeMillis()
        val up = notes.flatMap { n ->
            n.content.lines().mapNotNull { ln ->
                parseLineSchedule(ln, now)?.let { (ms, lb) ->
                    if (ms > now + 15000L) Triple(ms, lb, ln.replace("☐", "").trim().take(48)) else null
                }
            }
        }.sortedBy { it.first }.take(12)
        up.forEachIndexed { idx, (ms, lb, bd) ->
            val pi = android.app.PendingIntent.getBroadcast(
                ctx, 7800 + idx,
                android.content.Intent(ctx, BriefingReceiver::class.java)
                    .putExtra("mode", "ITEM")
                    .putExtra("title", "⏰ 일정 알림 ($lb)")
                    .putExtra("body", bd),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            setSafe(am, ms, pi)
        }
        rescheduleAllBriefingAlarms(ctx)
        up.size
    }.getOrDefault(0)

    fun buildVoiceBriefingPayload(notes: List<NoteItem>, slotLabel: String = ""): VoiceBriefingPayload {
        val now = System.currentTimeMillis()
        val timed = notes.flatMap { n ->
            n.content.lines().mapNotNull { ln ->
                parseLineSchedule(ln, now)?.let { (ms, lb) ->
                    if (ms > now - 7200000L) Triple(ms, lb, ln.replace("☐", "").replace(Regex("#[^ #]+"), "").trim()) else null
                }
            }
        }.sortedBy { it.first }.take(6)
        val checks = notes.flatMap { n ->
            n.content.lines().filter { it.trimStart().startsWith("☐ ") }.map {
                it.trim().removePrefix("☐ ").replace(Regex("#[^ #]+"), "").trim()
            }
        }.filter { it.isNotEmpty() }.distinct().take(6)

        val hasItems = timed.isNotEmpty() || checks.isNotEmpty()
        val visual = buildString {
            append("📋 오늘의 일정·체크리스트 브리핑\n")
            if (timed.isNotEmpty()) {
                append("⏰ 시간 일정 (${timed.size}건):\n")
                timed.forEach { (_, lb, tx) -> append("• [$lb] ${tx.take(44)}\n") }
            }
            if (checks.isNotEmpty()) {
                append("☑ 미완료 할 일 (${checks.size}건):\n")
                checks.forEach { tx -> append("• ${tx.take(44)}\n") }
            }
            if (!hasItems) append("예정된 시간 일정이나 미완료 할 일이 없습니다.")
        }.trim()

        val curHr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greet = when {
            curHr in 5..10 -> "좋은 아침입니다."
            curHr in 11..16 -> "안녕하세요."
            curHr in 17..21 -> "편안한 저녁입니다."
            else -> "안녕하세요."
        }
        val spoken = if (!hasItems) {
            "$greet 현재 예정된 시간 일정이나 미완료된 할 일이 없습니다."
        } else {
            buildString {
                append(greet).append(" ")
                if (slotLabel.isNotBlank()) append("$slotLabel ")
                append("일정 브리핑을 시작합니다. ")
                if (timed.isNotEmpty()) {
                    append("예정된 시간 일정은 총 ${timed.size}건입니다. ")
                    timed.forEachIndexed { idx, (_, lb, tx) ->
                        val naturalTime = lb.replace(":00", "시 정각").replace(":", "시 ") + (if (lb.endsWith(":00")) "" else "분")
                        append("${idx + 1}번, $naturalTime, ${tx.take(60)}. ")
                    }
                }
                if (checks.isNotEmpty()) {
                    append("미완료된 할 일은 ${checks.size}건입니다. ")
                    checks.forEachIndexed { idx, tx ->
                        append("${idx + 1}번, ${tx.take(60)}. ")
                    }
                }
                append("이상 브리핑을 마칩니다.")
            }.trim()
        }
        return VoiceBriefingPayload(hasItems, visual, spoken)
    }

    fun buildBriefingSummary(notes: List<NoteItem>): String = buildVoiceBriefingPayload(notes).visualSummary

    fun isHeadsetOrBtConnected(am: android.media.AudioManager): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { d ->
                d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    d.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    d.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    d.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                    (android.os.Build.VERSION.SDK_INT >= 31 && (d.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER))
            }
        } else false
    }.getOrDefault(false)

    fun stopBriefingPlayback(ctx: Context) {
        briefingSpeakingNow = false
        monitorJob?.cancel()
        stopOneShotTts()
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(7701)
        }
        keepAw(ctx, false)
    }

    fun triggerScheduledBriefingFromAlarm(ctx: Context, slotLabel: String) {
        val appC = ctx.applicationContext
        val cfg = loadBriefCfg(appC)
        rescheduleAllBriefingAlarms(appC, cfg)
        if (!cfg.enabled || cfg.slots.none { it.enabled }) return
        val notes = LauncherRepository(appC).loadNotes()
        val payload = buildVoiceBriefingPayload(notes, slotLabel)
        if (!payload.hasItems && cfg.skipIfEmpty) return

        val am = appC.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val ringer = am.ringerMode
        val headset = isHeadsetOrBtConnected(am)
        val shouldSpeak = cfg.forceOnSilent || ringer == android.media.AudioManager.RINGER_MODE_NORMAL || headset
        val useAlarmStream = cfg.forceOnSilent && ringer != android.media.AudioManager.RINGER_MODE_NORMAL && !headset

        if (!shouldSpeak) {
            postBriefingNotification(
                appC,
                "📳 매너모드 일정 브리핑 ($slotLabel)",
                payload.visualSummary,
                7701,
                withStopAction = false
            )
            return
        }

        briefingSpeakingNow = true
        keepAw(appC, true)
        postBriefingNotification(
            appC,
            "🔊 자동 일정 브리핑 낭독 중 ($slotLabel)",
            payload.visualSummary,
            7701,
            withStopAction = true
        )
        speakBriefingWithStream(appC, payload.spokenScript, useAlarmStream)
        monitorJob?.cancel()
        monitorJob = monitorScope.launch {
            kotlinx.coroutines.delay(1400L)
            var ticks = 0
            while (briefingSpeakingNow && ticks < 360) {
                if (!isTtsBusyNow() && (System.currentTimeMillis() - ttsLastEnqueueMs) >= 600L) break
                kotlinx.coroutines.delay(250L)
                ticks++
            }
            briefingSpeakingNow = false
            keepAw(appC, false)
        }
    }

    fun postBriefingNotification(
        ctx: Context,
        title: String,
        body: String,
        notifId: Int = 7701,
        withStopAction: Boolean = false
    ) = runCatching {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(android.app.NotificationChannel(CH_ID, "일정 브리핑", android.app.NotificationManager.IMPORTANCE_HIGH))
        }
        val cPi = android.app.PendingIntent.getActivity(
            ctx, notifId,
            android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CH_ID) else android.app.Notification.Builder(ctx)
        b.setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body.lines().firstOrNull() ?: body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setContentIntent(cPi)
            .setAutoCancel(true)
        if (withStopAction) {
            val stopPi = android.app.PendingIntent.getBroadcast(
                ctx, 7709,
                android.content.Intent(ctx, BriefingReceiver::class.java).putExtra("mode", "STOP_BRIEF"),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(android.app.Notification.Action.Builder(android.R.drawable.ic_media_pause, "⏹️ 낭독 중지", stopPi).build())
        }
        nm.notify(notifId, b.build())
    }
}
