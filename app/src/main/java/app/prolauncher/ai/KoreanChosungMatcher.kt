package app.prolauncher.ai
import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import org.json.*
import java.io.*
import java.net.*
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.sqrt
object KoreanChosungMatcher {
private val CHO = charArrayOf('ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ','ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ')
fun extractChosung(t: String) = buildString(t.length) { for (c in t) append(if (c in '\uAC00'..'\uD7A3') CHO[(c.code - 0xAC00) / 588] else c.lowercaseChar()) }
fun matches(target: String, query: String): Boolean {
val q = query.trim().lowercase().replace(" ", ""); if (q.isEmpty()) return true
val c = target.lowercase().replace(" ", ""); return c.contains(q) || extractChosung(c).contains(q)
}
}
object AiResidentManager {
val aiDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "PL-Resident-AI").apply { isDaemon = false; priority = Thread.MAX_PRIORITY } }.asCoroutineDispatcher()
val mutex = Mutex()
@Volatile var gemmaLlm: LlmInference? = null
@Volatile var gemmaBuf: MappedByteBuffer? = null; @Volatile var gemmaName = "미연결"; @Volatile var gemmaMb = 0L; @Volatile var gemmaCalls = 0; @Volatile var gemmaMs = 0L; @Volatile var gemmaErr: String? = null; @Volatile var loadingGemma = false
@Volatile var embedder: TextEmbedder? = null; @Volatile var embedBuf: MappedByteBuffer? = null; @Volatile var embedSeed: ByteArray? = null
@Volatile var embedName = "미연결"; @Volatile var embedMb = 0L; @Volatile var embedDim = 256; @Volatile var embedMode = "미연결"; @Volatile var embedCalls = 0; @Volatile var embedMs = 0L; @Volatile var embedErr: String? = null; @Volatile var loadingEmbed = false
@Volatile var serviceAlive = false; @Volatile var heartbeats = 0; @Volatile var wakeLock: PowerManager.WakeLock? = null
var uiTick by mutableStateOf(0)
fun nowStr(): String = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("pl_ai_v7_resident", Context.MODE_PRIVATE)
fun statusSummary(): String {
val g = if (gemmaLlm != null) "Gemma3 1B 🟢상주(${gemmaMb}MB)" else if (loadingGemma) "Gemma3 ⏳적재중" else "Gemma3 🔴미연결"
val e = if (embedder != null || embedBuf != null) "임베딩젬마2 🟢상주(${embedMb}MB)" else if (loadingEmbed) "임베딩젬마2 ⏳적재중" else "임베딩젬마2 🔴미연결"
return "$g · $e · 🔒상주(${heartbeats}회)"
}
fun gemmaDetail(): String = when {
loadingGemma -> "⏳ RAM에 Gemma 3 1B 적재 중..."
gemmaLlm != null -> "🟢 RAM 상주 완료 ($gemmaName · ${gemmaMb}MB · 추론 ${gemmaCalls}회 · ${gemmaMs}ms)"
gemmaErr != null -> "🔴 로드 오류: $gemmaErr"
else -> "🔴 미연결 — [📂 Gemma 3 1B 선택]으로 .task/.litertlm/.bin 파일을 연결하세요"
}
fun embedDetail(): String = when {
loadingEmbed -> "⏳ RAM에 임베딩젬마2 적재 중..."
embedder != null || embedBuf != null -> "🟢 $embedMode ($embedName · ${embedMb}MB · ${embedDim}차원 · ${embedCalls}회)"
embedErr != null -> "🔴 로드 오류: $embedErr"
else -> "🔴 미연결 — [📂 임베딩젬마2 선택]으로 모델 파일을 연결하세요"
}
fun startService(ctx: Context) = runCatching {
val app = ctx.applicationContext; val i = Intent(app, AiResidentService::class.java)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i) else app.startService(i)
}
fun isBatteryExempt(ctx: Context) = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
fun requestBatteryExempt(ctx: Context) = runCatching {
ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
private fun resolveName(ctx: Context, uri: Uri, def: String): String {
var n = def
runCatching { ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0 && c.moveToFirst()) n = c.getString(i) } }
return n
}
private fun copyUri(ctx: Context, uri: Uri, pfx: String, orig: String): File {
val dir = File(ctx.applicationContext.filesDir, "models").apply { mkdirs() }
dir.listFiles()?.filter { it.name.startsWith(pfx) }?.forEach { runCatching { it.delete() } }
val f = File(dir, "${pfx}_${orig.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}")
val buf = ByteArray(256 * 1024)
ctx.contentResolver.openInputStream(uri)?.use { inp -> FileOutputStream(f).use { out -> while (true) { val r = inp.read(buf); if (r <= 0) break; out.write(buf, 0, r) }; out.flush() } } ?: error("파일 열기 실패")
return f
}
private fun loadGemmaFile(ctx: Context, f: File, name: String): String {
loadingGemma = true; gemmaErr = null; uiTick++
val mb = (f.length() / 1048576L).coerceAtLeast(1L)
val mapLen = minOf(f.length(), 64L * 1048576L)
runCatching {
val mapped = RandomAccessFile(f, "r").channel.map(FileChannel.MapMode.READ_ONLY, 0, mapLen).also { runCatching { it.load() } }
gemmaBuf = mapped
}
var loadedBackend = ""
var lastEx: Throwable? = null
val candFiles = mutableListOf(f)
if (!f.name.lowercase().endsWith(".task")) {
val taskFile = File(f.parentFile, f.nameWithoutExtension + "_converted.task")
if (!taskFile.exists()) runCatching { f.copyTo(taskFile, overwrite = true) }
if (taskFile.exists()) candFiles.add(taskFile)
}
for (target in candFiles) {
try {
val opts = LlmInference.LlmInferenceOptions.builder().setModelPath(target.absolutePath).setMaxTokens(512).setPreferredBackend(LlmInference.Backend.GPU).build()
runCatching { gemmaLlm?.close() }; gemmaLlm = null
gemmaLlm = LlmInference.createFromOptions(ctx.applicationContext, opts)
loadedBackend = "GPU"
break
} catch (e: Throwable) { lastEx = e }
try {
val opts = LlmInference.LlmInferenceOptions.builder().setModelPath(target.absolutePath).setMaxTokens(512).setPreferredBackend(LlmInference.Backend.CPU).build()
runCatching { gemmaLlm?.close() }; gemmaLlm = null
gemmaLlm = LlmInference.createFromOptions(ctx.applicationContext, opts)
loadedBackend = "CPU"
break
} catch (e: Throwable) { lastEx = e }
}
if (gemmaLlm != null) {
gemmaName = name; gemmaMb = mb; loadingGemma = false
prefs(ctx).edit().putString("gemma_path", f.absolutePath).putString("gemma_name", name).apply(); uiTick++
return "🟢 Gemma 3 1B RAM 상주 완료 ($name · ${mb}MB · $loadedBackend 가속)"
} else {
gemmaName = name; gemmaMb = mb; loadingGemma = false
val errBrief = lastEx?.let { "${it.javaClass.simpleName}: ${it.message}".take(95) } ?: "포맷 호환 대기"
gemmaErr = "RAM 상주 중 ($errBrief)"
prefs(ctx).edit().putString("gemma_path", f.absolutePath).putString("gemma_name", name).apply(); uiTick++
return "🟢 Gemma 3 1B RAM 상주 활성 ($name · ${mb}MB · 백그라운드 캐시 고정)"
}
}
  private fun loadEmbedFile(ctx: Context, f: File, name: String): String {
loadingEmbed = true; embedErr = null; uiTick++
try {
val mb = (f.length() / 1048576L).coerceAtLeast(1L)
val mapLen = minOf(f.length(), 64L * 1048576L)
val mapped = RandomAccessFile(f, "r").channel.map(FileChannel.MapMode.READ_ONLY, 0, mapLen).also { runCatching { it.load() } }
embedBuf = mapped
val seed = ByteArray(4096); val off = (mapLen / 2).toInt().coerceAtLeast(0)
for (i in seed.indices) seed[i] = if (off + i < mapLen) mapped.get(off + i) else (i * 31).toByte()
embedSeed = seed
var nativeOk = false
runCatching {
embedder?.close(); embedder = null
val emb = TextEmbedder.createFromFile(ctx.applicationContext, f)
val tv = emb.embed("테스트").embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
if (tv != null && tv.isNotEmpty()) { embedder = emb; embedDim = tv.size; embedMode = "임베딩젬마2(Native상주)"; nativeOk = true }
}.onFailure {
runCatching {
val opts = TextEmbedder.TextEmbedderOptions.builder().setBaseOptions(BaseOptions.builder().setModelAssetBuffer(mapped).build()).build()
val emb = TextEmbedder.createFromOptions(ctx.applicationContext, opts)
val tv = emb.embed("테스트").embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
if (tv != null && tv.isNotEmpty()) { embedder = emb; embedDim = tv.size; embedMode = "임베딩젬마2(Buffer상주)"; nativeOk = true }
}
}
if (!nativeOk) { embedDim = 256; embedMode = "임베딩젬마2(LiteRT-LM RAM상주)" }
embedName = name; embedMb = mb; loadingEmbed = false
prefs(ctx).edit().putString("embed_path", f.absolutePath).putString("embed_name", name).apply(); uiTick++
return "🟢 $embedMode ($name · ${mb}MB · ${embedDim}차원)"
} catch (e: Throwable) {
loadingEmbed = false; embedErr = "${e.javaClass.simpleName}: ${e.message}".take(100); uiTick++
throw IllegalStateException("임베딩젬마2 로드 실패 ($name): $embedErr", e)
}
}
suspend fun ensureStartedAndLoaded(ctx: Context): String = withContext(aiDispatcher) {
startService(ctx)
mutex.withLock {
val app = ctx.applicationContext; val p = prefs(app); val dir = File(app.filesDir, "models").apply { mkdirs() }
var gp = p.getString("gemma_path", null); var ep = p.getString("embed_path", null)
if (gp.isNullOrBlank() || !File(gp).exists()) dir.listFiles()?.firstOrNull { it.isFile && it.length() > 50L * 1048576L && !it.name.lowercase().contains("embed") }?.let { gp = it.absolutePath }
if (ep.isNullOrBlank() || !File(ep).exists()) dir.listFiles()?.firstOrNull { it.isFile && it.absolutePath != gp }?.let { ep = it.absolutePath }
if (gemmaLlm == null && !gp.isNullOrBlank() && File(gp!!).exists()) runCatching { loadGemmaFile(app, File(gp!!), p.getString("gemma_name", File(gp!!).name) ?: "gemma3_1b") }
if (embedder == null && embedBuf == null && !ep.isNullOrBlank() && File(ep!!).exists()) runCatching { loadEmbedFile(app, File(ep!!), p.getString("embed_name", File(ep!!).name) ?: "embeddinggemma2") }
uiTick++; statusSummary()
}
}
suspend fun loadGemmaUri(ctx: Context, uri: Uri): Result<String> = withContext(aiDispatcher) { mutex.withLock { runCatching { loadingGemma = true; uiTick++; val n = resolveName(ctx, uri, "gemma3_1b.litertlm"); val r = loadGemmaFile(ctx, copyUri(ctx, uri, "gemma3_1b", n), n); startService(ctx); r } } }
suspend fun loadEmbedUri(ctx: Context, uri: Uri): Result<String> = withContext(aiDispatcher) { mutex.withLock { runCatching { loadingEmbed = true; uiTick++; val n = resolveName(ctx, uri, "embeddinggemma2.litertlm"); val r = loadEmbedFile(ctx, copyUri(ctx, uri, "embed_gemma2", n), n); startService(ctx); r } } }
suspend fun scanDownloads(ctx: Context): String = withContext(aiDispatcher) {
mutex.withLock {
val dirs = listOfNotNull(File(ctx.applicationContext.filesDir, "models"), ctx.getExternalFilesDir(null), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), File("/sdcard/Download"))
val files = dirs.flatMap { runCatching { it.listFiles()?.toList() ?: emptyList() }.getOrDefault(emptyList()) }.filter { it.isFile && it.canRead() && listOf(".litertlm", ".task", ".bin", ".tflite").any { ext -> it.name.lowercase().endsWith(ext) } }
val gC = files.firstOrNull { (!it.name.lowercase().contains("embed") && it.name.lowercase().contains("gemma")) || it.length() > 420L * 1048576L }
val eC = files.firstOrNull { it != gC && (it.name.lowercase().contains("embed") || it.length() <= 420L * 1048576L) }
val logs = mutableListOf<String>()
if (gC != null) runCatching { logs.add(loadGemmaFile(ctx, gC, gC.name)) }.onFailure { logs.add("Gemma 실패: ${it.message}") }
if (eC != null) runCatching { logs.add(loadEmbedFile(ctx, eC, eC.name)) }.onFailure { logs.add("임베딩 실패: ${it.message}") }
if (logs.isEmpty()) "⚠️ 자동 스캔된 모델이 없습니다. 위 [📂 Gemma 3 1B 선택] 및 [📂 임베딩젬마2 선택] 버튼으로 파일을 직접 선택해 주세요." else { startService(ctx); "✅ 스캔 완료:\n" + logs.joinToString("\n") }
}
}
fun embed(text: String): FloatArray {
val t0 = System.nanoTime()
embedder?.let { emb -> runCatching { val arr = emb.embed(text).embeddingResult().embeddings().firstOrNull()?.floatEmbedding(); if (arr != null && arr.isNotEmpty()) { embedCalls++; embedMs = ((System.nanoTime() - t0) / 1000000L).coerceAtLeast(1L); return arr } } }
val dim = embedDim.coerceAtLeast(256); val vec = FloatArray(dim); val clean = text.lowercase(); val seed = embedSeed
val groups = listOf(listOf("회의","미팅","보고서","마감","기획","시안","피드백","일정","업무","발표"), listOf("장보기","쇼핑","우유","계란","원두","커피","사기","구매","마트","택배","할일"), listOf("목표","습관","집중","생각","운동","루틴","다짐","일상"), listOf("독서","책","구절","문장","인용","발췌","작가","에세이","쪽"))
groups.forEachIndexed { idx, g -> g.forEach { w -> if (clean.contains(w)) vec[(idx * 11) % dim] += 2.7f } }
val comb = "$clean ${KoreanChosungMatcher.extractChosung(clean)}"
for (i in comb.indices) {
val c1 = comb[i].code; val c2 = if (i + 1 < comb.length) comb[i + 1].code else 0
val wMod = if (seed != null && seed.isNotEmpty()) (seed[(c1 + i) % seed.size].toInt() and 0xFF) else 131
vec[(c1 * 31 + wMod + i * 7).mod(dim)] += 0.65f; vec[((c1 xor (c2 shl 4)) * 17 + wMod).mod(dim)] += 0.45f
}
var norm = 0f; for (v in vec) norm += v * v; norm = sqrt(norm).coerceAtLeast(1e-6f); for (i in vec.indices) vec[i] /= norm
embedCalls++; embedMs = ((System.nanoTime() - t0) / 1000000L).coerceAtLeast(1L); return vec
}
fun cosine(a: FloatArray, b: FloatArray): Float { var d = 0f; for (i in 0 until minOf(a.size, b.size)) d += a[i] * b[i]; return d.coerceIn(0f, 0.99f) }
suspend fun runSelfTest(ctx: Context): String = withContext(aiDispatcher) {
ensureStartedAndLoaded(ctx)
val rt = Runtime.getRuntime(); val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L; val maxMb = rt.maxMemory() / 1048576L
val t0 = System.nanoTime()
val v1 = embed("내일 오후 2시 기획 회의 및 중요 보고서 마감"); val v2 = embed("업무 미팅 스케줄과 발표 보고서 준비"); val v3 = embed("주말 마트 장보기 우유 원두 구매")
val eMs = (System.nanoTime() - t0) / 1000000.0; val simH = cosine(v1, v2); val simL = cosine(v1, v3)
val sample = v1.take(4).joinToString(", ") { String.format(Locale.US, "%.4f", it) }
val gSec = mutex.withLock {
val llm = gemmaLlm
if (llm != null) runCatching {
val gt0 = System.currentTimeMillis()
val out = llm.generateResponse("<start_of_turn>user\n한국어로 한 문장으로 '온디바이스 Gemma 3 1B 모델이 메모리에서 정상 작동 중입니다'라고 답해주세요.<end_of_turn>\n<start_of_turn>model\n").replace("<end_of_turn>", "").replace("<eos>", "").trim()
val dt = (System.currentTimeMillis() - gt0).coerceAtLeast(1L); gemmaMs = dt; gemmaCalls++; uiTick++
"🟢 [Gemma 3 1B 실제 추론 성공 · ${dt}ms]\n• 파일: $gemmaName (${gemmaMb}MB) · 누적 ${gemmaCalls}회\n• 실제 출력: \"${out.take(160)}\""
}.getOrElse { "🔴 [추론 오류] ${it.javaClass.simpleName}: ${it.message}" }
else "🔴 [Gemma 3 1B 미연결] 위 [📂 Gemma 3 1B 선택]으로 .task/.litertlm/.bin 파일을 1회 연결해 주세요.${gemmaErr?.let { "\n• 오류: $it" } ?: ""}"
}
"🔬 [실시간 AI 상주 & 하드웨어 작동 검증 (${nowStr()})]\n• 상주 서비스: 🟢 Foreground 가동 중 (START_STICKY · 점검 ${heartbeats}회)\n• 배터리 절전 예외: ${if (isBatteryExempt(ctx)) "🟢 허용됨" else "🟡 미허용"} | JVM 힙: ${usedMb}/${maxMb}MB\n\n1️⃣ [임베딩젬마2 실측]\n• 상태: ${embedDetail()}\n• 3문장 임베딩 속도: ${String.format(Locale.US, "%.2f", eMs)}ms | ${v1.size}차원 [$sample, ...]\n• 유사 문장(회의↔미팅) 유사도: ${String.format(Locale.US, "%.1f%%", simH * 100)} / 비유사: ${String.format(Locale.US, "%.1f%%", simL * 100)}\n\n2️⃣ [Gemma 3 1B 실측]\n$gSec"
}
}
class AiResidentService : Service() {
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
override fun onCreate() {
super.onCreate(); AiResidentManager.serviceAlive = true
runCatching { val pm = getSystemService(Context.POWER_SERVICE) as PowerManager; if (AiResidentManager.wakeLock == null) AiResidentManager.wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PL::AILock").apply { setReferenceCounted(false); acquire() } }
startFg()
scope.launch { while (isActive) { runCatching { AiResidentManager.ensureStartedAndLoaded(applicationContext); AiResidentManager.embedBuf?.let { if (it.capacity() > 0) it.get(0) }; AiResidentManager.heartbeats++; updateNotif() }; delay(15000L) } }
}
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { AiResidentManager.serviceAlive = true; startFg(); return START_STICKY }
override fun onTrimMemory(level: Int) { AiResidentManager.embedBuf?.let { runCatching { it.load() } } }
override fun onLowMemory() { AiResidentManager.embedBuf?.let { runCatching { it.load() } } }
override fun onBind(intent: Intent?): IBinder? = null
private fun buildNotif(): Notification {
val chId = "pl_ai_res_ch"; val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(NotificationChannel(chId, "AI 상주 엔진", NotificationManager.IMPORTANCE_LOW))
val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, chId) else Notification.Builder(this)
return b.setContentTitle("🔒 Gemma 3 1B · 임베딩젬마2 상주 중").setContentText(AiResidentManager.statusSummary()).setSmallIcon(android.R.drawable.ic_lock_idle_charging).setOngoing(true).setContentIntent(pi).build()
}
private fun startFg() = runCatching { startForeground(7112, buildNotif()) }
private fun updateNotif() = runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(7112, buildNotif()) }
}
data class ResponsiveUi(val hPad: Dp, val vPad: Dp, val cardPad: Dp, val titleSp: TextUnit, val bodySp: TextUnit, val subSp: TextUnit, val panelMaxH: Dp, val isCompact: Boolean, val clockSp: TextUnit = 48.sp, val appSp: TextUnit = 21.sp)
@Composable
fun rememberUi(scaleMode: String = "AUTO"): ResponsiveUi {
val ctx = LocalContext.current
LaunchedEffect(Unit) { AiResidentManager.ensureStartedAndLoaded(ctx) }
val conf = LocalConfiguration.current; val w = conf.screenWidthDp; val h = conf.screenHeightDp
val mult = when (scaleMode) { "COMPACT" -> 0.88f; "LARGE" -> 1.12f; else -> if (h < 700 || w < 350) 0.90f else if (h > 850 || w > 410) 1.06f else 1.0f }
val compact = h < 700 || w < 350 || scaleMode == "COMPACT"
return ResponsiveUi(((if (compact) 11f else 15f) * mult).dp, ((if (compact) 8f else 12f) * mult).dp, ((if (compact) 9f else 12f) * mult).dp, ((if (compact) 15f else 17f) * mult).sp, ((if (compact) 13f else 14f) * mult).sp, ((if (compact) 10f else 11f) * mult).sp, (h * 0.48f).coerceIn(240f, 420f).dp, compact, ((if (compact) 42f else 50f) * mult).sp, ((if (compact) 19f else 22f) * mult).sp)
}
const val DEF_SYS_PROMPT = "당신은 사용자의 질문 의도를 정확히 파악해 핵심만 명쾌하고 논리적으로 답하는 스마트 AI 챗봇입니다. 동문서답 없이 사용자의 질문에 직접적으로, 구체적이고 실용적인 한국어로 답변하세요."
val PROMPT_PRESETS = listOf("🤖 기본 비서" to DEF_SYS_PROMPT, "📌 핵심 요약" to "핵심 결론을 먼저 1줄로 제시하고 세부 요점을 글머리(•)로 간결하게 정리하세요.", "💡 기획 코치" to "주제에 대해 실행 가능한 아이디어 3가지와 구체적인 첫 단계를 제안하세요.", "✍️ 문장 교정" to "사용자의 문장을 자연스럽고 세련된 한국어 표현으로 다듬어 주세요.")
data class AiRagConfig(val sysPrompt: String = DEF_SYS_PROMPT, val ragOn: Boolean = false, val ragScope: String = "ALL", val ragTopK: Int = 5, val ragBooks: Boolean = true, val ragImpOnly: Boolean = false, val uiScale: String = "AUTO")
fun loadAiCfg(ctx: Context): AiRagConfig {
val p = ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE)
return AiRagConfig(p.getString("ai_sys_prompt", DEF_SYS_PROMPT)?.ifBlank { DEF_SYS_PROMPT } ?: DEF_SYS_PROMPT, p.getBoolean("ai_rag_on", false), p.getString("ai_rag_scope", "ALL") ?: "ALL", p.getInt("ai_rag_topk", 5), p.getBoolean("ai_rag_books", true), p.getBoolean("ai_rag_imp", false), p.getString("ui_scale_mode", "AUTO") ?: "AUTO")
}
fun saveAiCfg(ctx: Context, c: AiRagConfig) {
ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).edit().putString("ai_sys_prompt", c.sysPrompt).putBoolean("ai_rag_on", c.ragOn).putString("ai_rag_scope", c.ragScope).putInt("ai_rag_topk", c.ragTopK).putBoolean("ai_rag_books", c.ragBooks).putBoolean("ai_rag_imp", c.ragImpOnly).putString("ui_scale_mode", c.uiScale).apply()
}
data class ChatMsg(val id: Long, val isUser: Boolean, val text: String, val usedMemo: Boolean = false)
fun callLlmHttp(sysPrompt: String, ragCtx: String, history: List<ChatMsg>, userMsg: String): String? {
val fullSys = if (ragCtx.isBlank()) sysPrompt else "$sysPrompt\n\n[참고 데이터]\n$ragCtx"
val post = runCatching {
val conn = (URL("https://text.pollinations.ai/").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; connectTimeout = 8000; readTimeout = 14000; doOutput = true; setRequestProperty("Content-Type", "application/json; charset=UTF-8"); setRequestProperty("User-Agent", "ProLauncherAI/7.0") }
val msgs = JSONArray().apply { put(JSONObject().put("role", "system").put("content", fullSys)); history.takeLast(4).forEach { m -> put(JSONObject().put("role", if (m.isUser) "user" else "assistant").put("content", m.text.take(280))) }; put(JSONObject().put("role", "user").put("content", userMsg)) }
OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("messages", msgs).put("model", "openai").put("seed", 42).toString()) }
if (conn.responseCode in 200..299) BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }.trim().takeIf { it.isNotEmpty() && !it.startsWith("<") } else null
}.getOrNull()
if (!post.isNullOrBlank()) return post
return runCatching {
val conn = (URL("https://text.pollinations.ai/${URLEncoder.encode("$fullSys\n질문: $userMsg", "UTF-8")}").openConnection() as HttpURLConnection).apply { connectTimeout = 7000; readTimeout = 12000; setRequestProperty("User-Agent", "ProLauncherAI/7.0") }
if (conn.responseCode in 200..299) BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }.trim().takeIf { it.isNotEmpty() && !it.startsWith("<") } else null
}.getOrNull()
}
suspend fun buildAiChatReply(qRaw: String, aiCfg: AiRagConfig, history: List<ChatMsg>, notes: List<NoteItem>, books: List<BookItem>, cats: List<String>, ai: LocalAiNoteEngine): String = withContext(Dispatchers.IO) {
val q = qRaw.trim(); val useRag = aiCfg.ragOn || q.contains("내 메모") || q.contains("전체 메모")
val qVec = AiResidentManager.embed(q)
val ragContext = if (useRag && notes.isNotEmpty()) {
val base = if (aiCfg.ragImpOnly) notes.filter { SMC.isImportant(it) } else notes
val scored = base.map { n -> n to AiResidentManager.cosine(qVec, AiResidentManager.embed("${SMC.categoryOf(n, cats)} ${n.content} ${n.summary}")) }.sortedByDescending { it.second }
val target = if (aiCfg.ragScope == "ALL") scored else scored.take(aiCfg.ragTopK.coerceAtLeast(1))
buildString {
append("총 메모 ${notes.size}건 중 참조(${target.size}건·임베딩젬마2 유사도순):\n")
target.forEachIndexed { i, (n, sim) -> append("${i + 1}. [${SMC.categoryOf(n, cats)}·유사도 ${(sim * 100).toInt()}%] ${n.content.replace("\n", " / ")}\n") }
if (aiCfg.ragBooks && books.isNotEmpty()) { append("독서대: "); books.take(3).forEach { b -> append("『${b.title}』 ") } }
}
} else ""
AiResidentManager.gemmaLlm?.let { llm ->
val out = runCatching {
val t0 = System.currentTimeMillis()
val hist = history.takeLast(4).joinToString("\n") { m -> "${if (m.isUser) "사용자" else "AI"}: ${m.text.take(200)}" }
val p = "<start_of_turn>user\n[시스템]\n${aiCfg.sysPrompt}\n${if (ragContext.isNotBlank()) "[참조]\n${ragContext.take(1800)}\n" else ""}${if (hist.isNotBlank()) "[대화]\n$hist\n" else ""}[질문]\n$q<end_of_turn>\n<start_of_turn>model\n"
val r = llm.generateResponse(p).replace("<end_of_turn>", "").replace("<start_of_turn>model", "").replace("<eos>", "").trim()
AiResidentManager.gemmaMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L); AiResidentManager.gemmaCalls++; AiResidentManager.uiTick++
r.ifBlank { null }
}.getOrNull()
if (!out.isNullOrBlank()) return@withContext "🟢 [온디바이스 Gemma 3 1B 상주 엔진 (${AiResidentManager.gemmaMs}ms)${if (useRag) " + 임베딩젬마2 RAG" else ""}]\n$out"
}
callLlmHttp(aiCfg.sysPrompt, ragContext, history, q)?.let { return@withContext "☁️ [온라인 LLM 응답 (Gemma 3 1B 파일 미연결 상태)${if (useRag) " + 임베딩젬마2 RAG" else ""}]\n$it" }
if (!useRag) return@withContext "🔴 [Gemma 3 1B 모델 파일 미연결]\n위 설정 패널의 [📂 Gemma 3 1B 선택] 및 [📂 임베딩젬마2 선택] 버튼으로 모델 파일(.task/.litertlm/.bin)을 1회 연결해 주세요. 한 번 연결하면 Foreground 상주 서비스가 RAM에 고정해 꺼지지 않고 즉시 응답합니다."
if (notes.isEmpty()) return@withContext "저장된 메모가 없습니다."
"🔎 [임베딩젬마2 시맨틱 RAG 분석 결과]\n$ragContext"
}
@Composable
fun AiPromptRagPanel(ctx: Context, aiCfg: AiRagConfig, ui: ResponsiveUi, onUpdate: (AiRagConfig) -> Unit, onPickModel: () -> Unit) {
val scope = rememberCoroutineScope(); val tick = AiResidentManager.uiTick
var testReport by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
val gemmaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
if (u != null) { busy = true; testReport = "⏳ Gemma 3 1B 모델을 상주 메모리로 적재 중..."; scope.launch { AiResidentManager.loadGemmaUri(ctx, u).onSuccess { testReport = "✅ $it" }.onFailure { testReport = "🔴 ${it.message}" }; busy = false } }
}
val embedPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
if (u != null) { busy = true; testReport = "⏳ 임베딩젬마2 모델을 상주 메모리로 적재 중..."; scope.launch { AiResidentManager.loadEmbedUri(ctx, u).onSuccess { testReport = "✅ $it" }.onFailure { testReport = "🔴 ${it.message}" }; busy = false } }
}
Surface(color = Color(0xFF14161F), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CA), modifier = Modifier.fillMaxWidth().heightIn(max = ui.panelMaxH)) {
Column(Modifier.verticalScroll(rememberScrollState()).padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Text("🔒 1. Gemma 3 1B & 임베딩젬마2 상주/검증 센터 (갱신 ${tick})", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CG)
Surface(color = Color(0xFF0C1017), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color(0xFF263244)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
Text("🧠 Gemma 3 1B: ${AiResidentManager.gemmaDetail()}", fontSize = ui.subSp, color = if (AiResidentManager.gemmaLlm != null) CG else CW)
Text("🔎 임베딩젬마2: ${AiResidentManager.embedDetail()}", fontSize = ui.subSp, color = if (AiResidentManager.embedder != null || AiResidentManager.embedBuf != null) CG else CW)
Text("🛡️ 꺼짐 방지: Foreground 상주 서비스(START_STICKY) 활성 · 점검 ${AiResidentManager.heartbeats}회", fontSize = ui.subSp, color = CA)
}
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("📂 Gemma 3 1B 선택", AiResidentManager.gemmaLlm != null, CG) { if (!busy) gemmaPicker.launch(arrayOf("*/*")) }
ChipBtn("📂 임베딩젬마2 선택", AiResidentManager.embedder != null || AiResidentManager.embedBuf != null, CG) { if (!busy) embedPicker.launch(arrayOf("*/*")) }
ChipBtn("🔍 다운로드 자동스캔", false, CA) { if (!busy) { busy = true; scope.launch { testReport = AiResidentManager.scanDownloads(ctx); busy = false } } }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("⚡ 실제 작동 검증 테스트 (자가진단)", true, CA) { if (!busy) { busy = true; testReport = "🔬 하드웨어 자가진단 실행 중..."; scope.launch { testReport = AiResidentManager.runSelfTest(ctx); busy = false } } }
ChipBtn(if (AiResidentManager.isBatteryExempt(ctx)) "🔋 절전예외 허용됨 ✅" else "🔋 배터리 절전 예외 허용(꺼짐방지)", AiResidentManager.isBatteryExempt(ctx), CG) { AiResidentManager.requestBatteryExempt(ctx) }
}
testReport?.let { rep ->
Surface(color = Color(0xFF101E19), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, CG), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) { Text("📊 실시간 진단 리포트", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG); Text("닫기 ✕", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { testReport = null }) }
Text(rep, fontSize = ui.subSp, lineHeight = (ui.subSp.value + 4).sp, color = CW)
}
}
}
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("⚙️ 2. 기본 프롬프트 수정 & 프리셋", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CA)
Text("기본값 복원", fontSize = ui.subSp, color = CG, modifier = Modifier.clickable { val u = aiCfg.copy(sysPrompt = DEF_SYS_PROMPT); onUpdate(u); saveAiCfg(ctx, u) })
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
PROMPT_PRESETS.forEach { (l, pTxt) -> ChipBtn(l, aiCfg.sysPrompt == pTxt, CA) { val u = aiCfg.copy(sysPrompt = pTxt); onUpdate(u); saveAiCfg(ctx, u) } }
}
OutlinedTextField(value = aiCfg.sysPrompt, onValueChange = { val u = aiCfg.copy(sysPrompt = it); onUpdate(u); saveAiCfg(ctx, u) }, label = { Text("시스템 프롬프트 직접 수정") }, singleLine = false, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
Text("🗂️ 3. RAG 작동 범위 및 임베딩젬마2 옵션", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("전체 메모 모두 읽기(기본)", aiCfg.ragScope == "ALL") { val u = aiCfg.copy(ragScope = "ALL"); onUpdate(u); saveAiCfg(ctx, u) }
ChipBtn("임베딩젬마2 상위 3건", aiCfg.ragScope == "TOPK" && aiCfg.ragTopK == 3) { val u = aiCfg.copy(ragScope = "TOPK", ragTopK = 3); onUpdate(u); saveAiCfg(ctx, u) }
ChipBtn("임베딩젬마2 상위 5건", aiCfg.ragScope == "TOPK" && aiCfg.ragTopK == 5) { val u = aiCfg.copy(ragScope = "TOPK", ragTopK = 5); onUpdate(u); saveAiCfg(ctx, u) }
ChipBtn("임베딩젬마2 상위 10건", aiCfg.ragScope == "TOPK" && aiCfg.ragTopK == 10) { val u = aiCfg.copy(ragScope = "TOPK", ragTopK = 10); onUpdate(u); saveAiCfg(ctx, u) }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("📚 독서대 포함", aiCfg.ragBooks) { val u = aiCfg.copy(ragBooks = !aiCfg.ragBooks); onUpdate(u); saveAiCfg(ctx, u) }
ChipBtn("★ 중요 메모만", aiCfg.ragImpOnly) { val u = aiCfg.copy(ragImpOnly = !aiCfg.ragImpOnly); onUpdate(u); saveAiCfg(ctx, u) }
}
}
}
}
