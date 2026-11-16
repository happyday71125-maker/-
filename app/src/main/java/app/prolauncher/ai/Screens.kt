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

@Composable
fun NotesRagView(
notes: List<NoteItem>, books: List<BookItem>, cfg: LauncherConfig,
ai: LocalAiNoteEngine, quoted: String?, onClearQuote: () -> Unit,
onSaveNotes: (List<NoteItem>) -> Unit, isAiPage: Boolean = false
) {
val ctx = LocalContext.current
val repo = remember { LauncherRepository(ctx) }
val scope = rememberCoroutineScope()
var aiCfg by remember { mutableStateOf(loadAiCfg(ctx)) }
val ui = rememberUi(aiCfg.uiScale)
var cats by remember { mutableStateOf(SMC.loadMemoCats(ctx)) }
var bCfg by remember { mutableStateOf(SMC.loadBtnCfg(ctx)) }
var cfgOpen by remember { mutableStateOf(false) }
var aiPromptCfgOpen by remember { mutableStateOf(false) }
var newCat by remember { mutableStateOf("") }
var renTarget by remember { mutableStateOf<String?>(null) }
var renVal by remember { mutableStateOf("") }
var mode by remember(isAiPage) { mutableStateOf(if (isAiPage) "AI" else "MEMO") }
var inputTfv by remember { mutableStateOf(TextFieldValue("")) }
val input = inputTfv.text
fun setInputProg(s: String) { inputTfv = TextFieldValue(s, TextRange(s.length)) }
var memoCfgTab by remember { mutableStateOf(0) }
var mCat by remember { mutableStateOf("자동") }
var newImp by remember { mutableStateOf(false) }

var pickMemoOpen by remember { mutableStateOf(false) }
var briefHour by remember { mutableStateOf(ScheduleBriefingManager.loadMorningHour(ctx)) }
var isThinking by remember { mutableStateOf(false) }
var chatMsgs by remember {
mutableStateOf(listOf(ChatMsg(1L, false, "안녕하세요! LLM AI 챗봇입니다.\n• [RAG OFF]: 설정된 시스템 프롬프트에 따라 모든 주제에 답하는 일반 AI 챗봇으로 작동합니다.\n• [전체 메모 RAG ON]: 저장된 전체 메모(${notes.size}건)를 읽고 분석해 답변합니다.", false)))
}
var aiMsg by remember { mutableStateOf("🌊 C++ 네이티브 실시간 토큰 스트리밍 · 임베딩젬마2 GGUF") }
var sQuery by remember { mutableStateOf("") }
var sOut by remember { mutableStateOf<String?>(null) }
var selTab by remember { mutableStateOf("전체") }
var editId by remember { mutableStateOf<Long?>(null) }
var editTfv by remember { mutableStateOf(TextFieldValue("")) }
val editTxt = editTfv.text
fun setEditProg(s: String) { editTfv = TextFieldValue(s, TextRange(s.length)) }
var editCat by remember { mutableStateOf("일상") }
var editImp by remember { mutableStateOf(false) }

LaunchedEffect(quoted) {
if (!quoted.isNullOrBlank()) {
mode = "MEMO"; if (cats.contains("독서")) mCat = "독서"
setInputProg(if (input.isBlank()) quoted else "$input\n\n$quoted"); onClearQuote()
}
}
val bkExp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> if (u != null) runCatching { val j = ctx.getSharedPreferences("pl_ai_v2_prefs", 0).getString("notes_json", "[]") ?: "[]"; ctx.contentResolver.openOutputStream(u)?.use { it.write(j.toByteArray()) }; aiMsg = "💾 메모 파일 백업 완료 (" + notes.size + "건)" } }
val bkImp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) runCatching { val j = ctx.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: ""; if (j.trim().startsWith("[")) { val old = notes; ctx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putString("notes_json", j).commit(); val merged = (repo.loadNotes() + old).distinctBy { it.id }; onSaveNotes(merged); aiMsg = "♻️ 메모 복원 완료 (총 " + merged.size + "건)" } } }
val tabs = remember(cats) { listOf("전체", "★", "☑") + cats }
val list = remember(notes, selTab, cats) {
val b = when (selTab) {
"전체" -> notes
"★" -> notes.filter { SMC.isImportant(it) }
"☑" -> notes.filter { it.content.contains("☐") || it.content.contains("☑") || SMC.categoryOf(it, cats) == "할일" }
else -> notes.filter { SMC.categoryOf(it, cats) == selTab }
}
b.sortedWith(compareByDescending<NoteItem> { SMC.isImportant(it) }.thenByDescending { it.id })
}
fun ins(c: String, t: String) = if (c.isEmpty() || c.endsWith("\n")) c + t else "$c\n$t"
fun nowStr() = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
// Full TextFieldValue state preserves Korean Hangul IME composition range without jamo separation
fun cleanMemoText(raw: String): String { val nl = 10.toChar().toString(); return raw.lines().mapNotNull { ln -> val tr = ln.trim(); if (tr == "☐" || tr == "☑" || tr == "•") null else if (tr.startsWith("☐") && !tr.startsWith("☐ ")) ln.replaceFirst("☐", "☐ ") else if (tr.startsWith("☑") && !tr.startsWith("☑ ")) ln.replaceFirst("☑", "☑ ") else ln }.joinToString(nl).trim() }
fun applySmartCheck(raw: String): String { val nl = 10.toChar().toString(); if (raw.isBlank()) return "☐ "; if (raw.endsWith(nl)) return raw + "☐ "; val ls = raw.lines().toMutableList(); val lastTr = ls.last().trim(); if (lastTr == "☐" || lastTr == "☑") { ls[ls.lastIndex] = ""; return ls.joinToString(nl).trimEnd() }; if (lastTr.startsWith("☐") || lastTr.startsWith("☑")) return raw + nl + "☐ "; var conv = false; for (i in ls.indices) { val t = ls[i].trimStart(); if (t.isNotEmpty() && !t.startsWith("☐") && !t.startsWith("☑") && !t.startsWith("#") && !t.startsWith("──")) { ls[i] = "☐ " + t.removePrefix("• ").removePrefix("•").trimStart(); conv = true } }; return if (conv) ls.joinToString(nl) else raw + nl + "☐ " }
fun applySmartBullet(raw: String): String { val nl = 10.toChar().toString(); if (raw.isBlank()) return "• "; if (raw.endsWith(nl)) return raw + "• "; val ls = raw.lines().toMutableList(); val lastTr = ls.last().trim(); if (lastTr == "•") { ls[ls.lastIndex] = ""; return ls.joinToString(nl).trimEnd() }; if (lastTr.startsWith("•")) return raw + nl + "• "; var conv = false; for (i in ls.indices) { val t = ls[i].trimStart(); if (t.isNotEmpty() && !t.startsWith("•") && !t.startsWith("☐") && !t.startsWith("☑") && !t.startsWith("#") && !t.startsWith("──")) { ls[i] = "• " + t; conv = true } }; return if (conv) ls.joinToString(nl) else raw + nl + "• " }
fun handleMemoEnterKey(raw: String): String { val nl = 10.toChar().toString(); val lastTr = raw.lines().lastOrNull()?.trimStart() ?: ""; return when { lastTr == "☐" || lastTr == "☑" || lastTr == "•" -> raw.lines().dropLast(1).joinToString(nl) + nl; lastTr.startsWith("☐") || lastTr.startsWith("☑") -> raw + nl + "☐ "; lastTr.startsWith("•") -> raw + nl + "• "; else -> raw + nl } }
fun onMemoValueChange(oldTfv: TextFieldValue, tfv: TextFieldValue): TextFieldValue { if (tfv.composition != null) return tfv; val oldTx = oldTfv.text; val newTx = tfv.text; val cur = tfv.selection.end.coerceIn(0, newTx.length); val nl = 10.toChar(); if (cur > 0 && newTx[cur - 1] == nl && newTx.count { it == nl } == oldTx.count { it == nl } + 1) { val ls = newTx.lastIndexOf(nl, (cur - 2).coerceAtLeast(0)) + 1; val prevLn = if (cur - 1 >= ls) newTx.substring(ls, cur - 1).trimStart() else ""; if (prevLn == "☐" || prevLn == "☑" || prevLn == "•") { val cl = newTx.substring(0, ls) + newTx.substring(cur); return TextFieldValue(cl, TextRange(ls.coerceIn(0, cl.length))) }; if (prevLn.startsWith("☐") || prevLn.startsWith("☑")) { val insT = newTx.substring(0, cur) + "☐ " + newTx.substring(cur); return TextFieldValue(insT, TextRange(cur + 2)) }; if (prevLn.startsWith("•")) { val insT = newTx.substring(0, cur) + "• " + newTx.substring(cur); return TextFieldValue(insT, TextRange(cur + 2)) } }; return tfv }
fun convertOrToggleNoteChecks(n: NoteItem): NoteItem { val nl = 10.toChar().toString(); val ls = n.content.lines().toMutableList(); val hasChk = ls.any { val t = it.trimStart(); t.startsWith("☐") || t.startsWith("☑") }; if (!hasChk) { for (i in ls.indices) { val t = ls[i].trimStart(); if (t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("──")) ls[i] = "☐ " + t.removePrefix("• ").removePrefix("•").trimStart() } } else { val hasUnchk = ls.any { it.trimStart().startsWith("☐") }; for (i in ls.indices) { val t = ls[i].trimStart(); if (hasUnchk && t.startsWith("☐")) ls[i] = "☑ " + t.removePrefix("☐").trimStart() else if (!hasUnchk && t.startsWith("☑")) ls[i] = "☐ " + t.removePrefix("☑").trimStart() } }; return n.copy(content = ls.joinToString(nl)) }
val chatListState = rememberLazyListState(); EmojiHelpDialog(); var chatJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }; var dictMode by remember { mutableStateOf(0) }; var pendingVoiceSend by remember { mutableStateOf("") }
fun execDictCmd(cmd: String, tx: String) { dictMode = 0; when (cmd) { "SAVE_MEMO" -> { val raw = cleanMemoText(tx); if (raw.isNotEmpty()) { val c0 = SmartMemoClassifier.guessCategory(raw, cats); val nx = listOf(NoteItem(nextId(), makeHashtags(raw, c0), "", listOf(c0, if (raw.contains("중요")) "중요" else "일반"), SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date()), "LocalAI")) + notes; onSaveNotes(nx); setInputProg(""); aiMsg = "✅ 음성 명령으로 [$c0] 메모가 즉시 저장되었습니다!" } }; "SEND_AI" -> { val q = tx.trim(); if (q.isNotEmpty()) { setInputProg(""); sharedAiChatIn = ""; mode = "AI"; pendingVoiceSend = q } }; "STOP" -> { aiMsg = "⏹️ 연속 받아쓰기 종료" } } }
val dictFallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res -> val spk = if (res.resultCode == android.app.Activity.RESULT_OK) res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim() ?: "" else ""; if (spk.isNotEmpty()) { if (dictMode == 1) { setInputProg(if (input.isBlank()) spk else "$input $spk") } else sharedAiChatIn = if (sharedAiChatIn.isBlank()) spk else "$sharedAiChatIn $spk"; aiMsg = "✅ 음성 입력: $spk" }; dictMode = 0 }
val dictPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok && dictMode != 0) { val dm = dictMode; val initT = if (dm == 1) input else sharedAiChatIn; if (JarvisRuntime.on) JarvisRuntime.stop(ctx); startOneShotDictation(ctx, initT, { pt -> if (dm == 1) { setInputProg(pt) } else sharedAiChatIn = pt }, { fn -> dictMode = 0; if (dm == 1) { setInputProg(fn) } else sharedAiChatIn = fn }, { st -> aiMsg = st }, { dictMode = 0; runCatching { dictFallLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")) } }, { cmd, tx -> execDictCmd(cmd, tx) }) } else { dictMode = 0; aiMsg = "⚠️ 마이크 권한이 필요합니다." } }
fun triggerDictation(target: Int) { if (dictMode != 0 || (oneShotActive && !JarvisRuntime.on)) { dictMode = 0; stopOneShotDictation(); aiMsg = "⏹️ 연속 받아쓰기 중지"; return }; if (JarvisRuntime.on) JarvisRuntime.stop(ctx); dictMode = target; val initT = if (target == 1) input else sharedAiChatIn; if (ctx.checkSelfPermission("android.permission.RECORD_AUDIO") == 0) { startOneShotDictation(ctx, initT, { pt -> if (target == 1) { setInputProg(pt) } else sharedAiChatIn = pt }, { fn -> dictMode = 0; if (target == 1) { setInputProg(fn) } else sharedAiChatIn = fn }, { st -> aiMsg = st }, { dictMode = 0; runCatching { dictFallLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")) } }, { cmd, tx -> execDictCmd(cmd, tx) }) } else dictPermLauncher.launch("android.permission.RECORD_AUDIO") }
val nearBottom by remember { derivedStateOf { val li = chatListState.layoutInfo; val lv = li.visibleItemsInfo.lastOrNull()?.index ?: 0; li.totalItemsCount == 0 || lv >= (li.totalItemsCount - 2).coerceAtLeast(0) } }
var lastAutoScrollMs by remember { mutableStateOf(0L) }; val lastBubbleLen = chatMsgs.lastOrNull()?.text?.length ?: 0
LaunchedEffect(chatMsgs.size) { if (chatMsgs.isNotEmpty()) runCatching { chatListState.animateScrollToItem(chatMsgs.size) } }
LaunchedEffect(lastBubbleLen, isThinking) { val now = System.currentTimeMillis(); if (chatMsgs.isNotEmpty() && (nearBottom || isThinking) && now - lastAutoScrollMs >= 75L) { lastAutoScrollMs = now; runCatching { chatListState.scrollToItem(chatMsgs.size) } } }
LaunchedEffect(notes.size) { ScheduleBriefingManager.syncNoteAlarms(ctx, notes) }
LaunchedEffect(aiCfg.idleMin) { while (aiCfg.idleMin > 0) { delay(30000L); if (!isThinking && !isAiBusy && checkIdleUnload(aiCfg.idleMin)) { val lb = if (aiCfg.idleMin == 180) "3시간" else "${aiCfg.idleMin}분"; aiMsg = "🧹 ${lb} 유휴 경과로 GGUF 모델을 메모리에서 내렸습니다 (사용 시 자동 재로드)" } } }
fun stopChat() { activeGenId++; if (ProLlamaJni.ready) runCatching { ProLlamaJni.abortGeneration() }; chatJob?.cancel(); chatJob = null; isThinking = false; streamActive = false; isAiBusy = false; stopOneShotTts(); chatMsgs = chatMsgs.mapIndexed { idx, m -> if (idx == chatMsgs.lastIndex && !m.isUser) m.copy(text = m.text.removeSuffix(" ▍")) else m }; if (vOn) uiP(400L) { sttGo(ctx, true) } }
fun sendChat(txt: String) { val q = txt.trim(); if (q.isEmpty() || isThinking) return; val callNm = aiCfg.apiModel.trim().ifBlank { wakeWord }; val (jHit, jRep) = JarvisController.handle(mainActRef ?: ctx, q, callNm, JarvisRuntime.navCb, { raw -> val c0 = SmartMemoClassifier.guessCategory(raw, cats); val nx = listOf(NoteItem(nextId(), makeHashtags(raw, c0), "", listOf(c0, "일반"), SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date()), "LocalAI")) + notes; onSaveNotes(nx); "✅ [$c0] 메모 저장됨" }, { notes }, false); if (jHit) { if (jRep == "STOP_JARVIS") { vStop(ctx); aiMsg = "⏹️ 자비스 종료됨"; return }; if (jRep.isNotBlank()) { chatMsgs = chatMsgs + ChatMsg(nextId(), true, q, aiCfg.ragOn) + ChatMsg(nextId(), false, jRep, aiCfg.ragOn); aiMsg = jRep; if (vOn || ttsOn) speakOneShot(ctx, jRep); if (vOn) scope.launch { delay(350L); var gd = 0; while (gd < 300 && isTtsBusyNow()) { delay(200L); gd++ }; isAiBusy = false; streamActive = false; sttGo(ctx, true) }; return } }; val prevH = chatMsgs; val uId = nextId(); val bId = nextId(); val myGen = ++activeGenId; isAiBusy = true; streamActive = true; ttsSpkIdx = 0; ttsFirstChunk = true; ttsPending.clear(); ttsActiveCount = 0; uiP(0L) { sttEpoch += 1; val old = sttEngine; sttEngine = null; runCatching { old?.cancel(); old?.destroy(); ttsEngine?.stop() } }; chatMsgs = chatMsgs + ChatMsg(uId, true, q, aiCfg.ragOn) + ChatMsg(bId, false, if (streamOn) "⚡ 0.0초 ▍" else "⏳...", aiCfg.ragOn); vBuf = ""; vSpkNow = false; vSeq++; sharedAiChatIn = ""; isThinking = true; chatJob = scope.launch { keepAw(ctx, true); var firstTok = false; var latestLive = ""; val t0 = System.currentTimeMillis(); val wt = launch { while (myGen == activeGenId && !firstTok && isThinking) { val ds = (System.currentTimeMillis() - t0) / 100L; chatMsgs = chatMsgs.map { if (it.id == bId) it.copy(text = "⚡ ${ds / 10}.${ds % 10}초 ▍") else it }; delay(200L) } }; try { val r = withContext(Dispatchers.IO) { buildAiChatReply(q, aiCfg, prevH, notes, books, cats, ai, { lv -> if (myGen == activeGenId && lv.isNotBlank()) { firstTok = true; latestLive = lv; if (streamOn) uiP(0L) { if (myGen == activeGenId) { chatMsgs = chatMsgs.map { if (it.id == bId) it.copy(text = lv + " ▍") else it }; feedStreamTts(ctx, lv, false) } } } }, myGen) }; if (myGen == activeGenId) { firstTok = true; wt.cancel(); val fa = r.ifBlank { latestLive.ifBlank { "⚠️ 응답 생성 실패" } }; chatMsgs = chatMsgs.map { if (it.id == bId) it.copy(text = fa) else it }; isThinking = false; feedStreamTts(ctx, fa, true); if (vOn || ttsOn) { delay(350L); var gd = 0; while (myGen == activeGenId && gd < 600) { if (!isTtsBusyNow() && (System.currentTimeMillis() - ttsLastEnqueueMs) >= 650L) break; delay(220L); gd++ } }; if (myGen == activeGenId) { delay(300L); streamActive = false; isAiBusy = false; if (vOn) { if (wakeOnly) wakeFollowUpUntil = System.currentTimeMillis() + 15000L; vSt = "듣는중"; sttGo(ctx, true) } else keepAw(ctx, false) } } } finally { wt.cancel(); if (myGen == activeGenId && isThinking) { isThinking = false; streamActive = false; isAiBusy = false; if (vOn) uiP(450L) { sttGo(ctx, true) } } } } }
val latestSendChat by rememberUpdatedState<(String) -> Unit> { q -> sendChat(q) }; SideEffect { vCb = latestSendChat }
LaunchedEffect(isAiPage, pendingHomeAiQuery) { if (isAiPage && pendingHomeAiQuery.isNotBlank()) { val hq = pendingHomeAiQuery.trim(); mode = "AI"; sendChat(hq); pendingHomeAiQuery = "" } }
LaunchedEffect(pendingVoiceSend) { if (pendingVoiceSend.isNotBlank()) { val vq = pendingVoiceSend.trim(); mode = "AI"; sendChat(vq); pendingVoiceSend = "" } }
LaunchedEffect(vOn) { while (vOn) { delay(3200L); if (wakeLockRef?.isHeld != true) keepAw(ctx, true); if (vOn && !isThinking && !isAiBusy && !streamActive && !isTtsBusyNow() && !vSpkNow && (System.currentTimeMillis() - lastSttBeatMs) > 6500L) { if (vBuf.isNotBlank()) flushV(ctx) else sttGo(ctx, true) } } }
val micP = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) { if (JarvisRuntime.on) JarvisRuntime.stop(ctx); vOn = true; isAiBusy = false; streamActive = false; vBuf = ""; keepAw(ctx, true); sttGo(ctx, true) } }
val vStart: () -> Unit = { vCb = latestSendChat; if (ctx.checkSelfPermission("android.permission.RECORD_AUDIO") == 0) { if (JarvisRuntime.on) JarvisRuntime.stop(ctx); vOn = true; isAiBusy = false; streamActive = false; vBuf = ""; keepAw(ctx, true); sttGo(ctx, true) } else micP.launch("android.permission.RECORD_AUDIO") }

if (blkOn) { Surface(color = Color.Black, modifier = Modifier.fillMaxSize().clickable { blkOn = false }) { Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { Text("🌙 암전 대화모드 (터치시 화면 켜짐) · " + vSt, fontSize = ui.bodySp, color = Color(0xFF6B7280)) } } } else Column(Modifier.fillMaxSize().padding(horizontal = ui.hPad, vertical = ui.vPad)) {
Row(FMW, SB, CV) {
Column(Modifier.weight(1f)) {
Text(if (mode == "AI") "🦙 온디바이스 GGUF AI 스튜디오" else "메모장 (${notes.size}건 · ★중요 ${notes.count { SMC.isImportant(it) }}건)", fontSize = ui.titleSp, fontWeight = FontWeight.Bold, color = CW)
Text(aiMsg, fontSize = ui.subSp, color = CG)
}
Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
if (mode == "AI") {
OutlinedButton(onClick = { topOpen = !topOpen; if (topOpen) aiPromptCfgOpen = false }) { Text("🎛️TTS·성능", fontSize = ui.subSp, color = if (topOpen) CA else CW) }
OutlinedButton(onClick = { aiPromptCfgOpen = !aiPromptCfgOpen; if (aiPromptCfgOpen) topOpen = false }) { Text("⚙️AI설정", fontSize = ui.subSp, color = if (aiPromptCfgOpen) CG else CW) }
} else {
OutlinedButton(onClick = { cfgOpen = !cfgOpen }) {
Text("⚙️", fontSize = ui.subSp)
}
}
}
}

if (cfgOpen && mode != "AI") {
Spacer(Modifier.height(6.dp))
Surface(color = Color(0xFF0E1524), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CG.copy(alpha = 0.75f)), modifier = FMW.heightIn(max = ui.panelMaxH)) {
Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
Row(FMW, SB, CV) {
Text("⚙️ 메모장 환경설정", fontSize = (ui.subSp.value + 1.5f).sp, fontWeight = FontWeight.Bold, color = CW)
Text("✕ 닫기", fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = CG, modifier = Modifier.clickable { cfgOpen = false }.padding(4.dp))
}
SettingsTabBar(listOf("🏷️ 분류 관리", "🛠️ 버튼·알림", "💾 백업·화면"), memoCfgTab) { memoCfgTab = it }
when (memoCfgTab) {
0 -> {
Surface(color = Color(0xFF09101C), shape = RoundedCornerShape(11.dp), border = BorderStroke(0.8.dp, Color(0xFF22314A)), modifier = FMW) {
Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("카테고리 이름 변경(탭) 및 삭제(✕)", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG)
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6) {
cats.forEach { c ->
Surface(color = CS, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Color(0xFF233047))) {
Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
Text("✏️ $c", fontSize = ui.subSp, color = CW, modifier = Modifier.clickable { renTarget = c; renVal = c })
if (cats.size > 1) Text("✕", fontSize = ui.subSp, color = Color(0xFFEF4444), modifier = Modifier.clickable {
val nx = cats.filterNot { it == c }; cats = nx; SMC.saveMemoCats(ctx, nx); if (mCat == c) mCat = nx.first()
})
}
}
}
}
renTarget?.let { tg ->
Row(FMW, verticalAlignment = CV, horizontalArrangement = HS6) {
OutlinedTextField(value = renVal, onValueChange = { renVal = it }, label = { Text("'$tg' 이름 변경") }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = {
val nv = renVal.trim()
if (nv.isNotEmpty()) {
val nx = cats.map { if (it == tg) nv else it }.distinct()
cats = nx; SMC.saveMemoCats(ctx, nx)
onSaveNotes(notes.map { n -> n.copy(tags = n.tags.map { if (it == tg) nv else it }) }); renTarget = null
}
}) { Text("💾", fontSize = ui.subSp) }
}
}
Row(FMW, verticalAlignment = CV, horizontalArrangement = HS6) {
OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 카테고리 이름 입력", fontSize = ui.subSp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !cats.contains(t)) { val nx = cats + t; cats = nx; SMC.saveMemoCats(ctx, nx); newCat = "" } }) { Text("➕", fontSize = ui.subSp) }
}
}
}
}
1 -> {
Surface(color = Color(0xFF09101C), shape = RoundedCornerShape(11.dp), border = BorderStroke(0.8.dp, Color(0xFF22314A)), modifier = FMW) {
Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("🛠️ 빠른 작성 도구 버튼 표시", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG)
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6) {
ChipBtn("☑ 체크박스", bCfg.showCheck) { bCfg = bCfg.copy(showCheck = !bCfg.showCheck); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("• 글머리", bCfg.showBullet) { bCfg = bCfg.copy(showBullet = !bCfg.showBullet); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("📅 날짜시간", bCfg.showDate) { bCfg = bCfg.copy(showDate = !bCfg.showDate); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("── 구분선", bCfg.showDivider) { bCfg = bCfg.copy(showDivider = !bCfg.showDivider); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("↵ 줄바꿈", bCfg.showNewline) { bCfg = bCfg.copy(showNewline = !bCfg.showNewline); SMC.saveBtnCfg(ctx, bCfg) }
}
Box(Modifier.fillMaxWidth().height(0.8.dp).background(Color(0xFF1C2940)))
Text("⏰ 모닝 일정 브리핑 자동 알림", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CA)
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6) {
listOf(-1 to "🔕 알림 끔", 7 to "🌅 오전 7시", 8 to "🌅 오전 8시", 9 to "🌅 오전 9시").forEach { (hr, lb) -> ChipBtn(lb, briefHour == hr, CA) { briefHour = hr; aiMsg = ScheduleBriefingManager.setMorningHour(ctx, hr) } }
ChipBtn("🔊 지금 브리핑 듣기", false, CG) { val bf = ScheduleBriefingManager.buildBriefingSummary(notes); sOut = bf; speakOneShot(ctx, bf); aiMsg = "🔊 오늘 할일·일정 브리핑 재생" }
}
}
}
}
else -> {
Surface(color = Color(0xFF09101C), shape = RoundedCornerShape(11.dp), border = BorderStroke(0.8.dp, Color(0xFF22314A)), modifier = FMW) {
Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("💾 메모 데이터 백업 및 복원", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG)
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6) {
ChipBtn("📤 파일로 백업(.json)", false, CG) { bkExp.launch("prolauncher_memos_backup.json") }
ChipBtn("📂 파일 복원", false, CA) { bkImp.launch(arrayOf("*/*")) }
ChipBtn("⚡ 내부 즉시백업", false, CG) { val j = ctx.getSharedPreferences("pl_ai_v2_prefs", 0).getString("notes_json", "[]") ?: "[]"; ctx.getSharedPreferences("pl_ai_v4_custom", 0).edit().putString("memo_backup_json", j).apply(); aiMsg = "✅ 메모 " + notes.size + "건 즉시 백업 완료!" }
ChipBtn("♻️ 내부 복원", false, CA) { val j = ctx.getSharedPreferences("pl_ai_v4_custom", 0).getString("memo_backup_json", null); if (!j.isNullOrBlank()) { val old = notes; ctx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putString("notes_json", j).commit(); val merged = (repo.loadNotes() + old).distinctBy { it.id }; onSaveNotes(merged); aiMsg = "✅ 메모 " + merged.size + "건 복원 완료!" } }
}
Box(Modifier.fillMaxWidth().height(0.8.dp).background(Color(0xFF1C2940)))
Text("📐 화면 글자·UI 배율", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CW)
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6) {
listOf("AUTO" to "자동(맞춤)", "COMPACT" to "작게", "NORMAL" to "표준", "LARGE" to "크게").forEach { (k, l) -> ChipBtn(l, aiCfg.uiScale == k, CG) { aiCfg = aiCfg.copy(uiScale = k); saveAiCfg(ctx, aiCfg) } }
}
}
}
}
}
}
}
}

