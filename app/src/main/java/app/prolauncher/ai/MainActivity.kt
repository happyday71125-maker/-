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

val SB = Arrangement.SpaceBetween
val CV = Alignment.CenterVertically
val CW = Color.White
val CG = Color(0xFF10B981)
val CM = Color(0xFF9CA3AF)
val CD = Color(0xFF6B7280)
val CS = Color(0xFF121318)

class MainActivity : ComponentActivity() {
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
val repo = LauncherRepository(this)
val ai = LocalAiNoteEngine(this)
setContent {
MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF08080A), surface = CS, primary = CG)) {
ProLauncherRoot(repo, ai)
}
}
}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProLauncherRoot(repo: LauncherRepository, ai: LocalAiNoteEngine) {
val scope = rememberCoroutineScope()
val pager = rememberPagerState(initialPage = 1, pageCount = { 3 })
var apps by remember { mutableStateOf(repo.loadInstalledApps()) }
var notes by remember { mutableStateOf(repo.loadNotes()) }
var todos by remember { mutableStateOf(repo.loadTodos()) }
var books by remember { mutableStateOf(repo.loadBooks()) }
var cfg by remember { mutableStateOf(repo.loadConfig()) }
var isDefHome by remember { mutableStateOf(repo.isDefaultHomeApp()) }
var drawerOpen by remember { mutableStateOf(false) }
var settingsOpen by remember { mutableStateOf(false) }
var interruptApp by remember { mutableStateOf<AppEntry?>(null) }
var quotedText by remember { mutableStateOf<String?>(null) }
val homeRoleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
isDefHome = repo.isDefaultHomeApp()
}
fun askDefaultHome() {
runCatching { homeRoleLauncher.launch(repo.buildRequestDefaultHomeIntent()) }.onFailure { repo.openSystemDefaultAppsSettings() }
}
fun launchOrInterrupt(app: AppEntry) {
drawerOpen = false
if (app.isInterruptEnabled) interruptApp = app else repo.launchApp(app.packageName)
}
BackHandler(enabled = drawerOpen || settingsOpen || interruptApp != null || pager.currentPage != 1) {
when {
interruptApp != null -> interruptApp = null
settingsOpen -> settingsOpen = false
drawerOpen -> drawerOpen = false
else -> scope.launch { pager.animateScrollToPage(1) }
}
}
val bgCol = when (cfg.themeName) { "SLATE" -> Color(0xFF0F172A); "FOREST" -> Color(0xFF07120E); else -> Color(0xFF08080A) }
Box(Modifier.fillMaxSize().background(bgCol).systemBarsPadding()) {
HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
when (page) {
0 -> ReaderView(books, cfg, repo, onSaveBooks = { books = it; repo.saveBooks(it) }, onCfg = { cfg = it; repo.saveConfig(it) },
onQuote = { q -> quotedText = q; scope.launch { pager.animateScrollToPage(2) } }, onHome = { scope.launch { pager.animateScrollToPage(1) } })
1 -> HomeView(apps.filter { it.isFavorite }.take(7), todos.count { !it.isDone }, isDefHome, cfg,
onAskHome = ::askDefaultHome, onLaunch = ::launchOrInterrupt, onSwipeUp = { drawerOpen = true },
onLeftReader = { scope.launch { pager.animateScrollToPage(0) } }, onRightNotes = { scope.launch { pager.animateScrollToPage(2) } },
onSettings = { settingsOpen = true })
2 -> NotesRagView(notes, todos, books, cfg, ai, quotedText, onClearQuote = { quotedText = null },
onSaveNotes = { notes = it; repo.saveNotes(it) }, onSaveTodos = { todos = it; repo.saveTodos(it) })
}
}
AnimatedVisibility(visible = drawerOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
DrawerSheet(apps, onClose = { drawerOpen = false }, onLaunch = ::launchOrInterrupt,
onFav = { repo.toggleFavorite(it.packageName); apps = repo.loadInstalledApps() },
onInt = { repo.toggleInterrupt(it.packageName); apps = repo.loadInstalledApps() })
}
AnimatedVisibility(visible = settingsOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
SettingsSheet(cfg, isDefHome, ai, onAskHome = ::askDefaultHome, onSysHome = { repo.openSystemDefaultAppsSettings() },
onCfg = { cfg = it; repo.saveConfig(it) }, onClose = { settingsOpen = false })
}
interruptApp?.let { app ->
InterruptModal(app, cfg.interruptSeconds, onCancel = { interruptApp = null }, onGo = { val p = app.packageName; interruptApp = null; repo.launchApp(p) })
}
}
}

