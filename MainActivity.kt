package app.prolauncher.ai
import android.content.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.*
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

data class QuickBtnConfig(
val showCheck: Boolean = true,
val showBullet: Boolean = true,
val showDate: Boolean = true,
val showDivider: Boolean = true,
val showNewline: Boolean = true,
val autoSumLong: Boolean = false,
val customLabel: String = "📌 템플릿",
val customText: String = "[제목]\n☐ 항목: "
)

object SmartMemoClassifier {
val DEF_MEMO_CATS = listOf("일상", "업무", "할일", "아이디어", "독서")
val DEF_BOOK_CATS = listOf("에세이", "인문/교양", "시/문학", "업무/문서")
private fun prefs(ctx: Context) = ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE)

fun loadMemoCats(ctx: Context): List<String> =
prefs(ctx).getString("memo_cats", null)?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { DEF_MEMO_CATS } ?: DEF_MEMO_CATS

fun saveMemoCats(ctx: Context, cats: List<String>) {
prefs(ctx).edit().putString("memo_cats", cats.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("|")).apply()
}

fun loadBookCats(ctx: Context): List<String> =
prefs(ctx).getString("book_cats", null)?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { DEF_BOOK_CATS } ?: DEF_BOOK_CATS

fun saveBookCats(ctx: Context, cats: List<String>) {
prefs(ctx).edit().putString("book_cats", cats.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("|")).apply()
}

fun loadBtnCfg(ctx: Context): QuickBtnConfig {
val p = prefs(ctx)
return QuickBtnConfig(
p.getBoolean("b_check", true), p.getBoolean("b_bullet", true), p.getBoolean("b_date", true),
p.getBoolean("b_div", true), p.getBoolean("b_nl", true), p.getBoolean("b_autosum", false),
p.getString("b_clabel", "📌 템플릿") ?: "📌 템플릿", p.getString("b_ctext", "[메모]\n☐ 항목: ") ?: "[메모]\n☐ 항목: "
)
}

fun saveBtnCfg(ctx: Context, c: QuickBtnConfig) {
prefs(ctx).edit().putBoolean("b_check", c.showCheck).putBoolean("b_bullet", c.showBullet).putBoolean("b_date", c.showDate)
.putBoolean("b_div", c.showDivider).putBoolean("b_nl", c.showNewline).putBoolean("b_autosum", c.autoSumLong)
.putString("b_clabel", c.customLabel).putString("b_ctext", c.customText).apply()
}

fun sanitizeNotes(notes: List<NoteItem>, repo: LauncherRepository): List<NoteItem> {
var chg = false
val res = notes.map { n ->
if (n.id == 2L && n.content.contains("시편")) {
chg = true
NoteItem(2L, "주간 주요 체크리스트\n☐ 프로젝트 일정 점검\n☐ 운동 및 독서 루틴 체크", "", listOf("일상", "할일"), n.createdAt, "LocalAI")
} else {
val nt = n.tags.map {
when {
it.contains("성경") || it.contains("묵상") || it == "목표/동기부여" -> { chg = true; "일상" }
it == "업무/일정" -> { chg = true; "업무" }
it == "할일/생활" -> { chg = true; "할일" }
it == "독서/시" -> { chg = true; "독서" }
else -> it
}
}
val ns = if (n.summary.contains("묵상") || n.summary.contains("성경") || (n.content.lines().size <= 2 && n.content.length < 75)) { chg = true; "" } else n.summary
if (nt != n.tags || ns != n.summary) n.copy(tags = nt, summary = ns) else n
}
}
if (chg) repo.saveNotes(res)
return res
}

fun sanitizeBooks(books: List<BookItem>, repo: LauncherRepository): List<BookItem> {
var chg = false
val res = books.map { b ->
if (b.title.contains("시편") || b.category.contains("성경")) {
chg = true
BookItem(b.id, "미니멀 집중과 생각 정리 가이드", "베이직 독서 노트", "에세이", listOf(
"【단순함의 힘】\n\n하루의 시작을 복잡하지 않게 여세요. 가장 중요한 일 한두 가지만 홈 화면에 두고, 스쳐 지나가는 생각은 짧게 기록해 두는 것만으로도 머릿속이 훨씬 가벼워집니다.",
"【깊은 독서와 기록】\n\n좋은 문장을 발견하면 메모장으로 인용해 나만의 생각을 덧붙여 보세요. 짧은 메모는 그대로 두고, 긴 글은 필요할 때만 AI 요약으로 핵심 1~3줄을 정리할 수 있습니다."
), 0)
} else b
}
if (chg) repo.saveBooks(res)
return res
}

fun guessCategory(text: String, cats: List<String>): String {
val low = text.lowercase()
val rules = mapOf(
"업무" to listOf("회의","미팅","보고서","기획","마감","발표","시안","피드백","출근","거래처","메일","일정","프로젝트","업무"),
"할일" to listOf("☐","☑","사기","구매","장보기","우유","원두","마트","택배","청소","세탁","은행","병원","예약","결제","체크"),
"독서" to listOf("독서","책","시집","윤동주","김소월","나태주","명상록","인용","필사","구절","문장","작가","에세이","쪽"),
"아이디어" to listOf("아이디어","생각","구상","영감","브레인스토밍","컨셉","기능","디자인","개선")
)
var best = cats.firstOrNull() ?: "일상"; var maxSc = 0
for (c in cats) {
val sc = (rules[c] ?: listOf(c)).count { low.contains(it) }
if (sc > maxSc) { maxSc = sc; best = c }
}
return best
}

fun categoryOf(n: NoteItem, cats: List<String>): String {
for (c in cats) if (n.tags.contains(c)) return c
return n.tags.firstOrNull { it !in listOf("중요","일반","메모","할일") } ?: guessCategory(n.content, cats)
}

