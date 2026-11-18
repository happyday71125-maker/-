package app.prolauncher.ai
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
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
import org.json.JSONArray
import org.json.JSONObject
data class BookmarkEntry(val bookId: Long, val bookTitle: String, val pageIndex: Int, val snippet: String, val charOffset: Int = -1)
fun loadBookmarks(ctx: Context): List<BookmarkEntry> = runCatching {
val raw = ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).getString("bookmarks_json", null) ?: return emptyList()
val arr = JSONArray(raw)
buildList {
for (i in 0 until arr.length()) {
val o = arr.getJSONObject(i)
add(BookmarkEntry(o.getLong("bid"), o.optString("title", "도서"), o.optInt("page", 0), o.optString("snip", ""), o.optInt("off", -1)))
}
}
}.getOrElse { emptyList() }
fun saveBookmarks(ctx: Context, list: List<BookmarkEntry>) {
val arr = JSONArray()
list.forEach { b -> arr.put(JSONObject().put("bid", b.bookId).put("title", b.bookTitle).put("page", b.pageIndex).put("snip", b.snippet).put("off", b.charOffset)) }
ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).edit().putString("bookmarks_json", arr.toString()).apply()
}
@Composable
fun ChipBtn(text: String, sel: Boolean = false, col: Color = CG, onClick: () -> Unit) {
Surface(
color = if (sel) col.copy(alpha = 0.18f) else Color(0xFF151C2C),
shape = RoundedCornerShape(50),
border = BorderStroke(if (sel) 1.dp else 0.7.dp, if (sel) col.copy(alpha = 0.75f) else Color(0xFF243047)),
modifier = Modifier.clickable { onClick() }
) {
Text(text, fontSize = 11.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, color = if (sel) col else Color(0xFFCBD5E1), maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
}
}
data class BookSortMeta(val sortOrder: Int = 0, val lastReadAt: Long = 0L, val pinned: Boolean = false, val charOffset: Int = -1)
fun loadBookMetaMap(ctx: Context): Map<Long, BookSortMeta> = runCatching {
val raw = ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).getString("book_sort_meta_v1", null) ?: return emptyMap()
val jo = JSONObject(raw); val mp = HashMap<Long, BookSortMeta>()
val ks = jo.keys()
while (ks.hasNext()) {
val k = ks.next(); val id = k.toLongOrNull() ?: continue; val o = jo.getJSONObject(k)
mp[id] = BookSortMeta(o.optInt("ord", 0), o.optLong("read", 0L), o.optBoolean("pin", false), o.optInt("off", -1))
}
mp
}.getOrElse { emptyMap() }
fun saveBookMetaMap(ctx: Context, mp: Map<Long, BookSortMeta>) = runCatching {
val jo = JSONObject()
mp.forEach { (id, m) -> jo.put(id.toString(), JSONObject().put("ord", m.sortOrder).put("read", m.lastReadAt).put("pin", m.pinned).put("off", m.charOffset)) }
ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).edit().putString("book_sort_meta_v1", jo.toString()).apply()
}
fun touchBookReadAt(ctx: Context, bid: Long, newCharOff: Int? = null): Map<Long, BookSortMeta> {
val cur = loadBookMetaMap(ctx).toMutableMap(); val prev = cur[bid] ?: BookSortMeta()
cur[bid] = prev.copy(lastReadAt = System.currentTimeMillis(), charOffset = newCharOff ?: prev.charOffset)
saveBookMetaMap(ctx, cur)
return cur
}
object ReaderTtsRuntime {
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
var on by mutableStateOf(false)
var bookId by mutableStateOf(0L)
var pageIdx by mutableStateOf(0)
var status by mutableStateOf<String?>(null)
var booksRef: List<BookItem> = emptyList()
var repoRef: LauncherRepository? = null
var saveCb: (List<BookItem>) -> Unit = {}
var job: Job? = null
fun syncState(books: List<BookItem>, repo: LauncherRepository, onSave: (List<BookItem>) -> Unit) {
booksRef = books; repoRef = repo; saveCb = onSave
}
fun start(ctx: Context, b: BookItem, startIdx: Int, initExcerpt: String = "") {
val appC = appCtxRef ?: ctx.applicationContext
if (vOn) vStop(appC); if (JarvisRuntime.on) JarvisRuntime.stop(appC)
stopOneShotDictation(); stopOneShotTts()
on = true; bookId = b.id; pageIdx = startIdx.coerceIn(0, (b.pages.size - 1).coerceAtLeast(0))
keepAw(appC, true); runLoop(appC, initExcerpt.trim())
}
fun jumpToPage(ctx: Context, newIdx: Int, initExcerpt: String = "") {
if (!on) return
val b = booksRef.find { it.id == bookId } ?: return
pageIdx = newIdx.coerceIn(0, (b.pages.size - 1).coerceAtLeast(0))
stopOneShotTts(); runLoop(appCtxRef ?: ctx.applicationContext, initExcerpt.trim())
}
fun stop(ctx: Context) {
on = false; job?.cancel(); job = null; stopOneShotTts()
status = "⏹️ 독서대 연속 낭독 중지됨"; keepAw(appCtxRef ?: ctx.applicationContext, false)
}
private fun findFirstSentenceCut(text: String, maxProbe: Int = 320): Int {
val lim = minOf(text.length, maxProbe)
for (i in 0 until lim) {
val c = text[i]
if (c == '\n' && i + 1 < text.length && text[i + 1] == '\n') return i + 2
val isDec = c == '.' && i > 0 && i + 1 < text.length && text[i - 1].isDigit() && text[i + 1].isDigit()
if (!isDec && (c == '.' || c == '!' || c == '?' || c == '。' || c == '…')) {
var end = i + 1
while (end < text.length && text[end] in charArrayOf('"', '\'', '”', '’', ')', ']', '』', '」')) end++
if (end >= text.length || text[end].isWhitespace()) return end
}
}
return -1
}
private fun runLoop(appC: Context, firstExcerpt: String) {
job?.cancel()
job = scope.launch {
var useEx = firstExcerpt
var carrySkip = 0
while (on) {
val b = booksRef.find { it.id == bookId }
if (b == null || b.pages.isEmpty()) { on = false; keepAw(appC, false); break }
val curIdx = pageIdx.coerceIn(0, b.pages.size - 1); pageIdx = curIdx
var rawTx = if (useEx.isNotEmpty()) {
carrySkip = 0
val e = useEx
useEx = ""
e
} else {
val pageRaw = b.pages.getOrElse(curIdx) { "" }
val sliced = if (carrySkip in 1..pageRaw.length) pageRaw.substring(carrySkip) else pageRaw
carrySkip = 0
sliced.trim()
}
if (rawTx.isNotBlank() && curIdx + 1 < b.pages.size) {
val lastCh = rawTx.trimEnd().lastOrNull { it != '"' && it != '\'' && it != '”' && it != '’' && it != ')' && it != ']' }
val endsClean = lastCh == null || lastCh == '.' || lastCh == '!' || lastCh == '?' || lastCh == '。' || lastCh == '…' || lastCh == ':' || lastCh == '】' || lastCh == '』'
if (!endsClean) {
val nextRaw = b.pages[curIdx + 1]
val cutNext = findFirstSentenceCut(nextRaw, 320)
if (cutNext > 0) {
val tail = nextRaw.substring(0, cutNext).trim()
if (tail.isNotEmpty()) {
rawTx = rawTx.trimEnd() + " " + tail
carrySkip = cutNext
}
}
}
}
status = "🔊 『${b.title}』 ${curIdx + 1}/${b.pages.size}섹션 낭독 중 (자동 넘김 · 화면 OFF 유지)"
if (rawTx.isNotBlank()) {
keepAw(appC, true); speakOneShot(appC, rawTx); delay(240L)
var gd = 0
while (on && gd < 14400) {
if (wakeLockRef?.isHeld != true) keepAw(appC, true)
if (!isTtsBusyNow() && (System.currentTimeMillis() - ttsLastEnqueueMs) >= 280L) break
delay(120L); gd++
}
} else delay(120L)
if (!on) break
val latestB = booksRef.find { it.id == bookId } ?: b
if (curIdx + 1 < latestB.pages.size) {
val nextIdx = curIdx + 1; pageIdx = nextIdx
val src = buildBookReflowSource(latestB.pages)
touchBookReadAt(appC, bookId, src.rawStartOf(nextIdx))
val updated = booksRef.map { if (it.id == bookId) it.copy(currentPage = nextIdx) else it }
booksRef = updated
runCatching { (repoRef ?: LauncherRepository(appC)).saveBooks(updated) }
uiP(0L) { runCatching { saveCb(updated) } }
delay(80L)
} else {
status = "✅ 『${latestB.title}』 마지막 페이지까지 낭독을 완료했습니다!"
on = false; keepAw(appC, false); break
}
}
}
}
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerSheet(apps: List<AppEntry>, onClose: () -> Unit, onLaunch: (AppEntry) -> Unit, onFav: (AppEntry) -> Unit, onInt: (AppEntry) -> Unit) {
val ctx = LocalContext.current
val ui = rememberUi(loadAiCfg(ctx).uiScale)
var q by remember { mutableStateOf("") }
var pullDown by remember { mutableStateOf(0f) }
val listState = androidx.compose.foundation.lazy.rememberLazyListState()
val downNested = remember(listState) {
object : NestedScrollConnection {
var overDown = 0f
override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
if (available.y < 0f) overDown = 0f
return Offset.Zero
}
override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
if (source == NestedScrollSource.Drag && available.y > 0f && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
overDown += available.y
if (overDown > 85f) { overDown = 0f; onClose() }
}
return Offset.Zero
}
}
}
val flt = remember(apps, q) { apps.filter { KoreanChosungMatcher.matches(it.label, q) } }
LaunchedEffect(flt, q) { if (q.trim().isNotEmpty() && !q.startsWith(" ") && flt.size == 1 && !flt.first().isInterruptEnabled) { val a = flt.first(); q = ""; onLaunch(a) } }
Column(
Modifier
.fillMaxSize()
.background(Color(0xFA07090E))
.nestedScroll(downNested)
.pointerInput(Unit) {
detectVerticalDragGestures(
onDragStart = { pullDown = 0f },
onVerticalDrag = { _, d ->
pullDown += d
if (pullDown > 75f) { pullDown = 0f; onClose() }
}
)
}
.padding(horizontal = ui.hPad + 4.dp, vertical = ui.vPad + 4.dp)
) {
Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
Box(Modifier.width(44.dp).height(4.dp).background(Color(0xFF334155), RoundedCornerShape(50)))
}
Row(Modifier.fillMaxWidth(), SB, CV) { Text("▼ 앱 서랍 (아래로 내려 닫기)", fontSize = ui.titleSp, fontWeight = FontWeight.Bold, color = CW); TextButton(onClick = onClose) { Text("✕", color = CM) } }
OutlinedTextField(value = q, onValueChange = { q = it }, placeholder = { Text("초성 검색 (예: ㅋㅋㅇㅌ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Spacer(Modifier.height(8.dp))
LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
items(flt, key = { it.packageName }) { a ->
Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onLaunch(a) }, onLongClick = { onFav(a) }).padding(vertical = 8.dp), SB, CV) {
Text(a.label + (if (a.isFavorite) " ★" else ""), fontSize = (ui.bodySp.value + 3).sp, color = Color(0xFFE5E7EB))
Text(if (a.isInterruptEnabled) "⏳★" else "⏳", fontSize = (ui.subSp.value + 1).sp, color = if (a.isInterruptEnabled) CA else Color(0xFF4B5563), modifier = Modifier.clickable { onInt(a) }.padding(6.dp))
}
}
}
}
}
@Composable
fun SettingsSheet(cfg: LauncherConfig, isDefHome: Boolean, onAskHome: () -> Unit, onSysHome: () -> Unit, onCfg: (LauncherConfig) -> Unit, onClose: () -> Unit) {
val ctx = LocalContext.current
var aiCfg by remember { mutableStateOf(loadAiCfg(ctx)) }
val ui = rememberUi(aiCfg.uiScale)
var cats by remember { mutableStateOf(SmartMemoClassifier.loadMemoCats(ctx)) }
var newCat by remember { mutableStateOf("") }
var sTab by remember { mutableStateOf(0) }
Column(Modifier.fillMaxSize().background(Color(0xFA07090E)).padding(horizontal = ui.hPad + 4.dp, vertical = ui.vPad + 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Column {
Text("⚙️ 런처 통합 환경설정", fontSize = (ui.titleSp.value + 2).sp, fontWeight = FontWeight.Bold, color = CW)
Text("항목별로 분리된 탭에서 원하는 설정을 깔끔하게 조절하세요", fontSize = ui.subSp, color = CM)
}
OutlinedButton(onClick = onClose) { Text("✕ 닫기", fontSize = ui.subSp, color = CG) }
}
SettingsTabBar(listOf("🔊 음성·TTS", "🦙 AI 모델", "🗂️ 프롬프트", "🎨 화면·홈"), sTab) { sTab = it }
Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
if (sTab in 0..2) {
AiPromptRagPanel(ctx, aiCfg, ui, onUpdate = { aiCfg = it; saveAiCfg(ctx, it) }, fixedTab = sTab)
} else {
Surface(color = Color(0xFF0E1524), shape = RoundedCornerShape(14.dp), border = BorderStroke(0.8.dp, Color(0xFF23324B)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
Text("📐 화면 글자·UI 배율", fontWeight = FontWeight.Bold, fontSize = ui.bodySp, color = CG)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
listOf("AUTO" to "자동(해상도 맞춤)", "COMPACT" to "컴팩트(작게)", "NORMAL" to "표준", "LARGE" to "크게").forEach { (k, l) ->
ChipBtn(l, aiCfg.uiScale == k, CG) { aiCfg = aiCfg.copy(uiScale = k); saveAiCfg(ctx, aiCfg) }
}
}
Box(Modifier.fillMaxWidth().height(0.8.dp).background(Color(0xFF1C2940)))
Text("🎨 배경 컬러 테마 & 정렬", fontWeight = FontWeight.Bold, fontSize = ui.bodySp, color = CA)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("AMOLED" to "🌑 아몰레드 블랙", "SLATE" to "🌌 슬레이트 네이비", "FOREST" to "🌲 포레스트 그린").forEach { (k, l) -> ChipBtn(l, cfg.themeName == k, CA) { onCfg(cfg.copy(themeName = k)) } } }
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("LEFT" to "왼쪽 정렬", "CENTER" to "가운데 정렬", "RIGHT" to "오른쪽 정렬").forEach { (k, l) -> ChipBtn(l, cfg.textAlign == k, CW) { onCfg(cfg.copy(textAlign = k)) } } }
}
}
Surface(color = Color(0xFF0E1524), shape = RoundedCornerShape(14.dp), border = BorderStroke(0.8.dp, Color(0xFF23324B)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
Text("🏷️ 기본 메모 카테고리 관리", fontWeight = FontWeight.Bold, fontSize = ui.bodySp, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
cats.forEach { c -> ChipBtn(if (cats.size > 1) "$c ✕" else c) { if (cats.size > 1) { val nx = cats.filterNot { it == c }; cats = nx; SmartMemoClassifier.saveMemoCats(ctx, nx) } } }
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 메모 카테고리 추가", fontSize = ui.subSp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !cats.contains(t)) { val nx = cats + t; cats = nx; SmartMemoClassifier.saveMemoCats(ctx, nx); newCat = "" } }) { Text("➕", fontSize = ui.subSp) }
}
}
}
Surface(color = Color(0xFF0E1524), shape = RoundedCornerShape(14.dp), border = BorderStroke(0.8.dp, Color(0xFF23324B)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("🏠 기본 홈 런처 지정", fontWeight = FontWeight.Bold, fontSize = ui.bodySp, color = CG)
Text("스마트폰 홈 버튼을 눌렀을 때 Pro Launcher AI가 바로 열리도록 설정합니다.", fontSize = ui.subSp, color = CM)
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onAskHome) { Text("🏠 기본 홈 권한 요청", fontSize = (ui.subSp.value + 1).sp) }; OutlinedButton(onClick = onSysHome) { Text("시스템 기본 앱 설정", fontSize = (ui.subSp.value + 1).sp) } }
}
}
Surface(color = Color(0xFF0E1524), shape = RoundedCornerShape(14.dp), border = BorderStroke(0.8.dp, Color(0xFF23324B)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("🤖 자비스 음성 호출·접근성·도움말", fontWeight = FontWeight.Bold, fontSize = ui.bodySp, color = CA)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn(if (wakeOnly) "👂 호출어 대기 ON (${wakeWord})" else "🎙️ 상시 반응 모드", wakeOnly, CA) { setWakeOnly(ctx, !wakeOnly) }
ChipBtn(if (ProJarvisAccessibilityService.inst != null) "🤖 접근성 제어 ON" else "⚙️ 접근성 권한 켜기", ProJarvisAccessibilityService.inst != null, CG) {
runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
ChipBtn("❓ 전체 기능 도움말", false, CW) { onClose(); showEmojiHelp = true }
}
}
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
TextButton(onClick = { if (left == 0) onGo() }, enabled = left == 0) { Text(if (left > 0) "${left}초 후 열기 가능..." else "그래도 ${app.label} 열기") }
}
}
}
