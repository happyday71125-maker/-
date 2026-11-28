package app.prolauncher.ai
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors

object LiteRtLmBridge {
 private val singleDisp = Executors.newSingleThreadExecutor { r -> Thread(r, "pl-litert").apply { isDaemon = true } }.asCoroutineDispatcher()
 private var engine: Engine? = null; private var loadedPath = ""; private var loadedBe = ""; private var loadedCtx = 0
 @Volatile private var activeConv: Conversation? = null
 var modelName by mutableStateOf("미연결"); var modelMb by mutableStateOf(0L)
 var backendPref by mutableStateOf("GPU"); var enginePref by mutableStateOf("AUTO"); var activeBackendLabel by mutableStateOf("대기")

 fun initPrefs(ctx: Context) {
  val sp = ctx.applicationContext.getSharedPreferences("pl_ai_v2_prefs", Context.MODE_PRIVATE)
  backendPref = sp.getString("litert_backend_pref", "GPU") ?: "GPU"
  enginePref = sp.getString("local_engine_pref", "AUTO") ?: "AUTO"
  refreshDetected()
 }
 fun setBackendPref(ctx: Context, p: String) { val n = if (p.equals("CPU", true)) "CPU" else "GPU"; backendPref = n; ctx.applicationContext.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putString("litert_backend_pref", n).apply() }
 fun setEnginePref(ctx: Context, p: String) { val n = when (p.uppercase(Locale.US)) { "LITERT" -> "LITERT"; "GGUF" -> "GGUF"; else -> "AUTO" }; enginePref = n; ctx.applicationContext.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putString("local_engine_pref", n).apply() }
 fun findLocalLiteRt(): File? = listOf(File(baseFilesDir(), "litert_models"), File(baseFilesDir(), "llm_models"), baseFilesDir()).flatMap { it.listFiles()?.toList() ?: emptyList() }.filter { it.isFile && it.length() > 1048576L && (it.name.endsWith(".litertlm", true) || it.name.endsWith(".task", true)) }.maxByOrNull { it.lastModified() }
 fun refreshDetected() = runCatching { val f = findLocalLiteRt(); if (f != null) { modelName = f.name; modelMb = f.length() / 1048576L } else { modelName = "미연결"; modelMb = 0L } }
 fun abortCurrent() { runCatching { activeConv?.cancelProcess() } }
 fun releaseNow() { abortCurrent(); val c = activeConv; activeConv = null; runCatching { c?.close() }; val e = engine; engine = null; loadedPath = ""; loadedBe = ""; loadedCtx = 0; activeBackendLabel = "대기"; runCatching { e?.close() } }
 fun clearModels(ctx: Context): String { appCtxRef = ctx.applicationContext; releaseNow(); var b = 0L; File(ctx.applicationContext.filesDir, "litert_models").listFiles()?.forEach { b += it.length(); runCatching { it.delete() } }; refreshDetected(); return "🗑️ LiteRT-LM (.litertlm) 모델 비우기 완료 (${b / 1048576L}MB 확보)" }