fun isImportant(n: NoteItem) = !n.tags.contains("일반") && (n.tags.contains("중요") || n.summary.contains("★중요") || n.content.contains("[중요]"))

fun toggleImportant(n: NoteItem): NoteItem {
val next = !isImportant(n)
val base = n.tags.filterNot { it == "중요" || it == "일반" }
return n.copy(tags = if (next) listOf("중요") + base else base + listOf("일반"))
}

fun toggleCheckLine(n: NoteItem, idx: Int): NoteItem {
val ls = n.content.lines().toMutableList()
if (idx in ls.indices) {
val l = ls[idx]
ls[idx] = when {
l.trimStart().startsWith("☐ ") -> l.replaceFirst("☐ ", "☑ ")
l.trimStart().startsWith("☑ ") -> l.replaceFirst("☑ ", "☐ ")
else -> l
}
}
return n.copy(content = ls.joinToString("\n"))
}

fun summarizeByLength(raw: String, force: Boolean = false): String {
val clean = raw.lines().map { it.replace(Regex("#[^\\s#]+"), "").replace("☐", "").replace("☑", "").replace("•", "").trim() }.filter { it.isNotEmpty() }
val chars = clean.sumOf { it.length }
if (!force && clean.size <= 2 && chars < 75) return ""
val sents = raw.replace("\n", ". ").split(Regex("[.!?]+")).map { it.replace(Regex("#[^\\s#]+"), "").replace("☐", "").replace("☑", "").trim() }.filter { it.length >= 4 }
if (sents.size <= 1 && chars < 75) return if (force) "1~2줄 이내의 짧은 메모는 요약 없이 원문 그대로 표시됩니다." else ""
val target = when { chars < 160 && sents.size <= 3 -> 1; chars < 320 && sents.size <= 6 -> 2; else -> 3 }
return sents.mapIndexed { i, s ->
val sc = (if (i == 0) 4 else 0) + (if (Regex("(\\d+시|\\d+월|\\d+일|중요|핵심|마감|미팅|발표|준비|목표)").containsMatchIn(s)) 5 else 0) + (s.length.coerceAtMost(45) / 10)
Triple(i, s, sc)
}.sortedByDescending { it.third }.take(target).sortedBy { it.first }.joinToString("\n") { (_, s, _) ->
val cut = if (s.length > 46) s.take(46) + "…" else s
if (target == 1) cut else "• $cut"
}
}