if (mode == "AI") { Spacer(Modifier.height(4.dp)); Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6, verticalAlignment = CV) { ChipBtn("💬", !aiCfg.ragOn && !vOn, CW) { vStop(ctx); aiCfg = aiCfg.copy(ragOn = false); saveAiCfg(ctx, aiCfg); aiMsg = "💬 일반 챗봇 모드" }; ChipBtn("🗂️", aiCfg.ragOn, CG) { aiCfg = aiCfg.copy(ragOn = !aiCfg.ragOn); saveAiCfg(ctx, aiCfg); aiMsg = if (aiCfg.ragOn) "🗂️ 메모 RAG 켜짐" else "💬 일반 챗봇 모드" }; val cpuIcon = when (aiCfg.cpuMode) { 1 -> "🔋"; 3 -> "🚀"; else -> "⚖️" }; ChipBtn(cpuIcon, true, CG) { val nxM = if (aiCfg.cpuMode == 1) 2 else if (aiCfg.cpuMode == 2) 3 else 1; val nx = aiCfg.copy(cpuMode = nxM); aiCfg = nx; saveAiCfg(ctx, nx); aiMsg = when (nxM) { 1 -> "🔋 절전(2코어·저발열)"; 3 -> "🚀 고성능(4코어·최고속)"; else -> "⚖️ 균형(3코어·권장)" } }; val curTk = aiCfg.maxTok.coerceAtLeast(512); ChipBtn("📏${curTk}", true, CA) { val nxTk = when (curTk) { 512 -> 1024; 1024 -> 2048; 2048 -> 4096; else -> 512 }; val nx = aiCfg.copy(maxTok = nxTk, ctxSize = maxOf(aiCfg.ctxSize, nxTk + 512)); aiCfg = nx; saveAiCfg(ctx, nx); aiMsg = "📏 최대 출력 토큰: ${nxTk}토큰" }; ChipBtn("🔗${aiCfg.histTurns}", aiCfg.histTurns > 0, CG) { val nxH = when (aiCfg.histTurns) { 0 -> 2; 2; 2 -> 4; 4 -> 6; 6 -> 10; else -> 0 }; val nx = aiCfg.copy(histTurns = nxH); aiCfg = nx; saveAiCfg(ctx, nx); aiMsg = if (nxH == 0) "🔗 기억 대화 0개 (단발·최고속)" else "🔗 이전 대화 ${nxH}개 연속 기억" }; val idLb = when (aiCfg.idleMin) { 0 -> "🧹∞"; 180 -> "🧹3h"; else -> "🧹${aiCfg.idleMin}m" }; ChipBtn(idLb, aiCfg.idleMin > 0, CW) { val nxI = when (aiCfg.idleMin) { 30 -> 60; 60 -> 180; 180 -> 0; else -> 30 }; val nx = aiCfg.copy(idleMin = nxI); aiCfg = nx; saveAiCfg(ctx, nx); aiMsg = if (nxI == 0) "🧹 모델 상주 모드 (자동 해제 OFF)" else "🧹 ${if (nxI == 180) "3시간" else "${nxI}분"} 유휴 시 모델 RAM 자동 해제" }; ChipBtn(if (thinkOn) "🧠" else "⚡", thinkOn, CA) { setThinkOn(ctx, !thinkOn); aiMsg = if (thinkOn) "🧠 심층 추론 ON" else "⚡ 초고속 직답 모드" }; ChipBtn(if (streamOn) "🌊" else "📄", streamOn, CG) { setStreamOn(ctx, !streamOn); aiMsg = if (streamOn) "🌊 실시간 토큰 스트리밍" else "📄 일괄 출력" }; ChipBtn(if (vOn) "🔴" else "🎙️", vOn, CA) { if (vOn) vStop(ctx) else vStart() }; ChipBtn(if (wakeOnly) "👂" else "🎙️", wakeOnly, CA) { setWakeOnly(ctx, !wakeOnly); aiMsg = if (wakeOnly) "👂 호출어 대기 ON: [${wakeWord}] 부를 때만 반응" else "🎙️ 상시 반응 모드" }; ChipBtn("⏱️${vWaitSec}", vOn, CW) { vWaitSec = if (vWaitSec == 2) 3 else if (vWaitSec == 3) 5 else 2; aiMsg = "⏱️ 음성 대기 ${vWaitSec}초" }; ChipBtn(if (ttsOn) "🔊" else "🔇", ttsOn, CG) { ttsOn = !ttsOn; if (!ttsOn) stopOneShotTts() }; ChipBtn("🌙", blkOn, CW) { keepAw(ctx, true); blkOn = true }; ChipBtn("🎛️TTS", topOpen, CA) { topOpen = !topOpen }; ChipBtn("❓", false, CA) { showEmojiHelp = true } }; if (topOpen) Surface(color = Color(0xFF141923), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (aiCfg.ragOn) CG else Color(0xFF233047)), modifier = FMW) {
Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), SB, CV) {
Column(Modifier.weight(1f)) {
Text(if (aiCfg.ragOn) "🗂️ 전체 메모 RAG 모드 ON (${if (aiCfg.ragScope == "ALL") "전체 메모 ${notes.size}건 읽기" else "상위 ${aiCfg.ragTopK}건 검색"})" else "💬 일반 LLM AI 챗봇 모드 (RAG OFF)", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = if (aiCfg.ragOn) CG else CW)
Text(if (aiCfg.ragOn) "전체 메모를 LLM이 읽고 종합 분석해 답변합니다." else "내 메모 없이 설정된 프롬프트로 모든 질문에 답하는 일반 AI 챗봇입니다.", fontSize = ui.subSp, color = CM)
}
Switch(checked = aiCfg.ragOn, onCheckedChange = { aiCfg = aiCfg.copy(ragOn = it); saveAiCfg(ctx, aiCfg) })
}; SupertonicControlSection(ctx, ui, compact = true)
}

