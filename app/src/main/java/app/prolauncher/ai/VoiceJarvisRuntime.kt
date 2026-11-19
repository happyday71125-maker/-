package app.prolauncher.ai
import android.os.*
import android.speech.*
import android.speech.tts.*
import android.content.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

typealias SMC = SmartMemoClassifier
val FMW = Modifier.fillMaxWidth()
val HS6 = Arrangement.spacedBy(6.dp)
val VS6 = Arrangement.spacedBy(6.dp)
val VS8 = Arrangement.spacedBy(8.dp)
var msgSeq = System.currentTimeMillis(); fun nextId(): Long { msgSeq += 1L; return msgSeq }; var pendingHomeAiQuery by mutableStateOf("")
var briefingSpeakingNow by mutableStateOf(false); var vOn by mutableStateOf(false); var ttsOn by mutableStateOf(true); var vSt by mutableStateOf("대기중"); var btmOpen by mutableStateOf(true); var blkOn by mutableStateOf(false)
var ttsEngine: TextToSpeech? = null; var ttsReady = false; val ttsPending = mutableListOf<Pair<String, Boolean>>(); var ttsLastEnqueueMs = 0L; var ttsActiveCount = 0; var ttsLastActiveMs = 0L
fun isTtsBusyNow(): Boolean { val engSpk = SupertonicTtsEngine.playing || runCatching { ttsEngine?.isSpeaking == true }.getOrDefault(false); if (engSpk) ttsLastActiveMs = System.currentTimeMillis(); val recentQ = ttsPending.isNotEmpty() || (System.currentTimeMillis() - ttsLastEnqueueMs) <  420L; val tracked = ttsActiveCount > 0 && (System.currentTimeMillis() - ttsLastActiveMs) < 15000L; if (!engSpk && !recentQ && !tracked) ttsActiveCount = 0; return engSpk || recentQ || tracked }
var sttEngine: SpeechRecognizer? = null; var sttEpoch = 0; var oneShotStt: SpeechRecognizer? = null; var dictEpoch = 0; var oneShotActive by mutableStateOf(false); var dictAccumText: String = ""; var dictLastEmitted: String = ""; var dictHadPartial = false; var dPartCb: (String) -> Unit = {}; var dDoneCb: (String) -> Unit = {}; var dStatCb: (String) -> Unit = {}; var dFallCb: () -> Unit = {}; var dCmdCb: (String, String) -> Unit = { _, _ -> }; var dPhraseCb: ((String) -> Unit)? = null; var wakeLockRef: PowerManager.WakeLock? = null; var vCb: (String) -> Unit = {}
var isAiBusy by mutableStateOf(false); var streamActive by mutableStateOf(false); var lastSttBeatMs = 0L; var ttsSpkIdx = 0; var ttsFirstChunk = true
var lastEcho by mutableStateOf(""); var pauseUntil by mutableStateOf(0L); var vBuf by mutableStateOf(""); var vSeq = 0; var vSpkNow = false; var vWaitSec by mutableStateOf(3)
fun uiP(d: Long = 0L, f: () -> Unit) { if (d <= 0L && Looper.myLooper() == Looper.getMainLooper()) f() else Handler(Looper.getMainLooper()).postDelayed(Runnable { f() }, d.coerceAtLeast(0L)) }
fun stopOneShotDictation() = uiP(0L) {
    dictEpoch += 1
    oneShotActive = false
    dictHadPartial = false
    val old = oneShotStt
    oneShotStt = null
    runCatching { old?.cancel(); old?.destroy() }
}
fun syncDictationBuffer(ctx: Context, manualText: String) = uiP(0L) {
    val normalized = manualText.trimEnd()
    if (!oneShotActive || dPhraseCb != null) {
        dictAccumText = normalized
        dictLastEmitted = manualText
        return@uiP
    }
    if (manualText == dictLastEmitted) return@uiP
    dictAccumText = normalized
    dictLastEmitted = manualText
    if (dictHadPartial || oneShotStt != null) {
        dictHadPartial = false
        val ep = ++dictEpoch
        val old = oneShotStt
        oneShotStt = null
        runCatching { old?.cancel(); old?.destroy() }
        uiP(120L) {
            if (oneShotActive && ep == dictEpoch) {
                listenDictStep(ep, ctx, dPartCb, dDoneCb, dStatCb, dFallCb, dCmdCb, dPhraseCb)
            }
        }
    }
}

