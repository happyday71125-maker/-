package app.prolauncher.ai
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
@Composable
fun ReaderView(
books: List<BookItem>, cfg: LauncherConfig, repo: LauncherRepository,
onSaveBooks: (List<BookItem>) -> Unit, onCfg: (LauncherConfig) -> Unit,
onQuote: (String) -> Unit, onHome: () -> Unit, onQuickAddNote: (String) -> Unit = {}
) {
val ctx = LocalContext.current; val aiCfg = remember { loadAiCfg(ctx) }; val ui = rememberUi(aiCfg.uiScale); val scope = rememberCoroutineScope(); EmojiHelpDialog()
SideEffect { ReaderTtsRuntime.syncState(books, repo, onSaveBooks) }
var twoPageMode by remember { mutableStateOf(ctx.getSharedPreferences("pl_ai_v2_prefs", 0).getBoolean("reader_two_page", false)) }
var bCats by remember { mutableStateOf(SmartMemoClassifier.loadBookCats(ctx)) }; var selCat by remember { mutableStateOf("전체") }; var catBarOpen by remember { mutableStateOf(false) }
val allCats = remember(bCats, books) { (listOf("전체") + (bCats + books.map { it.category.ifBlank { "기타" } }).distinct()) }
var bms by remember { mutableStateOf(loadBookmarks(ctx)) }; var bmOpen by remember { mutableStateOf(false) }
var selId by remember { mutableStateOf(books.firstOrNull()?.id ?: 1001L) }; var shelf by remember { mutableStateOf(false) }; var cfgOpen by remember { mutableStateOf(false) }
var rCfgTab by remember { mutableStateOf(0) }; var ttsBarMin by remember { mutableStateOf(false) }
var sortMode by remember { mutableStateOf(ctx.getSharedPreferences("pl_ai_v2_prefs", 0).getString("reader_book_sort", "CUSTOM") ?: "CUSTOM") }
var reorderMode by remember { mutableStateOf(false) }; var bookQuery by remember { mutableStateOf("") }; var newCat by remember { mutableStateOf("") }
var quoteConfirmText by remember { mutableStateOf<String?>(null) }
var bookMeta by remember { mutableStateOf(loadBookMetaMap(ctx)) }
fun metaOf(id: Long, defOrd: Int = 0) = bookMeta[id] ?: BookSortMeta(sortOrder = defOrd)
fun setBookSort(m: String) { sortMode = m; ctx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putString("reader_book_sort", m).apply() }
fun toggleBookPin(targetId: Long) { val mp = bookMeta.toMutableMap(); val pr = mp[targetId] ?: BookSortMeta(); mp[targetId] = pr.copy(pinned = !pr.pinned); bookMeta = mp; saveBookMetaMap(ctx, mp) }
fun markBookRead(targetId: Long, off: Int? = null) { bookMeta = touchBookReadAt(ctx, targetId, off) }
fun moveBookOrder(targetId: Long, actType: Int) {
val cur = books.mapIndexed { idx, bk -> idx to bk }.sortedWith(Comparator { p1, p2 ->
val m1 = metaOf(p1.second.id, p1.first); val m2 = metaOf(p2.second.id, p2.first)
if (m1.pinned != m2.pinned) (if (m1.pinned) -1 else 1) else m1.sortOrder.compareTo(m2.sortOrder).let { if (it != 0) it else p1.first.compareTo(p2.first) }
}).map { it.second }.toMutableList()
val idx0 = cur.indexOfFirst { it.id == targetId }; if (idx0 < 0) return
val item = cur.removeAt(idx0)
val dst = when (actType) { -2 -> 0; -1 -> (idx0 - 1).coerceAtLeast(0); 1 -> (idx0 + 1).coerceAtMost(cur.size); else -> cur.size }
cur.add(dst, item)
val mp = bookMeta.toMutableMap()
cur.forEachIndexed { i, bk -> val pr = mp[bk.id] ?: BookSortMeta(); mp[bk.id] = pr.copy(sortOrder = i) }
bookMeta = mp; saveBookMetaMap(ctx, mp); setBookSort("CUSTOM")
}
var readerAiOpen by remember { mutableStateOf(false) }; var readerAiQ by remember { mutableStateOf("") }; var readerAiAns by remember { mutableStateOf("") }
var readerAiBusy by remember { mutableStateOf(false) }; var readerAiNotice by remember { mutableStateOf<String?>(null) }; var readerAiJob by remember { mutableStateOf<Job?>(null) }
val act = books.find { it.id == selId } ?: books.firstOrNull()
fun askPageAi(askP: String, bT: String, pLabel: String, pTx: String, isPart: Boolean) {
if (pTx.isBlank()) return
readerAiJob?.cancel(); if (ProLlamaJni.ready) runCatching { ProLlamaJni.abortGeneration() }
val g = ++activeGenId; readerAiBusy = true; readerAiNotice = null
val tag = if (isPart) "선택 구절(${pTx.length}자)" else "${pLabel} 본문"
readerAiAns = "⚡ [$tag] 실시간 분석 중... ▍"
readerAiJob = scope.launch {
val cc = loadAiCfg(ctx)
val hdr = if (isPart) "도서: 『$bT』 ($pLabel 중 선택 구절)\n[선택 구절]\n${pTx.take(1200)}" else "도서: 『$bT』 ($pLabel)\n[본문]\n${pTx.take(1200)}"
val ans = withContext(Dispatchers.IO) {
callLlmHttp("도서 본문만을 근거로 한국어로 명쾌하게 해설하세요.", "", emptyList(), "$hdr\n\n[요청]\n$askP", cc.apiUrl, cc.apiModel, cc.apiKey, { lv ->
if (g == activeGenId && lv.isNotBlank()) uiP { readerAiAns = lv + " ▍" }
}, g, cc.temp, cc.ctxSize, cc.maxTok.coerceAtLeast(512), cc.cpuMode, cc.histTurns)
}
if (g == activeGenId) {
readerAiAns = (ans ?: "").ifBlank { readerAiAns.removeSuffix(" ▍").ifBlank { "⚠️ 해설을 생성하지 못했습니다." } }
readerAiBusy = false
}
}
}
val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
if (u != null) scope.launch(Dispatchers.IO) {
repo.importBookFromUri(u)?.let { b0 ->
val b = if (selCat != "전체") b0.copy(category = selCat) else b0
withContext(Dispatchers.Main) { onSaveBooks(listOf(b) + books); selId = b.id; shelf = false }
}
}
}
val bg = when (cfg.readerTheme) { "SEPIA" -> Color(0xFFF4ECD8); "PAPER" -> Color(0xFFFAF8F5); else -> Color(0xFF07090E) }
val pageSurf = when (cfg.readerTheme) { "SEPIA" -> Color(0xFFF8F1DF); "PAPER" -> Color(0xFFFFFFFF); else -> Color(0xFF0C111B) }
val fg = when (cfg.readerTheme) { "SEPIA" -> Color(0xFF2C221E); "PAPER" -> Color(0xFF1C1917); else -> Color(0xFFE5E7EB) }
val mut = when (cfg.readerTheme) { "SEPIA" -> Color(0xFF6E5A4F); "PAPER" -> Color(0xFF78716C); else -> CM }
val spineBase = when (cfg.readerTheme) { "SEPIA" -> Color(0xFF3E2D21); "PAPER" -> Color(0xFF292524); else -> Color(0xFF000000) }
Column(Modifier.fillMaxSize().background(bg).padding(horizontal = ui.hPad, vertical = ui.vPad)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = CV) {
TextButton(onClick = { shelf = !shelf; bmOpen = false }) { Text(if (shelf) "📖" else "📚${books.size}", fontSize = ui.bodySp, color = fg, fontWeight = FontWeight.Bold) }
OutlinedButton(onClick = { bmOpen = !bmOpen; if (bmOpen) shelf = false }) { Text("🔖${bms.size}", fontSize = ui.subSp, color = if (bmOpen) CA else fg) }
OutlinedButton(onClick = { twoPageMode = !twoPageMode; ctx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putBoolean("reader_two_page", twoPageMode).apply() }) { Text(if (twoPageMode) "📖2쪽" else "📄1쪽", fontSize = ui.subSp, color = if (twoPageMode) CG else fg) }
}
Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = CV) {
OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("📂", fontSize = ui.subSp, color = fg) }
OutlinedButton(onClick = { cfgOpen = !cfgOpen }) { Text("⚙️", fontSize = ui.subSp, color = fg) }
TextButton(onClick = { showEmojiHelp = true }) { Text("❓", fontSize = ui.subSp, color = mut) }
TextButton(onClick = onHome) { Text("🏠", fontSize = (ui.subSp.value + 1).sp, color = mut) }
}
}
if (ReaderTtsRuntime.on && !ttsBarMin) {
SideEffect { SupertonicTtsEngine.init(ctx) }
val bNow = books.find { it.id == ReaderTtsRuntime.bookId }
Surface(color = Color(0xE60D1626), shape = RoundedCornerShape(50), border = BorderStroke(0.8.dp, CG.copy(alpha = 0.7f)), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), SB, CV) {
Text("🔊 ${bNow?.title?.take(10) ?: "도서"} ${ReaderTtsRuntime.pageIdx + 1}/${bNow?.pages?.size ?: 1} · ${String.format(java.util.Locale.US, "%.2fx", SupertonicTtsEngine.speed)} · 💎${SupertonicTtsEngine.steps}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CG, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CV) {
Text("➖", fontSize = 11.sp, color = CM, modifier = Modifier.clickable { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, SupertonicTtsEngine.steps, ((SupertonicTtsEngine.speed * 20f).toInt() - 1).coerceIn(12, 36) / 20f) })
Text("➕", fontSize = 11.sp, color = CA, modifier = Modifier.clickable { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, SupertonicTtsEngine.steps, ((SupertonicTtsEngine.speed * 20f).toInt() + 1).coerceIn(12, 36) / 20f) })
Text("🎛️", fontSize = 11.sp, color = CW, modifier = Modifier.clickable { rCfgTab = 1; cfgOpen = !cfgOpen })
Text("🔽숨김", fontSize = 10.sp, color = CM, modifier = Modifier.clickable { ttsBarMin = true })
Text("⏹️", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF87171), modifier = Modifier.clickable { ReaderTtsRuntime.stop(ctx) })
}
}
}
} else if (!ReaderTtsRuntime.on && ReaderTtsRuntime.status?.startsWith("✅") == true) {
Surface(color = Color(0xFF0F1A2C), shape = RoundedCornerShape(8.dp), border = BorderStroke(0.8.dp, CA), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), SB, CV) {
Text(ReaderTtsRuntime.status ?: "", fontSize = ui.subSp, color = CA, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
Text("✕", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { ReaderTtsRuntime.status = null }.padding(start = 8.dp))
}
}
}
if (bmOpen) {
Surface(color = Color(0xFF0F1624), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CA), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(max = ui.panelMaxH)) {
Column(Modifier.padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) { Text("🔖 북마크 목록", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CA); Text("✕", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { bmOpen = false }) }
if (bms.isEmpty()) Text("저장된 북마크가 없습니다.", fontSize = ui.subSp, color = CM)
else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
items(bms, key = { "${it.bookId}_${it.pageIndex}_${it.charOffset}" }) { bm ->
Surface(color = CS, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable {
val targetB = books.find { it.id == bm.bookId }
val targetOff = if (bm.charOffset >= 0) bm.charOffset else targetB?.let { buildBookReflowSource(it.pages).rawStartOf(bm.pageIndex) } ?: 0
markBookRead(bm.bookId, targetOff)
selId = bm.bookId
onSaveBooks(books.map { if (it.id == bm.bookId) it.copy(currentPage = bm.pageIndex) else it })
if (ReaderTtsRuntime.on && ReaderTtsRuntime.bookId == bm.bookId) ReaderTtsRuntime.jumpToPage(ctx, bm.pageIndex)
shelf = false; bmOpen = false
}) {
Row(Modifier.padding(10.dp), SB, CV) {
Column(Modifier.weight(1f)) {
Text("🔖 ${bm.bookTitle} · 섹션 ${bm.pageIndex + 1}", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CW)
Text(bm.snippet, fontSize = ui.subSp, color = CM, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
Text("✕", fontSize = (ui.subSp.value + 1).sp, color = Color(0xFFEF4444), modifier = Modifier.clickable {
val nx = bms.filterNot { it.bookId == bm.bookId && it.pageIndex == bm.pageIndex && it.charOffset == bm.charOffset }
bms = nx; saveBookmarks(ctx, nx)
}.padding(4.dp))
}
}
}
}
}
}
}
if (cfgOpen) {
SideEffect { SupertonicTtsEngine.init(ctx) }
Surface(color = Color(0xFF0E1524), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CG.copy(alpha = 0.75f)), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(max = ui.panelMaxH)) {
Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("⚙️ 독서대 전용 설정", fontSize = (ui.subSp.value + 1.5f).sp, fontWeight = FontWeight.Bold, color = CW)
Text("✕ 닫기", fontSize = ui.subSp, fontWeight = FontWeight.SemiBold, color = CG, modifier = Modifier.clickable { cfgOpen = false }.padding(4.dp))
}
SettingsTabBar(listOf("📖 화면·글꼴", "🔊 TTS 낭독", "🏷️ 분류 관리"), rCfgTab) { rCfgTab = it }
when (rCfgTab) {
0 -> {
Surface(color = Color(0xFF09101C), shape = RoundedCornerShape(11.dp), border = BorderStroke(0.8.dp, Color(0xFF22314A)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("구글북 스타일 연속 페이지 조판 & 테마", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CA)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn("📄 1쪽 맞춤 보기", !twoPageMode, CG) { twoPageMode = false; ctx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putBoolean("reader_two_page", false).apply() }
ChipBtn("📖 2쪽 연속 펼침 (구글북 스타일)", twoPageMode, CG) { twoPageMode = true; ctx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putBoolean("reader_two_page", true).apply() }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
listOf("SEPIA" to "📜 세피아", "PAPER" to "📄 밝은종이", "NIGHT" to "🌙 야간모드").forEach { (k, l) -> ChipBtn(l, cfg.readerTheme == k, CA) { onCfg(cfg.copy(readerTheme = k)) } }
}
Box(Modifier.fillMaxWidth().height(0.8.dp).background(Color(0xFF1C2940)))
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("서체 및 글자 크기 (${cfg.readerFontSizeSp}pt · 자동 재조판)", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CW)
Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn(if (cfg.readerUseSerif) "✒️ 명조체" else "🔤 고딕체", true, CG) { onCfg(cfg.copy(readerUseSerif = !cfg.readerUseSerif)) }
ChipBtn("➖ 축소") { onCfg(cfg.copy(readerFontSizeSp = (cfg.readerFontSizeSp - 2).coerceAtLeast(14))) }
ChipBtn("➕ 확대") { onCfg(cfg.copy(readerFontSizeSp = (cfg.readerFontSizeSp + 2).coerceAtMost(28))) }
}
}
}
}
}
1 -> {
Surface(color = Color(0xFF09101C), shape = RoundedCornerShape(11.dp), border = BorderStroke(0.8.dp, Color(0xFF22314A)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("음성 엔진 & 초경량 모델 받기 (화면 꺼져도 연속 낭독 유지)", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG)
Text(SupertonicTtsEngine.statusText, fontSize = ui.subSp, color = CM)
SupertonicTtsEngine.dlProgress?.let { dp -> Text(dp, fontSize = ui.subSp, color = CG) }
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn(if (SupertonicTtsEngine.enabled) "🔊 Supertonic ON" else "📱 기본 TTS", SupertonicTtsEngine.enabled, CG) { SupertonicTtsEngine.setMode(ctx, !SupertonicTtsEngine.enabled) }
ChipBtn("⬇️🪶 INT8 초경량(105MB)", SupertonicTtsEngine.modelTier == "INT8", CG) { SupertonicTtsEngine.downloadSupertonicTier(ctx, "INT8") }
ChipBtn("⬇️⚡ FP16(195MB)", SupertonicTtsEngine.modelTier == "FP16", CA) { SupertonicTtsEngine.downloadSupertonicTier(ctx, "FP16") }
ChipBtn("⬇️👑 FP32(398MB)", SupertonicTtsEngine.modelTier == "FP32", CW) { SupertonicTtsEngine.downloadSupertonicTier(ctx, "FP32") }
}
Box(Modifier.fillMaxWidth().height(0.8.dp).background(Color(0xFF1C2940)))
SupertonicControlSection(ctx, ui, compact = false)
}
}
}
else -> {
Surface(color = Color(0xFF09101C), shape = RoundedCornerShape(11.dp), border = BorderStroke(0.8.dp, Color(0xFF22314A)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("🏷️ 독서대 도서 카테고리 편집 (탭하여 삭제)", fontSize = ui.subSp, color = CA, fontWeight = FontWeight.Bold)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
bCats.forEach { c -> ChipBtn(if (bCats.size > 1) "$c ✕" else c) { if (bCats.size > 1) { val u = bCats.filterNot { it == c }; bCats = u; SmartMemoClassifier.saveBookCats(ctx, u); if (selCat == c) selCat = "전체" } } }
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 도서 카테고리 이름 (예: 철학, 소설, 역사)", fontSize = ui.subSp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !bCats.contains(t)) { val u = bCats + t; bCats = u; SmartMemoClassifier.saveBookCats(ctx, u); newCat = "" } }) { Text("➕", fontSize = ui.subSp) }
}
}
}
}
}
}
}
}
if (shelf || act == null) {
ReaderShelfSection(
ctx = ctx, ui = ui, books = books, bCats = bCats, allCats = allCats,
selCat = selCat, onSelCat = { selCat = it }, onOpenCatCfg = { rCfgTab = 2; cfgOpen = true },
cfgOpen = cfgOpen, rCfgTab = rCfgTab, sortMode = sortMode,
onSetSort = { setBookSort(it); if (it != "CUSTOM") reorderMode = false },
reorderMode = reorderMode, onToggleReorder = { reorderMode = !reorderMode; if (reorderMode) setBookSort("CUSTOM") },
bookQuery = bookQuery, onBookQuery = { bookQuery = it }, bookMeta = bookMeta, bms = bms,
fg = fg, mut = mut, onSaveBooks = onSaveBooks,
onOpenBook = { bk, withTts -> markBookRead(bk.id); selId = bk.id; shelf = false; if (withTts) { ttsBarMin = false; ReaderTtsRuntime.start(ctx, bk, bk.currentPage) } },
onTogglePin = { toggleBookPin(it) }, onMoveOrder = { bid, actT -> moveBookOrder(bid, actT) },
onDeleteBook = { bid -> val nx = books.filterNot { it.id == bid }; onSaveBooks(nx); if (selId == bid) selId = nx.firstOrNull()?.id ?: 0L }
)
} else {
val reflowSrc = remember(act.id, act.pages) { buildBookReflowSource(act.pages) }
val ttsHere = ReaderTtsRuntime.on && ReaderTtsRuntime.bookId == act.id
val savedCharOff = bookMeta[act.id]?.charOffset ?: -1
var curCharOff by remember(act.id) {
val initOff = if (savedCharOff in 0..reflowSrc.fullText.length) savedCharOff else reflowSrc.rawStartOf(act.currentPage)
mutableStateOf(initOff)
}
LaunchedEffect(savedCharOff) {
if (savedCharOff in 0..reflowSrc.fullText.length && savedCharOff != curCharOff && !ttsHere) curCharOff = savedCharOff
}
LaunchedEffect(ttsHere, ReaderTtsRuntime.pageIdx) {
if (ttsHere) curCharOff = reflowSrc.rawStartOf(ReaderTtsRuntime.pageIdx)
}
val textMeasurer = rememberTextMeasurer()
val pageTextStyle = remember(cfg.readerFontSizeSp, cfg.readerUseSerif, fg) {
TextStyle(
fontSize = cfg.readerFontSizeSp.sp,
lineHeight = (cfg.readerFontSizeSp * 1.58f).sp,
fontFamily = if (cfg.readerUseSerif) FontFamily.Serif else FontFamily.SansSerif,
color = fg
)
}
var vPages by remember(act.id, reflowSrc.fullText) {
mutableStateOf(listOf(PageSlice(0, reflowSrc.fullText.length.coerceAtMost(1200))))
}
val stepPg = if (twoPageMode) 2 else 1
val rawVIdx = remember(vPages, curCharOff) {
val found = vPages.indexOfLast { it.s <= curCharOff }
if (found >= 0) found else 0
}
val vIdx = (if (twoPageMode) (rawVIdx / 2) * 2 else rawVIdx).coerceIn(0, (vPages.size - 1).coerceAtLeast(0))
val leftSlice = vPages.getOrElse(vIdx) { PageSlice(0, 0) }
val leftPageTx = remember(reflowSrc.fullText, leftSlice) {
reflowSrc.fullText.substring(leftSlice.s.coerceIn(0, reflowSrc.fullText.length), leftSlice.e.coerceIn(0, reflowSrc.fullText.length)).trimEnd('\n', '\r')
}
val hasRightPage = twoPageMode && (vIdx + 1 < vPages.size)
val rightSlice = if (hasRightPage) vPages[vIdx + 1] else null
val rightPageTx = remember(reflowSrc.fullText, rightSlice) {
if (rightSlice == null) "" else reflowSrc.fullText.substring(rightSlice.s.coerceIn(0, reflowSrc.fullText.length), rightSlice.e.coerceIn(0, reflowSrc.fullText.length)).trimEnd('\n', '\r')
}
val spreadText = remember(leftPageTx, rightPageTx, hasRightPage) {
if (hasRightPage) (leftPageTx + "\n" + rightPageTx).trim() else leftPageTx
}
val spreadEndIdx = if (hasRightPage) vIdx + 2 else vIdx + 1
val pgLabel = if (hasRightPage) "${vIdx + 1}–${vIdx + 2} / ${vPages.size}쪽" else "${vIdx + 1} / ${vPages.size}쪽"
val progressPct = ((spreadEndIdx * 100) / vPages.size.coerceAtLeast(1)).coerceIn(1, 100)
val curRawIdx = reflowSrc.rawIdxOf(leftSlice.s)
val curSpreadEndChar = rightSlice?.e ?: leftSlice.e
val isBm = bms.any {
it.bookId == act.id && (if (it.charOffset >= 0) it.charOffset in leftSlice.s..curSpreadEndChar else it.pageIndex == curRawIdx)
}
fun goToVPage(targetV: Int) {
if (vPages.isEmpty()) return
val aligned = if (twoPageMode) (targetV / 2) * 2 else targetV
val maxStart = if (twoPageMode) ((vPages.size - 1).coerceAtLeast(0) / 2) * 2 else (vPages.size - 1).coerceAtLeast(0)
val nextV = aligned.coerceIn(0, maxStart)
if (nextV == vIdx) return
val newOff = vPages[nextV].s
val newRaw = reflowSrc.rawIdxOf(newOff)
curCharOff = newOff; readerAiAns = ""
markBookRead(act.id, newOff)
if (act.currentPage != newRaw) onSaveBooks(books.map { if (it.id == act.id) it.copy(currentPage = newRaw) else it })
if (ttsHere) {
val remRaw = reflowSrc.fullText.substring(newOff.coerceIn(0, reflowSrc.fullText.length), reflowSrc.rawEndOf(newRaw).coerceIn(0, reflowSrc.fullText.length)).trim()
ReaderTtsRuntime.jumpToPage(ctx, newRaw, remRaw)
}
}
val segments = remember(act.id, vIdx, spreadText) {
spreadText.lines().map { it.trim() }.filter { it.isNotEmpty() }.flatMap { ln ->
if (ln.length <= 130) listOf(ln) else ln.split(Regex("(?<=[.!?。])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
}.ifEmpty { if (spreadText.isNotBlank()) listOf(spreadText.trim()) else emptyList() }
}
var selectMode by remember(act.id, vIdx) { mutableStateOf(false) }
var selSet by remember(act.id, vIdx) { mutableStateOf(setOf<Int>()) }
var customExcerpt by remember(act.id, vIdx) { mutableStateOf("") }
var editExcerptOpen by remember(act.id, vIdx) { mutableStateOf(false) }
val activeExcerpt = customExcerpt.trim().ifEmpty { if (selSet.isNotEmpty()) segments.filterIndexed { i, _ -> i in selSet }.joinToString("\n") else "" }
val hasSel = activeExcerpt.isNotBlank()
val aiTargetText = if (hasSel) activeExcerpt else spreadText
Row(Modifier.fillMaxWidth().padding(top = 4.dp), SB, CV) {
Row(verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
Surface(color = CG.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp), border = BorderStroke(0.8.dp, CG.copy(alpha = 0.65f)), modifier = Modifier.clickable { catBarOpen = !catBarOpen }) {
Text("${act.category.ifBlank { "분류" }} ▾", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
}
Text(act.title, fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.SemiBold, color = mut, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = CV) {
ChipBtn(if (hasSel) "✂️${selSet.size.coerceAtLeast(1)}" else "✂️", selectMode || hasSel, CA) { selectMode = !selectMode }
ChipBtn(if (ttsHere) "⏹️" else "🔊", ttsHere, CG) {
if (ttsHere) ReaderTtsRuntime.stop(ctx) else {
val startEx = if (hasSel) activeExcerpt else reflowSrc.fullText.substring(leftSlice.s.coerceIn(0, reflowSrc.fullText.length), reflowSrc.rawEndOf(curRawIdx).coerceIn(0, reflowSrc.fullText.length)).trim()
ReaderTtsRuntime.start(ctx, act, curRawIdx, startEx)
}
}
ChipBtn(if (isBm) "🔖★" else "🔖", isBm, CA) {
val nx = if (isBm) bms.filterNot { it.bookId == act.id && (if (it.charOffset >= 0) it.charOffset in leftSlice.s..curSpreadEndChar else it.pageIndex == curRawIdx) }
else listOf(BookmarkEntry(act.id, act.title, curRawIdx, aiTargetText.replace("\n", " ").take(45), leftSlice.s)) + bms
bms = nx; saveBookmarks(ctx, nx)
}
}
}
if (catBarOpen) {
Surface(color = Color(0xFF111927), shape = RoundedCornerShape(10.dp), border = BorderStroke(0.8.dp, CG.copy(alpha = 0.5f)), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
(bCats + listOf("인문·철학", "소설", "에세이", "자기계발", "경제·경영", "기타")).distinct().forEach { c ->
ChipBtn(c, act.category == c, CG) {
if (!bCats.contains(c)) { bCats = bCats + c; SmartMemoClassifier.saveBookCats(ctx, bCats) }
onSaveBooks(books.map { if (it.id == act.id) it.copy(category = c) else it }); catBarOpen = false
}
}
ChipBtn("➕", false, CW) { rCfgTab = 2; cfgOpen = true; catBarOpen = false }
}
}
}
}
if (selectMode || hasSel) {
Surface(color = Color(0xFF101929), shape = RoundedCornerShape(10.dp), border = BorderStroke(0.9.dp, CA.copy(alpha = 0.65f)), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
Text(if (hasSel) "🎯${activeExcerpt.length}" else "✂️", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CA)
if (segments.isNotEmpty()) {
ChipBtn("☑️", selSet.size == segments.size, CG) { val allIdx = segments.indices.toSet(); selSet = allIdx; customExcerpt = segments.joinToString("\n") }
if (hasSel) ChipBtn("🔄", false, CM) { selSet = emptySet(); customExcerpt = ""; editExcerptOpen = false }
}
ChipBtn("✏️", editExcerptOpen, CW) {
if (!editExcerptOpen && customExcerpt.isBlank()) customExcerpt = if (selSet.isNotEmpty()) segments.filterIndexed { i, _ -> i in selSet }.joinToString("\n") else spreadText
editExcerptOpen = !editExcerptOpen
}
if (hasSel) {
ChipBtn("🔈", false, CG) { speakOneShot(ctx, activeExcerpt) }
ChipBtn("🦙", readerAiOpen, CG) { readerAiOpen = true; askPageAi("내가 선택한 이 구절의 핵심 의미와 맥락을 쉽게 해설해줘.", act.title, pgLabel, activeExcerpt, true) }
}
}
if (editExcerptOpen) {
OutlinedTextField(value = customExcerpt, onValueChange = { customExcerpt = it }, label = { Text("AI에 넣을 구절 직접 다듬기", fontSize = ui.subSp) }, singleLine = false, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
}
}
}
}
BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp)) {
val density = LocalDensity.current
val totalW = this.maxWidth
val totalH = this.maxHeight
val outerPad = if (twoPageMode) 12.dp else 14.dp
val spineW = if (twoPageMode) 20.dp else 0.dp
val colW = if (twoPageMode) ((totalW - outerPad * 2 - spineW) / 2f).coerceAtLeast(80.dp) else (totalW - outerPad * 2).coerceAtLeast(100.dp)
val textH = (totalH - 56.dp).coerceAtLeast(60.dp)
val colWPx = with(density) { colW.roundToPx() }
val textHPx = with(density) { textH.toPx() } - 2f
LaunchedEffect(act.id, reflowSrc.fullText, colWPx, textHPx, cfg.readerFontSizeSp, cfg.readerUseSerif) {
if (colWPx > 40 && textHPx > 40f) {
val pagesComputed = withContext(Dispatchers.Default) {
runCatching { paginateBookReflow(reflowSrc.fullText, colWPx, textHPx, pageTextStyle, textMeasurer) }.getOrElse { listOf(PageSlice(0, reflowSrc.fullText.length)) }
}
vPages = pagesComputed
}
}
val latestTurn by rememberUpdatedState<(Int) -> Unit> { delta -> goToVPage(vIdx + delta * stepPg) }
val latestToggleSelect by rememberUpdatedState { selectMode = !selectMode }
if (selectMode) {
Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
segments.forEachIndexed { sIdx, seg ->
val isSel = sIdx in selSet
Surface(
color = if (isSel) CG.copy(alpha = 0.16f) else mut.copy(alpha = 0.08f),
shape = RoundedCornerShape(10.dp),
border = BorderStroke(if (isSel) 1.2.dp else 0.6.dp, if (isSel) CG else mut.copy(alpha = 0.3f)),
modifier = Modifier.fillMaxWidth().clickable {
val nx = if (isSel) selSet - sIdx else selSet + sIdx
selSet = nx; customExcerpt = segments.filterIndexed { i, _ -> i in nx }.joinToString("\n")
}
) {
Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
Text(if (isSel) "☑" else "☐", fontSize = (cfg.readerFontSizeSp - 1).sp, fontWeight = FontWeight.Bold, color = if (isSel) CG else mut)
Text(seg, style = pageTextStyle, modifier = Modifier.weight(1f))
}
}
}
}
} else {
var dragAccum by remember { mutableStateOf(0f) }
Surface(
color = pageSurf,
shape = RoundedCornerShape(12.dp),
border = BorderStroke(0.8.dp, mut.copy(alpha = 0.28f)),
modifier = Modifier
.fillMaxSize()
.pointerInput(act.id, twoPageMode) {
detectHorizontalDragGestures(
onDragStart = { dragAccum = 0f },
onDragEnd = {
if (dragAccum < -52f) latestTurn(1)
else if (dragAccum > 52f) latestTurn(-1)
dragAccum = 0f
}
) { _, dragAmount -> dragAccum += dragAmount }
}
.pointerInput(act.id, twoPageMode) {
detectTapGestures { offset ->
val w = size.width.toFloat().coerceAtLeast(1f)
when {
offset.x < w * 0.24f -> latestTurn(-1)
offset.x > w * 0.76f -> latestTurn(1)
else -> latestToggleSelect()
}
}
}
) {
if (twoPageMode) {
Row(Modifier.fillMaxSize().padding(horizontal = outerPad), verticalAlignment = Alignment.Top) {
Column(Modifier.width(colW).fillMaxHeight().padding(top = 8.dp, bottom = 6.dp)) {
Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
Text(act.title, fontSize = 10.sp, color = mut.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
}
Spacer(Modifier.height(4.dp))
Box(Modifier.width(colW).height(textH)) {
Text(leftPageTx, style = pageTextStyle, overflow = TextOverflow.Clip, modifier = Modifier.fillMaxSize())
}
Spacer(Modifier.height(4.dp))
Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
Text("${vIdx + 1}", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = mut)
}
}
Box(
Modifier
.width(spineW)
.fillMaxHeight()
.background(
Brush.horizontalGradient(
listOf(
Color.Transparent,
spineBase.copy(alpha = 0.08f),
spineBase.copy(alpha = 0.22f),
spineBase.copy(alpha = 0.08f),
Color.Transparent
)
)
),
contentAlignment = Alignment.Center
) {
Box(Modifier.width(1.dp).fillMaxHeight(0.94f).background(mut.copy(alpha = 0.26f)))
}
Column(Modifier.width(colW).fillMaxHeight().padding(top = 8.dp, bottom = 6.dp)) {
Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterEnd) {
Text("${act.category.ifBlank { "독서" }} · ${progressPct}%", fontSize = 10.sp, color = mut.copy(alpha = 0.8f), maxLines = 1)
}
Spacer(Modifier.height(4.dp))
Box(Modifier.width(colW).height(textH)) {
if (hasRightPage) {
Text(rightPageTx, style = pageTextStyle, overflow = TextOverflow.Clip, modifier = Modifier.fillMaxSize())
} else {
Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
Text("— 마지막 페이지 —", fontSize = ui.subSp, color = mut.copy(alpha = 0.65f))
}
}
}
Spacer(Modifier.height(4.dp))
Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterEnd) {
Text(if (hasRightPage) "${vIdx + 2}" else "", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = mut)
}
}
}
} else {
Column(Modifier.fillMaxSize().padding(horizontal = outerPad, vertical = 7.dp)) {
Row(Modifier.fillMaxWidth().height(16.dp), SB, CV) {
Text(act.title, fontSize = 10.sp, color = mut.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
Text("${progressPct}%", fontSize = 10.sp, color = mut.copy(alpha = 0.8f))
}
Spacer(Modifier.height(4.dp))
Box(Modifier.width(colW).height(textH)) {
Text(leftPageTx, style = pageTextStyle, overflow = TextOverflow.Clip, modifier = Modifier.fillMaxSize())
}
Spacer(Modifier.height(4.dp))
Row(Modifier.fillMaxWidth().height(18.dp), SB, CV) {
Text("좌·우 터치 또는 스와이프로 넘김", fontSize = 10.sp, color = mut.copy(alpha = 0.6f))
Text("${vIdx + 1} / ${vPages.size}", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = mut)
}
}
}
}
}
}
Row(Modifier.fillMaxWidth().padding(bottom = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn(if (hasSel) "🦙${activeExcerpt.length}" else "🦙", readerAiOpen, CG) {
readerAiOpen = !readerAiOpen
if (readerAiOpen && readerAiAns.isBlank()) askPageAi(if (hasSel) "선택한 구절의 핵심 의미와 포인트를 쉽게 해설해줘." else "현재 펼쳐진 페이지 핵심을 3줄로 요약하고 쉽게 해설해줘.", act.title, pgLabel, aiTargetText, hasSel)
}
ChipBtn(if (selectMode) "☑️" else "✂️", selectMode || hasSel, CA) { selectMode = !selectMode }
ChipBtn(if (ttsHere) "⏹️" else "🔊", ttsHere, CG) {
if (ttsHere) ReaderTtsRuntime.stop(ctx) else {
val startEx = if (hasSel) activeExcerpt else reflowSrc.fullText.substring(leftSlice.s.coerceIn(0, reflowSrc.fullText.length), reflowSrc.rawEndOf(curRawIdx).coerceIn(0, reflowSrc.fullText.length)).trim()
ReaderTtsRuntime.start(ctx, act, curRawIdx, startEx)
}
}
if (readerAiOpen && readerAiAns.isNotBlank() && !readerAiBusy) {
ChipBtn("💾", false, CA) {
onQuickAddNote("📖 『${act.title}』 ($pgLabel${if (hasSel) "·선택구절" else ""}) 해설\n${readerAiAns.removeSuffix(" ▍")}")
readerAiNotice = "✅ 메모장에 저장됨!"
}
}
ChipBtn("📝", false, CW) { quoteConfirmText = "『${act.title}』 ($pgLabel) 발췌:\n\"${aiTargetText.take(260)}\"\n\n메모: " }
}
if (readerAiOpen) {
Surface(color = Color(0xF50E1525), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, if (hasSel) CA.copy(alpha = 0.75f) else CG.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).heightIn(max = 240.dp)) {
Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
Text(if (hasSel) "🎯${activeExcerpt.length}" else "📖$pgLabel", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = if (hasSel) CA else CG)
ChipBtn("✂️", selectMode || hasSel, CA) { selectMode = !selectMode }
ChipBtn("📌", false, CG) { askPageAi("핵심 내용을 3줄로 요약해줘.", act.title, pgLabel, aiTargetText, hasSel) }
ChipBtn("💡", false, CA) { askPageAi("맥락과 의미를 알기 쉽게 해설해줘.", act.title, pgLabel, aiTargetText, hasSel) }
ChipBtn("✨", false, CW) { askPageAi("가장 인상 깊은 핵심 문장 1개를 뽑고 설명해줘.", act.title, pgLabel, aiTargetText, hasSel) }
if (readerAiAns.isNotBlank() && !readerAiBusy) ChipBtn("🔈", false, CG) { speakOneShot(ctx, readerAiAns.removeSuffix(" ▍")) }
}
readerAiNotice?.let { nt -> Text(nt, fontSize = ui.subSp, color = CG) }
Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
Text(readerAiAns.ifBlank { if (hasSel) "🎯 선택한 구절만 AI에게 전달됩니다! 위 [📌/💡/✨]을 누르거나 질문을 입력해 보세요." else "💡 본문 중앙 터치 또는 [✂️]로 원하는 문장만 골라 질문할 수 있습니다." }, fontSize = ui.bodySp, color = CW)
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = readerAiQ, onValueChange = { readerAiQ = it }, placeholder = { Text(if (hasSel) "선택한 구절에 대해 질문..." else "현재 펼쳐진 페이지 질문...", fontSize = ui.subSp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val q = readerAiQ.trim(); if (q.isNotEmpty()) { readerAiQ = ""; askPageAi(q, act.title, pgLabel, aiTargetText, hasSel) } }, enabled = !readerAiBusy) { Text("↑", fontSize = ui.subSp) }
}
}
}
}
if (vPages.size > stepPg) {
Slider(
value = vIdx.toFloat(),
onValueChange = { goToVPage(it.toInt()) },
valueRange = 0f..(vPages.size - 1).coerceAtLeast(1).toFloat(),
modifier = Modifier.fillMaxWidth().height(20.dp)
)
}
val canNext = if (twoPageMode) (vIdx + 2 < vPages.size) else (vIdx + 1 < vPages.size)
Row(Modifier.fillMaxWidth(), SB, CV) {
TextButton(onClick = { goToVPage(vIdx - stepPg) }, enabled = vIdx > 0) { Text("◀", color = if (vIdx > 0) fg else mut.copy(alpha = 0.35f)) }
Row(verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
Text(
(if (isBm) "🔖 " else "") + (if (ttsHere) "🔊 " else "") + pgLabel,
fontSize = (ui.subSp.value + 0.5f).sp,
fontWeight = if (isBm || ttsHere) FontWeight.Bold else FontWeight.Medium,
color = if (ttsHere) CG else if (isBm) CA else mut,
modifier = Modifier.clickable { if (ReaderTtsRuntime.on) ttsBarMin = !ttsBarMin }
)
if (ReaderTtsRuntime.on && ttsBarMin) ChipBtn("🔼낭독바", false, CG) { ttsBarMin = false }
ChipBtn(if (twoPageMode) "📖2쪽연속" else "📄1쪽", twoPageMode, CA) {
twoPageMode = !twoPageMode
ctx.getSharedPreferences("pl_ai_v2_prefs", 0).edit().putBoolean("reader_two_page", twoPageMode).apply()
}
ChipBtn("🎛️TTS", cfgOpen && rCfgTab == 1, CG) { if (cfgOpen && rCfgTab == 1) cfgOpen = false else { rCfgTab = 1; cfgOpen = true } }
}
TextButton(onClick = { goToVPage(vIdx + stepPg) }, enabled = canNext) { Text("▶", color = if (canNext) fg else mut.copy(alpha = 0.35f)) }
}
}
quoteConfirmText?.let { draft ->
var edDraft by remember(draft) { mutableStateOf(draft) }
AlertDialog(
onDismissRequest = { quoteConfirmText = null },
title = { Text("📝 메모장에 기록하시겠습니까?", fontSize = ui.titleSp) },
text = {
Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("선택한 독서대 구절을 메모장으로 가져갑니다. 내용을 확인하거나 메모를 덧붙인 뒤 [💾]를 눌러 주세요.", fontSize = (ui.subSp.value + 1).sp, color = CM)
OutlinedTextField(value = edDraft, onValueChange = { edDraft = it }, singleLine = false, minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
}
},
confirmButton = { Button(onClick = { val q = edDraft; quoteConfirmText = null; onQuote(q) }) { Text("💾") } },
dismissButton = { TextButton(onClick = { quoteConfirmText = null }) { Text("✕") } }
)
}
}
}