fun exportBackup(ctx: Context, notes: List<NoteItem>): Boolean = runCatching {
val root = JSONObject()
val arr = JSONArray()
notes.forEach { n -> arr.put(JSONObject().apply { put("id", n.id); put("content", n.content); put("summary", n.summary); put("tags", JSONArray(n.tags)); put("createdAt", n.createdAt) }) }
root.put("notes", arr); root.put("memoCats", JSONArray(loadMemoCats(ctx))); root.put("bookCats", JSONArray(loadBookCats(ctx)))
(ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PL_Backup", root.toString()))
true
}.getOrDefault(false)

fun importBackup(ctx: Context, repo: LauncherRepository): List<NoteItem>? = runCatching {
val txt = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: return null
val root = JSONObject(txt)
val arr = root.getJSONArray("notes")
val list = buildList {
for (i in 0 until arr.length()) {
val o = arr.getJSONObject(i)
val ta = o.optJSONArray("tags") ?: JSONArray()
add(NoteItem(o.getLong("id"), o.getString("content"), o.optString("summary", ""), buildList { for (j in 0 until ta.length()) add(ta.getString(j)) }, o.optString("createdAt", "오늘"), "LocalAI"))
}
}
repo.saveNotes(list)
root.optJSONArray("memoCats")?.let { ca -> saveMemoCats(ctx, buildList { for (i in 0 until ca.length()) add(ca.getString(i)) }) }
list
}.getOrNull()
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
var notes by remember { mutableStateOf(SmartMemoClassifier.sanitizeNotes(repo.loadNotes(), repo)) }
var todos by remember { mutableStateOf(repo.loadTodos()) }
var books by remember { mutableStateOf(SmartMemoClassifier.sanitizeBooks(repo.loadBooks(), repo)) }
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
1 -> HomeView(apps.filter { it.isFavorite }.take(7), notes.filter { SmartMemoClassifier.isImportant(it) }.take(3), isDefHome, cfg,
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
favs: List<AppEntry>, importantNotes: List<NoteItem>, isDefHome: Boolean, cfg: LauncherConfig,
onAskHome: () -> Unit, onLaunch: (AppEntry) -> Unit, onSwipeUp: () -> Unit,
onLeftReader: () -> Unit, onRightNotes: () -> Unit, onSettings: () -> Unit
) {
val ctx = LocalContext.current
val memoCats = remember { SmartMemoClassifier.loadMemoCats(ctx) }
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
Spacer(Modifier.height(10.dp))
Surface(color = Color(0xFF14161D), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF262936)), modifier = Modifier.fillMaxWidth().clickable { onRightNotes() }) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("📌 중요 메모 (${importantNotes.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CA)
Text("메모장 열기 →", fontSize = 11.sp, color = CM)
}
if (importantNotes.isEmpty()) {
Text("메모에서 ★ 중요 표시를 켜면 홈 화면에서 바로 확인됩니다.", fontSize = 12.sp, color = CD)
} else importantNotes.forEach { n ->
val cat = SmartMemoClassifier.categoryOf(n, memoCats)
Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
Text("[$cat] ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CG)
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
Text("← 독서대 (문서·책)", fontSize = 13.sp, color = CD, modifier = Modifier.clickable { onLeftReader() })
Text("메모 · AI · 검색 →", fontSize = 13.sp, color = CD, modifier = Modifier.clickable { onRightNotes() })
}
}
}
}
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = LauncherRepository(this)
        val aiEngine = LocalAiNoteEngine(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF08080A),
                    surface = Color(0xFF121318),
                    primary = Color(0xFF10B981),
                    onBackground = Color(0xFFF3F4F6),
                    onSurface = Color(0xFFE5E7EB)
                )
            ) {
                ProLauncherAiApp(repository = repository, aiEngine = aiEngine)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProLauncherAiApp(
    repository: LauncherRepository,
    aiEngine: LocalAiNoteEngine
) {
    val scope = rememberCoroutineScope()
    // Page 0: Reading Stand (독서대 - Left of Home)
    // Page 1: Minimal Home (런처 홈 - Center)
    // Page 2: Local AI Notes & Tasks with EmbeddingGemma 2 RAG (오른쪽)
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })

    var apps by remember { mutableStateOf(repository.loadInstalledApps()) }
    var notes by remember { mutableStateOf(repository.loadNotes()) }
    var todos by remember { mutableStateOf(repository.loadTodos()) }
    var books by remember { mutableStateOf(repository.loadBooks()) }
    var config by remember { mutableStateOf(repository.loadConfig()) }
    var isDefaultHome by remember { mutableStateOf(repository.isDefaultHomeApp()) }

    // Swipe-Up App Drawer state & Settings Modal state
    var isAppDrawerOpen by remember { mutableStateOf(false) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var pendingInterruptApp by remember { mutableStateOf<AppEntry?>(null) }
    var quotedPassageForMemo by remember { mutableStateOf<String?>(null) }

    // System RoleManager Default Home Launcher requester
    val homeRoleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isDefaultHome = repository.isDefaultHomeApp()
    }

    fun requestDefaultHomePermission() {
        try {
            homeRoleLauncher.launch(repository.buildRequestDefaultHomeIntent())
        } catch (_: Exception) {
            repository.openSystemDefaultAppsSettings()
        }
    }

    BackHandler(
        enabled = isAppDrawerOpen || isSettingsOpen || pendingInterruptApp != null || pagerState.currentPage != 1
    ) {
        when {
            pendingInterruptApp != null -> pendingInterruptApp = null
            isSettingsOpen -> isSettingsOpen = false
            isAppDrawerOpen -> isAppDrawerOpen = false
            pagerState.currentPage != 1 -> scope.launch { pagerState.animateScrollToPage(1) }
        }
    }

    fun requestAppLaunch(app: AppEntry) {
        isAppDrawerOpen = false
        if (app.isInterruptEnabled) {
            pendingInterruptApp = app
        } else {
            repository.launchApp(app.packageName)
        }
    }

    val bgColor = when (config.themeName) {
        "SLATE" -> Color(0xFF0F172A)
        "FOREST" -> Color(0xFF07120E)
        "PAPER" -> Color(0xFF1C1917)
        else -> Color(0xFF08080A)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .systemBarsPadding()
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> ReadingStandScreen(
                    books = books,
                    config = config,
                    repository = repository,
                    onSaveBooks = {
                        books = it
                        repository.saveBooks(it)
                    },
                    onUpdateConfig = {
                        config = it
                        repository.saveConfig(it)
                    },
                    onSendQuoteToMemo = { quoteText ->
                        quotedPassageForMemo = quoteText
                        scope.launch { pagerState.animateScrollToPage(2) }
                    },
                    onBackToHome = { scope.launch { pagerState.animateScrollToPage(1) } }
                )
                1 -> MinimalHomeScreen(
                    favorites = apps.filter { it.isFavorite }.take(7),
                    pendingTodoCount = todos.count { !it.isDone },
                    isDefaultHome = isDefaultHome,
                    config = config,
                    onRequestDefaultHome = { requestDefaultHomePermission() },
                    onLaunch = { requestAppLaunch(it) },
                    onSwipeUpAppDrawer = { isAppDrawerOpen = true },
                    onNavigateReader = { scope.launch { pagerState.animateScrollToPage(0) } },
                    onNavigateNotes = { scope.launch { pagerState.animateScrollToPage(2) } },
                    onOpenSettings = { isSettingsOpen = true }
                )
                2 -> LocalAiNotesScreen(
                    notes = notes,
                    todos = todos,
                    books = books,
                    config = config,
                    aiEngine = aiEngine,
                    quotedPassage = quotedPassageForMemo,
                    onConsumeQuotedPassage = { quotedPassageForMemo = null },
                    onSaveNotes = {
                        notes = it
                        repository.saveNotes(it)
                    },
                    onSaveTodos = {
                        todos = it
                        repository.saveTodos(it)
                    }
                )
            }
        }

        // Swipe-Up App Drawer (Appears from bottom when swiping up on Home Screen)
        AnimatedVisibility(
            visible = isAppDrawerOpen,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            AppDrawerScreen(
                apps = apps,
                onClose = { isAppDrawerOpen = false },
                onLaunch = { requestAppLaunch(it) },
                onToggleFavorite = {
                    repository.toggleFavorite(it.packageName)
                    apps = repository.loadInstalledApps()
                },
                onToggleInterrupt = {
                    repository.toggleInterrupt(it.packageName)
                    apps = repository.loadInstalledApps()
                }
            )
        }

        // Dedicated Launcher & AI Settings Screen
        AnimatedVisibility(
            visible = isSettingsOpen,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            LauncherSettingsScreen(
                config = config,
                isDefaultHome = isDefaultHome,
                aiEngine = aiEngine,
                onRequestDefaultHome = { requestDefaultHomePermission() },
                onOpenSystemHomeSettings = { repository.openSystemDefaultAppsSettings() },
                onUpdateConfig = {
                    config = it
                    aiEngine.ragEngine.mrlDimension = it.ragMrlDim
                    repository.saveConfig(it)
                },
                onClose = { isSettingsOpen = false }
            )
        }

        // Mindful Interrupt Overlay
        pendingInterruptApp?.let { targetApp ->
            MindfulInterruptOverlay(
                app = targetApp,
                durationSeconds = config.interruptSeconds,
                onCancel = { pendingInterruptApp = null },
                onProceed = {
                    val pkg = targetApp.packageName
                    pendingInterruptApp = null
                    repository.launchApp(pkg)
                }
            )
        }
    }
}

