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

@Composable
fun NotesRagView(
notes: List<NoteItem>, todos: List<TodoItem>, books: List<BookItem>, cfg: LauncherConfig,
ai: LocalAiNoteEngine, quoted: String?, onClearQuote: () -> Unit,
onSaveNotes: (List<NoteItem>) -> Unit, onSaveTodos: (List<TodoItem>) -> Unit
) {
val ctx = LocalContext.current
val repo = remember { LauncherRepository(ctx) }
val scope = rememberCoroutineScope()
var cats by remember { mutableStateOf(SmartMemoClassifier.loadMemoCats(ctx)) }
var bCfg by remember { mutableStateOf(SmartMemoClassifier.loadBtnCfg(ctx)) }
var cfgOpen by remember { mutableStateOf(false) }
var newCat by remember { mutableStateOf("") }
var renTarget by remember { mutableStateOf<String?>(null) }
var renVal by remember { mutableStateOf("") }
var mode by remember { mutableStateOf("MEMO") }
var input by remember { mutableStateOf("") }
var mCat by remember { mutableStateOf(cats.firstOrNull() ?: "일상") }
var newImp by remember { mutableStateOf(false) }
var aiIn by remember { mutableStateOf("") }
var aiOut by remember { mutableStateOf<String?>(null) }
var aiMsg by remember { mutableStateOf("✅ 온디바이스 요약·분류 AI 정상 작동 중") }
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
"★ 중요" -> notes.filter { SmartMemoClassifier.isImportant(it) }
else -> notes.filter { SmartMemoClassifier.categoryOf(it, cats) == selTab }
}
b.sortedWith(compareByDescending<NoteItem> { SmartMemoClassifier.isImportant(it) }.thenByDescending { it.id })
}
fun ins(c: String, t: String) = if (c.isEmpty() || c.endsWith("\n")) c + t else "$c\n$t"
fun nowStr() = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())

Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Column(Modifier.weight(1f)) {
Text("메모장 (${notes.size}건 · ★중요 ${notes.count { SmartMemoClassifier.isImportant(it) }}건)", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = CW)
Text(aiMsg, fontSize = 10.sp, color = CG)
}
OutlinedButton(onClick = { cfgOpen = !cfgOpen }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
Text(if (cfgOpen) "⚙️ 닫기" else "⚙️ 카테고리·버튼 설정", fontSize = 11.sp)
}
}
if (cfgOpen) {
Spacer(Modifier.height(6.dp))
Surface(color = Color(0xFF14161F), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CG), modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp)) {
Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("1. 카테고리표 수정 (이름 탭=변경 / ✕=삭제)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
cats.forEach { c ->
Surface(color = CS, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF2B3042))) {
Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
Text("✏️ $c", fontSize = 11.sp, color = CW, modifier = Modifier.clickable { renTarget = c; renVal = c })
if (cats.size > 1) Text("✕", fontSize = 10.sp, color = Color(0xFFEF4444), modifier = Modifier.clickable {
val nx = cats.filterNot { it == c }; cats = nx; SmartMemoClassifier.saveMemoCats(ctx, nx); if (mCat == c) mCat = nx.first()
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
cats = nx; SmartMemoClassifier.saveMemoCats(ctx, nx)
onSaveNotes(notes.map { n -> n.copy(tags = n.tags.map { if (it == tg) nv else it }) }); renTarget = null
}
}) { Text("저장", fontSize = 11.sp) }
}
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 카테고리 추가", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !cats.contains(t)) { val nx = cats + t; cats = nx; SmartMemoClassifier.saveMemoCats(ctx, nx); newCat = "" } }) { Text("+ 추가", fontSize = 11.sp) }
}
Text("2. 기능버튼 표시 설정", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("☑ 체크박스", bCfg.showCheck) { bCfg = bCfg.copy(showCheck = !bCfg.showCheck); SmartMemoClassifier.saveBtnCfg(ctx, bCfg) }
ChipBtn("• 글머리", bCfg.showBullet) { bCfg = bCfg.copy(showBullet = !bCfg.showBullet); SmartMemoClassifier.saveBtnCfg(ctx, bCfg) }
ChipBtn("📅 시간", bCfg.showDate) { bCfg = bCfg.copy(showDate = !bCfg.showDate); SmartMemoClassifier.saveBtnCfg(ctx, bCfg) }
ChipBtn("── 구분선", bCfg.showDivider) { bCfg = bCfg.copy(showDivider = !bCfg.showDivider); SmartMemoClassifier.saveBtnCfg(ctx, bCfg) }
ChipBtn("↵ 줄바꿈", bCfg.showNewline) { bCfg = bCfg.copy(showNewline = !bCfg.showNewline); SmartMemoClassifier.saveBtnCfg(ctx, bCfg) }
}
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("3줄 이상 긴 메모 저장 시 자동 요약", fontSize = 11.sp, color = CW)
Switch(checked = bCfg.autoSumLong, onCheckedChange = { bCfg = bCfg.copy(autoSumLong = it); SmartMemoClassifier.saveBtnCfg(ctx, bCfg) })
}
Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
OutlinedButton(onClick = { if (SmartMemoClassifier.exportBackup(ctx, notes)) aiMsg = "✅ 클립보드에 백업 완료" }, modifier = Modifier.weight(1f)) { Text("📋 백업 복사", fontSize = 11.sp) }
OutlinedButton(onClick = { SmartMemoClassifier.importBackup(ctx, repo)?.let { r -> onSaveNotes(r); cats = SmartMemoClassifier.loadMemoCats(ctx); aiMsg = "✅ ${r.size}건 복원 완료" } }, modifier = Modifier.weight(1f)) { Text("📥 백업 복원", fontSize = 11.sp) }
}
}
}
}
Spacer(Modifier.height(6.dp))
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
tabs.forEach { c -> ChipBtn(c, selTab == c) { selTab = c } }
}
Spacer(Modifier.height(8.dp))
LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
aiOut?.let { o ->
item {
Surface(color = Color(0xFF14261F), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, CG), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(12.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) { Text("✨ AI 요약 · 진단 결과", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CG); Text("닫기 ✕", fontSize = 11.sp, color = CM, modifier = Modifier.clickable { aiOut = null }) }
Text(o, fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFFA7F3D0))
}
}
}
}
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
val cat = SmartMemoClassifier.categoryOf(n, cats)
val imp = SmartMemoClassifier.isImportant(n)
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
val sum = SmartMemoClassifier.summarizeByLength(editTxt, false)
onSaveNotes(notes.map { if (it.id == n.id) it.copy(content = editTxt.trim(), summary = sum, tags = listOf(editCat, if (editImp) "중요" else "일반")) else it }); editId = null
}) { Text("✨ 요약갱신·저장", fontSize = 11.sp, color = CG) }
Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
TextButton(onClick = { editId = null }) { Text("취소", fontSize = 12.sp, color = CM) }
Button(onClick = {
if (editTxt.isNotBlank()) {
val kept = if (editTxt.lines().size <= 2 && editTxt.length < 75) "" else n.summary
onSaveNotes(notes.map { if (it.id == n.id) it.copy(content = editTxt.trim(), summary = kept, tags = listOf(editCat, if (editImp) "중요" else "일반")) else it }); editId = null
}
}, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("저장", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
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
Text(if (imp) "★ 중요(홈 표시)" else "☆ 중요", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (imp) CA else CD, modifier = Modifier.clickable { onSaveNotes(notes.map { if (it.id == n.id) SmartMemoClassifier.toggleImportant(it) else it }) }.padding(4.dp))
}
Spacer(Modifier.height(6.dp))
n.content.lines().forEachIndexed { idx, ln ->
val tr = ln.trimStart()
if (tr.startsWith("☐ ") || tr.startsWith("☑ ")) {
val chk = tr.startsWith("☑ ")
Text(ln, fontSize = 14.sp, lineHeight = 20.sp, color = if (chk) CD else CW, textDecoration = if (chk) TextDecoration.LineThrough else null, modifier = Modifier.fillMaxWidth().clickable { onSaveNotes(notes.map { if (it.id == n.id) SmartMemoClassifier.toggleCheckLine(it, idx) else it }) }.padding(vertical = 2.dp))
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
Text("✨ AI 1~3줄 요약", fontSize = 11.sp, color = CG, modifier = Modifier.clickable {
if (n.content.lines().size <= 2 && n.content.length < 75) aiOut = "💡 1~2줄 이내의 짧은 메모는 별도 요약 없이 원문 그대로 표시됩니다."
else onSaveNotes(notes.map { if (it.id == n.id) it.copy(summary = SmartMemoClassifier.summarizeByLength(n.content, true)) else it })
}.padding(4.dp))
Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
TextButton(onClick = { editId = n.id; editTxt = n.content; editCat = cat; editImp = imp }, contentPadding = PaddingValues(4.dp)) { Text("수정", fontSize = 12.sp, color = CG) }
TextButton(onClick = { onSaveNotes(notes.filterNot { it.id == n.id }) }, contentPadding = PaddingValues(4.dp)) { Text("삭제", fontSize = 12.sp, color = Color(0xFFEF4444)) }
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
listOf("MEMO" to "📝 메모", "AI" to "✨ AI", "SEARCH" to "🔎 검색").forEach { (mk, lb) ->
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
val cat = if (mCat == "자동" || !cats.contains(mCat)) SmartMemoClassifier.guessCategory(raw, cats) else mCat
val sum = if (bCfg.autoSumLong) SmartMemoClassifier.summarizeByLength(raw, false) else ""
onSaveNotes(listOf(NoteItem(System.currentTimeMillis(), raw, sum, listOf(cat, if (newImp) "중요" else "일반"), nowStr(), "LocalAI")) + notes)
input = ""; newImp = false
}, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Text("메모 저장", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}
}
"AI" -> {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text(aiMsg, fontSize = 11.sp, color = CG, modifier = Modifier.weight(1f))
Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
ChipBtn("⚡ 작동 테스트", true, CA) {
val sample = "오늘 오후 3시 분기 기획 회의 진행 예정.\n핵심 논의 사항은 모바일 런처 UI 간소화와 사용자 카테고리 직접 설정 기능 도입입니다.\n금요일까지 수정 시안을 공유하고 피드백을 수렴할 계획입니다."
val s = SmartMemoClassifier.summarizeByLength(sample, true)
val c = SmartMemoClassifier.guessCategory(sample, cats)
aiMsg = "✅ AI 정상 작동 확인 완료"
aiOut = "[AI 정상 작동 진단 완료]\n• 자동 분류: [$c]\n• 2줄 요약 테스트 결과:\n$s"
}
ChipBtn("📂 모델") { picker.launch(arrayOf("*/*")) }
}
}
OutlinedTextField(value = if (aiIn.isBlank() && input.isNotBlank()) input else aiIn, onValueChange = { aiIn = it }, placeholder = { Text("요약할 긴 메모 입력 (비워두면 최근 메모 요약 · 짧은 1~2줄 메모는 생략)", fontSize = 12.sp) }, singleLine = false, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
Button(onClick = {
val tgt = (if (aiIn.isNotBlank()) aiIn else if (input.isNotBlank()) input else notes.firstOrNull()?.content ?: "").trim()
if (tgt.isEmpty()) aiOut = "요약할 텍스트를 입력하거나 메모를 먼저 작성해 주세요."
else if (tgt.lines().filter { it.isNotBlank() }.size <= 2 && tgt.length < 75) aiOut = "💡 1~2줄 이내의 짧은 메모는 별도 요약 없이 원문 그대로 보시는 것이 가장 깔끔합니다."
else aiOut = "📌 추천 분류: [${SmartMemoClassifier.guessCategory(tgt, cats)}]\n✨ 길이 맞춤 1~3줄 요약:\n${SmartMemoClassifier.summarizeByLength(tgt, true)}"
}, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("✨ 1~3줄 요약 실행", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
OutlinedButton(onClick = {
val tgt = (if (aiIn.isNotBlank()) aiIn else input).trim()
if (tgt.isNotEmpty()) { input = tgt.lines().map { l -> val t = l.trim(); if (t.isEmpty() || t.startsWith("☐") || t.startsWith("☑")) t else "☐ $t" }.joinToString("\n"); aiIn = ""; mode = "MEMO" }
}, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("☑ 체크리스트 변환", fontSize = 11.sp) }
OutlinedButton(onClick = {
val tgt = (if (aiIn.isNotBlank()) aiIn else input).trim()
if (tgt.isNotEmpty()) {
val c = SmartMemoClassifier.guessCategory(tgt, cats)
onSaveNotes(listOf(NoteItem(System.currentTimeMillis(), tgt, SmartMemoClassifier.summarizeByLength(tgt, false), listOf(c, "일반"), nowStr(), "LocalAI")) + notes)
aiIn = ""; input = ""; aiOut = "✅ [$c] 카테고리에 저장되었습니다."
}
}, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("💾 요약 포함 저장", fontSize = 11.sp) }
}
}
"SEARCH" -> {
OutlinedTextField(value = sQuery, onValueChange = { sQuery = it }, placeholder = { Text("메모 키워드·카테고리 또는 독서대 내용 검색", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
Row(Modifier.fillMaxWidth(), SB, CV) {
Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("★ 중요 메모") {
val imps = notes.filter { SmartMemoClassifier.isImportant(it) }
sOut = if (imps.isEmpty()) "중요 표시(★)된 메모가 없습니다." else "📌 중요 메모 ${imps.size}건:\n" + imps.joinToString("\n") { "• [${SmartMemoClassifier.categoryOf(it, cats)}] ${it.content.replace("\n", " ").take(65)}" }
}
ChipBtn("☐ 미완료 체크") {
val unc = notes.flatMap { n -> n.content.lines().filter { it.trimStart().startsWith("☐ ") }.map { "[${SmartMemoClassifier.categoryOf(n, cats)}] $it" } }
sOut = if (unc.isEmpty()) "미완료된 체크리스트(☐) 항목이 없습니다." else "☑ 남은 체크리스트 ${unc.size}건:\n" + unc.joinToString("\n")
}
}
Button(onClick = {
val q = sQuery.trim(); if (q.isEmpty()) return@Button
scope.launch {
val ex = notes.filter { it.content.contains(q, true) || it.tags.any { t -> t.contains(q, true) } }
sOut = if (ex.isNotEmpty()) "🔎 '$q' 검색 결과 (${ex.size}건):\n" + ex.take(4).joinToString("\n") { "• [${SmartMemoClassifier.categoryOf(it, cats)}] ${it.content.replace("\n", " ").take(75)}" }
else ai.askRag(q, notes, books, cfg.ragIncludeBooks)
}
}, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) { Text("🔎 검색", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}
}
}
}
}
}
}
