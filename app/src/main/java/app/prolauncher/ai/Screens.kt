package app.prolauncher.ai
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
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

typealias SMC = SmartMemoClassifier
private val FMW = Modifier.fillMaxWidth()
private val HS6 = Arrangement.spacedBy(6.dp)
private val VS6 = Arrangement.spacedBy(6.dp)
private val VS8 = Arrangement.spacedBy(8.dp)

@Composable
fun NotesRagView(
notes: List<NoteItem>, todos: List<TodoItem>, books: List<BookItem>, cfg: LauncherConfig,
ai: LocalAiNoteEngine, quoted: String?, onClearQuote: () -> Unit,
onSaveNotes: (List<NoteItem>) -> Unit, onSaveTodos: (List<TodoItem>) -> Unit
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
var mode by remember { mutableStateOf("MEMO") }
var input by remember { mutableStateOf("") }
var mCat by remember { mutableStateOf(cats.firstOrNull() ?: "일상") }
var newImp by remember { mutableStateOf(false) }

var chatIn by remember { mutableStateOf("") }
var isThinking by remember { mutableStateOf(false) }
var chatMsgs by remember {
mutableStateOf(listOf(ChatMsg(1L, false, "안녕하세요! LLM AI 챗봇입니다.\n• [RAG OFF]: 설정된 시스템 프롬프트에 따라 모든 주제에 답하는 일반 AI 챗봇으로 작동합니다.\n• [전체 메모 RAG ON]: 저장된 전체 메모(${notes.size}건)를 읽고 분석해 답변합니다.", false)))
}
var aiMsg by remember { mutableStateOf("✅ LLM 챗봇 + 전체 메모 RAG 엔진 활성") }
var sQuery by remember { mutableStateOf("") }
var sOut by remember { mutableStateOf<String?>(null) }
var selTab by remember { mutableStateOf("전체") }
var editId by remember { mutableStateOf<Long?>(null) }
var editTxt by remember { mutableStateOf("") }
var editCat by remember { mutableStateOf("일상") }
var editImp by remember { mutableStateOf(false) }

LaunchedEffect(quoted) {
if (!quoted.isNullOrBlank()) {
mode = "MEMO"; if (cats.contains("독서")) mCat = "독서"
input = if (input.isBlank()) quoted else "$input\n\n$quoted"; onClearQuote()
}
}
val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
if (u != null) scope.launch { ai.loadModelUri(u).onSuccess { aiMsg = "✅ $it" }.onFailure { aiMsg = "⚠️ 실패: ${it.message}" } }
}
val tabs = remember(cats) { listOf("전체", "★ 중요") + cats }
val list = remember(notes, selTab, cats) {
val b = when (selTab) {
"전체" -> notes
"★ 중요" -> notes.filter { SMC.isImportant(it) }
else -> notes.filter { SMC.categoryOf(it, cats) == selTab }
}
b.sortedWith(compareByDescending<NoteItem> { SMC.isImportant(it) }.thenByDescending { it.id })
}
fun ins(c: String, t: String) = if (c.isEmpty() || c.endsWith("\n")) c + t else "$c\n$t"
fun nowStr() = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
fun sendChat(txt: String) {
val q = txt.trim(); if (q.isEmpty() || isThinking) return
val prevHistory = chatMsgs
chatMsgs = chatMsgs + ChatMsg(System.currentTimeMillis(), true, q, aiCfg.ragOn)
chatIn = ""; isThinking = true
scope.launch {
val r = buildAiChatReply(q, aiCfg, prevHistory, notes, books, cats, ai)
chatMsgs = chatMsgs + ChatMsg(System.currentTimeMillis() + 1L, false, r, aiCfg.ragOn)
isThinking = false
}
}

Column(Modifier.fillMaxSize().padding(horizontal = ui.hPad, vertical = ui.vPad)) {
Row(FMW, SB, CV) {
Column(Modifier.weight(1f)) {
Text(if (mode == "AI") "✨ AI 챗봇 (LLM & 전체 메모 RAG)" else "메모장 (${notes.size}건 · ★중요 ${notes.count { SMC.isImportant(it) }}건)", fontSize = ui.titleSp, fontWeight = FontWeight.Bold, color = CW)
Text(aiMsg, fontSize = ui.subSp, color = CG)
}
Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
if (mode == "AI") {
OutlinedButton(onClick = { aiPromptCfgOpen = !aiPromptCfgOpen }) {
Text(if (aiPromptCfgOpen) "⚙️ 닫기" else "⚙️ 프롬프트·RAG 설정", fontSize = ui.subSp)
}
} else {
OutlinedButton(onClick = { cfgOpen = !cfgOpen }) {
Text(if (cfgOpen) "⚙️ 닫기" else "⚙️ 카테고리·배율 설정", fontSize = ui.subSp)
}
}
}
}