@Composable
fun MinimalHomeScreen(
    favorites: List<AppEntry>,
    pendingTodoCount: Int,
    isDefaultHome: Boolean,
    config: LauncherConfig,
    onRequestDefaultHome: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onSwipeUpAppDrawer: () -> Unit,
    onNavigateReader: () -> Unit,
    onNavigateNotes: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var currentTime by remember { mutableStateOf(Date()) }
    var verticalDragSum by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = Date()
            delay(1000L)
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    val dateFormat = remember { SimpleDateFormat("M월 d일 EEEE", Locale.KOREA) }

    val horizontalAlign = when (config.textAlign) {
        "CENTER" -> Alignment.CenterHorizontally
        "RIGHT" -> Alignment.End
        else -> Alignment.Start
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { verticalDragSum = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        verticalDragSum += dragAmount
                        // Swipe Up (negative Y drag) opens the App Drawer
                        if (verticalDragSum < -95f) {
                            verticalDragSum = 0f
                            onSwipeUpAppDrawer()
                        }
                    }
                )
            }
            .padding(horizontal = 32.dp, vertical = 34.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header: Clock, Date, Settings Icon & Default Home Launcher Permission Banner
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = timeFormat.format(currentTime),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Light,
                        color = Color.White
                    )
                    Text(
                        text = dateFormat.format(currentTime),
                        fontSize = 15.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }
                TextButton(onClick = onOpenSettings) {
                    Text("⚙️ 설정", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                }
            }

            if (!isDefaultHome) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = Color(0xFF14261F),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRequestDefaultHome() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🏠 기본 홈 화면(런처)으로 설정하기",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF10B981)
                            )
                            Text(
                                text = "탭하면 안드로이드 기본 홈 앱 지정 권한 창이 열립니다",
                                fontSize = 11.sp,
                                color = Color(0xFFA7F3D0)
                            )
                        }
                        Text("지정 →", fontSize = 12.sp, color = Color(0xFF10B981))
                    }
                }
            }

            if (pendingTodoCount > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "오늘 남은 할 일 ${pendingTodoCount}건 · 오른쪽 스와이프 →",
                    fontSize = 13.sp,
                    color = Color(0xFF10B981),
                    modifier = Modifier.clickable { onNavigateNotes() }
                )
            }
        }

        // Center: Minimalist Text-Only Pinned Apps
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = horizontalAlign,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (favorites.isEmpty()) {
                Text(
                    text = "위로 스와이프(↑)하여 자주 쓰는 앱을 ★ 홈에 고정하세요",
                    fontSize = 16.sp,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier.clickable { onSwipeUpAppDrawer() }
                )
            } else {
                favorites.forEach { app ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { onLaunch(app) }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = app.label,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFFF3F4F6)
                        )
                        if (app.isInterruptEnabled) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "⏳", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Bottom Gesture Navigation Bar (Left: Reading Stand | Up: App Drawer | Right: AI Notes)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = Color(0xFF121318),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.clickable { onSwipeUpAppDrawer() }
            ) {
                Text(
                    text = "↑ 위로 스와이프: 앱 서랍 (초성 검색)",
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "← 독서대 (시·성경·책)",
                    fontSize = 13.sp,
                    color = Color(0xFF6B7280),
                    modifier = Modifier.clickable { onNavigateReader() }
                )
                Text(
                    text = "로컬 AI 메모 & RAG →",
                    fontSize = 13.sp,
                    color = Color(0xFF6B7280),
                    modifier = Modifier.clickable { onNavigateNotes() }
                )
            }
        }
    }
}