 suspend fun importModelUri(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
  appCtxRef = ctx.applicationContext; var name = "gemma4_e2b.litertlm"
  runCatching { ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) } }
  val safe = (if (name.endsWith(".litertlm", true) || name.endsWith(".task", true)) name else "$name.litertlm").replace(Regex("[^a-zA-Z0-9가-힣._-]"), "_")
  val dir = File(ctx.applicationContext.filesDir, "litert_models").apply { mkdirs() }
  withContext(singleDisp) { releaseNow() }; dir.listFiles()?.forEach { runCatching { it.delete() } }
  val target = File(dir, safe)
  val ok = runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(target).use { o -> i.copyTo(o) } }; target.exists() && target.length() > 1048576L }.getOrDefault(false)
  if (!ok) { runCatching { target.delete() }; return@withContext "⚠️ 유효한 .litertlm 파일이 아닙니다." }
  refreshDetected(); "✅ LiteRT-LM 모델 등록 완료! [${target.name.take(22)} · ${target.length() / 1048576L}MB · ${backendPref} 우선]"
 }

 @OptIn(ExperimentalApi::class)
 private fun ensureEngineLocked(f: File, reqCtx: Int): Pair<Engine?, String> {
  val want = backendPref; val p = f.absolutePath; val c = reqCtx.coerceIn(1024, 32768)
  if (engine != null && loadedPath == p && loadedBe == want && loadedCtx == c) return engine to activeBackendLabel
  releaseNow(); val cDir = appCtxRef?.cacheDir?.absolutePath
  for (be in if (want == "GPU") listOf("GPU", "CPU") else listOf("CPU")) {
   val eng = runCatching { runCatching { ExperimentalFlags.enableSpeculativeDecoding = (be == "GPU") }; Engine(EngineConfig(modelPath = p, backend = if (be == "GPU") Backend.GPU() else Backend.CPU(), maxNumTokens = c, cacheDir = cDir)).also { it.initialize() } }.getOrNull()
   if (eng != null) { engine = eng; loadedPath = p; loadedBe = want; loadedCtx = c; activeBackendLabel = be; return eng to be }
  }
  return null to "실패"
 }

 suspend fun generate(f: File, sysP: String, history: List<ChatMsg>, userMsg: String, onStream: ((String) -> Unit)? = null, genId: Long = 0L, temp: Float = 0.6f, ctxSize: Int = 4096, maxTok: Int = 2048, histTurns: Int = 2): String? {
  lastModelUseMs = System.currentTimeMillis(); llmMutex.lock()
  try {
   return withContext(singleDisp) {
    val effCtx = maxOf(ctxSize, maxTok + 512).coerceIn(1024, 32768); val effMax = maxTok.coerceIn(256, 8192)
    val (eng, usedBe) = ensureEngineLocked(f, effCtx); if (eng == null) return@withContext null
    val hLim = (histTurns.coerceIn(0, 20)) * 2
    val hist = if (hLim <= 0) "" else history.takeLast(hLim).joinToString("\n") { (if (it.isUser) "U: " else "A: ") + it.text.substringBefore("\n\n⏱️").substringBefore("\n⏱️").replace("▍", "").take(140) }
    val fullS = if (hist.isBlank()) sysP.take(500) else "${sysP.take(400)}\n$hist"
    val convCfg = ConversationConfig(systemInstruction = Contents.of(fullS), samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temp.coerceIn(0.05f, 1.2f).toDouble()), thinkingConfig = ThinkingConfig(enableThinking = thinkOn), maxOutputToken = effMax)
    var conv: Conversation? = null; val sb = StringBuilder(); var chunks = 0; var tStart = System.currentTimeMillis(); var lastMs = 0L
    try {
     conv = eng.createConversation(convCfg); activeConv = conv
     conv.sendMessageAsync(userMsg).collect { msg ->
      if (genId != 0L && genId != activeGenId) { runCatching { conv.cancelProcess() }; throw CancellationException("aborted") }
      val ch = msg.toString()
      if (ch.isNotEmpty()) {
       if (chunks == 0) tStart = System.currentTimeMillis(); chunks++; sb.append(ch)
       if (streamOn && onStream != null) { val now = System.currentTimeMillis(); if (now - lastMs >= 30L || ch.contains('\n')) { lastMs = now; val el = ((now - tStart).coerceAtLeast(50L)) / 1000f; runCatching { onStream.invoke(sb.toString() + "\n\n⏱️ %.1f ch/s · LiteRT-%s · ctx %d".format(Locale.US, chunks / el, usedBe, effCtx)) } } }
      }
     }
    } catch (_: CancellationException) {} catch (_: Throwable) { if (sb.isEmpty()) return@withContext null } finally { if (activeConv === conv) activeConv = null; runCatching { conv?.close() } }
    lastModelUseMs = System.currentTimeMillis()
    val cl = cleanThink(sb.toString().trim(), sysP).takeIf { it.isNotBlank() } ?: return@withContext null
    val fin = cl + "\n\n⏱️ %.1f초 · LiteRT-%s · ctx %d · 기억 %d대화".format(Locale.US, ((System.currentTimeMillis() - tStart).coerceAtLeast(50L)) / 1000f, usedBe, effCtx, histTurns)
    if (streamOn && onStream != null) runCatching { onStream.invoke(fin) }; fin
   }
  } finally { lastModelUseMs = System.currentTimeMillis(); llmMutex.unlock() }
 }
}
