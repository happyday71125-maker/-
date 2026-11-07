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
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

@Composable
fun ReaderView(
books: List<BookItem>, cfg: LauncherConfig, repo: LauncherRepository,
onSaveBooks: (List<BookItem>) -> Unit, onCfg: (LauncherConfig) -> Unit,
onQuote: (String) -> Unit, onHome: () -> Unit
) {
var selId by remember { mutableStateOf(books.firstOrNull()?.id ?: 1001L) }
var shelfMode by remember { mutableStateOf(false) }
var editBook by remember { mutableStateOf<BookItem?>(null) }
val active = books.find { it.id == selId } ?: books.firstOrNull()
val scope = rememberCoroutineScope()
val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
if (uri != null) scope.launch(Dispatchers.IO) { repo.importBookFromUri(uri)?.let { b -> withContext(Dispatchers.Main) { onSaveBooks(listOf(b) + books); selId = b.id; shelfMode = false } } }
}
val bg = when (cfg.readerTheme) { "SEPIA" -> Color(0xFFF4ECD8); "PAPER" -> Color(0xFFFAF8F5); else -> Color(0xFF0A0B0E) }
val fg = when (cfg.readerTheme) { "SEPIA" -> Color(0xFF2C221E); "PAPER" -> Color(0xFF1C1917); else -> Color(0xFFE5E7EB) }
val mut = when (cfg.readerTheme) { "SEPIA" -> Color(0xFF6E5A4F); "PAPER" -> Color(0xFF78716C); else -> CM }
Column(Modifier.fillMaxSize().background(bg).padding(20.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
TextButton(onClick = { shelfMode = !shelfMode }) { Text(if (shelfMode) "📖 본문 보기" else "📚 서재 목록/삭제 (${books.size})", color = fg, fontWeight = FontWeight.Bold) }
Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("📂 파일 추가 (.pdf/.txt/.epub)", fontSize = 11.sp, color = fg) }
TextButton(onClick = onHome) { Text("홈 →", fontSize = 12.sp, color = mut) }
}
}
Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), SB, CV) {
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
listOf("SEPIA" to "양피지", "PAPER" to "종이", "NIGHT" to "야간").forEach { (k, l) ->
Text(l, fontSize = 11.sp, color = if (cfg.readerTheme == k) CG else mut, modifier = Modifier.clickable { onCfg(cfg.copy(readerTheme = k)) }.padding(4.dp))
}
}
Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
Text(if (cfg.readerUseSerif) "명조체" else "고딕체", fontSize = 11.sp, color = fg, modifier = Modifier.clickable { onCfg(cfg.copy(readerUseSerif = !cfg.readerUseSerif)) }.padding(4.dp))
Text("가-", fontSize = 12.sp, color = fg, modifier = Modifier.clickable { onCfg(cfg.copy(readerFontSizeSp = (cfg.readerFontSizeSp - 2).coerceAtLeast(14))) }.padding(4.dp))
Text("${cfg.readerFontSizeSp}pt", fontSize = 11.sp, color = mut)
Text("가+", fontSize = 12.sp, color = fg, modifier = Modifier.clickable { onCfg(cfg.copy(readerFontSizeSp = (cfg.readerFontSizeSp + 2).coerceAtMost(28))) }.padding(4.dp))
}
}
if (shelfMode || active == null) {
if (books.isEmpty()) {
Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
Text("서재에 등록된 책이나 문서가 없습니다.", fontSize = 14.sp, color = mut)
Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("📂 새 책/문서 파일 열기 (.pdf/.txt/.epub)") }
}
} else {
LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
items(books, key = { it.id }) { b ->
Surface(color = mut.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable { selId = b.id; shelfMode = false }) {
Row(Modifier.padding(14.dp).fillMaxWidth(), SB, CV) {
Column(Modifier.weight(1f)) {
Text("[${b.category}] ${b.title}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = fg)
Text("${b.author} · ${b.currentPage + 1}/${b.pages.size}쪽", fontSize = 12.sp, color = mut)
}
Row {
TextButton(onClick = { editBook = b }, contentPadding = PaddingValues(4.dp)) { Text("수정", fontSize = 12.sp, color = CG) }
TextButton(onClick = {
val next = books.filterNot { it.id == b.id }
onSaveBooks(next)
if (selId == b.id) selId = next.firstOrNull()?.id ?: 0L
}, contentPadding = PaddingValues(4.dp)) { Text("🗑️ 삭제", fontSize = 12.sp, color = Color(0xFFEF4444)) }
}
}
}
}
}
}
} else {
val idx = active.currentPage.coerceIn(0, (active.pages.size - 1).coerceAtLeast(0))
val content = active.pages.getOrElse(idx) { "" }
Row(Modifier.fillMaxWidth().padding(top = 6.dp), SB, CV) {
Text("${active.title} · ${active.author}", fontSize = 12.sp, color = mut, modifier = Modifier.weight(1f))
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
Text("✏️ 제목수정", fontSize = 11.sp, color = CG, modifier = Modifier.clickable { editBook = active }.padding(4.dp))
Text("🗑️ 이 책 삭제", fontSize = 11.sp, color = Color(0xFFEF4444), modifier = Modifier.clickable {
val next = books.filterNot { it.id == active.id }
onSaveBooks(next)
selId = next.firstOrNull()?.id ?: 0L
shelfMode = true
}.padding(4.dp))
}
}
Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
Text(content, fontSize = cfg.readerFontSizeSp.sp, lineHeight = (cfg.readerFontSizeSp * 1.65f).sp, fontFamily = if (cfg.readerUseSerif) FontFamily.Serif else FontFamily.SansSerif, color = fg)
}
Row(Modifier.fillMaxWidth(), SB, CV) {
TextButton(onClick = { if (idx > 0) onSaveBooks(books.map { if (it.id == active.id) it.copy(currentPage = idx - 1) else it }) }, enabled = idx > 0) { Text("← 이전 쪽", color = fg) }
Text("${idx + 1} / ${active.pages.size} 쪽", fontSize = 12.sp, color = mut)
TextButton(onClick = { if (idx < active.pages.size - 1) onSaveBooks(books.map { if (it.id == active.id) it.copy(currentPage = idx + 1) else it }) }, enabled = idx < active.pages.size - 1) { Text("다음 쪽 →", color = fg) }
}
Button(onClick = { onQuote("『${active.title}』 (${idx + 1}쪽) 인용:\n\"${content.take(180)}\"\n\n나의 묵상: ") }, modifier = Modifier.fillMaxWidth()) {
Text("📝 이 구절 인용하여 AI 메모장에 묵상 기록하기")
}
}
}
editBook?.let { target ->
var t by remember(target) { mutableStateOf(target.title) }
var c by remember(target) { mutableStateOf(target.category) }
AlertDialog(
onDismissRequest = { editBook = null },
title = { Text("📚 도서 제목/분류 수정") },
text = {
Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
OutlinedTextField(value = t, onValueChange = { t = it }, label = { Text("도서 제목") }, singleLine = true)
OutlinedTextField(value = c, onValueChange = { c = it }, label = { Text("분류 (예: 성경, 시, PDF 도서)") }, singleLine = true)
}
},
confirmButton = {
Button(onClick = {
if (t.isNotBlank()) {
onSaveBooks(books.map { if (it.id == target.id) it.copy(title = t.trim(), category = c.trim().ifBlank { "도서" }) else it })
editBook = null
}
}) { Text("저장") }
},
dismissButton = { TextButton(onClick = { editBook = null }) { Text("취소") } }
)
}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerSheet(apps: List<AppEntry>, onClose: () -> Unit, onLaunch: (AppEntry) -> Unit, onFav: (AppEntry) -> Unit, onInt: (AppEntry) -> Unit) {
var q by remember { mutableStateOf("") }
val filtered = remember(apps, q) { apps.filter { KoreanChosungMatcher.matches(it.label, q) } }
LaunchedEffect(filtered, q) {
if (q.trim().isNotEmpty() && !q.startsWith(" ") && filtered.size == 1 && !filtered.first().isInterruptEnabled) {
val a = filtered.first(); q = ""; onLaunch(a)
}
}
Column(Modifier.fillMaxSize().background(Color(0xFA08080A)).padding(24.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("↑ 앱 서랍 (초성 검색)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CW)
TextButton(onClick = onClose) { Text("↓ 닫기", color = CM) }
}
OutlinedTextField(value = q, onValueChange = { q = it }, placeholder = { Text("초성 검색 (예: ㅋㅋㅇㅌ) · 공백 시작 시 자동실행 해제") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Spacer(Modifier.height(8.dp))
LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
items(filtered, key = { it.packageName }) { a ->
Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onLaunch(a) }, onLongClick = { onFav(a) }).padding(vertical = 10.dp), SB, CV) {
Text(a.label + (if (a.isFavorite) " ★" else ""), fontSize = 18.sp, color = Color(0xFFE5E7EB))
Text(if (a.isInterruptEnabled) "⏳ 차단중" else "⏳", fontSize = 12.sp, color = if (a.isInterruptEnabled) CA else Color(0xFF4B5563), modifier = Modifier.clickable { onInt(a) }.padding(6.dp))
}
}
}
}
}