if (cfgOpen && mode != "AI") {
Spacer(Modifier.height(4.dp))
Surface(color = Color(0xFF14161F), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CG), modifier = FMW.heightIn(max = ui.panelMaxH)) {
Column(Modifier.verticalScroll(rememberScrollState()).padding(ui.cardPad), verticalArrangement = VS6) {
Text("1. 화면 해상도 배율 설정", fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold, color = CW)
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6) {
listOf("AUTO" to "자동(해상도 맞춤)", "COMPACT" to "컴팩트(작게)", "NORMAL" to "표준", "LARGE" to "크게").forEach { (k, l) ->
ChipBtn(l, aiCfg.uiScale == k) { aiCfg = aiCfg.copy(uiScale = k); saveAiCfg(ctx, aiCfg) }
}
}
Text("2. 카테고리표 수정 (이름 탭=변경 / ✕=삭제)", fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold, color = CW)
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6) {
cats.forEach { c ->
Surface(color = CS, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF2B3042))) {
Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
OutlinedTextField(value = renVal, onValueChange = { renVal = it }, label = { Text("'$tg' 변경") }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = {
val nv = renVal.trim()
if (nv.isNotEmpty()) {
val nx = cats.map { if (it == tg) nv else it }.distinct()
cats = nx; SMC.saveMemoCats(ctx, nx)
onSaveNotes(notes.map { n -> n.copy(tags = n.tags.map { if (it == tg) nv else it }) }); renTarget = null
}
}) { Text("저장", fontSize = ui.subSp) }
}
}
Row(FMW, verticalAlignment = CV, horizontalArrangement = HS6) {
OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 카테고리 추가", fontSize = ui.subSp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !cats.contains(t)) { val nx = cats + t; cats = nx; SMC.saveMemoCats(ctx, nx); newCat = "" } }) { Text("+ 추가", fontSize = ui.subSp) }
}
Text("3. 기능버튼 표시 설정", fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold, color = CW)
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6) {
ChipBtn("☑ 체크박스", bCfg.showCheck) { bCfg = bCfg.copy(showCheck = !bCfg.showCheck); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("• 글머리", bCfg.showBullet) { bCfg = bCfg.copy(showBullet = !bCfg.showBullet); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("📅 시간", bCfg.showDate) { bCfg = bCfg.copy(showDate = !bCfg.showDate); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("── 구분선", bCfg.showDivider) { bCfg = bCfg.copy(showDivider = !bCfg.showDivider); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("↵ 줄바꿈", bCfg.showNewline) { bCfg = bCfg.copy(showNewline = !bCfg.showNewline); SMC.saveBtnCfg(ctx, bCfg) }
}
}
}
}

if (mode == "AI") {
Spacer(Modifier.height(4.dp))
Surface(color = Color(0xFF141923), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (aiCfg.ragOn) CG else Color(0xFF2B3042)), modifier = FMW) {
Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), SB, CV) {
Column(Modifier.weight(1f)) {
Text(if (aiCfg.ragOn) "🗂️ 전체 메모 RAG 모드 ON (${if (aiCfg.ragScope == "ALL") "전체 메모 ${notes.size}건 읽기" else "상위 ${aiCfg.ragTopK}건 검색"})" else "💬 일반 LLM AI 챗봇 모드 (RAG OFF)", fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold, color = if (aiCfg.ragOn) CG else CW)
Text(if (aiCfg.ragOn) "전체 메모를 LLM이 읽고 종합 분석해 답변합니다." else "내 메모 없이 설정된 프롬프트로 모든 질문에 답하는 일반 AI 챗봇입니다.", fontSize = ui.subSp, color = CM)
}
Switch(checked = aiCfg.ragOn, onCheckedChange = { aiCfg = aiCfg.copy(ragOn = it); saveAiCfg(ctx, aiCfg) })
}
}

if (aiPromptCfgOpen) {
Spacer(Modifier.height(4.dp))
AiPromptRagPanel(ctx, aiCfg, ui, onUpdate = { aiCfg = it }, onPickModel = { picker.launch(arrayOf("*/*")) })
}