if (aiPromptCfgOpen) {
Spacer(Modifier.height(4.dp))
AiPromptRagPanel(ctx, aiCfg, ui, onUpdate = { aiCfg = it })
}

Spacer(Modifier.height(6.dp))
Box(Modifier.weight(1f).fillMaxWidth()) {
LazyColumn(state = chatListState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
items(chatMsgs, key = { it.id }) { m ->
val uShape = RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
val aShape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
Row(FMW, horizontalArrangement = if (m.isUser) Arrangement.End else Arrangement.Start) {
Surface(
color = if (m.isUser) Color(0xFF112A24) else Color(0xFF101624),
shape = if (m.isUser) uShape else aShape,
border = BorderStroke(0.8.dp, if (m.isUser) CG.copy(alpha = 0.45f) else Color(0xFF222E44)),
modifier = if (m.isUser) Modifier.widthIn(min = 72.dp, max = 300.dp) else Modifier.fillMaxWidth(0.95f)
) {
Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
if (!m.isUser) {
Row(FMW, SB, CV) {
Text((if (m.usedMemo) "🗂️ RAG · " else "🦙 ") + aiCfg.apiModel.trim().ifBlank { AiResidentManager.gemmaName.takeIf { !it.contains("미연결") }?.take(16) ?: "로컬 GGUF" }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = if (m.usedMemo) CG else CA)
Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
Text("🔊", maxLines = 1, softWrap = false, fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = CA, modifier = Modifier.clickable { speakOneShot(ctx, m.text.removeSuffix(" ▍")); aiMsg = "🔊 답변 전체를 끝까지 읽어드립니다." }); Text("📋", maxLines = 1, softWrap = false, fontSize = ui.subSp, fontWeight = FontWeight.Medium, color = CM, modifier = Modifier.clickable { copyToClip(ctx, m.text.removeSuffix(" ▍")); aiMsg = "📋 답변이 클립보드에 복사되었습니다." })
Text("💾", maxLines = 1, softWrap = false, fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = CG, modifier = Modifier.clickable {
val cleanT = m.text.removeSuffix(" ▍")
val cat = SMC.guessCategory(cleanT, cats)
val nx = listOf(NoteItem(nextId(), makeHashtags(cleanT, cat), "", listOf(cat, "일반"), nowStr(), "LocalAI")) + notes
onSaveNotes(nx); ScheduleBriefingManager.syncNoteAlarms(ctx, nx)
aiMsg = "✅ AI 답변을 [$cat] 메모로 저장했습니다."
})
}
}
}
Text(m.text, fontSize = ui.bodySp, lineHeight = (ui.bodySp.value + 6).sp, color = if (m.isUser) Color(0xFFECFDF5) else CW)
}
}
}
}
item(key = "btm_anchor") { Spacer(Modifier.height(4.dp)) }
}
if (!nearBottom && chatMsgs.size > 2) {
Surface(
color = Color(0xEE131C2E), shape = RoundedCornerShape(50),
border = BorderStroke(0.9.dp, CG.copy(alpha = 0.65f)),
modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp, end = 6.dp).clickable { scope.launch { runCatching { chatListState.animateScrollToItem(chatMsgs.size) } } }
) {
Text("↓", fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = CG, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
}
}
}
} else {
Spacer(Modifier.height(4.dp))
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6) {
tabs.forEach { c -> ChipBtn(c, selTab == c) { selTab = c } }
}
Spacer(Modifier.height(6.dp))
LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = VS8) {
sOut?.let { o ->
item {
Surface(color = Color(0xFF14202E), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Color(0xFF38BDF8)), modifier = FMW) {
Column(Modifier.padding(ui.cardPad)) {
Row(FMW, SB, CV) { Text("🔎 검색 결과", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8)); Text("✕", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { sOut = null }) }
Text(o, fontSize = ui.bodySp, lineHeight = 18.sp, color = CW)
}
}
}
}
items(list, key = { it.id }) { n -> val cat = SMC.categoryOf(n, cats); val imp = SMC.isImportant(n); val ed = editId == n.id; Surface(color = CS, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (ed) CG else if (imp) CA.copy(alpha = 0.65f) else Color(0xFF1E293B)), modifier = FMW) { if (ed) { Column(Modifier.padding(ui.cardPad), verticalArrangement = VS6) { Row(FMW, SB, CV) { Text("✏️ 화면 내 바로 수정", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CG); Text(if (editImp) "★" else "☆", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = if (editImp) CA else CM, modifier = Modifier.clickable { editImp = !editImp }.padding(4.dp)) }; Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6, verticalAlignment = CV) { Text("분류:", fontSize = ui.subSp, color = CM); cats.forEach { c -> ChipBtn(c, editCat == c) { editCat = c } } }; Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6) { if (bCfg.showCheck) ChipBtn("☑", editTxt.contains("☐") || editTxt.contains("☑"), CG) { val r = applySmartCheck(editTxt); setEditProg(r) }; if (bCfg.showBullet) ChipBtn("•", editTxt.contains("•"), CW) { val r = applySmartBullet(editTxt); setEditProg(r) }; if (bCfg.showDate) ChipBtn("📅") { val sp = if (editTxt.isEmpty() || editTxt.endsWith(" ") || editTxt.endsWith("\n")) "" else " "; val r = editTxt + sp + nowStr() + " "; setEditProg(r) }; if (bCfg.showDivider) ChipBtn("──") { val r = ins(editTxt, "────────────\n"); setEditProg(r) }; if (bCfg.showNewline) ChipBtn("↵") { val r = handleMemoEnterKey(editTxt); setEditProg(r) } }; OutlinedTextField(value = editTfv, onValueChange = { tfv -> editTfv = onMemoValueChange(editTfv, tfv) }, singleLine = false, minLines = 3, maxLines = 10, modifier = FMW); Row(FMW, SB, CV) { TextButton(onClick = { val tg = makeHashtags(cleanMemoText(editTxt), editCat); onSaveNotes(notes.map { if (it.id == n.id) it.copy(content = tg, summary = "", tags = listOf(editCat, if (editImp) "중요" else "일반")) else it }); editId = null; aiMsg = "🏷️ 해시태그 생성 및 저장 완료!" }) { Text("🏷️", fontSize = ui.subSp, color = CG) }; Row(horizontalArrangement = HS6) { TextButton(onClick = { editId = null }) { Text("✕", fontSize = (ui.subSp.value + 1).sp, color = CM) }; Button(onClick = { val clE = cleanMemoText(editTxt); if (clE.isNotEmpty()) { val kept = if (clE.lines().size <= 2 && clE.length < 75) "" else n.summary; onSaveNotes(notes.map { if (it.id == n.id) it.copy(content = clE, summary = kept, tags = listOf(editCat, if (editImp) "중요" else "일반")) else it }); editId = null; aiMsg = "✅ 메모 수정 완료!" } }) { Text("💾", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold) } } } } } else { Column(Modifier.padding(ui.cardPad)) { Row(FMW, SB, CV) { Row(verticalAlignment = CV, horizontalArrangement = HS6) { Surface(color = Color(0xFF1E2433), shape = RoundedCornerShape(6.dp)) { Text(cat, fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = CG, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }; Text(n.createdAt, fontSize = ui.subSp, color = CD); val chkTot = n.content.lines().count { val t = it.trimStart(); t.startsWith("☐") || t.startsWith("☑") }; if (chkTot > 0) { val chkDone = n.content.lines().count { it.trimStart().startsWith("☑") }; Text("☑ $chkDone/$chkTot", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = if (chkDone == chkTot) CG else CA) }; ScheduleBriefingManager.detectNoteScheduleLabel(n)?.let { slb -> Text("⏰ " + slb, fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = CA) } }; Text(if (imp) "★" else "☆", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = if (imp) CA else CD, modifier = Modifier.clickable { onSaveNotes(notes.map { if (it.id == n.id) SMC.toggleImportant(it) else it }) }.padding(4.dp)) }; Spacer(Modifier.height(4.dp)); n.content.lines().forEachIndexed { idx, ln -> val tr = ln.trimStart(); if (tr.startsWith("☐") || tr.startsWith("☑")) { val chk = tr.startsWith("☑"); Surface(color = if (chk) Color(0xFF0B111E) else Color(0xFF152033), shape = RoundedCornerShape(8.dp), border = BorderStroke(0.7.dp, if (chk) Color(0xFF1E293B) else CG.copy(alpha = 0.45f)), modifier = FMW.padding(vertical = 2.dp).clickable { val nx = notes.map { if (it.id == n.id) SMC.toggleCheckLine(it, idx) else it }; onSaveNotes(nx); aiMsg = if (!chk) "✅ 체크 완료!" else "☐ 체크 해제됨" }) { Text(ln, fontSize = ui.bodySp, lineHeight = (ui.bodySp.value + 5).sp, color = if (chk) CD else CW, textDecoration = if (chk) TextDecoration.LineThrough else null, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) } } else Text(ln, fontSize = ui.bodySp, lineHeight = (ui.bodySp.value + 5).sp, color = CW) }; Row(FMW.padding(top = 4.dp), SB, CV) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) { Text("☑", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CA, modifier = Modifier.clickable { val nx = notes.map { if (it.id == n.id) convertOrToggleNoteChecks(it) else it }; onSaveNotes(nx); aiMsg = "☑ 체크리스트 변환/전체체크 적용됨!" }.padding(4.dp)); Text("🏷️", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG, modifier = Modifier.clickable { onSaveNotes(notes.map { if (it.id == n.id) it.copy(content = makeHashtags(cleanMemoText(n.content), cat), summary = "") else it }); aiMsg = "🏷️ 메모 하단에 해시태그가 생성되었습니다!" }.padding(4.dp)) }; Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = CV) { Text("🤖", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CA, modifier = Modifier.clickable { copyMemoToAi(n.content); aiMsg = "🤖 AI 대화창에 메모+구분선 복사됨!" }.padding(horizontal = 5.dp, vertical = 6.dp)); Text("📋", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CW, modifier = Modifier.clickable { copyToClip(ctx, n.content); aiMsg = "📋 클립보드에 복사되었습니다." }.padding(horizontal = 5.dp, vertical = 6.dp)); Text("✏️", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CG, modifier = Modifier.clickable { editId = n.id; setEditProg(n.content); editCat = cat; editImp = imp }.padding(horizontal = 5.dp, vertical = 6.dp)); Text("🗑️", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444), modifier = Modifier.clickable { onSaveNotes(notes.filterNot { it.id == n.id }) }.padding(horizontal = 5.dp, vertical = 6.dp)) } } } } } } }
Spacer(Modifier.height(6.dp))
if (editId == null || isAiPage) Surface(color = Color(0xFF0F1624), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFF233047)), modifier = FMW) {
Column(Modifier.padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(5.dp)) {
if (!isAiPage) Row(FMW.background(Color(0xFF0B0C10), RoundedCornerShape(10.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
listOf("MEMO" to "📝", "SEARCH" to "🔎").forEach { (mk, lb) ->
val act = mode == mk
Surface(color = if (act) CG else Color.Transparent, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { mode = mk }) {
Text(lb, fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = if (act) Color.Black else CM, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 6.dp))
}
}
}
when (mode) {
"MEMO" -> { Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) { Text("분류:", fontSize = ui.subSp, color = CM); (listOf("자동") + cats).forEach { c -> ChipBtn(c, mCat == c) { mCat = c } } }; Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) { ChipBtn(if (dictMode == 1) "🔴" else "🎙️", dictMode == 1, CA) { triggerDictation(1) }; if (bCfg.showCheck) ChipBtn("☑", input.contains("☐") || input.contains("☑"), CG) { val r = applySmartCheck(input); setInputProg(r); aiMsg = "☑ 체크리스트 적용 (엔터 입력 시 다음 ☐ 항목 자동 생성)" }; if (bCfg.showBullet) ChipBtn("•", input.contains("•"), CW) { val r = applySmartBullet(input); setInputProg(r) }; if (bCfg.showDate) ChipBtn("📅") { val sp = if (input.isEmpty() || input.endsWith(" ") || input.endsWith("\n")) "" else " "; val r = input + sp + nowStr() + " "; setInputProg(r) }; if (bCfg.showDivider) ChipBtn("──") { val r = ins(input, "────────────\n"); setInputProg(r) }; if (bCfg.showNewline) ChipBtn("↵") { val r = handleMemoEnterKey(input); setInputProg(r) }; if (bCfg.customLabel.isNotBlank()) ChipBtn(bCfg.customLabel, false, CA) { val r = ins(input, bCfg.customText); setInputProg(r) }; ChipBtn("🏷️", false, CG) { if (input.isNotBlank()) { val c0 = if (mCat == "자동" || !cats.contains(mCat)) SMC.guessCategory(input, cats) else mCat; val r = makeHashtags(cleanMemoText(input), c0); setInputProg(r); aiMsg = "🏷️ #$c0 해시태그가 생성되었습니다." } else aiMsg = "⚠️ 먼저 메모 내용을 입력해 주세요." }; ChipBtn("🤖", false, CA) { if (input.isNotBlank()) { copyMemoToAi(cleanMemoText(input)); aiMsg = "🤖 작성 메모가 AI 대화창에 구분선과 함께 복사됨!" } }; ChipBtn("🔊", false, CG) { val bf = ScheduleBriefingManager.buildBriefingSummary(notes); sOut = bf; speakOneShot(ctx, bf); aiMsg = "🔊 오늘 일정·체크리스트 음성 브리핑 실행" }; if (input.isNotEmpty()) ChipBtn("🗑️", false, CM) { setInputProg(""); aiMsg = "🗑️ 입력창을 비웠습니다." }; ChipBtn("❓", false, CM) { showEmojiHelp = true } }; OutlinedTextField(value = inputTfv, onValueChange = { tfv -> inputTfv = onMemoValueChange(inputTfv, tfv) }, placeholder = { Text("메모·체크리스트 작성 (☑ 누르거나 글 입력 후 ☑로 즉시 변환, 엔터 시 ☐ 자동 줄바꿈)", fontSize = (ui.subSp.value + 1).sp) }, singleLine = false, minLines = 2, maxLines = if (ui.isCompact) 3 else 4, modifier = FMW); Row(FMW, SB, CV) { Text(if (newImp) "★" else "☆", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = if (newImp) CA else CM, modifier = Modifier.clickable { newImp = !newImp }.padding(4.dp)); Button(onClick = { val raw = cleanMemoText(input); if (raw.isEmpty()) return@Button; val cat = if (mCat == "자동" || !cats.contains(mCat)) SMC.guessCategory(raw, cats) else mCat; val nx = listOf(NoteItem(nextId(), makeHashtags(raw, cat), "", listOf(cat, if (newImp) "중요" else "일반"), nowStr(), "LocalAI")) + notes; onSaveNotes(nx); ScheduleBriefingManager.syncNoteAlarms(ctx, nx); setInputProg(""); newImp = false; aiMsg = "✅ [$cat] 메모가 저장되었습니다!" }) { Text("💾", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold) } } }
"AI" -> {
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6, verticalAlignment = CV) {
ChipBtn(if (dictMode == 2) "🔴" else "🎙️", dictMode == 2, CA) { triggerDictation(2) }; ChipBtn("📊", aiCfg.ragOn) {
aiCfg = aiCfg.copy(ragOn = true, ragScope = "ALL"); saveAiCfg(ctx, aiCfg)
sendChat("내 전체 메모들을 카테고리별로 분석하고 핵심 할 일과 요점을 정리해줘")
}

ChipBtn("📋", pickMemoOpen, CA) { pickMemoOpen = !pickMemoOpen }
ChipBtn("🔍", false, CG) {
aiMsg = "⏳ 네이티브 C++ GGUF 스트리밍 & 임베딩젬마2 점검 중..."
scope.launch(Dispatchers.IO) {
val jniSt = if (ProLlamaJni.ready) "✅JNI엔진활성" else "⚠️JNI미로드"
val cGf = findLocalGguf()
val eGf = findEmbedGguf()
val ragSt = if (aiCfg.ragOn) "ON(메모 ${notes.size}건)" else "OFF(일반챗)"
val embRep = if (eGf == null) "임베딩젬마2 미등록(하이브리드 대기)" else {
val v = embedWithGguf("임베딩젬마2 점검", true)
if (v != null) "✅임베딩젬마2(${eGf.name.take(14)} · ${v.size}차원)" else "⚠️임베딩파일(${eGf.name.take(14)})"
}
val chatRep = if (cGf == null) "⚠️채팅 GGUF 미등록 ([⚙️ AI 세팅]에서 .gguf 업로드)" else {
var tokCallbacks = 0
val r = runLocalGguf(cGf, "한국어로 한 문장 인사하세요.", emptyList(), "안녕", { tokCallbacks++ }, 0L, aiCfg.temp, aiCfg.ctxSize, 64)
if (!r.isNullOrBlank()) "✅채팅GGUF(${cGf.name.take(14)} · 실시간콜백 ${tokCallbacks}회 · 응답: ${r.lines().joinToString(" ").take(18)})" else "⚠️채팅GGUF 점검필요(${cGf.name.take(14)})"
}
val fullRep = "$jniSt | $chatRep | $embRep | RAG:$ragSt"
withContext(Dispatchers.Main) { aiMsg = fullRep; chatMsgs = chatMsgs + ChatMsg(nextId(), false, fullRep, aiCfg.ragOn) }
}
}
ChipBtn(if (btmOpen) "🔽" else "🔼", false, CM) { btmOpen = !btmOpen }
ChipBtn("❓", false, CA) { showEmojiHelp = true }; ChipBtn("🗑️", false, CM) { chatMsgs = listOf(ChatMsg(nextId(), false, "대화가 초기화되었습니다. 무엇이든 편하게 질문해 주세요!", aiCfg.ragOn)) }
if (pickMemoOpen && notes.isNotEmpty()) {
notes.take(15).forEach { mn -> val pv = mn.content.lines().firstOrNull { it.isNotBlank() }?.take(12) ?: "메모"; ChipBtn("➕ " + pv, false, CA) { copyMemoToAi(mn.content); aiMsg = "🤖 메모가 구분선과 함께 입력창에 추가되었습니다." } }
}
}
if (btmOpen) Row(FMW, verticalAlignment = CV, horizontalArrangement = HS6) {
OutlinedTextField(value = sharedAiChatIn, onValueChange = { sharedAiChatIn = it }, shape = RoundedCornerShape(14.dp), placeholder = { Text(if (aiCfg.ragOn) "임베딩젬마2 RAG 활성: 내 메모·독서 내용 질문" else "무엇이든 질문하세요 (실시간 스트리밍)", fontSize = (ui.subSp.value + 1).sp, color = CD) }, singleLine = false, minLines = 1, maxLines = 5, modifier = Modifier.weight(1f))
if (isThinking) {
Button(onClick = { stopChat() }, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444), contentColor = Color.White)) { Text("⏹️", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
} else {
Button(onClick = { sendChat(sharedAiChatIn) }, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CG, contentColor = Color(0xFF062019))) { Text("↑", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
}
}
}
"SEARCH" -> {
OutlinedTextField(value = sQuery, onValueChange = { sQuery = it }, placeholder = { Text("메모 키워드·카테고리 또는 독서대 내용 검색", fontSize = (ui.subSp.value + 1).sp) }, singleLine = true, modifier = FMW)
Row(FMW, SB, CV) {
Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = HS6) {
ChipBtn("★") {
val imps = notes.filter { SMC.isImportant(it) }
sOut = if (imps.isEmpty()) "중요 표시(★)된 메모가 없습니다." else "📌 중요 메모 ${imps.size}건:\n" + imps.joinToString("\n") { "• [${SMC.categoryOf(it, cats)}] ${it.content.replace("\n", " ").take(65)}" }
}
ChipBtn("☑") {
val unc = notes.flatMap { n -> n.content.lines().filter { it.trimStart().startsWith("☐ ") }.map { "[${SMC.categoryOf(n, cats)}] $it" } }
sOut = if (unc.isEmpty()) "미완료된 체크리스트(☐) 항목이 없습니다." else "☑ 남은 체크리스트 ${unc.size}건:\n" + unc.joinToString("\n")
}
}
Button(onClick = {
val q = sQuery.trim(); if (q.isEmpty()) return@Button
scope.launch {
val ex = notes.filter { it.content.contains(q, true) || it.tags.any { t -> t.contains(q, true) } }
sOut = if (ex.isNotEmpty()) "🔎 '$q' 검색 결과 (${ex.size}건):\n" + ex.take(4).joinToString("\n") { "• [${SMC.categoryOf(it, cats)}] ${it.content.replace("\n", " ").take(75)}" }
else ai.askRag(q, notes, books, cfg.ragIncludeBooks)
}
}) { Text("🔎", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold) }
}
}
}
}
}
}
}