@Composable
fun HomeView(
favs: List<AppEntry>, todoCnt: Int, isDefHome: Boolean, cfg: LauncherConfig,
onAskHome: () -> Unit, onLaunch: (AppEntry) -> Unit, onSwipeUp: () -> Unit,
onLeftReader: () -> Unit, onRightNotes: () -> Unit, onSettings: () -> Unit
) {
var now by remember { mutableStateOf(Date()) }
var dragY by remember { mutableStateOf(0f) }
LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000L) } }
val tf = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
val df = remember { SimpleDateFormat("M월 d일 EEEE", Locale.KOREA) }
val align = when (cfg.textAlign) { "CENTER" -> Alignment.CenterHorizontally; "RIGHT" -> Alignment.End; else -> Alignment.Start }
Column(
Modifier.fillMaxSize().pointerInput(Unit) {
detectVerticalDragGestures(onDragStart = { dragY = 0f }, onVerticalDrag = { _, d ->
dragY += d
if (dragY < -90f) { dragY = 0f; onSwipeUp() }
})
}.padding(30.dp),
verticalArrangement = SB
) {
Column(Modifier.fillMaxWidth()) {
Row(Modifier.fillMaxWidth(), SB, Alignment.Top) {
Column {
Text(tf.format(now), fontSize = 56.sp, fontWeight = FontWeight.Light, color = CW)
Text(df.format(now), fontSize = 15.sp, color = CM)
}
TextButton(onClick = onSettings) { Text("⚙️ 설정", color = CM) }
}
if (!isDefHome) {
Spacer(Modifier.height(10.dp))
Surface(color = Color(0xFF14261F), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable { onAskHome() }) {
Row(Modifier.padding(12.dp), SB, CV) {
Column(Modifier.weight(1f)) {
Text("🏠 기본 홈 화면(런처) 권한 지정하기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CG)
Text("탭하여 기본 홈 앱으로 설정하세요", fontSize = 11.sp, color = Color(0xFFA7F3D0))
}
Text("지정 →", fontSize = 12.sp, color = CG)
}
}
}
if (todoCnt > 0) {
Spacer(Modifier.height(8.dp))
Text("오늘 남은 할 일 ${todoCnt}건 · 오른쪽 스와이프 →", fontSize = 13.sp, color = CG, modifier = Modifier.clickable { onRightNotes() })
}
}
Column(Modifier.fillMaxWidth(), horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(16.dp)) {
if (favs.isEmpty()) {
Text("위로 스와이프(↑)하여 앱을 ★ 홈에 고정하세요", color = CM, modifier = Modifier.clickable { onSwipeUp() })
} else favs.forEach { a ->
Row(verticalAlignment = CV, modifier = Modifier.clickable { onLaunch(a) }.padding(vertical = 4.dp)) {
Text(a.label, fontSize = 24.sp, color = Color(0xFFF3F4F6))
if (a.isInterruptEnabled) Text(" ⏳", fontSize = 13.sp)
}
}
}
Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
Surface(color = CS, shape = RoundedCornerShape(20.dp), modifier = Modifier.clickable { onSwipeUp() }) {
Text("↑ 위로 올리기: 앱 서랍 (초성 검색)", fontSize = 12.sp, color = CM, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}
Row(Modifier.fillMaxWidth(), SB) {
Text("← 독서대 (시·성경·PDF)", fontSize = 13.sp, color = CD, modifier = Modifier.clickable { onLeftReader() })
Text("AI 메모 & RAG →", fontSize = 13.sp, color = CD, modifier = Modifier.clickable { onRightNotes() })
}
}
}
}

@Composable
fun ReaderView(
books: List<BookItem>, cfg: LauncherConfig, repo: LauncherRepository,
onSaveBooks: (List<BookItem>) -> Unit, onCfg: (LauncherConfig) -> Unit,
onQuote: (String) -> Unit, onHome: () -> Unit
) {
var selId by remember { mutableStateOf(books.firstOrNull()?.id ?: 1001L) }
var shelfMode by remember { mutableStateOf(false) }
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
TextButton(onClick = { shelfMode = !shelfMode }) { Text(if (shelfMode) "📖 본문 보기" else "📚 서재 (${books.size})", color = fg, fontWeight = FontWeight.Bold) }
Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("📂 파일 열기 (.pdf/.txt/.epub)", fontSize = 11.sp, color = fg) }
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
LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
items(books, key = { it.id }) { b ->
Surface(color = mut.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable { selId = b.id; shelfMode = false }) {
Column(Modifier.padding(14.dp)) {
Text("[${b.category}] ${b.title}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = fg)
Text("${b.author} · ${b.currentPage + 1}/${b.pages.size}쪽", fontSize = 12.sp, color = mut)
}
}
}
}
} else {
val idx = active.currentPage.coerceIn(0, (active.pages.size - 1).coerceAtLeast(0))
val content = active.pages.getOrElse(idx) { "" }
Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
Text("${active.title} · ${active.author}", fontSize = 12.sp, color = mut)
Spacer(Modifier.height(12.dp))
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
}