fun listenDictStep(
    ep: Int,
    ctx: Context,
    onPart: (String) -> Unit,
    onDone: (String) -> Unit,
    onStatus: (String) -> Unit,
    onFallback: () -> Unit,
    onCmd: (String, String) -> Unit,
    onPhrase: ((String) -> Unit)? = null
): Unit = uiP(0L) {
    if (!oneShotActive || ep != dictEpoch) return@uiP
    if (isTtsBusyNow() || (onPhrase != null && (isAiBusy || streamActive))) {
        uiP(350L) { if (oneShotActive && ep == dictEpoch) listenDictStep(ep, ctx, onPart, onDone, onStatus, onFallback, onCmd, onPhrase) }
        return@uiP
    }
    runCatching {
        val old = oneShotStt
        oneShotStt = null
        runCatching { old?.cancel(); old?.destroy() }
        dictHadPartial = false
        val appC = appCtxRef ?: ctx.applicationContext
        val targetCtx: Context = if (appInFg && mainActRef != null) mainActRef!! else appC
        val sr = runCatching { SpeechRecognizer.createSpeechRecognizer(targetCtx) }.getOrElse { SpeechRecognizer.createSpeechRecognizer(appC) }
        oneShotStt = sr
        JarvisRuntime.lastBeatMs = System.currentTimeMillis()
        fun emitPart(tx: String) {
            dictLastEmitted = tx
            onPart(tx)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                if (!oneShotActive || ep != dictEpoch) return
                srFailStreak = 0
                JarvisRuntime.lastBeatMs = System.currentTimeMillis()
                onStatus(
                    if (onPhrase != null) (
                        if (JarvisController.sleeping) "🌙 자비스 대기모드 ([${wakeWord}]라고 불러주세요)"
                        else if (wakeOnly && System.currentTimeMillis() >= wakeFollowUpUntil) "👂 [${wakeWord}] 호출 대기 중 (화면 OFF 유지)..."
                        else "🤖🎙️ 자비스 경청 중... 편하게 말씀하세요"
                        )
                    else if (dictAccumText.isBlank()) "🔴 연속 받아쓰기 중... (명령: 메모저장 / 질문전송)"
                    else "🔴 이어서 받아쓰는 중... (명령: 메모저장 / 질문전송)"
                )
            }
            override fun onBeginningOfSpeech() {
                srFailStreak = 0
                dictHadPartial = true
                JarvisRuntime.lastBeatMs = System.currentTimeMillis()
            }
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { JarvisRuntime.lastBeatMs = System.currentTimeMillis() }
            override fun onPartialResults(b: Bundle?) {
                if (!oneShotActive || ep != dictEpoch) return
                srFailStreak = 0
                JarvisRuntime.lastBeatMs = System.currentTimeMillis()
                val pt = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""
                if (pt.isNotEmpty()) {
                    dictHadPartial = true
                    if (onPhrase != null && (JarvisController.sleeping || (wakeOnly && System.currentTimeMillis() >= wakeFollowUpUntil))) {
                        val (wk, _) = matchWakePhrase(pt, wakeWord)
                        if (wk) { emitPart(pt); onStatus("🗣️ $pt") }
                    } else {
                        val comb = if (dictAccumText.isBlank()) pt else if (dictAccumText.endsWith(10.toChar())) dictAccumText + pt else "$dictAccumText $pt"
                        emitPart(comb)
                        onStatus("🗣️ $pt")
                    }
                }
            }
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onError(e: Int) {
                if (!oneShotActive || ep != dictEpoch) return
                dictHadPartial = false
                val cur = oneShotStt
                oneShotStt = null
                runCatching { cur?.cancel(); cur?.destroy() }
                JarvisRuntime.lastBeatMs = System.currentTimeMillis()
                if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    srFailStreak = 0
                    val wMs = if (appInFg) 180L else 420L
                    uiP(wMs) { if (oneShotActive && ep == dictEpoch) listenDictStep(ep, ctx, onPart, onDone, onStatus, onFallback, onCmd, onPhrase) }
                    return
                }
                srFailStreak += 1
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && ctx.checkSelfPermission("android.permission.RECORD_AUDIO") != 0) {
                    oneShotActive = false
                    onFallback()
                } else if (appInFg && onPhrase == null && srFailStreak >= 6) {
                    srFailStreak = 0
                    oneShotActive = false
                    onFallback()
                } else {
                    val waitMs = if (!appInFg) 900L else if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) 680L else 340L
                    uiP(waitMs) { if (oneShotActive && ep == dictEpoch) listenDictStep(ep, ctx, onPart, onDone, onStatus, onFallback, onCmd, onPhrase) }
                }
            }
            override fun onResults(b: Bundle?) {
                if (!oneShotActive || ep != dictEpoch) return
                srFailStreak = 0
                dictHadPartial = false
                val cur = oneShotStt
                oneShotStt = null
                runCatching { cur?.destroy() }
                JarvisRuntime.lastBeatMs = System.currentTimeMillis()
                val raw = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""
                if (onPhrase != null) {
                    if (raw.isNotEmpty()) onPhrase(raw)
                    else uiP(if (appInFg) 180L else 380L) { if (oneShotActive && ep == dictEpoch) listenDictStep(ep, ctx, onPart, onDone, onStatus, onFallback, onCmd, onPhrase) }
                    return
                }
                if (raw.isNotEmpty()) {
                    val saveSuf = listOf("메모 저장해줘", "메모 저장", "메모저장", "저장해줘", "메모해줘").firstOrNull { raw.endsWith(it) }
                    val sendSuf = listOf("질문 전송해줘", "질문 전송", "질문전송", "전송해줘", "물어봐줘", "물어봐").firstOrNull { raw.endsWith(it) }
                    val stopSuf = listOf("녹음 중지", "녹음중지", "받아쓰기 종료", "입력 종료").firstOrNull { raw.endsWith(it) }
                    val nlSuf = listOf("줄바꿈", "다음 줄", "엔터").firstOrNull { raw.endsWith(it) }
                    if (raw in listOf("전체 지우기", "전체지우기", "다 지워", "모두 지워")) {
                        dictAccumText = ""
                        emitPart("")
                        onStatus("🗑️ 전체 지움 완료! 새로 말씀하세요...")
                    } else if (saveSuf != null) {
                        val rem = raw.removeSuffix(saveSuf).trim()
                        if (rem.isNotEmpty()) dictAccumText = if (dictAccumText.isBlank()) rem else "$dictAccumText $rem"
                        val fin = dictAccumText.trim()
                        emitPart(fin)
                        stopOneShotDictation()
                        dictLastEmitted = fin
                        onDone(fin)
                        onCmd("SAVE_MEMO", fin)
                        return
                    } else if (sendSuf != null) {
                        val rem = raw.removeSuffix(sendSuf).trim()
                        if (rem.isNotEmpty()) dictAccumText = if (dictAccumText.isBlank()) rem else "$dictAccumText $rem"
                        val fin = dictAccumText.trim()
                        emitPart(fin)
                        stopOneShotDictation()
                        dictLastEmitted = fin
                        onDone(fin)
                        onCmd("SEND_AI", fin)
                        return
                    } else if (stopSuf != null) {
                        val rem = raw.removeSuffix(stopSuf).trim()
                        if (rem.isNotEmpty()) dictAccumText = if (dictAccumText.isBlank()) rem else "$dictAccumText $rem"
                        val fin = dictAccumText.trim()
                        emitPart(fin)
                        stopOneShotDictation()
                        dictLastEmitted = fin
                        onDone(fin)
                        onCmd("STOP", fin)
                        return
                    } else if (nlSuf != null) {
                        val rem = raw.removeSuffix(nlSuf).trim()
                        if (rem.isNotEmpty()) dictAccumText = if (dictAccumText.isBlank()) rem else "$dictAccumText $rem"
                        dictAccumText += 10.toChar()
                        emitPart(dictAccumText)
                        onStatus("↵ 줄바꿈 적용됨! 계속 말씀하세요...")
                    } else {
                        dictAccumText = if (dictAccumText.isBlank()) raw else if (dictAccumText.endsWith(10.toChar())) dictAccumText + raw else "$dictAccumText $raw"
                        emitPart(dictAccumText)
                        onStatus("✅ 이어쓰기: $raw")
                    }
                }
                uiP(180L) { if (oneShotActive && ep == dictEpoch) listenDictStep(ep, ctx, onPart, onDone, onStatus, onFallback, onCmd, onPhrase) }
            }
        })
        sr.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, targetCtx.packageName)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (onPhrase != null) 2200L else 2600L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (onPhrase != null) 1600L else 2000L)
        )
    }.onFailure {
        if (appInFg && onPhrase == null) {
            oneShotActive = false
            onFallback()
        } else {
            uiP(1000L) { if (oneShotActive && ep == dictEpoch) listenDictStep(ep, ctx, onPart, onDone, onStatus, onFallback, onCmd, onPhrase) }
        }
    }
}