@Composable
fun NotesRagView(
notes: List<NoteItem>, todos: List<TodoItem>, books: List<BookItem>, cfg: LauncherConfig,
ai: LocalAiNoteEngine, quoted: String?, onClearQuote: () -> Unit,
onSaveNotes: (List<NoteItem>) -> Unit, onSaveTodos: (List<TodoItem>) -> Unit
) {
val scope = rememberCoroutineScope()
var composerMode by remember { mutableStateOf("MEMO") }
var input by remember { mutableStateOf("") }
var newImportant by remember { mutableStateOf(false) }
var askQ by remember { mutableStateOf("") }
var ragAns by remember { mutableStateOf<String?>(null) }
var selCat by remember { mutableStateOf("전체") }
var status by remember { mutableStateOf("EmbeddingGemma 2 + AI 6대 자동분류 활성") }
var editNote by remember { mutableStateOf<NoteItem?>(null) }
var editTodo by remember { mutableStateOf<TodoItem?>(null) }

LaunchedEffect(quoted) {
if (!quoted.isNullOrBlank()) {
composerMode = "MEMO"
input = if (input.isBlank()) quoted else "$input\n\n$quoted"
onClearQuote()
}
}
val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
if (uri != null) scope.launch {
ai.loadModelUri(uri).onSuccess { status = it }.onFailure { status = "⚠️ 실패: ${it.message}" }
}
}
val catList = listOf("전체", "★ 중요", "업무/일정", "할일/생활", "묵상/성경", "독서/시", "목표/동기부여", "아이디어")
val filteredNotes = remember(notes, selCat) {
val base = when (selCat) {
"전체" -> notes
"★ 중요" -> notes.filter { SmartMemoClassifier.isImportant(it) }
else -> notes.filter { SmartMemoClassifier.categoryOf(it) == selCat }
}
base.sortedWith(compareByDescending<NoteItem> { SmartMemoClassifier.isImportant(it) }.thenByDescending { it.id })
}

Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Column(Modifier.weight(1f)) {
Text("AI 스마트 메모 & RAG", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CW)
Text(status, fontSize = 11.sp, color = CG)
}
OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
Text("📂 모델", fontSize = 11.sp)
}
}
Spacer(Modifier.height(6.dp))
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
catList.forEach { c ->
val sel = selCat == c
Surface(
color = if (sel) CG.copy(alpha = 0.22f) else CS,
shape = RoundedCornerShape(16.dp),
border = BorderStroke(1.dp, if (sel) CG else Color(0xFF262936)),
modifier = Modifier.clickable { selCat = c }
) {
Text(c, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, color = if (sel) CG else CM, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
}
}
}
Spacer(Modifier.height(8.dp))
LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
ragAns?.let { ans ->
item {
Surface(color = Color(0xFF14261F), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, CG), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(12.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("🔎 AI 질문(RAG) 답변", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CG)
Text("닫기 ✕", fontSize = 11.sp, color = CM, modifier = Modifier.clickable { ragAns = null })
}
Spacer(Modifier.height(4.dp))
Text(ans, fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFFA7F3D0))
}
}
}
}
item {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("스마트 메모 보관함 (${filteredNotes.size}건 · ★중요 ${notes.count { SmartMemoClassifier.isImportant(it) }}건)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CW)
Text("★ 탭하여 홈 고정", fontSize = 11.sp, color = CA)
}
}
items(filteredNotes, key = { it.id }) { n ->
val cat = SmartMemoClassifier.categoryOf(n)
val icon = SmartMemoClassifier.iconOf(cat)
val imp = SmartMemoClassifier.isImportant(n)
Surface(
color = CS,
shape = RoundedCornerShape(10.dp),
border = BorderStroke(1.dp, if (imp) CA.copy(alpha = 0.65f) else Color(0xFF1F2230)),
modifier = Modifier.fillMaxWidth()
) {
Column(Modifier.padding(12.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Row(verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
Surface(color = Color(0xFF1E2433), shape = RoundedCornerShape(6.dp)) {
Text("$icon $cat", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CG, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
}
Text(n.createdAt, fontSize = 11.sp, color = CD)
}
Text(
text = if (imp) "★ 중요(홈 표시)" else "☆ 중요 지정",
fontSize = 11.sp,
fontWeight = FontWeight.Bold,
color = if (imp) CA else CD,
modifier = Modifier.clickable {
onSaveNotes(notes.map { if (it.id == n.id) SmartMemoClassifier.toggleImportant(it) else it })
}.padding(4.dp)
)
}
Spacer(Modifier.height(6.dp))
Text(n.content, fontSize = 14.sp, lineHeight = 20.sp, color = CW)
Spacer(Modifier.height(4.dp))
Text("🤖 ${n.summary}", fontSize = 11.sp, color = CG)
Row(Modifier.fillMaxWidth(), SB, CV) {
Text(n.tags.joinToString(" ") { "#$it" }, fontSize = 11.sp, color = CD, modifier = Modifier.weight(1f))
Row {
TextButton(onClick = { editNote = n }, contentPadding = PaddingValues(4.dp)) { Text("수정", fontSize = 12.sp, color = CG) }
TextButton(onClick = { onSaveNotes(notes.filterNot { it.id == n.id }) }, contentPadding = PaddingValues(4.dp)) { Text("삭제", fontSize = 12.sp, color = Color(0xFFEF4444)) }
}
}
}
}
}
item {
Spacer(Modifier.height(4.dp))
Text("AI 추출 할 일 체크리스트 (${todos.count { !it.isDone }})", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CG)
}
items(todos, key = { it.id }) { t ->
Row(Modifier.fillMaxWidth().background(CS, RoundedCornerShape(8.dp)).padding(10.dp), SB, CV) {
Column(Modifier.weight(1f).clickable { onSaveTodos(todos.map { if (it.id == t.id) it.copy(isDone = !it.isDone) else it }) }) {
Text((if (t.isDone) "✓ " else "○ ") + t.title, fontSize = 13.sp, color = if (t.isDone) CD else CW, textDecoration = if (t.isDone) TextDecoration.LineThrough else null)
Text("기한: ${t.dueHint} · ${t.priority}", fontSize = 11.sp, color = CM)
}
Row {
TextButton(onClick = { editTodo = t }, contentPadding = PaddingValues(4.dp)) { Text("수정", fontSize = 11.sp, color = CG) }
TextButton(onClick = { onSaveTodos(todos.filterNot { it.id == t.id }) }, contentPadding = PaddingValues(4.dp)) { Text("삭제", fontSize = 11.sp, color = Color(0xFFEF4444)) }
}
}
}
}
Spacer(Modifier.height(8.dp))
Surface(color = Color(0xFF14161F), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFF2B3042)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
val isMemo = composerMode == "MEMO"
Row(Modifier.fillMaxWidth().background(Color(0xFF0B0C10), RoundedCornerShape(10.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
Surface(color = if (isMemo) CG else Color.Transparent, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { composerMode = "MEMO" }) {
Text("📝 새 메모 작성 (기본)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isMemo) Color.Black else CM, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 7.dp))
}
Surface(color = if (!isMemo) CG else Color.Transparent, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { composerMode = "ASK" }) {
Text("🔎 내 메모 질문 (RAG)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (!isMemo) Color.Black else CM, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 7.dp))
}
}
if (isMemo) {
OutlinedTextField(
value = input, onValueChange = { input = it },
placeholder = { Text("새 메모 작성 (AI가 6대 카테고리·중요도·할 일을 자동 분류합니다)", fontSize = 12.sp) },
singleLine = false, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth()
)
Row(Modifier.fillMaxWidth(), SB, CV) {
Row(verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
Text(if (newImportant) "★ 중요 메모(홈 표시)" else "☆ 중요 표시", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (newImportant) CA else CM, modifier = Modifier.clickable { newImportant = !newImportant }.padding(4.dp))
Text("↵ 줄바꿈", fontSize = 12.sp, color = CM, modifier = Modifier.clickable { input += "\n" }.padding(4.dp))
}
Button(onClick = {
if (input.isBlank()) return@Button
val raw = input.trim()
val forceImp = newImportant
scope.launch {
val (newNote, newTasks) = SmartMemoClassifier.analyzeAndBuild(raw, forceImp, ai)
onSaveNotes(listOf(newNote) + notes)
if (newTasks.isNotEmpty()) onSaveTodos(newTasks + todos)
input = ""
newImportant = false
}
}, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
Text("✨ AI 분류·저장", fontSize = 12.sp, fontWeight = FontWeight.Bold)
}
}
} else {
OutlinedTextField(
value = askQ, onValueChange = { askQ = it },
placeholder = { Text("내 메모와 독서대에 질문 (예: 중요 메모 알려줘, 내일 일정 뭐야?)", fontSize = 12.sp) },
singleLine = true, modifier = Modifier.fillMaxWidth()
)
Row(Modifier.fillMaxWidth(), SB, CV) {
Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
listOf("중요 메모", "내일 일정", "위로 문장").forEach { chip ->
Text("#$chip", fontSize = 11.sp, color = CG, modifier = Modifier.clickable {
askQ = chip
scope.launch {
val imps = notes.filter { SmartMemoClassifier.isImportant(it) }
ragAns = if (chip == "중요 메모" && imps.isNotEmpty()) {
"📌 [홈 고정 중요 메모 ${imps.size}건]\n" + imps.joinToString("\n") { "• ${it.summary}: ${it.content.replace("\n", " ").take(60)}" }
} else ai.askRag(chip, notes, books, cfg.ragIncludeBooks)
}
}.padding(4.dp))
}
}
Button(onClick = {
scope.launch {
val q = askQ.trim()
val imps = notes.filter { SmartMemoClassifier.isImportant(it) }
ragAns = if ((q.contains("중요") || q.contains("핵심")) && imps.isNotEmpty()) {
"📌 [홈 고정 중요 메모 ${imps.size}건]\n" + imps.joinToString("\n") { "• ${it.summary}: ${it.content.replace("\n", " ").take(65)}" }
} else ai.askRag(q, notes, books, cfg.ragIncludeBooks)
}
}, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
Text("🔎 AI 질문하기", fontSize = 12.sp, fontWeight = FontWeight.Bold)
}
}
}
}
}
}
editNote?.let { target ->
var txt by remember(target) { mutableStateOf(target.content) }
var imp by remember(target) { mutableStateOf(SmartMemoClassifier.isImportant(target)) }
AlertDialog(
onDismissRequest = { editNote = null },
title = { Text("✏️ 메모 넓게 수정하기 (AI 재분류)") },
text = {
Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = txt, onValueChange = { txt = it }, singleLine = false, minLines = 7, maxLines = 15, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp))
Row(Modifier.fillMaxWidth(), SB, CV) {
Text(if (imp) "★ 중요 메모(홈 표시)" else "☆ 일반 메모", color = if (imp) CA else CM, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { imp = !imp }.padding(4.dp))
TextButton(onClick = { txt += "\n" }) { Text("↵ 줄바꿈 추가") }
}
}
},
confirmButton = {
Button(onClick = {
if (txt.isNotBlank()) scope.launch {
val (rebuilt, _) = SmartMemoClassifier.analyzeAndBuild(txt.trim(), imp, ai)
val finalNote = if (!imp) rebuilt.copy(tags = rebuilt.tags.filterNot { it == "중요" } + listOf("일반"), summary = rebuilt.summary.replace(" (★중요)", "")) else rebuilt
onSaveNotes(notes.map { if (it.id == target.id) finalNote.copy(id = target.id, createdAt = target.createdAt) else it })
editNote = null
}
}) { Text("AI 재분석 및 저장") }
},
dismissButton = { TextButton(onClick = { editNote = null }) { Text("취소") } }
)
}
editTodo?.let { target ->
var t by remember(target) { mutableStateOf(target.title) }
var d by remember(target) { mutableStateOf(target.dueHint) }
AlertDialog(
onDismissRequest = { editTodo = null },
title = { Text("할 일 수정") },
text = {
Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
OutlinedTextField(value = t, onValueChange = { t = it }, label = { Text("내용 (줄바꿈 가능)") }, singleLine = false, minLines = 3, maxLines = 6)
OutlinedTextField(value = d, onValueChange = { d = it }, label = { Text("기한") }, singleLine = true)
}
},
confirmButton = {
Button(onClick = {
if (t.isNotBlank()) { onSaveTodos(todos.map { if (it.id == target.id) it.copy(title = t.trim(), dueHint = d.trim()) else it }); editTodo = null }
}) { Text("저장") }
},
dismissButton = { TextButton(onClick = { editTodo = null }) { Text("취소") } }
)
}
}