@Composable
fun ReadingStandScreen(
    books: List<BookItem>,
    config: LauncherConfig,
    repository: LauncherRepository,
    onSaveBooks: (List<BookItem>) -> Unit,
    onUpdateConfig: (LauncherConfig) -> Unit,
    onSendQuoteToMemo: (String) -> Unit,
    onBackToHome: () -> Unit
) {
    var selectedBookId by remember { mutableStateOf(books.firstOrNull()?.id ?: 1001L) }
    var selectedCategory by remember { mutableStateOf("전체") }
    var isShelfMode by remember { mutableStateOf(false) }

    val activeBook = books.find { it.id == selectedBookId } ?: books.firstOrNull()

    val bookFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val imported = repository.importBookFromUri(uri)
            if (imported != null) {
                val updated = listOf(imported) + books
                onSaveBooks(updated)
                selectedBookId = imported.id
                isShelfMode = false
            }
        }
    }

    val readerBg = when (config.readerTheme) {
        "SEPIA" -> Color(0xFFF4ECD8)
        "PAPER" -> Color(0xFFFAF8F5)
        else -> Color(0xFF0A0B0E)
    }
    val readerTextCol = when (config.readerTheme) {
        "SEPIA" -> Color(0xFF2C221E)
        "PAPER" -> Color(0xFF1C1917)
        else -> Color(0xFFE5E7EB)
    }
    val readerMutedCol = when (config.readerTheme) {
        "SEPIA" -> Color(0xFF6E5A4F)
        "PAPER" -> Color(0xFF78716C)
        else -> Color(0xFF9CA3AF)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(readerBg)
            .padding(20.dp)
    ) {
        // Top Reader Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { isShelfMode = !isShelfMode }) {
                    Text(
                        text = if (isShelfMode) "📖 본문 보기로 돌아가기" else "📚 서재 목록 (${books.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = readerTextCol
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { bookFilePicker.launch(arrayOf("*/*")) }
                ) {
                    Text("📂 파일 열기 (.pdf/.txt/.epub)", fontSize = 11.sp, color = readerTextCol)
                }
                TextButton(onClick = onBackToHome) {
                    Text("홈 →", fontSize = 12.sp, color = readerMutedCol)
                }
            }
        }

        // Typography & Theme Bar (Google Play Books Style)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("SEPIA" to "양피지", "PAPER" to "종이", "NIGHT" to "야간").forEach { (key, label) ->
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        color = if (config.readerTheme == key) Color(0xFF10B981) else readerMutedCol,
                        fontWeight = if (config.readerTheme == key) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clickable { onUpdateConfig(config.copy(readerTheme = key)) }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (config.readerUseSerif) "명조체" else "고딕체",
                    fontSize = 11.sp,
                    color = readerTextCol,
                    modifier = Modifier
                        .clickable { onUpdateConfig(config.copy(readerUseSerif = !config.readerUseSerif)) }
                        .padding(4.dp)
                )
                Text(
                    text = "가-",
                    fontSize = 12.sp,
                    color = readerTextCol,
                    modifier = Modifier
                        .clickable {
                            onUpdateConfig(config.copy(readerFontSizeSp = (config.readerFontSizeSp - 2).coerceAtLeast(14)))
                        }
                        .padding(4.dp)
                )
                Text(
                    text = "${config.readerFontSizeSp}pt",
                    fontSize = 11.sp,
                    color = readerMutedCol
                )
                Text(
                    text = "가+",
                    fontSize = 12.sp,
                    color = readerTextCol,
                    modifier = Modifier
                        .clickable {
                            onUpdateConfig(config.copy(readerFontSizeSp = (config.readerFontSizeSp + 2).coerceAtMost(28)))
                        }
                        .padding(4.dp)
                )
            }
        }

        Divider(color = readerMutedCol.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 6.dp))

        if (isShelfMode || activeBook == null) {
            // Google Play Books Style Library Shelf
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("전체", "시", "성경", "동기부여", "PDF 도서", "내 파일").forEach { cat ->
                    Surface(
                        color = if (selectedCategory == cat) Color(0xFF10B981) else Color.Transparent,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable { selectedCategory = cat }
                    ) {
                        Text(
                            text = cat,
                            fontSize = 12.sp,
                            color = if (selectedCategory == cat) Color.Black else readerTextCol,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            val filteredBooks = books.filter { selectedCategory == "전체" || it.category == selectedCategory }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filteredBooks, key = { it.id }) { book ->
                    Surface(
                        color = readerMutedCol.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedBookId = book.id
                                isShelfMode = false
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "[${book.category}] ${book.title}",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = readerTextCol
                                )
                                Text(
                                    text = "${book.author} · 총 ${book.pages.size}쪽 (진행: ${book.currentPage + 1}/${book.pages.size}쪽)",
                                    fontSize = 12.sp,
                                    color = readerMutedCol
                                )
                            }
                            if (book.category == "내 파일") {
                                TextButton(
                                    onClick = {
                                        onSaveBooks(books.filterNot { it.id == book.id })
                                    }
                                ) {
                                    Text("삭제", fontSize = 11.sp, color = Color(0xFFEF4444))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Active Book Page Reader View
            val pageIdx = activeBook.currentPage.coerceIn(0, (activeBook.pages.size - 1).coerceAtLeast(0))
            val pageContent = activeBook.pages.getOrElse(pageIdx) { "" }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp, horizontal = 6.dp)
            ) {
                Text(
                    text = "${activeBook.title} · ${activeBook.author}",
                    fontSize = 12.sp,
                    color = readerMutedCol
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = pageContent,
                    fontSize = config.readerFontSizeSp.sp,
                    lineHeight = (config.readerFontSizeSp * 1.65f).sp,
                    fontFamily = if (config.readerUseSerif) FontFamily.Serif else FontFamily.SansSerif,
                    color = readerTextCol
                )
            }

            // Bottom Page Controls & Quote to AI Memo Button
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (pageIdx > 0) {
                                onSaveBooks(
                                    books.map {
                                        if (it.id == activeBook.id) it.copy(currentPage = pageIdx - 1) else it
                                    }
                                )
                            }
                        },
                        enabled = pageIdx > 0
                    ) {
                        Text("← 이전 쪽", color = readerTextCol)
                    }

                    Text(
                        text = "${pageIdx + 1} / ${activeBook.pages.size} 쪽 (${((pageIdx + 1) * 100) / activeBook.pages.size.coerceAtLeast(1)}%)",
                        fontSize = 12.sp,
                        color = readerMutedCol
                    )

                    TextButton(
                        onClick = {
                            if (pageIdx < activeBook.pages.size - 1) {
                                onSaveBooks(
                                    books.map {
                                        if (it.id == activeBook.id) it.copy(currentPage = pageIdx + 1) else it
                                    }
                                )
                            }
                        },
                        enabled = pageIdx < activeBook.pages.size - 1
                    ) {
                        Text("다음 쪽 →", color = readerTextCol)
                    }
                }

                Button(
                    onClick = {
                        val excerpt = "『${activeBook.title}』 (${pageIdx + 1}쪽) 인용:\n"${pageContent.take(180)}"\n\n나의 묵상/생각: "
                        onSendQuoteToMemo(excerpt)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📝 이 구절 인용하여 AI 메모장에 묵상 기록하기")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawerScreen(
    apps: List<AppEntry>,
    onClose: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onToggleFavorite: (AppEntry) -> Unit,
    onToggleInterrupt: (AppEntry) -> Unit
) {
    var rawQuery by remember { mutableStateOf("") }
    val autoLaunchDisabled = rawQuery.startsWith(" ")
    val filtered = remember(apps, rawQuery) {
        apps.filter { KoreanChosungMatcher.matches(it.label, rawQuery) }
    }

    LaunchedEffect(filtered, rawQuery) {
        if (rawQuery.trim().isNotEmpty() && !autoLaunchDisabled && filtered.size == 1) {
            val single = filtered.first()
            if (!single.isInterruptEnabled) {
                rawQuery = ""
                onLaunch(single)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFA08080A))
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "앱 서랍 (초성 검색)",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            TextButton(onClick = onClose) {
                Text("↓ 닫기 (홈으로)", color = Color(0xFF9CA3AF))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = rawQuery,
            onValueChange = { rawQuery = it },
            placeholder = { Text("앱 이름 또는 초성 입력 (예: ㅋㅋㅇㅌ) · 공백 시작 시 자동실행 해제") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "길게 눌러 ★ 홈 고정 추가/해제 · 우측 ⏳ 버튼으로 방해 앱 인터럽트 설정",
            fontSize = 12.sp,
            color = Color(0xFF6B7280)
        )
        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onLaunch(app) },
                            onLongClick = { onToggleFavorite(app) }
                        )
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.label,
                            fontSize = 18.sp,
                            color = Color(0xFFE5E7EB)
                        )
                        if (app.isFavorite) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "★", color = Color(0xFF10B981), fontSize = 14.sp)
                        }
                    }
                    Text(
                        text = if (app.isInterruptEnabled) "⏳ 인터럽트 켜짐" else "⏳",
                        fontSize = 12.sp,
                        color = if (app.isInterruptEnabled) Color(0xFFF59E0B) else Color(0xFF4B5563),
                        modifier = Modifier
                            .clickable { onToggleInterrupt(app) }
                            .padding(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LocalAiNotesScreen(
    notes: List<NoteItem>,
    todos: List<TodoItem>,
    books: List<BookItem>,
    config: LauncherConfig,
    aiEngine: LocalAiNoteEngine,
    quotedPassage: String?,
    onConsumeQuotedPassage: () -> Unit,
    onSaveNotes: (List<NoteItem>) -> Unit,
    onSaveTodos: (List<TodoItem>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var memoInput by remember { mutableStateOf("") }
    var askQuery by remember { mutableStateOf("") }
    var aiAnswer by remember { mutableStateOf<String?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var loadedModelInfo by remember { mutableStateOf<LoadedModelMetadata?>(aiEngine.activeModel) }
    var modelLoadStatus by remember { mutableStateOf("EmbeddingGemma 2 RAG + 온디바이스 AI 활성") }
    var editingNote by remember { mutableStateOf<NoteItem?>(null) }
    var editingTodo by remember { mutableStateOf<TodoItem?>(null) }

    LaunchedEffect(quotedPassage) {
        if (!quotedPassage.isNullOrBlank()) {
            memoInput = if (memoInput.isBlank()) quotedPassage else "$memoInput\n\n$quotedPassage"
            onConsumeQuotedPassage()
        }
    }

    LaunchedEffect(Unit) {
        aiEngine.autoDetectExistingModel()?.let { detected ->
            loadedModelInfo = detected
            modelLoadStatus = "자동 감지됨: ${detected.fileName} (${detected.architectureHint})"
        }
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            modelLoadStatus = "모델 파일 언패킹 및 헤더 분석 중..."
            scope.launch {
                val res = aiEngine.loadUserProvidedModel(uri)
                res.onSuccess { meta ->
                    loadedModelInfo = meta
                    val sizeMb = meta.sizeBytes / (1024 * 1024)
                    modelLoadStatus = "✅ 로드 완료: ${meta.fileName} (${sizeMb}MB · ${meta.architectureHint})"
                }.onFailure { err ->
                    modelLoadStatus = "⚠️ 모델 로드 실패: ${err.message}"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Notes & Tasks · EmbeddingGemma 2 RAG",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = modelLoadStatus,
                    fontSize = 11.sp,
                    color = Color(0xFF10B981)
                )
            }
            OutlinedButton(
                onClick = { modelPickerLauncher.launch(arrayOf("*/*")) }
            ) {
                Text("📂 모델 선택", fontSize = 11.sp)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        // Spacious Multi-Line Memo Input (Supports free Enter line breaks)
        OutlinedTextField(
            value = memoInput,
            onValueChange = { memoInput = it },
            placeholder = {
                Text("여러 줄로 자유롭게 메모·묵상·할 일을 작성하세요 (엔터 줄바꿈 지원)\n예: 내일 오후 2시 기획안 제출\n퇴근길에 우유 사기 #중요")
            },
            singleLine = false,
            minLines = 4,
            maxLines = 10,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 110.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(
                onClick = { memoInput += "\n" }
            ) {
                Text("↵ 줄바꿈 추가", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
            Button(
                onClick = {
                    if (memoInput.isBlank() || isAnalyzing) return@Button
                    isAnalyzing = true
                    val raw = memoInput.trim()
                    scope.launch {
                        val parsed = aiEngine.analyzeMemo(raw)
                        val now = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
                        val newNote = NoteItem(
                            id = System.currentTimeMillis(),
                            content = raw,
                            summary = parsed.summary,
                            tags = parsed.tags,
                            createdAt = now,
                            engineUsed = parsed.engineUsed
                        )
                        onSaveNotes(listOf(newNote) + notes)
                        val newTodos = parsed.extractedTasks.mapIndexed { idx, t ->
                            TodoItem(
                                id = System.currentTimeMillis() + idx + 1,
                                title = t.title,
                                dueHint = t.dueHint,
                                priority = t.priority,
                                isDone = false
                            )
                        }
                        if (newTodos.isNotEmpty()) {
                            onSaveTodos(newTodos + todos)
                        }
                        memoInput = ""
                        isAnalyzing = false
                    }
                }
            ) {
                Text(if (isAnalyzing) "온디바이스 AI 분석 중..." else "✨ 로컬 AI 저장 & 할 일 추출")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // EmbeddingGemma 2 On-Device RAG Question Bar (Searches Notes + Reading Stand Books)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = askQuery,
                onValueChange = { askQuery = it },
                placeholder = { Text("🔎 EmbeddingGemma 2 RAG 질문 (예: 위로가 되는 구절이나 장볼 목록 알려줘)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        aiAnswer = aiEngine.askNotesQuestion(
                            question = askQuery,
                            notes = notes,
                            books = books,
                            includeBooks = config.ragIncludeBooks
                        )
                    }
                }
            ) {
                Text("RAG 검색")
            }
        }

        aiAnswer?.let { ans ->
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = Color(0xFF14261F),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = ans,
                        fontSize = 12.sp,
                        color = Color(0xFFA7F3D0)
                    )
                    TextButton(onClick = { aiAnswer = null }) {
                        Text("닫기", fontSize = 11.sp, color = Color(0xFF10B981))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    text = "할 일 체크리스트 (${todos.count { !it.isDone }})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF10B981)
                )
            }
            items(todos, key = { it.id }) { todo ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121318), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                onSaveTodos(
                                    todos.map {
                                        if (it.id == todo.id) it.copy(isDone = !it.isDone) else it
                                    }
                                )
                            }
                    ) {
                        Text(
                            text = (if (todo.isDone) "✓ " else "○ ") + todo.title,
                            fontSize = 15.sp,
                            color = if (todo.isDone) Color(0xFF6B7280) else Color(0xFFF3F4F6),
                            textDecoration = if (todo.isDone) TextDecoration.LineThrough else null
                        )
                        Text(
                            text = "기한: ${todo.dueHint} · 우선순위: ${todo.priority}",
                            fontSize = 12.sp,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { editingTodo = todo }) {
                            Text("수정", fontSize = 12.sp, color = Color(0xFF10B981))
                        }
                        TextButton(
                            onClick = { onSaveTodos(todos.filterNot { it.id == todo.id }) }
                        ) {
                            Text("삭제", fontSize = 12.sp, color = Color(0xFFEF4444))
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "스마트 메모 보관함 (${notes.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF9CA3AF)
                )
            }
            items(notes, key = { it.id }) { note ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121318), RoundedCornerShape(8.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = note.content,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = Color(0xFFF3F4F6)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "AI 요약: ${note.summary}",
                        fontSize = 12.sp,
                        color = Color(0xFF10B981)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${note.tags.joinToString(" ") { "#$it" }} · ${note.createdAt}",
                            fontSize = 11.sp,
                            color = Color(0xFF6B7280),
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { editingNote = note }) {
                                Text("수정", fontSize = 12.sp, color = Color(0xFF10B981))
                            }
                            TextButton(
                                onClick = { onSaveNotes(notes.filterNot { it.id == note.id }) }
                            ) {
                                Text("삭제", fontSize = 12.sp, color = Color(0xFFEF4444))
                            }
                        }
                    }
                }
            }
        }
    }

    // Spacious Multi-Line Note Edit Dialog (wide, tall, free Enter line breaks)
    editingNote?.let { targetNote ->
        var editedText by remember(targetNote) { mutableStateOf(targetNote.content) }
        AlertDialog(
            onDismissRequest = { editingNote = null },
            title = { Text("✏️ 메모 넓게 수정하기 (줄바꿈 지원)") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = editedText,
                        onValueChange = { editedText = it },
                        singleLine = false,
                        minLines = 9,
                        maxLines = 18,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 420.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${editedText.length}자 · ${editedText.lines().size}줄",
                            fontSize = 11.sp,
                            color = Color(0xFF9CA3AF)
                        )
                        TextButton(onClick = { editedText += "\n" }) {
                            Text("↵ 줄바꿈 추가", fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updatedText = editedText.trim()
                        if (updatedText.isNotEmpty()) {
                            scope.launch {
                                val reParsed = aiEngine.analyzeMemo(updatedText)
                                onSaveNotes(
                                    notes.map {
                                        if (it.id == targetNote.id) {
                                            it.copy(
                                                content = updatedText,
                                                summary = reParsed.summary,
                                                tags = reParsed.tags,
                                                engineUsed = reParsed.engineUsed + " (수정됨)"
                                            )
                                        } else it
                                    }
                                )
                                editingNote = null
                            }
                        }
                    }
                ) {
                    Text("AI 재분석 및 저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingNote = null }) {
                    Text("취소")
                }
            }
        )
    }

    // Edit Todo Dialog
    editingTodo?.let { targetTodo ->
        var editedTitle by remember(targetTodo) { mutableStateOf(targetTodo.title) }
        var editedDue by remember(targetTodo) { mutableStateOf(targetTodo.dueHint) }
        var editedPriority by remember(targetTodo) { mutableStateOf(targetTodo.priority) }
        AlertDialog(
            onDismissRequest = { editingTodo = null },
            title = { Text("할 일 수정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editedTitle,
                        onValueChange = { editedTitle = it },
                        label = { Text("할 일 내용 (여러 줄 가능)") },
                        singleLine = false,
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedDue,
                        onValueChange = { editedDue = it },
                        label = { Text("기한/시간") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedPriority,
                        onValueChange = { editedPriority = it },
                        label = { Text("우선순위 (높음/보통)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editedTitle.isNotBlank()) {
                            onSaveTodos(
                                todos.map {
                                    if (it.id == targetTodo.id) {
                                        it.copy(
                                            title = editedTitle.trim(),
                                            dueHint = editedDue.trim().ifEmpty { "기한 없음" },
                                            priority = editedPriority.trim().ifEmpty { "보통" }
                                        )
                                    } else it
                                }
                            )
                            editingTodo = null
                        }
                    }
                ) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTodo = null }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
fun LauncherSettingsScreen(
    config: LauncherConfig,
    isDefaultHome: Boolean,
    aiEngine: LocalAiNoteEngine,
    onRequestDefaultHome: () -> Unit,
    onOpenSystemHomeSettings: () -> Unit,
    onUpdateConfig: (LauncherConfig) -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var embModelStatus by remember {
        mutableStateOf(
            aiEngine.ragEngine.activeEmbeddingModel?.fileName
                ?: "내장 한국어 MRL 시맨틱 엔진 (${config.ragMrlDim}차원)"
        )
    }

    val embPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val res = aiEngine.ragEngine.loadEmbeddingModelFromUri(uri)
                res.onSuccess { meta ->
                    embModelStatus = "✅ 로드됨: ${meta.fileName}"
                }.onFailure { err ->
                    embModelStatus = "⚠️ 실패: ${err.message}"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFA08080A))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⚙️ Pro Launcher AI 설정",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            TextButton(onClick = onClose) {
                Text("닫기", color = Color(0xFF10B981))
            }
        }

        // Section 1: Default Home Screen Permission (ROLE_HOME)
        Surface(
            color = Color(0xFF121318),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. 기본 홈 화면(런처) 권한 설정", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(
                    text = if (isDefaultHome) "✅ 현재 기본 홈 런처로 설정되어 있습니다."
                    else "⚠️ 아직 기본 홈 런처로 지정되지 않았습니다. 아래 버튼을 눌러 홈 화면 권한을 부여하세요.",
                    fontSize = 12.sp,
                    color = if (isDefaultHome) Color(0xFF10B981) else Color(0xFFF59E0B)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRequestDefaultHome) {
                        Text("🏠 기본 홈 앱 지정 팝업 띄우기", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onOpenSystemHomeSettings) {
                        Text("시스템 기본 앱 설정 열기", fontSize = 12.sp)
                    }
                }
            }
        }

        // Section 2: Home Screen Appearance & Interrupt
        Surface(
            color = Color(0xFF121318),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2. 미니멀 홈 화면 & 방해 앱 인터럽트", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("배경 테마", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("AMOLED" to "순수 블랙", "SLATE" to "슬레이트", "FOREST" to "포레스트").forEach { (k, label) ->
                        OutlinedButton(
                            onClick = { onUpdateConfig(config.copy(themeName = k)) }
                        ) {
                            Text(label, fontSize = 11.sp, color = if (config.themeName == k) Color(0xFF10B981) else Color.White)
                        }
                    }
                }
                Text("홈 앱 텍스트 정렬", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("LEFT" to "왼쪽", "CENTER" to "가운데", "RIGHT" to "오른쪽").forEach { (k, label) ->
                        OutlinedButton(
                            onClick = { onUpdateConfig(config.copy(textAlign = k)) }
                        ) {
                            Text(label, fontSize = 11.sp, color = if (config.textAlign == k) Color(0xFF10B981) else Color.White)
                        }
                    }
                }
                Text("방해 앱 인터럽트 호흡 대기 시간", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 10).forEach { sec ->
                        OutlinedButton(
                            onClick = { onUpdateConfig(config.copy(interruptSeconds = sec)) }
                        ) {
                            Text("${sec}초", fontSize = 11.sp, color = if (config.interruptSeconds == sec) Color(0xFF10B981) else Color.White)
                        }
                    }
                }
            }
        }

        // Section 3: EmbeddingGemma 2 On-Device RAG & Vector Settings
        Surface(
            color = Color(0xFF121318),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("3. EmbeddingGemma 2 온디바이스 RAG 설정", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(
                    text = "활성 임베딩 엔진: $embModelStatus",
                    fontSize = 12.sp,
                    color = Color(0xFF10B981)
                )
                OutlinedButton(
                    onClick = { embPickerLauncher.launch(arrayOf("*/*")) }
                ) {
                    Text("📂 EmbeddingGemma 2 모델 파일 선택 (.litertlm / .tflite)", fontSize = 12.sp)
                }

                Text("Matryoshka(MRL) 임베딩 벡터 차원 수", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(128, 256, 768).forEach { dim ->
                        OutlinedButton(
                            onClick = { onUpdateConfig(config.copy(ragMrlDim = dim)) }
                        ) {
                            Text("${dim}차원", fontSize = 11.sp, color = if (config.ragMrlDim == dim) Color(0xFF10B981) else Color.White)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("RAG 질문 시 왼쪽 '독서대' 책 구절도 함께 검색", fontSize = 12.sp, color = Color(0xFFE5E7EB))
                    Switch(
                        checked = config.ragIncludeBooks,
                        onCheckedChange = { onUpdateConfig(config.copy(ragIncludeBooks = it)) }
                    )
                }
            }
        }
    }
}

@Composable
fun MindfulInterruptOverlay(
    app: AppEntry,
    durationSeconds: Int,
    onCancel: () -> Unit,
    onProceed: () -> Unit
) {
    var secondsLeft by remember(app, durationSeconds) { mutableStateOf(durationSeconds) }
    LaunchedEffect(app, durationSeconds) {
        secondsLeft = durationSeconds
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft--
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF208080A))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "잠시 숨을 고르세요",
                fontSize = 26.sp,
                fontWeight = FontWeight.Light,
                color = Color.White
            )
            Text(
                text = "'${app.label}' 앱을 열기 전 의식적인 멈춤 시간입니다.\n정말 지금 필요한 작업인가요?",
                fontSize = 15.sp,
                color = Color(0xFF9CA3AF),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("닫고 집중 유지하기 (권장)")
            }
            TextButton(
                onClick = { if (secondsLeft == 0) onProceed() },
                enabled = secondsLeft == 0
            ) {
                Text(
                    if (secondsLeft > 0) "${secondsLeft}초 후 열기 가능..."
                    else "그래도 ${app.label} 열기"
                )
            }
        }
    }
}