fun startOneShotDictation(
    ctx: Context,
    initText: String = "",
    onPart: (String) -> Unit,
    onDone: (String) -> Unit,
    onStatus: (String) -> Unit,
    onFallback: () -> Unit,
    onCmd: (String, String) -> Unit = { _, _ -> },
    onPhrase: ((String) -> Unit)? = null
) = uiP(0L) {
    if (vOn) vStop(ctx)
    val old = oneShotStt
    oneShotStt = null
    runCatching { old?.cancel(); old?.destroy() }
    srFailStreak = 0
    val ep = ++dictEpoch
    val trimmed = initText.trimEnd()
    dictAccumText = trimmed
    dictLastEmitted = initText
    dictHadPartial = false
    dPartCb = onPart
    dDoneCb = onDone
    dStatCb = onStatus
    dFallCb = onFallback
    dCmdCb = onCmd
    dPhraseCb = onPhrase
    oneShotActive = true
    listenDictStep(ep, ctx, onPart, onDone, onStatus, onFallback, onCmd, onPhrase)
}

fun keepAw(ctx: Context, on: Boolean) = runCatching { if (on) { if (wakeLockRef == null) wakeLockRef = (ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PL:JarvisMicLock").apply { setReferenceCounted(false) }; if (wakeLockRef?.isHeld == false) wakeLockRef?.acquire(86400000L); runCatching { val itn = Intent(ctx.applicationContext, JarvisMicService::class.java); if (Build.VERSION.SDK_INT >= 26) ctx.applicationContext.startForegroundService(itn) else ctx.applicationContext.startService(itn) } } else { if (!JarvisRuntime.on && !vOn && !ReaderTtsRuntime.on && !SupertonicTtsEngine.playing && !briefingSpeakingNow) { if (wakeLockRef?.isHeld == true) wakeLockRef?.release(); runCatching { ctx.applicationContext.stopService(Intent(ctx.applicationContext, JarvisMicService::class.java)) } } }; Unit }
object JarvisRuntime {
 val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
 var on by mutableStateOf(false)
 var busy by mutableStateOf(false)
 var input by mutableStateOf("")
 var status by mutableStateOf<String?>(null)
 var hist = listOf<ChatMsg>()
 var lastBeatMs = 0L
 var navCb: (Int) -> Unit = {}
 var memoCb: (String) -> String = { "" }
 var notesCb: () -> List<NoteItem> = { emptyList() }
 var booksCb: () -> List<BookItem> = { emptyList() }
 var fallbackCb: () -> Unit = {}
 var watchJob: kotlinx.coroutines.Job? = null
 fun bindCallbacks(ctx: Context, onNav: (Int) -> Unit, onMemo: (String) -> String, getBooks: () -> List<BookItem> = booksCb, getN: () -> List<NoteItem>) {
  appCtxRef = ctx.applicationContext; navCb = onNav; memoCb = onMemo; booksCb = getBooks; notesCb = getN
 }
 fun start(ctx: Context, onFall: () -> Unit = {}) {
  fallbackCb = onFall
  if (vOn) vStop(ctx)
  loadAiCfg(ctx)
  on = true; busy = false; JarvisController.sleeping = false
  if (wakeOnly) wakeFollowUpUntil = System.currentTimeMillis() + 18000L
  keepAw(ctx, true)
  status = if (wakeOnly) "👂🎙️ 자비스 ON (18초 즉시 경청 · 이후 [${wakeWord}] 호출 대기)" else "🤖🎙️ 자비스 상시 음성 비서 ON! 명령이나 질문을 편하게 말씀하세요."
  listenNow(ctx)
  watchJob?.cancel()
  watchJob = scope.launch {
   while (on) {
    delay(3200L)
    if (wakeLockRef?.isHeld != true) keepAw(ctx, true)
    if (on && !busy && !isAiBusy && !streamActive && !isTtsBusyNow() && (!oneShotActive || System.currentTimeMillis() - lastBeatMs > 7500L)) {
     listenNow(mainActRef ?: appCtxRef ?: ctx)
    }
   }
  }
 }
 fun stop(ctx: Context) {
  on = false; busy = false; isAiBusy = false; streamActive = false; wakeFollowUpUntil = 0L
  watchJob?.cancel(); stopOneShotDictation(); stopOneShotTts(); keepAw(ctx, false)
  status = "⏹️ 자비스 상시 음성 비서 OFF"
 }
 fun onActivityResume() {
  val c = mainActRef ?: appCtxRef ?: return
  if (on && !busy && !isAiBusy && !isTtsBusyNow() && (!oneShotActive || System.currentTimeMillis() - lastBeatMs > 3000L)) uiP(250L) { if (on && !busy) listenNow(c) }
 }
 fun listenNow(ctx: Context) {
  if (!on || busy) return
  lastBeatMs = System.currentTimeMillis()
  startOneShotDictation(ctx, "", { pt -> if (!JarvisController.sleeping) input = pt }, { _ -> }, { st -> if (st.startsWith("🗣️") || status == null || status!!.startsWith("🤖🎙️") || status!!.startsWith("👂") || status!!.startsWith("🌙")) status = st }, { if (on && appInFg) { on = false; fallbackCb() } }, { _, _ -> }, onPhrase = { ph -> processUtterance(ctx, ph, true) })
 }
 fun waitTtsAndResume(ctx: Context, myGen: Long = activeGenId) {
  scope.launch {
   try {
    delay(350L)
    var gd = 0
    while (on && gd < 600 && myGen == activeGenId) {
     if (!isTtsBusyNow() && (System.currentTimeMillis() - ttsLastEnqueueMs) >= 650L) break
     delay(200L); gd++
    }
   } finally {
    busy = false; isAiBusy = false; streamActive = false
   }
   if (on && myGen == activeGenId) {
    if (wakeOnly && !JarvisController.sleeping) wakeFollowUpUntil = System.currentTimeMillis() + 14000L
    val nl = 10.toChar().toString()
    val tail = if (JarvisController.sleeping) "${nl}🌙 [대기모드] [${wakeWord}]라고 불러주세요" else if (wakeOnly) "${nl}👂 [연속대화 14초 열림 · 이후 [${wakeWord}] 호출 대기]" else "${nl}🤖🎙️ [경청중] 다음 명령을 말씀하세요..."
    status = (status ?: "").substringBefore("${nl}🤖🎙️").substringBefore("${nl}👂").substringBefore("${nl}🌙") + tail
    uiP(300L) { if (on && !busy) listenNow(mainActRef ?: appCtxRef ?: ctx) }
   }
  }
 }
 fun processUtterance(ctx: Context, raw: String, fromVoice: Boolean = true) {
  val tx = raw.trim()
  if (tx.isEmpty()) { if (on && !busy) uiP(200L) { listenNow(ctx) }; return }
  if (fromVoice && lastEcho.isNotEmpty() && (tx == lastEcho || (tx.length >= 4 && lastEcho.contains(tx)))) { if (on && !busy) uiP(260L) { listenNow(ctx) }; return }
  loadAiCfg(ctx)
  val callNm = wakeWord.trim().ifBlank { "자비스" }
  val (handled, rep) = JarvisController.handle(mainActRef ?: appCtxRef ?: ctx, tx, callNm, navCb, memoCb, notesCb, fromVoice)
  if (handled) {
   if (rep.isBlank()) { if (on && !busy) uiP(180L) { listenNow(ctx) }; return }
   stopOneShotDictation()
   if (rep == "STOP_JARVIS") { speakOneShot(ctx, "자비스 음성 비서를 종료합니다."); stop(ctx); return }
   busy = true; input = ""; status = rep
   lastEcho = rep.replace(Regex("[^가-힣a-zA-Z0-9 ]"), "").trim().take(24)
   if (on || ttsOn) speakOneShot(ctx, rep)
   if (on) waitTtsAndResume(ctx) else busy = false
   return
  }
  val qClean = if (fromVoice) matchWakePhrase(tx, callNm).second.ifBlank { tx } else tx
  stopOneShotDictation()
  busy = true; isAiBusy = true; streamActive = true; input = ""
  ttsSpkIdx = 0; ttsFirstChunk = true; ttsPending.clear()
  val g = ++activeGenId
  status = "🤖 [$qClean] 답변 생성 중... ▍"
  val prevH = hist
  scope.launch {
   val cc = loadAiCfg(ctx)
   val rag = if (cc.ragOn) withContext(Dispatchers.IO) { buildRagContextSmart(qClean, cc, notesCb(), booksCb(), SmartMemoClassifier.loadMemoCats(ctx)) } else ""
   val ans = withContext(Dispatchers.IO) {
    callLlmHttp(cc.sysPrompt, rag, prevH, qClean, cc.apiUrl, cc.apiModel, cc.apiKey, { lv ->
     if (g == activeGenId && lv.isNotBlank()) uiP(0L) { status = "🤖 $lv ▍"; if (ttsOn) feedStreamTts(ctx, lv, false) }
    }, g, cc.temp, cc.ctxSize, cc.maxTok.coerceAtLeast(512), cc.cpuMode, cc.histTurns)
   }
   if (g == activeGenId) {
    val nl = 10.toChar().toString()
    val fa = ans ?: "⚠️ 로컬 GGUF 모델을 먼저 [⚙️ 설정]에서 등록해 주세요."
    hist = (prevH + ChatMsg(nextId(), true, qClean, cc.ragOn) + ChatMsg(nextId(), false, fa, cc.ragOn)).takeLast(20)
    status = "🤖 $fa"
    lastEcho = fa.substringBefore("${nl}${nl}⏱️").take(24)
    if (ttsOn) feedStreamTts(ctx, fa, true)
    if (on) waitTtsAndResume(ctx, g) else { busy = false; isAiBusy = false; streamActive = false }
   }
  }
 }
}
fun normalizeTextForNaturalTts(raw: String): String {
    val clean0 = raw
        .substringBefore("\n\n⏱️")
        .substringBefore("\n⏱️")
        .substringBefore("⏱️ ")
        .replace("*", "")
        .replace("`", "")
        .replace("▍", "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    if (clean0.isBlank()) return ""
    val rawLines = clean0.lines()
    val sb = StringBuilder(clean0.length)
    var prevEndedSentence = true
    var hadBlankBreak = false
    for (ln0 in rawLines) {
        var ln = ln0.trim()
        if (ln.isEmpty()) {
            hadBlankBreak = true
            continue
        }
        if (ln.all { it == '─' || it == '-' || it == '═' || it == '_' }) {
            hadBlankBreak = true
            continue
        }
        if (ln.startsWith("— PDF ") && ln.endsWith("—")) {
            hadBlankBreak = true
            continue
        }
        val wasHeading = ln.startsWith("#")
        if (wasHeading) {
            ln = ln.trimStart('#').trim()
            if (ln.isEmpty()) continue
        }
        val isListOrBracket = wasHeading ||
            ln.startsWith("•") || ln.startsWith("- ") || ln.startsWith("☐") || ln.startsWith("☑") ||
            ln.startsWith("【") || Regex("^\\d+[.)]\\s+.*").matches(ln)
        if (sb.isNotEmpty()) {
            if (hadBlankBreak || prevEndedSentence || isListOrBracket) {
                sb.append('\n')
            } else {
                sb.append(' ')
            }
        }
        sb.append(ln)
        val lastMeaningful = ln.lastOrNull { it != '"' && it != '\'' && it != '”' && it != '’' && it != ')' && it != ']' }
        prevEndedSentence = wasHeading || (lastMeaningful != null && (
            lastMeaningful == '.' || lastMeaningful == '!' || lastMeaningful == '?' ||
                lastMeaningful == '。' || lastMeaningful == '…' || lastMeaningful == ':' ||
                lastMeaningful == ';' || lastMeaningful == '】' || lastMeaningful == '』' || lastMeaningful == '」'
            ))
        hadBlankBreak = false
    }
    return sb.toString().replace(Regex("[ \\t]{2,}"), " ").trim()
}

fun splitLongKoreanClause(sent: String, maxLen: Int): List<String> {
    if (sent.length <= maxLen) return listOf(sent)
    val out = mutableListOf<String>()
    var rem = sent.trim()
    val connectors = listOf("는데, ", "지만, ", "므로, ", "는데 ", "지만 ", "므로 ", "아서 ", "어서 ", "니까 ", "면서 ", "으며 ", "거나 ", "고 ")
    while (rem.isNotEmpty()) {
        if (rem.length <= maxLen) {
            out.add(rem)
            break
        }
        val lim = minOf(rem.length, maxLen)
        val minCut = (maxLen / 3).coerceAtLeast(24)
        var cut = -1
        for (i in lim - 1 downTo minCut) {
            val c = rem[i]
            if (c == ',' || c == ';' || c == ':' || c == '—') {
                cut = i + 1
                break
            }
        }
        if (cut == -1) {
            for (i in lim - 1 downTo minCut) {
                val sub = rem.substring(minCut.coerceAtMost(i), (i + 4).coerceAtMost(rem.length))
                val hit = connectors.firstOrNull { sub.endsWith(it) }
                if (hit != null) {
                    cut = (minCut.coerceAtMost(i) + sub.length).coerceAtMost(lim)
                    break
                }
            }
        }
        if (cut == -1) {
            for (i in lim - 1 downTo minCut) {
                if (rem[i] == ' ') {
                    cut = i + 1
                    break
                }
            }
        }
        if (cut <= 0) cut = lim
        val piece = rem.substring(0, cut).trim()
        if (piece.isNotEmpty()) out.add(piece)
        rem = rem.substring(cut).trimStart()
    }
    return out
}

fun splitSentenceAtoms(norm: String): List<String> {
    if (norm.isBlank()) return emptyList()
    val atoms = mutableListOf<String>()
    val buf = StringBuilder()
    var i = 0
    val n = norm.length
    while (i < n) {
        val c = norm[i]
        if (c == '\n') {
            val s = buf.toString().trim()
            if (s.isNotEmpty()) atoms.add(s)
            buf.setLength(0)
            i++
            continue
        }
        buf.append(c)
        val isTerm = c == '.' || c == '!' || c == '?' || c == '。' || c == '…'
        if (isTerm) {
            val isDecimal = c == '.' && i > 0 && i + 1 < n && norm[i - 1].isDigit() && norm[i + 1].isDigit()
            val curTrim = buf.toString().trimStart()
            val isLeadingNumberDot = c == '.' && curTrim.length <= 4 && curTrim.dropLast(1).all { it.isDigit() }
            val nextOk = i + 1 >= n || norm[i + 1].isWhitespace() || norm[i + 1] in charArrayOf('"', '\'', '”', '’', ')', ']', '』', '」')
            if (!isDecimal && !isLeadingNumberDot && nextOk) {
                while (i + 1 < n && norm[i + 1] in charArrayOf('"', '\'', '”', '’', ')', ']', '』', '」')) {
                    i++
                    buf.append(norm[i])
                }
                val s = buf.toString().trim()
                if (s.isNotEmpty()) atoms.add(s)
                buf.setLength(0)
            }
        }
        i++
    }
    val tail = buf.toString().trim()
    if (tail.isNotEmpty()) atoms.add(tail)
    return atoms
}

fun splitTtsChunks(raw: String, maxLen: Int = 180): List<String> {
    val norm = normalizeTextForNaturalTts(raw)
    if (norm.isEmpty()) return emptyList()
    val atoms = splitSentenceAtoms(norm).flatMap { splitLongKoreanClause(it, maxLen) }
    return atoms.filter { it.isNotBlank() }
}

fun splitSysTtsSentences(raw: String, maxLen: Int = 440): List<String> {
    val norm = normalizeTextForNaturalTts(raw)
    if (norm.isEmpty()) return emptyList()
    val atoms = splitSentenceAtoms(norm).flatMap { splitLongKoreanClause(it, maxLen) }
    if (atoms.isEmpty()) return emptyList()
    val merged = mutableListOf<String>()
    val cur = StringBuilder()
    for (a in atoms) {
        if (cur.isEmpty()) {
            cur.append(a)
        } else if (cur.length + 1 + a.length <= 240) {
            cur.append(' ').append(a)
        } else {
            merged.add(cur.toString())
            cur.setLength(0)
            cur.append(a)
        }
    }
    if (cur.isNotEmpty()) merged.add(cur.toString())
    return merged
}

fun attachTtsListener(eng: TextToSpeech) {
    eng.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(id: String?) { ttsLastActiveMs = System.currentTimeMillis() }
        override fun onDone(id: String?) { ttsLastActiveMs = System.currentTimeMillis(); ttsActiveCount = (ttsActiveCount - 1).coerceAtLeast(0) }
        @Deprecated("Deprecated in Java") override fun onError(id: String?) { ttsActiveCount = (ttsActiveCount - 1).coerceAtLeast(0) }
        override fun onError(id: String?, code: Int) { ttsActiveCount = (ttsActiveCount - 1).coerceAtLeast(0) }
    })
}

fun ensureTtsAndSpeak(ctx: Context, text: String, flush: Boolean) {
    SupertonicTtsEngine.init(ctx)
    if (SupertonicTtsEngine.canSpeakLocal()) {
        val localChunks = splitTtsChunks(text, 180)
        if (localChunks.isEmpty()) return
        if (flush) runCatching { ttsEngine?.stop() }
        SupertonicTtsEngine.enqueueSpeak(ctx, localChunks, flush) { rem -> ensureSysTtsSpeak(ctx, rem, false) }
        return
    }
    val sysChunks = splitSysTtsSentences(text, 440)
    if (sysChunks.isEmpty()) return
    ensureSysTtsSpeak(ctx, sysChunks, flush)
}

fun ensureSysTtsSpeak(ctx: Context, chunks: List<String>, flush: Boolean) {
    if (chunks.isEmpty()) return
    ttsLastEnqueueMs = System.currentTimeMillis()
    ttsLastActiveMs = System.currentTimeMillis()
    val rate = SupertonicTtsEngine.speed.coerceIn(0.6f, 1.8f)
    uiP(0L) {
        if (ttsEngine == null) {
            if (flush) { ttsPending.clear(); ttsActiveCount = 0 }
            chunks.forEachIndexed { idx, ch -> ttsPending.add(ch to (idx == 0 && flush)) }
            ttsEngine = TextToSpeech(ctx.applicationContext, TextToSpeech.OnInitListener { st ->
                if (st == TextToSpeech.SUCCESS) {
                    ttsEngine?.let { eng ->
                        eng.language = Locale.KOREAN
                        runCatching {
                            eng.setAudioAttributes(
                                android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                        }
                        runCatching { eng.setSpeechRate(SupertonicTtsEngine.speed.coerceIn(0.6f, 1.8f)) }
                        attachTtsListener(eng)
                        ttsReady = true
                        val items = ttsPending.toList()
                        ttsPending.clear()
                        items.forEach { (ck, fl) ->
                            if (fl) ttsActiveCount = 0
                            ttsActiveCount += 1
                            ttsLastEnqueueMs = System.currentTimeMillis()
                            ttsLastActiveMs = System.currentTimeMillis()
                            val prm = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC) }
                            eng.speak(ck, if (fl) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, prm, "U_" + System.nanoTime())
                        }
                    }
                } else {
                    ttsPending.clear()
                    ttsActiveCount = 0
                }
            })
        } else if (!ttsReady) {
            if (flush) { ttsPending.clear(); ttsActiveCount = 0 }
            chunks.forEachIndexed { idx, ch -> ttsPending.add(ch to (idx == 0 && flush)) }
        } else {
            ttsEngine?.let { eng ->
                runCatching {
                    eng.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                runCatching { eng.setSpeechRate(rate) }
                chunks.forEachIndexed { idx, ck ->
                    val fl = idx == 0 && flush
                    if (fl) ttsActiveCount = 0
                    ttsActiveCount += 1
                    ttsLastEnqueueMs = System.currentTimeMillis()
                    ttsLastActiveMs = System.currentTimeMillis()
                    val prm = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC) }
                    eng.speak(ck, if (fl) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, prm, "U_" + System.nanoTime())
                }
            }
        }
    }
}
fun speakOneShot(ctx: Context, text: String) { ensureTtsAndSpeak(ctx, text, true) }
fun speakBriefingWithStream(ctx: Context, text: String, forceAlarmStream: Boolean) {
    if (!forceAlarmStream) {
        ensureTtsAndSpeak(ctx, text, true)
        return
    }
    val chunks = splitSysTtsSentences(text, 440)
    if (chunks.isEmpty()) return
    ttsLastEnqueueMs = System.currentTimeMillis()
    ttsLastActiveMs = System.currentTimeMillis()
    val rate = SupertonicTtsEngine.speed.coerceIn(0.6f, 1.8f)
    uiP(0L) {
        fun speakOnAlarm(eng: TextToSpeech, list: List<String>) {
            runCatching {
                eng.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
            runCatching { eng.setSpeechRate(rate) }
            ttsActiveCount = 0
            list.forEachIndexed { idx, ck ->
                ttsActiveCount += 1
                ttsLastEnqueueMs = System.currentTimeMillis()
                ttsLastActiveMs = System.currentTimeMillis()
                val prm = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_ALARM) }
                eng.speak(ck, if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, prm, "ALM_" + System.nanoTime())
            }
        }
        if (ttsEngine == null || !ttsReady) {
            ttsEngine = TextToSpeech(ctx.applicationContext, TextToSpeech.OnInitListener { st ->
                if (st == TextToSpeech.SUCCESS) {
                    ttsEngine?.let { eng ->
                        eng.language = Locale.KOREAN
                        attachTtsListener(eng)
                        ttsReady = true
                        speakOnAlarm(eng, chunks)
                    }
                }
            })
        } else {
            ttsEngine?.let { eng -> speakOnAlarm(eng, chunks) }
        }
    }
}
fun stopOneShotTts() { SupertonicTtsEngine.stop(); uiP(0L) { ttsPending.clear(); ttsActiveCount = 0; runCatching { ttsEngine?.stop() } } }
fun flushV(ctx: Context) { val msg = vBuf.trim(); if (msg.isEmpty()) return; if (isAiBusy || streamActive || isTtsBusyNow()) { schedV(ctx, 900L); return }; vBuf = ""; vSpkNow = false; vSeq++; lastSttBeatMs = System.currentTimeMillis(); uiP(0L) { sttEpoch += 1; val old = sttEngine; sttEngine = null; runCatching { old?.cancel(); old?.destroy() } }; isAiBusy = true; vSt = "🧠 " + msg.take(12); vCb(msg) }
fun schedV(ctx: Context, ex: Long = 0L) { val tk = ++vSeq; val d = (vWaitSec * 1000L + ex).coerceAtLeast(1100L); uiP(d) { if (tk == vSeq && vOn) { if (isAiBusy || streamActive || isTtsBusyNow()) schedV(ctx, 900L) else if (vSpkNow) schedV(ctx, 1100L) else if (vBuf.isNotBlank()) flushV(ctx) } } }
fun sttGo(ctx: Context, recreate: Boolean = false): Unit = uiP(0L) { if (!vOn || isAiBusy || streamActive || isTtsBusyNow()) return@uiP; if (JarvisRuntime.on) JarvisRuntime.stop(ctx); stopOneShotDictation(); if (System.currentTimeMillis() < pauseUntil) { uiP(500L) { sttGo(ctx, false) }; return@uiP }; runCatching { lastSttBeatMs = System.currentTimeMillis(); val ep = ++sttEpoch; if (recreate && sttEngine != null) { val old = sttEngine; sttEngine = null; runCatching { old?.cancel(); old?.destroy() } }; val appC = appCtxRef ?: ctx.applicationContext; val tCtx: Context = if (appInFg && mainActRef != null) mainActRef!! else appC; if (sttEngine == null) sttEngine = runCatching { SpeechRecognizer.createSpeechRecognizer(tCtx) }.getOrElse { SpeechRecognizer.createSpeechRecognizer(appC) }; sttEngine?.setRecognitionListener(object : RecognitionListener { override fun onReadyForSpeech(p: Bundle?) { if (ep != sttEpoch) return; lastSttBeatMs = System.currentTimeMillis(); if (!isAiBusy && !streamActive) vSt = if (wakeOnly && System.currentTimeMillis() >= wakeFollowUpUntil) "👂" else if (vBuf.isBlank()) "듣는중" else "이어듣기" }; override fun onBeginningOfSpeech() { if (ep != sttEpoch) return; lastSttBeatMs = System.currentTimeMillis(); vSpkNow = true; vSeq++ }; override fun onRmsChanged(v: Float) {}; override fun onBufferReceived(b: ByteArray?) {}; override fun onEndOfSpeech() { if (ep != sttEpoch) return; lastSttBeatMs = System.currentTimeMillis(); vSpkNow = false }; override fun onPartialResults(b: Bundle?) { if (ep != sttEpoch) return; val pt = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""; if (pt.isNotEmpty() && !isAiBusy && !streamActive) { lastSttBeatMs = System.currentTimeMillis(); vSpkNow = true; vSeq++; sharedAiChatIn = if (vBuf.isBlank()) pt else vBuf + " " + pt; vSt = pt.take(8) } }; override fun onEvent(t: Int, p: Bundle?) {}; override fun onError(e: Int) { if (ep != sttEpoch) return; val had = vSpkNow; vSpkNow = false; lastSttBeatMs = System.currentTimeMillis(); val old = sttEngine; sttEngine = null; runCatching { old?.cancel(); old?.destroy() }; if (vOn && !isAiBusy && !streamActive && !isTtsBusyNow()) { if (vBuf.isNotBlank()) schedV(ctx, if (had) -600L else 0L); uiP(if (!appInFg) 800L else if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 750L else 380L) { sttGo(ctx, true) } } }; override fun onResults(b: Bundle?) { if (ep != sttEpoch) return; vSpkNow = false; lastSttBeatMs = System.currentTimeMillis(); if (!vOn || isAiBusy || streamActive || isTtsBusyNow()) return; val raw = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""; if (raw.isEmpty()) { if (vOn) uiP(300L) { sttGo(ctx, true) }; return }; var q = raw.replace(Regex("^[.,!? ]+"), "").trim(); if (lastEcho.isNotEmpty() && (q == lastEcho || (q.length >= 4 && lastEcho.contains(q)))) { uiP(400L) { sttGo(ctx, true) }; return }; if (wakeOnly && vBuf.isBlank() && System.currentTimeMillis() >= wakeFollowUpUntil) { val (wk, rem) = matchWakePhrase(q, wakeWord); if (!wk) { uiP(260L) { if (vOn) sttGo(ctx, true) }; return }; wakeFollowUpUntil = System.currentTimeMillis() + 18000L; q = rem; if (q.isEmpty()) { vSt = "🗣️말씀하세요"; speakOneShot(ctx, "네, 듣고 있습니다."); uiP(1200L) { if (vOn) sttGo(ctx, true) }; return } }; if (vBuf.isBlank() && q in listOf("잠깐", "잠시만", "기다려", "대기", "스톱")) { pauseUntil = System.currentTimeMillis() + 5000L; vSt = "대기"; uiP(1500L) { if (vOn) sttGo(ctx, true) }; return }; val cleanQ = q.replace(Regex("[음어아그쉿 ]+"), ""); if (cleanQ.length <= 1 && vBuf.isBlank()) { uiP(380L) { if (vOn) sttGo(ctx, true) }; return }; if (cleanQ.isNotEmpty()) { vBuf = if (vBuf.isBlank()) q else vBuf + " " + q; sharedAiChatIn = vBuf }; val inc = listOf("는데", "거든", "니까", "해서", "하고", "그리고", "그래서", "근데").any { q.endsWith(it) }; vSt = "이어듣기"; schedV(ctx, if (inc) 1400L else 0L); uiP(240L) { if (vOn && !isAiBusy && !streamActive) sttGo(ctx, true) } } }); sttEngine?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR").putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, tCtx.packageName).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true).putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3200L).putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2600L)) } }
fun pushTtsChunk(ctx: Context, piece: String) { val cl = piece.replace("*", "").replace("`", "").replace("▍", "").trim(); if (cl.isEmpty()) return; lastEcho = cl.take(28).trim(); ttsLastEnqueueMs = System.currentTimeMillis(); ttsLastActiveMs = System.currentTimeMillis(); uiP(0L) { runCatching { sttEngine?.cancel() }; isAiBusy = true; vSt = "답변중"; val fl = ttsFirstChunk; ttsFirstChunk = false; ensureTtsAndSpeak(ctx, cl, fl) } }
fun feedStreamTts(ctx: Context, cur: String, done: Boolean) {
    if ((!vOn && !JarvisRuntime.on && !ttsOn) || !ttsOn) {
        if (done) streamActive = false
        return
    }
    if (cur.startsWith("💭") && !cur.contains("────────────") && !done) return
    val noStat = cur.substringBefore("\n\n⏱️").substringBefore("\n⏱️").substringBefore("⏱️ ")
    val rawAns = if (noStat.contains("────────────")) noStat.substringAfter("────────────") else if (noStat.startsWith("💭")) "" else noStat
    val ans = rawAns.replace("▍", "").lines().filterNot { it.trim().startsWith("#") }.joinToString(" ").replace(Regex("\\s+"), " ").trimStart()
    if (ans.length <= ttsSpkIdx) {
        if (done) streamActive = false
        return
    }
    var rem = ans.substring(ttsSpkIdx)
    if (done) {
        ttsSpkIdx = ans.length
        streamActive = false
        if (rem.isNotBlank()) pushTtsChunk(ctx, rem)
        return
    }
    while (rem.isNotEmpty()) {
        var cut = -1
        for (i in 6 until rem.length) {
            val c = rem[i]
            val isDecimal = c == '.' && i > 0 && i + 1 < rem.length && rem[i - 1].isDigit() && rem[i + 1].isDigit()
            if (!isDecimal && (c == '.' || c == '!' || c == '?' || c == '。') && (i + 1 < rem.length && rem[i + 1] == ' ')) {
                cut = i + 1
                break
            }
        }
        if (cut == -1 && rem.length >= 160) {
            val commaIdx = rem.lastIndexOf(", ").takeIf { it >= 32 } ?: -1
            if (commaIdx > 0) {
                cut = commaIdx + 2
            } else {
                val conn = listOf("는데 ", "지만 ", "면서 ", "니까 ", "므로 ").map { it to rem.lastIndexOf(it) }.filter { it.second >= 36 }.maxByOrNull { it.second }
                if (conn != null) cut = conn.second + conn.first.length
            }
        }
        if (cut > 0) {
            val sent = rem.substring(0, cut).trim()
            ttsSpkIdx += cut
            if (sent.isNotEmpty()) pushTtsChunk(ctx, sent)
            rem = ans.substring(ttsSpkIdx)
        } else break
    }
}
fun vStop(ctx: Context) { vOn = false; blkOn = false; isAiBusy = false; streamActive = false; ttsPending.clear(); ttsActiveCount = 0; vBuf = ""; vSpkNow = false; vSeq++; sttEpoch += 1; vSt = "중지"; if (ProLlamaJni.ready) runCatching { ProLlamaJni.abortGeneration() }; uiP(0L) { val old = sttEngine; sttEngine = null; runCatching { old?.cancel(); old?.destroy(); ttsEngine?.stop() } }; keepAw(ctx, false) }
var sharedAiChatIn by mutableStateOf("")
fun copyMemoToAi(txt: String) {
val nl = 10.toChar().toString()
val blk = txt.trim() + nl + "────────────" + nl
sharedAiChatIn = if (sharedAiChatIn.isBlank()) blk else sharedAiChatIn.trimEnd() + nl + blk
}
fun copyToClip(ctx: android.content.Context, txt: String) = runCatching {
(ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("memo", txt))
}
fun makeHashtags(raw: String, cat: String): String {
val nl = 10.toChar().toString()
val ls = raw.trim().lines().dropLastWhile { ln -> ln.trim().let { it.isNotEmpty() && it.split(" ").filter { w -> w.isNotBlank() }.all { w -> w.startsWith("#") } } }
val body = ls.joinToString(nl).trim()
val stop = setOf("오늘","내일","지금","그냥","정말","너무","그리고","하지만","그래서","위해","대한","통해","관련","하기","있는","없는","입니다","합니다","완료","정리","메모","할일","일상","항목")
val suf = listOf("입니다","합니다","에서","으로","까지","부터","하고","하며","이라","이다","해서","해야","했던","하는","은","는","이","가","을","를","의","에","로","과","와","도","만")
val wds = body.map { if (it.isLetterOrDigit()) it else 32.toChar() }.joinToString("").split(" ").map { w -> var r = w.trim(); suf.firstOrNull { r.length > it.length + 1 && r.endsWith(it) }?.let { r = r.dropLast(it.length) }; r }.filter { it.length in 2..10 && !it.all { c -> c.isDigit() } && it !in stop }.distinct().take(5)
val tags = (listOf(cat) + wds).map { "#" + it.replace("#", "").trim() }.filter { it.length > 1 }.distinct().take(6).joinToString(" ")
return if (body.isEmpty()) tags else body + nl + nl + tags
}