Spacer(Modifier.height(6.dp))
LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = VS8) {
items(chatMsgs, key = { it.id }) { m ->
Row(FMW, horizontalArrangement = if (m.isUser) Arrangement.End else Arrangement.Start) {
Surface(color = if (m.isUser) CG.copy(alpha = 0.22f) else CS, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (m.isUser) CG else Color(0xFF262936)), modifier = Modifier.fillMaxWidth(0.92f)) {
Column(Modifier.padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(4.dp)) {
Row(FMW, SB, CV) {
Text(if (m.isUser) "나" else if (m.usedMemo) "✨ LLM (전체 메모 RAG)" else "🤖 LLM 챗봇", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = if (m.isUser) CG else CA)
if (!m.isUser) {
Text("💾 메모로 저장", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG, modifier = Modifier.clickable {
val cat = SMC.guessCategory(m.text, cats)
onSaveNotes(listOf(NoteItem(System.currentTimeMillis(), m.text, "", listOf(cat, "일반"), nowStr(), "LocalAI")) + notes)
aiMsg = "✅ AI 답변을 [$cat] 메모로 저장했습니다."
})
}
}
Text(m.text, fontSize = ui.bodySp, lineHeight = (ui.bodySp.value + 5).sp, color = CW)
}
}
}
}
if (isThinking) {
item {
Text("🤖 LLM이 답변을 생성하고 있습니다...", fontSize = ui.subSp + 1.sp, color = CG, modifier = Modifier.padding(8.dp))
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
Row(FMW, SB, CV) { Text("🔎 검색 결과", fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8)); Text("닫기 ✕", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { sOut = null }) }
Text(o, fontSize = ui.bodySp, lineHeight = 18.sp, color = CW)
}
}
}
}
items(list, key = { it.id }) { n ->
val cat = SMC.categoryOf(n, cats)
val imp = SMC.isImportant(n)
val ed = editId == n.id
Surface(color = CS, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (ed) CG else if (imp) CA.copy(alpha = 0.65f) else Color(0xFF1F2230)), modifier = FMW) {
if (ed) {
Column(Modifier.padding(ui.cardPad), verticalArrangement = VS6) {
Row(FMW, SB, CV) {
Text("✏️ 화면 내 바로 수정", fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold, color = CG)
Text(if (editImp) "★ 중요(홈 표시)" else "☆ 중요", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = if (editImp) CA else CM, modifier = Modifier.clickable { editImp = !editImp }.padding(4.dp))
}
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6, verticalAlignment = CV) {
Text("분류:", fontSize = ui.subSp, color = CM); cats.forEach { c -> ChipBtn(c, editCat == c) { editCat = c } }
}
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6) {
if (bCfg.showCheck) ChipBtn("☑ 체크박스") { editTxt = ins(editTxt, "☐ ") }
if (bCfg.showBullet) ChipBtn("• 글머리") { editTxt = ins(editTxt, "• ") }
if (bCfg.showDate) ChipBtn("📅 시간") { editTxt += "${nowStr()} " }
if (bCfg.showDivider) ChipBtn("── 구분선") { editTxt = ins(editTxt, "────────────\n") }
if (bCfg.showNewline) ChipBtn("↵ 줄바꿈") { editTxt += "\n" }
}
OutlinedTextField(value = editTxt, onValueChange = { editTxt = it }, singleLine = false, minLines = 3, maxLines = 10, modifier = FMW)
Row(FMW, SB, CV) {
TextButton(onClick = {
val sum = SMC.summarizeByLength(editTxt, false)
onSaveNotes(notes.map { if (it.id == n.id) it.copy(content = editTxt.trim(), summary = sum, tags = listOf(editCat, if (editImp) "중요" else "일반")) else it }); editId = null
}) { Text("✨ 요약갱신·저장", fontSize = ui.subSp, color = CG) }
Row(horizontalArrangement = HS6) {
TextButton(onClick = { editId = null }) { Text("취소", fontSize = ui.subSp + 1.sp, color = CM) }
Button(onClick = {
if (editTxt.isNotBlank()) {
val kept = if (editTxt.lines().size <= 2 && editTxt.length < 75) "" else n.summary
onSaveNotes(notes.map { if (it.id == n.id) it.copy(content = editTxt.trim(), summary = kept, tags = listOf(editCat, if (editImp) "중요" else "일반")) else it }); editId = null
}
}) { Text("저장", fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold) }
}
}
}
} else {
Column(Modifier.padding(ui.cardPad)) {
Row(FMW, SB, CV) {
Row(verticalAlignment = CV, horizontalArrangement = HS6) {
Surface(color = Color(0xFF1E2433), shape = RoundedCornerShape(6.dp)) { Text(cat, fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = CG, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
Text(n.createdAt, fontSize = ui.subSp, color = CD)
}
Text(if (imp) "★ 중요(홈 표시)" else "☆ 중요", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = if (imp) CA else CD, modifier = Modifier.clickable { onSaveNotes(notes.map { if (it.id == n.id) SMC.toggleImportant(it) else it }) }.padding(4.dp))
}
Spacer(Modifier.height(4.dp))
n.content.lines().forEachIndexed { idx, ln ->
val tr = ln.trimStart()
if (tr.startsWith("☐ ") || tr.startsWith("☑ ")) {
val chk = tr.startsWith("☑ ")
Text(ln, fontSize = ui.bodySp, lineHeight = (ui.bodySp.value + 5).sp, color = if (chk) CD else CW, textDecoration = if (chk) TextDecoration.LineThrough else null, modifier = FMW.clickable { onSaveNotes(notes.map { if (it.id == n.id) SMC.toggleCheckLine(it, idx) else it }) }.padding(vertical = 2.dp))
} else Text(ln, fontSize = ui.bodySp, lineHeight = (ui.bodySp.value + 5).sp, color = CW)
}
if (n.summary.isNotBlank()) {
Spacer(Modifier.height(4.dp))
Surface(color = Color(0xFF14261F), shape = RoundedCornerShape(8.dp), modifier = FMW) {
Row(Modifier.padding(8.dp), SB, Alignment.Top) {
Text("✨ 요약:\n${n.summary}", fontSize = ui.subSp, lineHeight = 15.sp, color = Color(0xFFA7F3D0), modifier = Modifier.weight(1f))
Text("✕", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { onSaveNotes(notes.map { if (it.id == n.id) it.copy(summary = "") else it }) }.padding(start = 6.dp))
}
}
}
Row(FMW.padding(top = 4.dp), SB, CV) {
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
Text("✨ 1~3줄 요약", fontSize = ui.subSp, color = CG, modifier = Modifier.clickable {
if (n.content.lines().size <= 2 && n.content.length < 75) aiMsg = "💡 1~2줄 짧은 메모는 요약 없이 원문 그대로 유지됩니다."
else onSaveNotes(notes.map { if (it.id == n.id) it.copy(summary = SMC.summarizeByLength(n.content, true)) else it })
}.padding(4.dp))
Text("💬 AI에게 질문", fontSize = ui.subSp, color = CA, modifier = Modifier.clickable {
mode = "AI"; sendChat("다음 메모 내용을 읽고 핵심 요약과 실행 조언을 알려줘:\n${n.content}")
}.padding(4.dp))
}
Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
TextButton(onClick = { editId = n.id; editTxt = n.content; editCat = cat; editImp = imp }) { Text("수정", fontSize = ui.subSp + 1.sp, color = CG) }
TextButton(onClick = { onSaveNotes(notes.filterNot { it.id == n.id }) }) { Text("삭제", fontSize = ui.subSp + 1.sp, color = Color(0xFFEF4444)) }
}
}
}
}
}
}
}
}
Spacer(Modifier.height(6.dp))
Surface(color = Color(0xFF14161F), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFF2B3042)), modifier = FMW) {
Column(Modifier.padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(5.dp)) {
Row(FMW.background(Color(0xFF0B0C10), RoundedCornerShape(10.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
listOf("MEMO" to "📝 메모", "AI" to "✨ AI 챗봇", "SEARCH" to "🔎 검색").forEach { (mk, lb) ->
val act = mode == mk
Surface(color = if (act) CG else Color.Transparent, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { mode = mk }) {
Text(lb, fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold, color = if (act) Color.Black else CM, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 6.dp))
}
}
}
when (mode) {
"MEMO" -> {
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
Text("분류:", fontSize = ui.subSp, color = CM); (cats + listOf("자동")).forEach { c -> ChipBtn(c, mCat == c) { mCat = c } }
}
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
if (bCfg.showCheck) ChipBtn("☑ 체크리스트") { input = ins(input, "☐ ") }
if (bCfg.showBullet) ChipBtn("• 글머리") { input = ins(input, "• ") }
if (bCfg.showDate) ChipBtn("📅 시간") { input += "${nowStr()} " }
if (bCfg.showDivider) ChipBtn("── 구분선") { input = ins(input, "────────────\n") }
if (bCfg.showNewline) ChipBtn("↵ 줄바꿈") { input += "\n" }
if (bCfg.customLabel.isNotBlank()) ChipBtn(bCfg.customLabel, false, CA) { input = ins(input, bCfg.customText) }
}
OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text("메모 작성 (수동 카테고리 선택 및 ☑ 체크리스트 버튼 활용)", fontSize = ui.subSp + 1.sp) }, singleLine = false, minLines = 2, maxLines = if (ui.isCompact) 3 else 4, modifier = FMW)
Row(FMW, SB, CV) {
Text(if (newImp) "★ 중요 메모(홈 표시)" else "☆ 중요 표시", fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold, color = if (newImp) CA else CM, modifier = Modifier.clickable { newImp = !newImp }.padding(4.dp))
Button(onClick = {
if (input.isBlank()) return@Button
val raw = input.trim()
val cat = if (mCat == "자동" || !cats.contains(mCat)) SMC.guessCategory(raw, cats) else mCat
val sum = if (bCfg.autoSumLong) SMC.summarizeByLength(raw, false) else ""
onSaveNotes(listOf(NoteItem(System.currentTimeMillis(), raw, sum, listOf(cat, if (newImp) "중요" else "일반"), nowStr(), "LocalAI")) + notes)
input = ""; newImp = false
}) { Text("메모 저장", fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold) }
}
}
"AI" -> {
Row(FMW.horizontalScroll(rememberScrollState()), horizontalArrangement = HS6, verticalAlignment = CV) {
ChipBtn("🗂️ 전체 메모 분석", aiCfg.ragOn) {
aiCfg = aiCfg.copy(ragOn = true, ragScope = "ALL"); saveAiCfg(ctx, aiCfg)
sendChat("내 전체 메모들을 카테고리별로 분석하고 핵심 할 일과 요점을 정리해줘")
}
ChipBtn("💬 일반 AI 질문", !aiCfg.ragOn) {
aiCfg = aiCfg.copy(ragOn = false); saveAiCfg(ctx, aiCfg)
}
ChipBtn("⚙️ 프롬프트·RAG 설정", aiPromptCfgOpen, CA) { aiPromptCfgOpen = !aiPromptCfgOpen }
ChipBtn("🗑️ 대화 초기화") { chatMsgs = listOf(ChatMsg(System.currentTimeMillis(), false, "대화가 초기화되었습니다. 무엇이든 물어보세요!", aiCfg.ragOn)) }
}
Row(FMW, verticalAlignment = CV, horizontalArrangement = HS6) {
OutlinedTextField(value = chatIn, onValueChange = { chatIn = it }, placeholder = { Text(if (aiCfg.ragOn) "전체 메모 RAG 연동 중: 내 메모에 대해 질문하세요" else "일반 LLM 챗봇: 무엇이든 자유롭게 질문하세요", fontSize = ui.subSp + 1.sp) }, singleLine = false, minLines = 1, maxLines = 3, modifier = Modifier.weight(1f))
Button(onClick = { sendChat(chatIn) }, enabled = !isThinking) { Text("전송 ↑", fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold) }
}
}
"SEARCH" -> {
OutlinedTextField(value = sQuery, onValueChange = { sQuery = it }, placeholder = { Text("메모 키워드·카테고리 또는 독서대 내용 검색", fontSize = ui.subSp + 1.sp) }, singleLine = true, modifier = FMW)
Row(FMW, SB, CV) {
Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = HS6) {
ChipBtn("★ 중요 메모") {
val imps = notes.filter { SMC.isImportant(it) }
sOut = if (imps.isEmpty()) "중요 표시(★)된 메모가 없습니다." else "📌 중요 메모 ${imps.size}건:\n" + imps.joinToString("\n") { "• [${SMC.categoryOf(it, cats)}] ${it.content.replace("\n", " ").take(65)}" }
}
ChipBtn("☐ 미완료 체크") {
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
}) { Text("🔎 검색", fontSize = ui.subSp + 1.sp, fontWeight = FontWeight.Bold) }
}
}
}
}
}
}
}
