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

@Composable
fun NotesRagView(
notes: List<NoteItem>, todos: List<TodoItem>, books: List<BookItem>, cfg: LauncherConfig,
ai: LocalAiNoteEngine, quoted: String?, onClearQuote: () -> Unit,
onSaveNotes: (List<NoteItem>) -> Unit, onSaveTodos: (List<TodoItem>) -> Unit
) {
val ctx = LocalContext.current
val repo = remember { LauncherRepository(ctx) }
val scope = rememberCoroutineScope()
var cats by remember { mutableStateOf(SMC.loadMemoCats(ctx)) }
var bCfg by remember { mutableStateOf(SMC.loadBtnCfg(ctx)) }
var cfgOpen by remember { mutableStateOf(false) }
var newCat by remember { mutableStateOf("") }
var renTarget by remember { mutableStateOf<String?>(null) }
var renVal by remember { mutableStateOf("") }
var mode by remember { mutableStateOf("MEMO") }
var input by remember { mutableStateOf("") }
var mCat by remember { mutableStateOf(cats.firstOrNull() ?: "일상") }
var newImp by remember { mutableStateOf(false) }

var chatIn by remember { mutableStateOf("") }
var chatWithMemos by remember { mutableStateOf(true) }
var chatMsgs by remember {
mutableStateOf(listOf(ChatMsg(1L, false, "안녕하세요! AI 채팅 탭입니다. 상단의 [🔗 내 메모와 상호작용 채팅]을 켜두면 내 메모들을 바탕으로 대화·요약·일정 브리핑을 받을 수 있고, 끄면 일반 AI 대화를 나눌 수 있습니다.", true)))
}
var aiMsg by remember { mutableStateOf("✅ 온디바이스 AI 채팅·요약 엔진 정상 작동 중") }
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
val q = txt.trim(); if (q.isEmpty()) return
chatMsgs = chatMsgs + ChatMsg(System.currentTimeMillis(), true, q, chatWithMemos)
chatIn = ""
scope.launch {
val r = buildAiChatReply(q, chatWithMemos, notes, books, cats, ai)
chatMsgs = chatMsgs + ChatMsg(System.currentTimeMillis() + 1L, false, r, chatWithMemos)
}
}

Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Column(Modifier.weight(1f)) {
Text(if (mode == "AI") "✨ AI 채팅 (내 메모 상호작용 지원)" else "메모장 (${notes.size}건 · ★중요 ${notes.count { SMC.isImportant(it) }}건)", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = CW)
Text(aiMsg, fontSize = 10.sp, color = CG)
}
OutlinedButton(onClick = { cfgOpen = !cfgOpen }) {
Text(if (cfgOpen) "⚙️ 닫기" else "⚙️ 카테고리·버튼 설정", fontSize = 11.sp)
}
}
if (cfgOpen) {
Spacer(Modifier.height(6.dp))
Surface(color = Color(0xFF14161F), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CG), modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("1. 카테고리표 수정 (이름 탭=변경 / ✕=삭제)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
cats.forEach { c ->
Surface(color = CS, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF2B3042))) {
Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
Text("✏️ $c", fontSize = 11.sp, color = CW, modifier = Modifier.clickable { renTarget = c; renVal = c })
if (cats.size > 1) Text("✕", fontSize = 10.sp, color = Color(0xFFEF4444), modifier = Modifier.clickable {
val nx = cats.filterNot { it == c }; cats = nx; SMC.saveMemoCats(ctx, nx); if (mCat == c) mCat = nx.first()
})
}
}
}
}
renTarget?.let { tg ->
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = renVal, onValueChange = { renVal = it }, label = { Text("'$tg' 변경") }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = {
val nv = renVal.trim()
if (nv.isNotEmpty()) {
val nx = cats.map { if (it == tg) nv else it }.distinct()
cats = nx; SMC.saveMemoCats(ctx, nx)
onSaveNotes(notes.map { n -> n.copy(tags = n.tags.map { if (it == tg) nv else it }) }); renTarget = null
}
}) { Text("저장", fontSize = 11.sp) }
}
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 카테고리 추가", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !cats.contains(t)) { val nx = cats + t; cats = nx; SMC.saveMemoCats(ctx, nx); newCat = "" } }) { Text("+ 추가", fontSize = 11.sp) }
}
Text("2. 기능버튼 표시 설정", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("☑ 체크박스", bCfg.showCheck) { bCfg = bCfg.copy(showCheck = !bCfg.showCheck); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("• 글머리", bCfg.showBullet) { bCfg = bCfg.copy(showBullet = !bCfg.showBullet); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("📅 시간", bCfg.showDate) { bCfg = bCfg.copy(showDate = !bCfg.showDate); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("── 구분선", bCfg.showDivider) { bCfg = bCfg.copy(showDivider = !bCfg.showDivider); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("↵ 줄바꿈", bCfg.showNewline) { bCfg = bCfg.copy(showNewline = !bCfg.showNewline); SMC.saveBtnCfg(ctx, bCfg) }
}
Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
OutlinedButton(onClick = { if (SMC.exportBackup(ctx, notes)) aiMsg = "✅ 클립보드에 백업 완료" }, modifier = Modifier.weight(1f)) { Text("📋 백업 복사", fontSize = 11.sp) }
OutlinedButton(onClick = { SMC.importBackup(ctx, repo)?.let { r -> onSaveNotes(r); cats = SMC.loadMemoCats(ctx); aiMsg = "✅ ${r.size}건 복원 완료" } }, modifier = Modifier.weight(1f)) { Text("📥 백업 복원", fontSize = 11.sp) }
}
}
}
}
if (mode == "AI") {
Spacer(Modifier.height(6.dp))
Surface(color = Color(0xFF141923), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (chatWithMemos) CG else Color(0xFF2B3042)), modifier = Modifier.fillMaxWidth()) {
Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), SB, CV) {
Column(Modifier.weight(1f)) {
Text(if (chatWithMemos) "🔗 내 메모와 상호작용 채팅 ON (${notes.size}건 연동)" else "💬 일반 AI 채팅 모드 (내 메모 연동 OFF)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (chatWithMemos) CG else CW)
Text(if (chatWithMemos) "내 메모·체크리스트·독서대 내용을 바탕으로 대화합니다." else "내 메모를 참조하지 않고 일반 AI 대화를 나눌 수 있습니다.", fontSize = 10.sp, color = CM)
}
Switch(checked = chatWithMemos, onCheckedChange = { chatWithMemos = it })
}
}
Spacer(Modifier.height(6.dp))
LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
items(chatMsgs, key = { it.id }) { m ->
Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.isUser) Arrangement.End else Arrangement.Start) {
Surface(color = if (m.isUser) CG.copy(alpha = 0.22f) else CS, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (m.isUser) CG else Color(0xFF262936)), modifier = Modifier.fillMaxWidth(0.9f)) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text(if (m.isUser) "나" else if (m.usedMemo) "✨ AI (내 메모 연동)" else "✨ AI (일반 채팅)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (m.isUser) CG else CA)
if (!m.isUser) {
Text("💾 메모로 저장", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CG, modifier = Modifier.clickable {
val cat = SMC.guessCategory(m.text, cats)
onSaveNotes(listOf(NoteItem(System.currentTimeMillis(), m.text, "", listOf(cat, "일반"), nowStr(), "LocalAI")) + notes)
aiMsg = "✅ AI 답변을 [$cat] 메모로 저장했습니다."
})
}
}
Text(m.text, fontSize = 13.sp, lineHeight = 19.sp, color = CW)
}
}
}
}
}
} else {
Spacer(Modifier.height(6.dp))
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
tabs.forEach { c -> ChipBtn(c, selTab == c) { selTab = c } }
}
Spacer(Modifier.height(8.dp))
LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
sOut?.let { o ->
item {
Surface(color = Color(0xFF14202E), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Color(0xFF38BDF8)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(12.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) { Text("🔎 검색 결과", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8)); Text("닫기 ✕", fontSize = 11.sp, color = CM, modifier = Modifier.clickable { sOut = null }) }
Text(o, fontSize = 12.sp, lineHeight = 18.sp, color = CW)
}
}
}
}
items(list, key = { it.id }) { n ->
val cat = SMC.categoryOf(n, cats)
val imp = SMC.isImportant(n)
val ed = editId == n.id
Surface(color = CS, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (ed) CG else if (imp) CA.copy(alpha = 0.65f) else Color(0xFF1F2230)), modifier = Modifier.fillMaxWidth()) {
if (ed) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("✏️ 화면 내 바로 수정", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CG)
Text(if (editImp) "★ 중요(홈 표시)" else "☆ 중요", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (editImp) CA else CM, modifier = Modifier.clickable { editImp = !editImp }.padding(4.dp))
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
Text("분류:", fontSize = 11.sp, color = CM); cats.forEach { c -> ChipBtn(c, editCat == c) { editCat = c } }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
if (bCfg.showCheck) ChipBtn("☑ 체크박스") { editTxt = ins(editTxt, "☐ ") }
if (bCfg.showBullet) ChipBtn("• 글머리") { editTxt = ins(editTxt, "• ") }
if (bCfg.showDate) ChipBtn("📅 시간") { editTxt += "${nowStr()} " }
if (bCfg.showDivider) ChipBtn("── 구분선") { editTxt = ins(editTxt, "────────────\n") }
if (bCfg.showNewline) ChipBtn("↵ 줄바꿈") { editTxt += "\n" }
}
OutlinedTextField(value = editTxt, onValueChange = { editTxt = it }, singleLine = false, minLines = 3, maxLines = 12, modifier = Modifier.fillMaxWidth())
Row(Modifier.fillMaxWidth(), SB, CV) {
TextButton(onClick = {
val sum = SMC.summarizeByLength(editTxt, false)
onSaveNotes(notes.map { if (it.id == n.id) it.copy(content = editTxt.trim(), summary = sum, tags = listOf(editCat, if (editImp) "중요" else "일반")) else it }); editId = null
}) { Text("✨ 요약갱신·저장", fontSize = 11.sp, color = CG) }
Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
TextButton(onClick = { editId = null }) { Text("취소", fontSize = 12.sp, color = CM) }
Button(onClick = {
if (editTxt.isNotBlank()) {
val kept = if (editTxt.lines().size <= 2 && editTxt.length < 75) "" else n.summary
onSaveNotes(notes.map { if (it.id == n.id) it.copy(content = editTxt.trim(), summary = kept, tags = listOf(editCat, if (editImp) "중요" else "일반")) else it }); editId = null
}
}) { Text("저장", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}
}
}
} else {
Column(Modifier.padding(12.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Row(verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
Surface(color = Color(0xFF1E2433), shape = RoundedCornerShape(6.dp)) { Text(cat, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CG, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
Text(n.createdAt, fontSize = 11.sp, color = CD)
}
Text(if (imp) "★ 중요(홈 표시)" else "☆ 중요", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (imp) CA else CD, modifier = Modifier.clickable { onSaveNotes(notes.map { if (it.id == n.id) SMC.toggleImportant(it) else it }) }.padding(4.dp))
}
Spacer(Modifier.height(6.dp))
n.content.lines().forEachIndexed { idx, ln ->
val tr = ln.trimStart()
if (tr.startsWith("☐ ") || tr.startsWith("☑ ")) {
val chk = tr.startsWith("☑ ")
Text(ln, fontSize = 14.sp, lineHeight = 20.sp, color = if (chk) CD else CW, textDecoration = if (chk) TextDecoration.LineThrough else null, modifier = Modifier.fillMaxWidth().clickable { onSaveNotes(notes.map { if (it.id == n.id) SMC.toggleCheckLine(it, idx) else it }) }.padding(vertical = 2.dp))
} else Text(ln, fontSize = 14.sp, lineHeight = 20.sp, color = CW)
}
if (n.summary.isNotBlank()) {
Spacer(Modifier.height(6.dp))
Surface(color = Color(0xFF14261F), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
Row(Modifier.padding(8.dp), SB, Alignment.Top) {
Text("✨ 요약:\n${n.summary}", fontSize = 11.sp, lineHeight = 16.sp, color = Color(0xFFA7F3D0), modifier = Modifier.weight(1f))
Text("✕", fontSize = 10.sp, color = CM, modifier = Modifier.clickable { onSaveNotes(notes.map { if (it.id == n.id) it.copy(summary = "") else it }) }.padding(start = 6.dp))
}
}
}
Row(Modifier.fillMaxWidth().padding(top = 4.dp), SB, CV) {
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
Text("✨ 1~3줄 요약", fontSize = 11.sp, color = CG, modifier = Modifier.clickable {
if (n.content.lines().size <= 2 && n.content.length < 75) aiMsg = "💡 1~2줄 짧은 메모는 요약 없이 원문 그대로 유지됩니다."
else onSaveNotes(notes.map { if (it.id == n.id) it.copy(summary = SMC.summarizeByLength(n.content, true)) else it })
}.padding(4.dp))
Text("💬 이 메모로 AI채팅", fontSize = 11.sp, color = CA, modifier = Modifier.clickable {
chatWithMemos = true; mode = "AI"; sendChat("다음 내 메모를 분석하고 핵심 요약해줘: ${n.content}")
}.padding(4.dp))
}
Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
TextButton(onClick = { editId = n.id; editTxt = n.content; editCat = cat; editImp = imp }) { Text("수정", fontSize = 12.sp, color = CG) }
TextButton(onClick = { onSaveNotes(notes.filterNot { it.id == n.id }) }) { Text("삭제", fontSize = 12.sp, color = Color(0xFFEF4444)) }
}
}
}
}
}
}
}
}
Spacer(Modifier.height(8.dp))
Surface(color = Color(0xFF14161F), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFF2B3042)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Row(Modifier.fillMaxWidth().background(Color(0xFF0B0C10), RoundedCornerShape(10.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
listOf("MEMO" to "📝 메모", "AI" to "✨ AI 채팅", "SEARCH" to "🔎 검색").forEach { (mk, lb) ->
val act = mode == mk
Surface(color = if (act) CG else Color.Transparent, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { mode = mk }) {
Text(lb, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (act) Color.Black else CM, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 7.dp))
}
}
}
when (mode) {
"MEMO" -> {
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
Text("분류:", fontSize = 11.sp, color = CM); (cats + listOf("자동")).forEach { c -> ChipBtn(c, mCat == c) { mCat = c } }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
if (bCfg.showCheck) ChipBtn("☑ 체크리스트") { input = ins(input, "☐ ") }
if (bCfg.showBullet) ChipBtn("• 글머리") { input = ins(input, "• ") }
if (bCfg.showDate) ChipBtn("📅 시간") { input += "${nowStr()} " }
if (bCfg.showDivider) ChipBtn("── 구분선") { input = ins(input, "────────────\n") }
if (bCfg.showNewline) ChipBtn("↵ 줄바꿈") { input += "\n" }
if (bCfg.customLabel.isNotBlank()) ChipBtn(bCfg.customLabel, false, CA) { input = ins(input, bCfg.customText) }
}
OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text("메모 작성 (수동 카테고리 선택 및 ☑ 체크리스트 버튼 활용)", fontSize = 12.sp) }, singleLine = false, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
Row(Modifier.fillMaxWidth(), SB, CV) {
Text(if (newImp) "★ 중요 메모(홈 표시)" else "☆ 중요 표시", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (newImp) CA else CM, modifier = Modifier.clickable { newImp = !newImp }.padding(4.dp))
Button(onClick = {
if (input.isBlank()) return@Button
val raw = input.trim()
val cat = if (mCat == "자동" || !cats.contains(mCat)) SMC.guessCategory(raw, cats) else mCat
val sum = if (bCfg.autoSumLong) SMC.summarizeByLength(raw, false) else ""
onSaveNotes(listOf(NoteItem(System.currentTimeMillis(), raw, sum, listOf(cat, if (newImp) "중요" else "일반"), nowStr(), "LocalAI")) + notes)
input = ""; newImp = false
}) { Text("메모 저장", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}
}
"AI" -> {
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn("📌 내 메모 전체 브리핑") { sendChat("내 메모 전체 핵심 브리핑하고 요약해줘") }
ChipBtn("☑ 미완료 할 일 확인") { sendChat("내 메모에서 아직 안 끝난 할 일과 일정 알려줘") }
ChipBtn("✨ 작성 중 메모 요약") { if (input.isNotBlank()) sendChat(input) else sendChat("최근 메모 핵심만 요약해줘") }
ChipBtn("🗑️ 대화 비우기") { chatMsgs = listOf(ChatMsg(System.currentTimeMillis(), false, "대화가 초기화되었습니다. 무엇을 도와드릴까요?", chatWithMemos)) }
ChipBtn("📂 모델") { picker.launch(arrayOf("*/*")) }
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = chatIn, onValueChange = { chatIn = it }, placeholder = { Text(if (chatWithMemos) "내 메모와 상호작용하여 질문·요약 요청하기" else "일반 AI에게 질문하거나 요약할 글 입력하기", fontSize = 12.sp) }, singleLine = false, minLines = 1, maxLines = 3, modifier = Modifier.weight(1f))
Button(onClick = { sendChat(chatIn) }) { Text("전송 ↑", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}
}
"SEARCH" -> {
OutlinedTextField(value = sQuery, onValueChange = { sQuery = it }, placeholder = { Text("메모 키워드·카테고리 또는 독서대 내용 검색", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
Row(Modifier.fillMaxWidth(), SB, CV) {
Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
}) { Text("🔎 검색", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}
}
}
}
}
}
}
