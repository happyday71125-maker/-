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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.*
import java.text.SimpleDateFormat
import java.util.*
val SB = Arrangement.SpaceBetween
val CV = Alignment.CenterVertically
val CW = Color(0xFFF1F5F9)
val CG = Color(0xFF34D399)
val CA = Color(0xFFFBBF24)
val CM = Color(0xFF94A3B8)
val CD = Color(0xFF64748B)
val CS = Color(0xFF101624)
data class QuickBtnConfig(
val showCheck: Boolean = true,
val showBullet: Boolean = true,
val showDate: Boolean = true,
val showDivider: Boolean = true,
val showNewline: Boolean = true,
val customLabel: String = "📌 템플릿",
val customText: String = "[제목]\n☐ 항목: "
)
object SmartMemoClassifier {
val DEF_MEMO_CATS = listOf("일상", "음성메모", "업무", "할일", "아이디어", "독서")
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
p.getBoolean("b_div", true), p.getBoolean("b_nl", true),
p.getString("b_clabel", "📌 템플릿") ?: "📌 템플릿", p.getString("b_ctext", "[메모]\n☐ 항목: ") ?: "[메모]\n☐ 항목: "
)
}
fun saveBtnCfg(ctx: Context, c: QuickBtnConfig) {
prefs(ctx).edit().putBoolean("b_check", c.showCheck).putBoolean("b_bullet", c.showBullet).putBoolean("b_date", c.showDate)
.putBoolean("b_div", c.showDivider).putBoolean("b_nl", c.showNewline)
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
"【깊은 독서와 기록】\n\n좋은 문장을 발견하면 북마크(🔖)로 표시하거나 메모장으로 인용해 나만의 생각을 덧붙여 보세요."
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
"독서" to listOf("독서","책","시집","윤동주","김소월","나태주","명상록","인용","발췌","구절","문장","작가","에세이","쪽"),
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
l.trimStart().startsWith("☐") -> l.replaceFirst("☐", "☑ ")
l.trimStart().startsWith("☑ ") -> l.replaceFirst("☑ ", "☐ ")
l.trimStart().startsWith("☑") -> l.replaceFirst("☑", "☐ ")
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
}
object ReaderVolumeKeyBridge {
  var isReaderTabCurrent: Boolean = false
var readerBookActive: Boolean = false
  var volumeTurnEnabled: Boolean = true
var onTurnPage: ((Int) -> Unit)? = null
private var lastTurnMs: Long = 0L
fun handleKeyEvent(event: android.view.KeyEvent): Boolean {
val code = event.keyCode
if (code != android.view.KeyEvent.KEYCODE_VOLUME_UP && code != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false
if (!isReaderTabCurrent || !readerBookActive || !volumeTurnEnabled) return false
if (ReaderTtsRuntime.on || SupertonicTtsEngine.playing || isTtsBusyNow() || briefingSpeakingNow) return false
val cb = onTurnPage ?: return false
if (event.action == android.view.KeyEvent.ACTION_DOWN) {
val now = android.os.SystemClock.uptimeMillis()
if (event.repeatCount == 0 || now - lastTurnMs >= 260L) {
lastTurnMs = now
cb(if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP) -1 else 1)
}
}
return true
}
}
var homeResumeTick by mutableStateOf(0)
var homeNewIntentTick by mutableStateOf(0)
class MainActivity : ComponentActivity() {
override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
if (ReaderVolumeKeyBridge.handleKeyEvent(event)) return true
return super.dispatchKeyEvent(event)
}
    override fun onResume() { super.onResume(); mainActRef = this; appInFg = true; homeResumeTick++; JarvisRuntime.onActivityResume() }