@Composable
fun SettingsSheet(
cfg: LauncherConfig, isDefHome: Boolean, ai: LocalAiNoteEngine,
onAskHome: () -> Unit, onSysHome: () -> Unit, onCfg: (LauncherConfig) -> Unit, onClose: () -> Unit
) {
val scope = rememberCoroutineScope()
var embStatus by remember { mutableStateOf(ai.modelLabel) }
val embPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
if (uri != null) scope.launch {
ai.loadModelUri(uri).onSuccess { embStatus = it }.onFailure { embStatus = "⚠️ 실패: ${it.message}" }
}
}
Column(Modifier.fillMaxSize().background(Color(0xFA08080A)).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("⚙️ 런처 및 AI 설정", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CW)
TextButton(onClick = onClose) { Text("닫기", color = CG) }
}
Surface(color = CS, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("1. 기본 홈 화면(런처) 권한", fontWeight = FontWeight.Bold, color = CW)
Text(if (isDefHome) "✅ 기본 홈 런처로 설정됨" else "⚠️ 기본 홈 런처 미지정", fontSize = 12.sp, color = CG)
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
Button(onClick = onAskHome) { Text("🏠 기본 홈 권한 요청", fontSize = 12.sp) }
OutlinedButton(onClick = onSysHome) { Text("시스템 기본 앱 설정", fontSize = 12.sp) }
}
}
}
Surface(color = CS, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("2. 홈 화면 테마 & 정렬", fontWeight = FontWeight.Bold, color = CW)
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
listOf("AMOLED" to "블랙", "SLATE" to "슬레이트", "FOREST" to "포레스트").forEach { (k, l) ->
OutlinedButton(onClick = { onCfg(cfg.copy(themeName = k)) }) { Text(l, fontSize = 11.sp) }
}
}
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
listOf("LEFT" to "왼쪽 정렬", "CENTER" to "가운데", "RIGHT" to "오른쪽").forEach { (k, l) ->
OutlinedButton(onClick = { onCfg(cfg.copy(textAlign = k)) }) { Text(l, fontSize = 11.sp) }
}
}
}
}
Surface(color = CS, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("3. EmbeddingGemma 2 온디바이스 RAG", fontWeight = FontWeight.Bold, color = CW)
Text("임베딩 상태: $embStatus", fontSize = 12.sp, color = CG)
OutlinedButton(onClick = { embPicker.launch(arrayOf("*/*")) }) { Text("📂 EmbeddingGemma 2 파일 선택 (.litertlm)", fontSize = 12.sp) }
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("RAG 검색 시 왼쪽 독서대 책 포함", fontSize = 12.sp, color = CW)
Switch(checked = cfg.ragIncludeBooks, onCheckedChange = { onCfg(cfg.copy(ragIncludeBooks = it)) })
}
}
}
}
}

@Composable
fun InterruptModal(app: AppEntry, sec: Int, onCancel: () -> Unit, onGo: () -> Unit) {
var left by remember(app, sec) { mutableStateOf(sec) }
LaunchedEffect(app, sec) { left = sec; while (left > 0) { delay(1000L); left-- } }
Box(Modifier.fillMaxSize().background(Color(0xF208080A)).padding(32.dp), contentAlignment = Alignment.Center) {
Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
Text("잠시 숨을 고르세요", fontSize = 26.sp, fontWeight = FontWeight.Light, color = CW)
Text("'${app.label}' 앱을 열기 전 의식적인 멈춤 시간입니다.", fontSize = 14.sp, color = CM, textAlign = TextAlign.Center)
Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("닫고 집중 유지하기") }
TextButton(onClick = { if (left == 0) onGo() }, enabled = left == 0) {
Text(if (left > 0) "${left}초 후 열기 가능..." else "그래도 ${app.label} 열기")
}
}
}
}
