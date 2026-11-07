package app.prolauncher.ai
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

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
Text(if (a.isInterruptEnabled) "⏳ 차단중" else "⏳", fontSize = 12.sp, color = if (a.isInterruptEnabled) Color(0xFFF59E0B) else Color(0xFF4B5563), modifier = Modifier.clickable { onInt(a) }.padding(6.dp))
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
var input by remember { mutableStateOf("") }
var askQ by remember { mutableStateOf("") }
var ragAns by remember { mutableStateOf<String?>(null) }
var status by remember { mutableStateOf("EmbeddingGemma 2 RAG + 온디바이스 AI 활성") }
var editNote by remember { mutableStateOf<NoteItem?>(null) }
var editTodo by remember { mutableStateOf<TodoItem?>(null) }
LaunchedEffect(quoted) {
if (!quoted.isNullOrBlank()) { input = if (input.isBlank()) quoted else "$input\n\n$quoted"; onClearQuote() }
}
val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
if (uri != null) scope.launch {
ai.loadModelUri(uri).onSuccess { status = it }.onFailure { status = "⚠️ 실패: ${it.message}" }
}
}
Column(Modifier.fillMaxSize().padding(22.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Column(Modifier.weight(1f)) {
Text("Notes & Tasks · EmbeddingGemma 2 RAG", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = CW)
Text(status, fontSize = 11.sp, color = CG)
}
OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }) { Text("📂 모델 선택", fontSize = 11.sp) }
}
Spacer(Modifier.height(8.dp))
OutlinedTextField(
value = input, onValueChange = { input = it },
placeholder = { Text("여러 줄로 자유롭게 메모·묵상·할 일을 적으세요 (엔터 줄바꿈 지원)") },
singleLine = false, minLines = 4, maxLines = 10,
modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp)
)
Row(Modifier.fillMaxWidth(), SB, CV) {
TextButton(onClick = { input += "\n" }) { Text("↵ 줄바꿈 추가", fontSize = 12.sp, color = CM) }
Button(onClick = {
if (input.isBlank()) return@Button
val raw = input.trim()
scope.launch {
val res = ai.analyzeMemo(raw)
val now = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
onSaveNotes(listOf(NoteItem(System.currentTimeMillis(), raw, res.summary, res.tags, now, res.engineUsed)) + notes)
if (res.extractedTasks.isNotEmpty()) onSaveTodos(res.extractedTasks + todos)
input = ""
}
}) { Text("✨ 로컬 AI 저장 & 할 일 추출") }
}
Spacer(Modifier.height(8.dp))
Row(Modifier.fillMaxWidth(), verticalAlignment = CV) {
OutlinedTextField(value = askQ, onValueChange = { askQ = it }, placeholder = { Text("🔎 EmbeddingGemma 2 RAG 질문 (메모+독서대 검색)") }, singleLine = true, modifier = Modifier.weight(1f))
Spacer(Modifier.width(6.dp))
Button(onClick = { scope.launch { ragAns = ai.askRag(askQ, notes, books, cfg.ragIncludeBooks) } }) { Text("RAG 검색") }
}
ragAns?.let { ans ->
Spacer(Modifier.height(6.dp))
Surface(color = Color(0xFF14261F), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(12.dp)) {
Text(ans, fontSize = 12.sp, color = Color(0xFFA7F3D0))
TextButton(onClick = { ragAns = null }) { Text("닫기", fontSize = 11.sp, color = CG) }
}
}
}
Spacer(Modifier.height(10.dp))
LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
item { Text("할 일 체크리스트 (${todos.count { !it.isDone }})", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = CG) }
items(todos, key = { it.id }) { t ->
Row(Modifier.fillMaxWidth().background(CS, RoundedCornerShape(8.dp)).padding(10.dp), SB, CV) {
Column(Modifier.weight(1f).clickable { onSaveTodos(todos.map { if (it.id == t.id) it.copy(isDone = !it.isDone) else it }) }) {
Text((if (t.isDone) "✓ " else "○ ") + t.title, fontSize = 14.sp, color = if (t.isDone) CD else CW, textDecoration = if (t.isDone) TextDecoration.LineThrough else null)
Text("기한: ${t.dueHint} · ${t.priority}", fontSize = 11.sp, color = CM)
}
Row {
TextButton(onClick = { editTodo = t }) { Text("수정", fontSize = 12.sp, color = CG) }
TextButton(onClick = { onSaveTodos(todos.filterNot { it.id == t.id }) }) { Text("삭제", fontSize = 12.sp, color = Color(0xFFEF4444)) }
}
}
}
item { Spacer(Modifier.height(4.dp)); Text("스마트 메모 보관함 (${notes.size})", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = CM) }
items(notes, key = { it.id }) { n ->
Column(Modifier.fillMaxWidth().background(CS, RoundedCornerShape(8.dp)).padding(12.dp)) {
Text(n.content, fontSize = 14.sp, lineHeight = 21.sp, color = CW)
Spacer(Modifier.height(4.dp))
Text("AI 요약: ${n.summary}", fontSize = 12.sp, color = CG)
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("${n.tags.joinToString(" ") { "#$it" }} · ${n.createdAt}", fontSize = 11.sp, color = CD, modifier = Modifier.weight(1f))
Row {
TextButton(onClick = { editNote = n }) { Text("수정", fontSize = 12.sp, color = CG) }
TextButton(onClick = { onSaveNotes(notes.filterNot { it.id == n.id }) }) { Text("삭제", fontSize = 12.sp, color = Color(0xFFEF4444)) }
}
}
}
}
}
}
editNote?.let { target ->
var txt by remember(target) { mutableStateOf(target.content) }
AlertDialog(
onDismissRequest = { editNote = null },
title = { Text("✏️ 메모 넓게 수정하기 (줄바꿈 지원)") },
text = {
Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = txt, onValueChange = { txt = it }, singleLine = false, minLines = 9, maxLines = 18, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp))
TextButton(onClick = { txt += "\n" }) { Text("↵ 줄바꿈 추가") }
}
},
confirmButton = {
Button(onClick = {
if (txt.isNotBlank()) scope.launch {
val r = ai.analyzeMemo(txt.trim())
onSaveNotes(notes.map { if (it.id == target.id) it.copy(content = txt.trim(), summary = r.summary, tags = r.tags) else it })
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