override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (intent.hasCategory(Intent.CATEGORY_HOME)) homeNewIntentTick++ }
override fun onPause() { appInFg = false; super.onPause() }
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState); mainActRef = this; appCtxRef = applicationContext; appInFg = true; loadAiCfg(this); val prevEh = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler { th, ex -> if (th.name != "main") { ex.printStackTrace() } else { prevEh?.uncaughtException(th, ex) } }
val repo = LauncherRepository(this)
val ai = LocalAiNoteEngine(this)
setContent {
MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF07090E), surface = CS, primary = CG, secondary = CA, surfaceVariant = Color(0xFF161E2E), outline = Color(0xFF253248))) {
ProLauncherRoot(repo, ai)
}
}
}
}
fun appendOrCreateBookNote(notes: List<NoteItem>, bookTitle: String, entryBody: String): List<NoteItem> {
val cleanTitle = bookTitle.trim().ifEmpty { "독서" }
val headerPrefix = "📚 『$cleanTitle』 독서노트"
val stamp = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
val newEntry = "[$stamp]\n${entryBody.trim()}"
val existing = notes.firstOrNull { n ->
n.content.trimStart().startsWith(headerPrefix) || (n.tags.contains("독서") && n.content.lines().firstOrNull()?.contains("『$cleanTitle』") == true)
}
return if (existing != null) {
val strippedOld = existing.content.trim().lines().dropLastWhile { ln ->
ln.trim().let { it.isNotEmpty() && it.split(" ").filter { w -> w.isNotBlank() }.all { w -> w.startsWith("#") } }
}.joinToString("\n").trimEnd()
val mergedRaw = if (strippedOld.startsWith("📚")) "$strippedOld\n\n────────────\n$newEntry" else "$headerPrefix\n\n$strippedOld\n\n────────────\n$newEntry"
val updatedNote = existing.copy(content = makeHashtags(mergedRaw, "독서"), summary = "", createdAt = stamp)
listOf(updatedNote) + notes.filterNot { it.id == existing.id }
} else {
val freshRaw = "$headerPrefix\n\n$newEntry"
val created = NoteItem(nextId(), makeHashtags(freshRaw, "독서"), "", listOf("독서", "일반"), stamp, "LocalAI")
listOf(created) + notes
}
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProLauncherRoot(repo: LauncherRepository, ai: LocalAiNoteEngine) {
val rootCtx = LocalContext.current; val scope = rememberCoroutineScope()
val pager = rememberPagerState(initialPage = 2, pageCount = { 5 })
var apps by remember { mutableStateOf(repo.loadInstalledApps()) }
var notes by remember { mutableStateOf(SmartMemoClassifier.sanitizeNotes(repo.loadNotes(), repo)) }
var books by remember { mutableStateOf(SmartMemoClassifier.sanitizeBooks(repo.loadBooks(), repo)) }
var cfg by remember { mutableStateOf(repo.loadConfig()) }
var isDefHome by remember { mutableStateOf(repo.isDefaultHomeApp()) }
var drawerOpen by remember { mutableStateOf(false) }
var settingsOpen by remember { mutableStateOf(false) }
var interruptApp by remember { mutableStateOf<AppEntry?>(null) }
LaunchedEffect(homeResumeTick) { isDefHome = repo.isDefaultHomeApp() }
LaunchedEffect(homeNewIntentTick) {
if (homeNewIntentTick > 0) {
interruptApp = null
settingsOpen = false
drawerOpen = false
}
}
val homeRoleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { isDefHome = repo.isDefaultHomeApp() }
fun askDefaultHome() {
if (repo.isXiaomiOrPocoDevice() && !repo.isIgnoringBatteryOptimizations()) {
repo.requestIgnoreBatteryOptimizations()
}
runCatching { homeRoleLauncher.launch(repo.buildRequestDefaultHomeIntent()) }.onFailure { repo.openSystemDefaultAppsSettings() }
}
LaunchedEffect(LauncherBackupManager.backupRev) {
if (LauncherBackupManager.backupRev > 0) {
cfg = repo.loadConfig()
apps = repo.loadInstalledApps()
notes = SmartMemoClassifier.sanitizeNotes(repo.loadNotes(), repo)
books = SmartMemoClassifier.sanitizeBooks(repo.loadBooks(), repo)
}
}
fun launchOrInterrupt(app: AppEntry) { drawerOpen = false; if (app.isInterruptEnabled) interruptApp = app else repo.launchApp(app.packageName) }
SideEffect {
ReaderVolumeKeyBridge.isReaderTabCurrent = (pager.currentPage == 1 && !drawerOpen && !settingsOpen && interruptApp == null)
}
    BackHandler(enabled = true) {
when {
interruptApp != null -> interruptApp = null
settingsOpen -> settingsOpen = false
drawerOpen -> drawerOpen = false
pager.currentPage != 2 -> scope.launch { pager.animateScrollToPage(2) }
}
}
SideEffect { JarvisRuntime.bindCallbacks(rootCtx, { pg -> scope.launch { pager.scrollToPage(pg.coerceIn(0, 4)) } }, { raw0 -> val isVm = raw0.startsWith("__VOICEMEMO__"); val raw = if (isVm) raw0.removePrefix("__VOICEMEMO__").trim() else raw0.trim(); val mc0 = SmartMemoClassifier.loadMemoCats(rootCtx); val c0 = if (isVm) { if (!mc0.contains("음성메모")) SmartMemoClassifier.saveMemoCats(rootCtx, listOf("음성메모") + mc0); "음성메모" } else SmartMemoClassifier.guessCategory(raw, mc0); val nx = listOf(NoteItem(nextId(), makeHashtags(raw, c0), "", listOf(c0, if (raw.contains("중요")) "중요" else "일반"), SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date()), "LocalAI")) + notes; notes = nx; repo.saveNotes(nx); val sc = ScheduleBriefingManager.syncNoteAlarms(rootCtx, nx); if (sc > 0) "🎙️ [$c0] 탭 저장 + ⏰알림 ${sc}건 예약됨" else if (isVm) "🎙️ [음성메모] 탭에 즉시 저장되었습니다!" else "✅ [$c0] 메모 즉시 저장됨" }, { books }, { notes }) }
val bgCol = when (cfg.themeName) { "SLATE" -> Color(0xFF0B1120); "FOREST" -> Color(0xFF07120E); else -> Color(0xFF07090E) }
Box(Modifier.fillMaxSize().background(bgCol).systemBarsPadding().imePadding()) {
HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
when (page) {
0 -> MarkdownWriterView(
onGoReader = { scope.launch { pager.animateScrollToPage(1) } },
onGoHome = { scope.launch { pager.animateScrollToPage(2) } },
onSaveToMemo = { raw -> val nx = listOf(NoteItem(nextId(), makeHashtags(raw, "문서"), "", listOf("문서", "일반"), SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date()), "LocalAI")) + notes; notes = nx; repo.saveNotes(nx) }
)
1 -> ReaderView(
books, cfg, repo,
onSaveBooks = { books = it; repo.saveBooks(it) },
onCfg = { cfg = it; repo.saveConfig(it) },
onHome = { scope.launch { pager.animateScrollToPage(2) } },
onBookNote = { bTitle, body, _ -> val nx = appendOrCreateBookNote(notes, bTitle, body); notes = nx; repo.saveNotes(nx) },
notes = notes,
onUpdateNotes = { notes = it; repo.saveNotes(it) }
)
2 -> HomeView(
apps.filter { it.isFavorite }.take(12), notes.filter { SmartMemoClassifier.isImportant(it) }.take(3), isDefHome, cfg,
onAskHome = ::askDefaultHome, onLaunch = ::launchOrInterrupt, onSwipeUp = { drawerOpen = true },
onLeftReader = { scope.launch { pager.animateScrollToPage(1) } },
onRightNotes = { scope.launch { pager.animateScrollToPage(3) } },
onSettings = { settingsOpen = true },
onRightAi = { scope.launch { pager.animateScrollToPage(4) } },
onQuickMemo = { raw0 -> val isVm = raw0.startsWith("__VOICEMEMO__"); val raw = if (isVm) raw0.removePrefix("__VOICEMEMO__").trim() else raw0.trim(); val mc0 = SmartMemoClassifier.loadMemoCats(rootCtx); val c0 = if (isVm) { if (!mc0.contains("음성메모")) SmartMemoClassifier.saveMemoCats(rootCtx, listOf("음성메모") + mc0); "음성메모" } else SmartMemoClassifier.guessCategory(raw, mc0); val nx = listOf(NoteItem(nextId(), makeHashtags(raw, c0), "", listOf(c0, if (raw.contains("중요")) "중요" else "일반"), SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date()), "LocalAI")) + notes; notes = nx; repo.saveNotes(nx); val sc = ScheduleBriefingManager.syncNoteAlarms(rootCtx, nx); if (sc > 0) "🎙️ [$c0] 탭 저장 + ⏰일정 알림 ${sc}건 자동 예약!" else if (isVm) "🎙️ [음성메모] 탭에 저장되었습니다!" else "✅ [$c0] 메모 저장됨" },
allNotes = notes,
onNavTab = { pg -> scope.launch { pager.animateScrollToPage(pg.coerceIn(0, 4)) } },
onToggleCheck = { nid, lIdx -> val nx = notes.map { if (it.id == nid) SmartMemoClassifier.toggleCheckLine(it, lIdx) else it }; notes = nx; repo.saveNotes(nx) },
onLeftWriter = { scope.launch { pager.animateScrollToPage(0) } }, allApps = apps
)
3 -> NotesRagView(notes, books, cfg, ai, onSaveNotes = { notes = it; repo.saveNotes(it) }, isAiPage = false)
4 -> NotesRagView(notes, books, cfg, ai, onSaveNotes = { notes = it; repo.saveNotes(it) }, isAiPage = true)
}
}
AnimatedVisibility(visible = drawerOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
DrawerSheet(apps, onClose = { drawerOpen = false }, onLaunch = ::launchOrInterrupt,
onFav = { repo.toggleFavorite(it.packageName, it.label); apps = repo.loadInstalledApps() },
onInt = { repo.toggleInterrupt(it.packageName, it.label); apps = repo.loadInstalledApps() })
}
AnimatedVisibility(visible = settingsOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
SettingsSheet(cfg, isDefHome, onAskHome = ::askDefaultHome, onSysHome = { repo.openSystemDefaultAppsSettings() },
onCfg = { cfg = it; repo.saveConfig(it) }, onClose = { settingsOpen = false })
}
interruptApp?.let { app ->
InterruptModal(app, cfg.interruptSeconds, onCancel = { interruptApp = null }, onGo = { val p = app.packageName; interruptApp = null; repo.launchApp(p) })
}
}
}
@Composable
fun HomeAppTileItem(a: AppEntry, appGapMode: Int, subSp: TextUnit, alignEnd: Boolean, onLaunch: (AppEntry) -> Unit) {
if (appGapMode == 0) {
Surface(color = Color(0xFF0E1626), shape = RoundedCornerShape(13.dp), border = BorderStroke(0.8.dp, Color(0xFF1F2E47)), modifier = Modifier.fillMaxWidth().clickable { onLaunch(a) }) {
Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), horizontalArrangement = if (alignEnd) Arrangement.End else SB, verticalAlignment = CV) {
Text(a.label, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF3F4F6), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (alignEnd) Modifier else Modifier.weight(1f, fill = false))
if (a.isInterruptEnabled) Text(" ⏳", fontSize = subSp, color = CA)
}
}
} else {
Row(verticalAlignment = CV, horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start, modifier = Modifier.fillMaxWidth().clickable { onLaunch(a) }.padding(horizontal = 6.dp, vertical = if (appGapMode == 1) 7.dp else 3.dp)) {
Text(a.label, fontSize = if (appGapMode == 1) 18.sp else 17.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF3F4F6), maxLines = 1, overflow = TextOverflow.Ellipsis)
if (a.isInterruptEnabled) Text(" ⏳", fontSize = subSp, color = CA)
}
}
}
@Composable
fun HomeView(
favs: List<AppEntry>, importantNotes: List<NoteItem>, isDefHome: Boolean, cfg: LauncherConfig,
onAskHome: () -> Unit, onLaunch: (AppEntry) -> Unit, onSwipeUp: () -> Unit,
onLeftReader: () -> Unit, onRightNotes: () -> Unit, onSettings: () -> Unit,
onRightAi: () -> Unit = {}, onQuickMemo: (String) -> String = { "" }, onQuickAi: (String) -> Unit = {}, allNotes: List<NoteItem> = emptyList(), onNavTab: (Int) -> Unit = {}, onToggleCheck: (Long, Int) -> Unit = { _, _ -> }, onLeftWriter: () -> Unit = {}, allApps: List<AppEntry> = emptyList()
) {
val homeCtx = androidx.compose.ui.platform.LocalContext.current; val ctx = homeCtx; val aiCfg = remember { loadAiCfg(homeCtx) }; val ui = rememberUi(aiCfg.uiScale); val memoCats = remember { SmartMemoClassifier.loadMemoCats(homeCtx) }; var now by remember { mutableStateOf(Date()) }; var dragY by remember { mutableStateOf(0f) }; var aiFolded by remember { mutableStateOf(false) }
var appColMode by remember { mutableStateOf(homeCtx.getSharedPreferences("pl_ai_v2_prefs", 0).getInt("home_app_col_mode", 1)) }
var appGapMode by remember { mutableStateOf(homeCtx.getSharedPreferences("pl_ai_v2_prefs", 0).getInt("home_app_gap_mode", 0)) }
var cpuPct by remember { mutableStateOf(12) }; var ramPct by remember { mutableStateOf(45) }; var ramTxt by remember { mutableStateOf("0.0/0.0G") }
var briefCfg by remember { mutableStateOf(ScheduleBriefingManager.loadBriefCfg(homeCtx)) }
var briefPanelOpen by remember { mutableStateOf(false) }
val homeRepo = remember { LauncherRepository(homeCtx) }
var homeAppFolders by remember(LauncherBackupManager.backupRev, allApps.size) { mutableStateOf(FolderRegistry.loadAppFolders(homeCtx, allApps)) }
var selHomeFolderId by remember { mutableStateOf(-1L) }
var homeFolderDlgOpen by remember { mutableStateOf(false) }
fun openSlotTimePicker(initialH: Int, initialM: Int, onPicked: (Int, Int) -> Unit) {
runCatching {
android.app.TimePickerDialog(homeCtx, { _, h, m -> onPicked(h, m) }, initialH.coerceIn(0, 23), initialM.coerceIn(0, 59), false).show()
}
}
SideEffect { JarvisRuntime.bindCallbacks(homeCtx, onNavTab, onQuickMemo, getN = { allNotes.ifEmpty { importantNotes } }); SupertonicTtsEngine.init(homeCtx); loadVoiceWaitSec(homeCtx) }; LaunchedEffect(JarvisRuntime.status) { if (!JarvisRuntime.status.isNullOrBlank()) aiFolded = false }; EmojiHelpDialog()
LaunchedEffect(Unit) { var lastCpu = android.os.Process.getElapsedCpuTime(); var lastWall = android.os.SystemClock.elapsedRealtime(); val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1); while (true) { now = Date(); val curCpu = android.os.Process.getElapsedCpuTime(); val curWall = android.os.SystemClock.elapsedRealtime(); val dWall = (curWall - lastWall).coerceAtLeast(1L); val rawC = (((curCpu - lastCpu) * 100L) / (dWall * cores.coerceAtMost(4))).toInt(); val activeBoost = if (isAiBusy || streamActive || SupertonicTtsEngine.playing || SupertonicTtsEngine.dlBusy) 28 else 6; val cVal = (rawC + activeBoost).coerceIn(4, 98); lastCpu = curCpu; lastWall = curWall; val am = homeCtx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager; val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); val totG = mi.totalMem.toFloat() / 1073741824f; val usedG = (mi.totalMem - mi.availMem).toFloat() / 1073741824f; val rVal = (((mi.totalMem - mi.availMem) * 100L) / mi.totalMem.coerceAtLeast(1L)).toInt().coerceIn(5, 99); cpuPct = cVal; ramPct = rVal; ramTxt = String.format(Locale.US, "%.1f/%.1fG", usedG, totG); delay(2000L) } }
val tf = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }; val df = remember { SimpleDateFormat("M월 d일 EEEE", Locale.KOREA) }
val align = when (cfg.textAlign) { "CENTER" -> Alignment.CenterHorizontally; "RIGHT" -> Alignment.End; else -> Alignment.Start }
val hFallbackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res -> if (res.resultCode == android.app.Activity.RESULT_OK) { val spk = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim() ?: ""; if (spk.isNotEmpty()) JarvisRuntime.processUtterance(homeCtx, spk, false) } }
val hMicP = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) JarvisRuntime.start(homeCtx) { runCatching { hFallbackLauncher.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")) } } else JarvisRuntime.status = "⚠️ 마이크 권한을 허용해 주세요." }
val upNested = remember {
object : NestedScrollConnection {
var pullUp = 0f
override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
if (source == NestedScrollSource.Drag) {
if (available.y < 0f) {
pullUp += -available.y
if (pullUp > 105f) { pullUp = 0f; onSwipeUp() }
} else if (consumed.y > 0f) pullUp = 0f
}
return Offset.Zero
}
}
}
BoxWithConstraints(Modifier.fillMaxSize()) {
val homeMaxH = this.maxHeight
val density = LocalDensity.current
val conf = LocalConfiguration.current
val imeBottomPx = WindowInsets.ime.getBottom(density)
val isKeyboardOpen = imeBottomPx > with(density) { 72.dp.toPx() } || homeMaxH < (conf.screenHeightDp.dp * 0.72f)
val favMaxH = (homeMaxH * 0.36f).coerceIn(96.dp, 256.dp)
Column(
Modifier
.fillMaxSize()
.then(if (isKeyboardOpen) Modifier else Modifier.nestedScroll(upNested).pointerInput(Unit) { detectVerticalDragGestures(onDragStart = { dragY = 0f }, onVerticalDrag = { _, d -> dragY += d; if (dragY < -75f) { dragY = 0f; onSwipeUp() } }) })
.padding(horizontal = ui.hPad + 6.dp, vertical = ui.vPad + 2.dp),
verticalArrangement = Arrangement.spacedBy(6.dp)
) {
Row(Modifier.fillMaxWidth(), SB, Alignment.CenterVertically) {
Column(Modifier.weight(1f)) {
if (isKeyboardOpen) {
Text("${tf.format(now)} · ${df.format(now)}", fontSize = (ui.bodySp.value + 1f).sp, fontWeight = FontWeight.SemiBold, color = CW, maxLines = 1, overflow = TextOverflow.Ellipsis)
} else {
Text(tf.format(now), fontSize = 36.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = (-1.2).sp, color = CW, maxLines = 1)
Text(df.format(now), fontSize = ui.subSp, color = CM, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
}

Surface(
color = Color(0xFF0E1626),
shape = RoundedCornerShape(10.dp),
border = BorderStroke(0.8.dp, Color(0xFF23324B)),
modifier = Modifier.padding(horizontal = 4.dp).clickable { releaseEmbedGguf(); System.gc(); JarvisRuntime.status = "🧹 메모리 정리 완료 (RAM ${ramTxt})" }
) {
Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
Text("CPU ${cpuPct}%", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (cpuPct > 75) Color(0xFFF87171) else CG, maxLines = 1, softWrap = false)
Text("·", fontSize = 11.sp, color = CD)
Text("RAM ${ramPct}%", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CA, maxLines = 1, softWrap = false)
}
}
Row(verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
Surface(color = Color(0xFF101827), shape = RoundedCornerShape(10.dp), border = BorderStroke(0.8.dp, Color(0xFF24324A)), modifier = Modifier.clickable { val py = ScheduleBriefingManager.buildVoiceBriefingPayload(allNotes.ifEmpty { importantNotes }); JarvisRuntime.status = py.visualSummary; speakOneShot(ctx, py.spokenScript) }) {
Text("🔊", fontSize = 14.sp, color = CG, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
}
Surface(color = Color(0xFF101827), shape = RoundedCornerShape(10.dp), border = BorderStroke(0.8.dp, Color(0xFF24324A)), modifier = Modifier.clickable { onSettings() }) {
Text("⚙️", fontSize = 14.sp, color = CW, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
}
}
}
Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Surface(
    color = Color(0xFF0E1525),
    shape = RoundedCornerShape(16.dp),
    border = BorderStroke(1.dp, if (JarvisRuntime.on) CG else Color(0xFF25344E)),
    modifier = Modifier.fillMaxWidth()
) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                color = if (JarvisRuntime.on) (if (JarvisController.sleeping || (wakeOnly && System.currentTimeMillis() >= wakeFollowUpUntil)) Color(0xFF1E293B) else Color(0xFF0F3D3E)) else Color(0xFF172236),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (JarvisRuntime.on) CG else Color(0xFF2E4057)),
                modifier = Modifier.clickable {
                    if (JarvisRuntime.on) JarvisRuntime.stop(homeCtx)
                    else if (homeCtx.checkSelfPermission("android.permission.RECORD_AUDIO") == 0) {
                        JarvisRuntime.start(homeCtx) {
                            runCatching {
                                hFallbackLauncher.launch(
                                    android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                                )
                            }
                        }
                    } else hMicP.launch("android.permission.RECORD_AUDIO")
                }
            ) {
                Text(
                    if (JarvisRuntime.on) (if (JarvisController.sleeping) "🌙" else if (wakeOnly) "👂🎙️" else "🤖🎙️") else "🎙️",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
                )
            }
            OutlinedTextField(
                value = JarvisRuntime.input,
                onValueChange = { JarvisRuntime.input = it; syncDictationBuffer(homeCtx, it) },
                placeholder = {
                    Text(
                        if (JarvisRuntime.on) (if (wakeOnly) "👂 [${wakeWord}] 호출 대기 중..." else "🤖 자비스 경청 중...")
                        else "메모·일정 기록 또는 즉석 AI 질문...",
                        fontSize = 12.sp,
                        color = CD
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )
if (JarvisRuntime.busy || isAiBusy) {
Surface(
                color = Color(0xFFEF4444),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.clickable {
                    activeGenId++
                    if (ProLlamaJni.ready) runCatching { ProLlamaJni.abortGeneration() }
                    JarvisRuntime.busy = false
                    isAiBusy = false
                    streamActive = false
                    stopOneShotTts()
                }
            ) {
                Text("⏹️", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CW, modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp))
            }
        } else {
    
      val hasTxt = JarvisRuntime.input.isNotBlank()
            Surface(
                color = if (hasTxt) CA else Color(0xFF172236),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (hasTxt) CA else Color(0xFF2E4057)),
                modifier = Modifier.clickable {
                    val t = JarvisRuntime.input.trim()
                    if (t.isNotEmpty()) {
                        JarvisRuntime.input = ""
                        syncDictationBuffer(homeCtx, "")
                        aiFolded = false
                        JarvisRuntime.processUtterance(homeCtx, t, false)
                    } else {
                        aiFolded = false
                        JarvisRuntime.status = "💡 입력창에 궁금한 내용을 적고 [⚡즉석질문]을 누르면 탭 이동 없이 홈 화면에서 바로 AI 답변을 보여줍니다."
                    }
                }
            ) {
                Text(
                    "⚡",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hasTxt) Color(0xFF07090E) else CA,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp)
                )
            }
        }
    }
    if (JarvisRuntime.input.isNotBlank()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
            ChipBtn("⚡", true, CA) {
                val t = JarvisRuntime.input.trim()
                if (t.isNotEmpty()) {
                    JarvisRuntime.input = ""
                    syncDictationBuffer(homeCtx, "")
                    aiFolded = false
                    JarvisRuntime.processUtterance(homeCtx, t, false)
                }
            }
            ChipBtn("📝", false, CG) {
                val t = JarvisRuntime.input.trim()
                if (t.isNotEmpty()) {
                    JarvisRuntime.status = onQuickMemo(t)
                    JarvisRuntime.input = ""
                    syncDictationBuffer(homeCtx, "")
                }
            }
            ChipBtn("🦙", false, CW) {
                val t = JarvisRuntime.input.trim()
                if (t.isNotEmpty()) {
                    JarvisRuntime.input = ""
                    syncDictationBuffer(homeCtx, "")
                    onQuickAi(t)
                }
            }
            ChipBtn("✕", false, CM) {
                JarvisRuntime.input = ""
                syncDictationBuffer(homeCtx, "")
            }
        }
    } 
        val activeSlotCnt = briefCfg.slots.count { it.enabled }
        val isAutoActive = briefCfg.enabled && activeSlotCnt > 0
        val nextBadge = ScheduleBriefingManager.nextSummaryBadge(briefCfg, now.time)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
          if (ProJarvisAccessibilityService.inst == null) {
             ChipBtn("♿", false, CW) {
              runCatching { homeCtx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
              JarvisRuntime.status = "⚙️ [Pro Launcher 자비스 음성제어]를 켜면 뒤로가기·스크롤·화면잠금·캡처 음성 제어가 동작합니다."
             }
            }
            ChipBtn(
             text = if (isAutoActive) "⏰ $nextBadge" else "🔕",
                sel = isAutoActive,
                col = if (isAutoActive) CG else CM
            ) {
                if (briefCfg.enabled) {
                    briefCfg = ScheduleBriefingManager.saveAndSync(homeCtx, briefCfg.copy(enabled = false))
                    ScheduleBriefingManager.stopBriefingPlayback(homeCtx)
                    JarvisRuntime.status = "🔕 자동 일정 브리핑 알람을 껐습니다. (설정하기 전에는 자동 낭독하지 않습니다)"
                } else {
                    if (briefCfg.slots.isEmpty()) {
                        val seed = BriefingSlot(System.currentTimeMillis(), 8, 0, "DAILY", true)
                        briefCfg = ScheduleBriefingManager.saveAndSync(homeCtx, briefCfg.copy(enabled = true, slots = listOf(seed)))
                        briefPanelOpen = true
                        JarvisRuntime.status = "⏰ 자동 브리핑 ON! 아래 [시간 설정]에서 원하는 시각을 자유롭게 변경·추가하세요."
                    } else {
                        val ensuredSlots = if (activeSlotCnt == 0) briefCfg.slots.mapIndexed { i, s -> if (i == 0) s.copy(enabled = true) else s } else briefCfg.slots
                        briefCfg = ScheduleBriefingManager.saveAndSync(homeCtx, briefCfg.copy(enabled = true, slots = ensuredSlots))
                        JarvisRuntime.status = "⏰ 자동 일정 브리핑 ON (${ScheduleBriefingManager.nextSummaryBadge(briefCfg)})"
                    }
                }
            }
            ChipBtn(
                text = if (briefCfg.slots.isEmpty()) "🕒" else "🕒${briefCfg.slots.size}",
                sel = briefPanelOpen,
                col = CA
            ) {
                briefPanelOpen = !briefPanelOpen
            }
            if (briefingSpeakingNow || SupertonicTtsEngine.playing) {
                ChipBtn("⏹️", false, Color(0xFFF87171)) {
                    ScheduleBriefingManager.stopBriefingPlayback(homeCtx)
                }
            }
            if (JarvisRuntime.status != null) {
                ChipBtn(if (aiFolded) "🔼" else "🔽", false, CM) { aiFolded = !aiFolded }
                ChipBtn("✕", false, CM) { JarvisRuntime.status = null; ScheduleBriefingManager.stopBriefingPlayback(homeCtx) }
            }
        }
        if (briefPanelOpen) {
            Surface(
                color = Color(0xFF09101D),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(0.9.dp, CA.copy(alpha = 0.65f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), SB, CV) {
                        Text("⏰ 스마트 일정 브리핑 시간 설정 (하루 다회 가능)", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CA)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
                            Text(
                                "▶️ 미리듣기",
                                fontSize = ui.subSp,
                                fontWeight = FontWeight.Bold,
                                color = CG,
                                modifier = Modifier.clickable {
                                    val py = ScheduleBriefingManager.buildVoiceBriefingPayload(allNotes.ifEmpty { importantNotes })
                                    JarvisRuntime.status = py.visualSummary
                                    speakOneShot(homeCtx, py.spokenScript)
                                }
                            )
                            Text("✕", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { briefPanelOpen = false }.padding(2.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                        ChipBtn("➕ 시:분 직접 추가", true, CG) {
                            openSlotTimePicker(8, 0) { h, m ->
                                val exists = briefCfg.slots.any { it.hour == h && it.minute == m }
                                val nxSlots = if (exists) briefCfg.slots.map { if (it.hour == h && it.minute == m) it.copy(enabled = true) else it }
                                else briefCfg.slots + BriefingSlot(System.currentTimeMillis(), h, m, "DAILY", true)
                                briefCfg = ScheduleBriefingManager.saveAndSync(homeCtx, briefCfg.copy(enabled = true, slots = nxSlots))
                                JarvisRuntime.status = "⏰ [${ScheduleBriefingManager.formatSlotTime(h, m)}] 브리핑 알람이 등록·활성화되었습니다."
                            }
                        }
                        listOf(7 to 30, 9 to 0, 13 to 0, 21 to 30).forEach { (ph, pm) ->
                            val lbl = "+%02d:%02d".format(ph, pm)
                            ChipBtn(lbl, false, CW) {
                                val exists = briefCfg.slots.any { it.hour == ph && it.minute == pm }
                                val nxSlots = if (exists) briefCfg.slots.map { if (it.hour == ph && it.minute == pm) it.copy(enabled = true) else it }
                                else briefCfg.slots + BriefingSlot(System.currentTimeMillis(), ph, pm, "DAILY", true)
                                briefCfg = ScheduleBriefingManager.saveAndSync(homeCtx, briefCfg.copy(enabled = true, slots = nxSlots))
                            }
                        }
                    }
                    if (briefCfg.slots.isEmpty()) {
                        Text(
                            "등록된 시간이 없습니다. 위 [➕ 시:분 직접 추가]를 눌러 원하는 시각을 등록할 때만 자동 브리핑이 작동합니다.",
                            fontSize = ui.subSp,
                            color = CM
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            briefCfg.slots.forEach { slot ->
                                Surface(
                                    color = if (slot.enabled && briefCfg.enabled) Color(0xFF102027) else Color(0xFF111827),
                                    shape = RoundedCornerShape(9.dp),
                                    border = BorderStroke(0.7.dp, if (slot.enabled && briefCfg.enabled) CG.copy(alpha = 0.6f) else Color(0xFF24324A)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), SB, CV) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
                                            Text(
                                                if (slot.enabled) "🔔" else "🔕",
                                                fontSize = 13.sp,
                                                modifier = Modifier.clickable {
                                                    val nx = briefCfg.slots.map { if (it.id == slot.id) it.copy(enabled = !it.enabled) else it }
                                                    val anyOn = nx.any { it.enabled }
                                                    briefCfg = ScheduleBriefingManager.saveAndSync(homeCtx, briefCfg.copy(enabled = if (!anyOn) false else briefCfg.enabled, slots = nx))
                                                }
                                            )
                                            Text(
                                                ScheduleBriefingManager.formatSlotTime(slot.hour, slot.minute),
                                                fontSize = (ui.bodySp.value + 1f).sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (slot.enabled && briefCfg.enabled) CW else CM,
                                                modifier = Modifier.clickable {
                                                    openSlotTimePicker(slot.hour, slot.minute) { nh, nm ->
                                                        val nx = briefCfg.slots.map { if (it.id == slot.id) it.copy(hour = nh, minute = nm, enabled = true) else it }
                                                        briefCfg = ScheduleBriefingManager.saveAndSync(homeCtx, briefCfg.copy(enabled = true, slots = nx))
                                                    }
                                                }
                                            )
                                            Surface(
                                                color = CA.copy(alpha = 0.16f),
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(0.7.dp, CA.copy(alpha = 0.6f)),
                                                modifier = Modifier.clickable {
                                                    val nx = briefCfg.slots.map { if (it.id == slot.id) it.copy(daysMode = ScheduleBriefingManager.nextDaysMode(it.daysMode)) else it }
                                                    briefCfg = ScheduleBriefingManager.saveAndSync(homeCtx, briefCfg.copy(slots = nx))
                                                }
                                            ) {
                                                Text(
                                                    ScheduleBriefingManager.daysLabel(slot.daysMode),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = CA,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = CV) {
                                            Text(
                                                "시간변경",
                                                fontSize = 10.sp,
                                                color = CG,
                                                modifier = Modifier.clickable {
                                                    openSlotTimePicker(slot.hour, slot.minute) { nh, nm ->
                                                        val nx = briefCfg.slots.map { if (it.id == slot.id) it.copy(hour = nh, minute = nm, enabled = true) else it }
                                                        briefCfg = ScheduleBriefingManager.saveAndSync(homeCtx, briefCfg.copy(enabled = true, slots = nx))
                                                    }
                                                }
                                            )
                                            Text(
                                                "🗑️",
                                                fontSize = 12.sp,
                                                color = Color(0xFFEF4444),
                                                modifier = Modifier.clickable {
                                                    val nx = briefCfg.slots.filterNot { it.id == slot.id }
                                                    val keepOn = briefCfg.enabled && nx.any { it.enabled }
                                                    briefCfg = ScheduleBriefingManager.saveAndSync(homeCtx, briefCfg.copy(enabled = keepOn, slots = nx))
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(0.7.dp).background(Color(0xFF1E2C45)))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
                        ChipBtn(
                            text = if (briefCfg.forceOnSilent) "🔔 무음이어도 알람음량 낭독" else "📳 무음·진동 시 매너보호(알림만)",
                            sel = !briefCfg.forceOnSilent,
                            col = if (briefCfg.forceOnSilent) CA else CG
                        ) {
                            briefCfg = ScheduleBriefingManager.saveAndSync(homeCtx, briefCfg.copy(forceOnSilent = !briefCfg.forceOnSilent))
                        }
                        ChipBtn(
                            text = if (briefCfg.skipIfEmpty) "📭 할 일 0건이면 생략 ON" else "📭 할 일 0건이어도 읽기",
                            sel = briefCfg.skipIfEmpty,
                            col = CW
                        ) {
                            briefCfg = ScheduleBriefingManager.saveAndSync(homeCtx, briefCfg.copy(skipIfEmpty = !briefCfg.skipIfEmpty))
                        }
                    }
                }
            }
        }
        JarvisRuntime.status?.let { st ->
            if (!aiFolded) {
                Box(Modifier.fillMaxWidth().heightIn(max = 105.dp).verticalScroll(rememberScrollState())) {
                    Text(st, fontSize = 12.sp, lineHeight = 17.sp, color = CG)
                }
            }
        }
    }
}
if (!isDefHome) {
val hRepo = remember(homeCtx) { LauncherRepository(homeCtx) }
val isPoco = remember { hRepo.isXiaomiOrPocoDevice() }
Surface(color = Color(0xFF14261F), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Row(Modifier.fillMaxWidth().clickable { onAskHome() }, SB, CV) {
Column(Modifier.weight(1f)) {
Text("🏠 기본 홈 화면(런처) 권한 지정하기", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CG)
Text(if (isPoco) "포코·샤오미는 ①배터리 제한해제 → ②자동실행 ON → ③기본런처 선택 시 풀리지 않습니다" else "탭하여 기본 홈 앱으로 설정하세요", fontSize = ui.subSp, color = Color(0xFFA7F3D0))
}
Text("→", fontSize = (ui.subSp.value + 1).sp, color = CG)
}
if (isPoco) {
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn("① 배터리 제한해제", hRepo.isIgnoringBatteryOptimizations(), CA) { hRepo.requestIgnoreBatteryOptimizations() }
ChipBtn("② 자동실행 켜기", false, CG) { hRepo.openXiaomiAutoStartSettings() }
ChipBtn("③ 기본 런처 선택", false, CW) { hRepo.openSystemDefaultAppsSettings() }
}
}
}
}
}
Surface(color = Color(0xFF0F1624), shape = RoundedCornerShape(16.dp), border = BorderStroke(0.8.dp, Color(0xFF222E44)), modifier = Modifier.fillMaxWidth().clickable { onRightNotes() }) { Column(Modifier.padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(4.dp)) { Row(Modifier.fillMaxWidth(), SB, CV) { Text("📌 중요 메모 (${importantNotes.size})", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CA); Text("→", fontSize = ui.subSp, color = CM) }; if (importantNotes.isEmpty()) { Text("메모에서 ★ 중요 표시를 켜면 홈 화면에서 바로 확인됩니다.", fontSize = (ui.subSp.value + 1).sp, color = CD) } else importantNotes.forEach { n -> val cat = SmartMemoClassifier.categoryOf(n, memoCats); Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { val ls = n.content.lines().filter { !it.trim().startsWith("#") && it.isNotBlank() }; ls.take(3).forEachIndexed { idx, ln -> val tr = ln.trimStart(); val isChk = tr.startsWith("☐") || tr.startsWith("☑"); val done = tr.startsWith("☑"); val origIdx = n.content.lines().indexOf(ln).coerceAtLeast(idx); Row(Modifier.fillMaxWidth().clickable { if (isChk) onToggleCheck(n.id, origIdx) else onRightNotes() }.padding(vertical = 1.dp), verticalAlignment = CV) { if (idx == 0) Text("[$cat] ", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG); Text(ln, fontSize = (ui.subSp.value + 1).sp, color = if (done) CD else Color(0xFFE5E7EB), textDecoration = if (done) TextDecoration.LineThrough else null, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) } } } } } }
}
if (!isKeyboardOpen) {
val activeHomeApps = remember(selHomeFolderId, favs, allApps, homeAppFolders) {
if (selHomeFolderId <= 0L) favs
else {
val pkgSet = homeAppFolders.find { it.id == selHomeFolderId }?.packages?.toSet() ?: emptySet()
allApps.filter { it.packageName in pkgSet }
}
}
Column(Modifier.fillMaxWidth().heightIn(min = 92.dp, max = favMaxH).padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
  Row(Modifier.fillMaxWidth(), SB, CV) {
Text("📁 홈 앱 폴더 · 앱서랍 ↑", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = CM, modifier = Modifier.clickable { onSwipeUp() }.padding(vertical = 2.dp))
Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = CV) {
Text(when (appGapMode) { 0 -> "🫧 카드(넓게)"; 1 -> "🌿 텍스트(넓게)"; else -> "▫ 기본간격" }, fontSize = 11.sp, color = CG, modifier = Modifier.clickable { val ng = (appGapMode + 1) % 3; appGapMode = ng; homeCtx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putInt("home_app_gap_mode", ng).apply() }.padding(vertical = 2.dp))
Text(when (appColMode) { 0 -> "◫ 좌·우(양끝)"; 1 -> "◫ 2열(균등)"; else -> "☰ 1열" }, fontSize = 11.sp, color = CA, modifier = Modifier.clickable { val nx = (appColMode + 1) % 3; appColMode = nx; homeCtx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putInt("home_app_col_mode", nx).apply() }.padding(vertical = 2.dp))
}
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
ChipBtn("★즐겨찾기(${favs.size})", selHomeFolderId == -1L, CA) { selHomeFolderId = -1L }
homeAppFolders.forEach { hf ->
val cnt = if (allApps.isEmpty()) hf.packages.size else allApps.count { it.packageName in hf.packages }
ChipBtn("📁${hf.name}($cnt)", selHomeFolderId == hf.id, CG) { selHomeFolderId = hf.id }
}
ChipBtn("➕", homeFolderDlgOpen, CW) { homeFolderDlgOpen = true }
}
    val vGap = when (appGapMode) { 0 -> 10.dp; 1 -> 14.dp; else -> 8.dp }
val hGap = when (appGapMode) { 0 -> 14.dp; 1 -> 24.dp; else -> 16.dp }
if (activeHomeApps.isEmpty()) {
Surface(color = Color(0xFF0E1524), shape = RoundedCornerShape(12.dp), border = BorderStroke(0.8.dp, Color(0xFF1E2B42)), modifier = Modifier.fillMaxWidth().clickable { if (selHomeFolderId > 0L) homeFolderDlgOpen = true else onSwipeUp() }) {
Text(if (selHomeFolderId > 0L) "이 폴더가 비어 있습니다. 탭하여 앱을 폴더에 담아 보세요." else "위로 스와이프(↑)하여 자주 쓰는 앱을 ★ 홈에 고정해 보세요", fontSize = ui.bodySp, color = CM, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
}
} else if (appColMode == 2) {
Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(vGap)) {
activeHomeApps.forEach { a -> HomeAppTileItem(a, appGapMode, ui.subSp, cfg.textAlign == "RIGHT", onLaunch) }
}
} else {
val leftApps = activeHomeApps.filterIndexed { i, _ -> i % 2 == 0 }
val rightApps = activeHomeApps.filterIndexed { i, _ -> i % 2 == 1 }
Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(hGap), verticalAlignment = Alignment.Top) {
Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(vGap)) {
leftApps.forEach { a -> HomeAppTileItem(a, appGapMode, ui.subSp, false, onLaunch) }
}
Column(Modifier.weight(1f), horizontalAlignment = if (appColMode == 0) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(vGap)) {
rightApps.forEach { a -> HomeAppTileItem(a, appGapMode, ui.subSp, appColMode == 0, onLaunch) }
}
}
}
}
Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { Surface(color = Color(0xFF101624), shape = RoundedCornerShape(50), border = BorderStroke(0.8.dp, Color(0xFF233047)), modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = CV) { Text("✍️", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CW, modifier = Modifier.clickable { onLeftWriter() }.padding(horizontal = 8.dp, vertical = 4.dp)); Text("📖", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CM, modifier = Modifier.clickable { onLeftReader() }.padding(horizontal = 8.dp, vertical = 4.dp)); Text("🔼앱서랍", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CW, modifier = Modifier.clickable { onSwipeUp() }.padding(horizontal = 8.dp, vertical = 4.dp)); Text("📝", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CG, modifier = Modifier.clickable { onRightNotes() }.padding(horizontal = 8.dp, vertical = 4.dp)); Text("🦙", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CA, modifier = Modifier.clickable { onRightAi() }.padding(horizontal = 8.dp, vertical = 4.dp)) } } }
}
}
 
 
} 
if (homeFolderDlgOpen) {
HomeAppFolderDialog(
ctx = homeCtx,
repo = homeRepo,
allApps = allApps.ifEmpty { favs },
folders = homeAppFolders,
ui = ui,
onUpdateFolders = { homeAppFolders = it },
onDismiss = { homeFolderDlgOpen = false }
)
}
}
