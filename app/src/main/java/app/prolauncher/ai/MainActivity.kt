package app.prolauncher.ai
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
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
val CA = Color(0xFFF59E0B)
val CM = Color(0xFF9CA3AF)
val CD = Color(0xFF6B7280)
val CS = Color(0xFF121318)

object SmartMemoClassifier {
val CATS = listOf(
Triple("업무/일정", "💼", listOf("회의","미팅","보고서","기획","마감","발표","시안","피드백","출근","퇴근","거래처","클라이언트","메일","일정","프로젝트","업무","오전","오후")),
Triple("할일/생활", "✅", listOf("사기","구매","장보기","장볼","우유","원두","커피","마트","택배","청소","빨래","세탁","은행","병원","약국","예약","수리","결제","챙기기","전화")),
Triple("묵상/성경", "🙏", listOf("시편","잠언","성경","말씀","기도","묵상","주님","여호와","하나님","은혜","감사","평안","믿음","교회","예배","아멘","복음","영혼","목자")),
Triple("독서/시", "📖", listOf("독서","책","시집","서시","윤동주","김소월","나태주","명상록","아우렐리우스","인용","필사","구절","문장","작가","소설","에세이","쪽")),
Triple("목표/동기부여", "🔥", listOf("목표","습관","다짐","동기부여","집중","성장","운동","루틴","새벽","도전","성공","인내","꾸준","실천","극복","절제")),
Triple("아이디어", "💡", listOf("아이디어","생각","구상","영감","브레인스토밍","컨셉","기능","디자인","개선","토글","메모"))
)
fun iconOf(cat: String) = CATS.find { it.first == cat }?.second ?: "💡"
fun classifyText(text: String): Pair<String, String> {
val low = text.lowercase()
var bestCat = "아이디어"; var bestIcon = "💡"; var bestScore = 0
for ((name, icon, kws) in CATS) {
var sc = kws.count { low.contains(it) } * 3
if (name == "업무/일정" && Regex("(\\d{1,2}시|오전|오후|내일|모레)").containsMatchIn(low)) sc += 2
if (name == "독서/시" && (low.contains("『") || low.contains("【") || low.contains("인용:"))) sc += 5
if (sc > bestScore) { bestScore = sc; bestCat = name; bestIcon = icon }
}
return bestCat to bestIcon
}
fun detectImportant(text: String): Boolean {
val low = text.lowercase()
if (listOf("중요","필수","꼭","긴급","절대","잊지","핵심","기억","마감","★","[중요]","#중요","!").any { low.contains(it) }) return true
return Regex("(오늘|내일)\\s*(오전|오후)?\\s*\\d{1,2}시").containsMatchIn(low)
}
fun categoryOf(n: NoteItem): String {
for ((name, _, _) in CATS) if (n.tags.contains(name) || n.summary.contains("[$name]")) return name
return classifyText(n.content).first
}
fun isImportant(n: NoteItem): Boolean {
if (n.tags.contains("일반")) return false
if (n.tags.contains("중요") || n.summary.contains("★중요")) return true
return detectImportant(n.content)
}
fun toggleImportant(n: NoteItem): NoteItem {
val next = !isImportant(n)
val cleanTags = n.tags.filterNot { it == "중요" || it == "일반" }
val newTags = if (next) listOf("중요") + cleanTags else cleanTags + listOf("일반")
val cleanSum = n.summary.replace(" (★중요)", "").replace("(★중요)", "").trim()
return n.copy(tags = newTags, summary = if (next) "$cleanSum (★중요)" else cleanSum)
}
suspend fun analyzeAndBuild(raw: String, forceImp: Boolean, ai: LocalAiNoteEngine): Pair<NoteItem, List<TodoItem>> {
val base = ai.analyzeMemo(raw)
val (cat, icon) = classifyText(raw)
val imp = forceImp || detectImportant(raw)
val first = raw.lines().map { it.replace(Regex("#[^\\s#]+"), "").replace("[중요]", "").trim() }.firstOrNull { it.isNotEmpty() } ?: raw.take(36)
val core = if (first.length > 32) first.take(32) + "…" else first
val act = listOf("하기","사기","구매","장보기","준비","제출","보내기","확인","예약","방문","미팅","회의","청소","전화","연락","챙기기","작성","마감")
val tasks = base.extractedTasks.filter { t -> act.any { m -> t.title.contains(m) } || t.dueHint != "오늘" }
val sum = "$icon [$cat] $core" + (if (tasks.isNotEmpty()) " · 할일 ${tasks.size}건" else "") + (if (imp) " (★중요)" else "")
val tags = (listOf(cat) + (if (imp) listOf("중요") else emptyList()) + base.tags.filterNot { it == cat || it == "중요" || it == "메모" }).distinct().take(5)
val now = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
return NoteItem(System.currentTimeMillis(), raw, sum, tags, now, base.engineUsed) to tasks
}
}

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
val homeRoleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { isDefHome = repo.isDefaultHomeApp() }
fun askDefaultHome() { runCatching { homeRoleLauncher.launch(repo.buildRequestDefaultHomeIntent()) }.onFailure { repo.openSystemDefaultAppsSettings() } }
fun launchOrInterrupt(app: AppEntry) { drawerOpen = false; if (app.isInterruptEnabled) interruptApp = app else repo.launchApp(app.packageName) }
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
1 -> HomeView(apps.filter { it.isFavorite }.take(7), notes.filter { SmartMemoClassifier.isImportant(it) }.take(3), todos.count { !it.isDone }, isDefHome, cfg,
onAskHome = ::askDefaultHome, onLaunch = ::launchOrInterrupt, onSwipeUp = { drawerOpen = true },
onLeftReader = { scope.launch { pager.animateScrollToPage(0) } }, onRightNotes = { scope.launch { pager.animateScrollToPage(2) } }, onSettings = { settingsOpen = true })
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
favs: List<AppEntry>, importantNotes: List<NoteItem>, todoCnt: Int, isDefHome: Boolean, cfg: LauncherConfig,
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
detectVerticalDragGestures(onDragStart = { dragY = 0f }, onVerticalDrag = { _, d -> dragY += d; if (dragY < -90f) { dragY = 0f; onSwipeUp() } })
}.padding(horizontal = 28.dp, vertical = 22.dp),
verticalArrangement = SB
) {
Column(Modifier.fillMaxWidth()) {
Row(Modifier.fillMaxWidth(), SB, Alignment.Top) {
Column {
Text(tf.format(now), fontSize = 52.sp, fontWeight = FontWeight.Light, color = CW)
Text(df.format(now), fontSize = 14.sp, color = CM)
}
TextButton(onClick = onSettings) { Text("⚙️ 설정", color = CM) }
}
if (!isDefHome) {
Spacer(Modifier.height(8.dp))
Surface(color = Color(0xFF14261F), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { onAskHome() }) {
Row(Modifier.padding(10.dp), SB, CV) {
Column(Modifier.weight(1f)) {
Text("🏠 기본 홈 화면(런처) 권한 지정하기", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CG)
Text("탭하여 기본 홈 앱으로 설정하세요", fontSize = 11.sp, color = Color(0xFFA7F3D0))
}
Text("지정 →", fontSize = 12.sp, color = CG)
}
}
}
if (todoCnt > 0) {
Spacer(Modifier.height(6.dp))
Text("오늘 남은 할 일 ${todoCnt}건 · 오른쪽 스와이프 →", fontSize = 13.sp, color = CG, modifier = Modifier.clickable { onRightNotes() })
}
Spacer(Modifier.height(10.dp))
Surface(color = Color(0xFF14161D), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF262936)), modifier = Modifier.fillMaxWidth().clickable { onRightNotes() }) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("📌 홈 중요 메모 (${importantNotes.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CA)
Text("전체 메모/질문 →", fontSize = 11.sp, color = CM)
}
if (importantNotes.isEmpty()) {
Text("메모에서 ★ 중요 표시를 켜면 홈 화면에서 바로 확인됩니다.", fontSize = 12.sp, color = CD)
} else importantNotes.forEach { n ->
val cat = SmartMemoClassifier.categoryOf(n)
Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
Text("${SmartMemoClassifier.iconOf(cat)} [$cat] ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CG)
Text(n.content.replace("\n", " "), fontSize = 12.sp, color = Color(0xFFE5E7EB), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
}
}
}
}
}
Column(Modifier.fillMaxWidth(), horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(12.dp)) {
if (favs.isEmpty()) {
Text("위로 스와이프(↑)하여 앱을 ★ 홈에 고정하세요", color = CM, modifier = Modifier.clickable { onSwipeUp() })
} else favs.forEach { a ->
Row(verticalAlignment = CV, modifier = Modifier.clickable { onLaunch(a) }.padding(vertical = 3.dp)) {
Text(a.label, fontSize = 23.sp, color = Color(0xFFF3F4F6))
if (a.isInterruptEnabled) Text(" ⏳", fontSize = 13.sp)
}
}
}
Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
Surface(color = CS, shape = RoundedCornerShape(20.dp), modifier = Modifier.clickable { onSwipeUp() }) {
Text("↑ 위로 올리기: 앱 서랍 (초성 검색)", fontSize = 12.sp, color = CM, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}
Row(Modifier.fillMaxWidth(), SB) {
Text("← 독서대 (시·성경·PDF)", fontSize = 13.sp, color = CD, modifier = Modifier.clickable { onLeftReader() })
Text("AI 메모 & 질문 →", fontSize = 13.sp, color = CD, modifier = Modifier.clickable { onRightNotes() })
}
}
}
}
