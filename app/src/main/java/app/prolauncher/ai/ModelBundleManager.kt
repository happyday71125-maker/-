package app.prolauncher.ai
import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject
data class BookmarkEntry(val bookId: Long, val bookTitle: String, val pageIndex: Int, val snippet: String)
fun loadBookmarks(ctx: Context): List<BookmarkEntry> = runCatching {
val raw = ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).getString("bookmarks_json", null) ?: return emptyList()
val arr = JSONArray(raw)
buildList { for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); add(BookmarkEntry(o.getLong("bid"), o.optString("title", "도서"), o.getInt("page"), o.optString("snip", ""))) } }
}.getOrElse { emptyList() }
fun saveBookmarks(ctx: Context, list: List<BookmarkEntry>) {
val arr = JSONArray()
list.forEach { b -> arr.put(JSONObject().apply { put("bid", b.bookId); put("title", b.bookTitle); put("page", b.pageIndex); put("snip", b.snippet) }) }
ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).edit().putString("bookmarks_json", arr.toString()).apply()
}
@Composable
fun ChipBtn(text: String, sel: Boolean = false, col: Color = CG, onClick: () -> Unit) {
val ui = rememberUi()
Surface(color = if (sel) col else Color(0xFF1E2433), shape = RoundedCornerShape(10.dp), modifier = Modifier.clickable { onClick() }) {
Text(
text = text,
fontSize = ui.menuSp,
fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
color = if (sel) Color.Black else CW,
maxLines = 1,
softWrap = false,
overflow = TextOverflow.Ellipsis,
modifier = Modifier.padding(horizontal = ui.menuHPad, vertical = ui.menuVPad)
)
}
}
@Composable
fun ReaderView(
books: List<BookItem>, cfg: LauncherConfig, repo: LauncherRepository,
onSaveBooks: (List<BookItem>) -> Unit, onCfg: (LauncherConfig) -> Unit,
onQuote: (String) -> Unit, onHome: () -> Unit
) {
val ctx = LocalContext.current
val tick = AiResidentManager.uiTick
var aiCfg by remember(tick) { mutableStateOf(loadAiCfg(ctx)) }
val ui = rememberUi(aiCfg.uiScale)
var bCats by remember { mutableStateOf(SMC.loadBookCats(ctx)) }
var bms by remember { mutableStateOf(loadBookmarks(ctx)) }
var bmOpen by remember { mutableStateOf(false) }
var selId by remember { mutableStateOf(books.firstOrNull()?.id ?: 1001L) }
var shelf by remember { mutableStateOf(false) }
var cfgOpen by remember { mutableStateOf(false) }
var newCat by remember { mutableStateOf("") }
var editId by remember { mutableStateOf<Long?>(null) }
var editT by remember { mutableStateOf("") }
var editC by remember { mutableStateOf("") }
var quoteConfirmText by remember { mutableStateOf<String?>(null) }
val act = books.find { it.id == selId } ?: books.firstOrNull()
val scope = rememberCoroutineScope()
val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
if (u != null) scope.launch(Dispatchers.IO) { repo.importBookFromUri(u)?.let { b -> withContext(Dispatchers.Main) { onSaveBooks(listOf(b) + books); selId = b.id; shelf = false } } }
}
val bg = when (cfg.readerTheme) { "SEPIA" -> Color(0xFFF4ECD8); "PAPER" -> Color(0xFFFAF8F5); else -> Color(0xFF0A0B0E) }
val fg = when (cfg.readerTheme) { "SEPIA" -> Color(0xFF2C221E); "PAPER" -> Color(0xFF1C1917); else -> Color(0xFFE5E7EB) }
val mut = when (cfg.readerTheme) { "SEPIA" -> Color(0xFF6E5A4F); "PAPER" -> Color(0xFF78716C); else -> CM }
Column(Modifier.fillMaxSize().background(bg).padding(horizontal = ui.hPad, vertical = ui.vPad)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text(if (shelf) "📚 서재 도서 목록 (${books.size}권)" else "📖 독서대 (${books.size}권)", fontSize = ui.titleSp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
ChipBtn("홈 화면 →", false, CA) { onHome() }
}
Spacer(Modifier.height(6.dp))
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn(if (shelf) "📖 본문 읽기" else "📚 서재 목록 (${books.size})", shelf, CG) { shelf = !shelf; if (shelf) bmOpen = false }
ChipBtn("🔖 북마크 (${bms.size})", bmOpen, CA) { bmOpen = !bmOpen; if (bmOpen) shelf = false }
ChipBtn("📂 책 파일 추가(TXT/PDF)", false, CG) { picker.launch(arrayOf("*/*")) }
ChipBtn(if (cfgOpen) "⚙️ 설정 닫기" else "⚙️ 글자·메뉴·서재 설정", cfgOpen, CG) { cfgOpen = !cfgOpen }
}
Spacer(Modifier.height(6.dp))
if (bmOpen) {
Surface(color = Color(0xFF14161F), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CA), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).heightIn(max = ui.panelMaxH)) {
Column(Modifier.padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("🔖 북마크 목록 (탭하면 해당 쪽으로 이동)", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CA)
Text("닫기 ✕", fontSize = ui.subSp, color = CM, modifier = Modifier.clickable { bmOpen = false })
}
if (bms.isEmpty()) Text("저장된 북마크가 없습니다. 본문 상단의 [🔖 북마크 표시]를 눌러 추가해 보세요.", fontSize = ui.subSp, color = CM)
else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
items(bms, key = { "${it.bookId}_${it.pageIndex}" }) { bm ->
Surface(color = CS, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable {
selId = bm.bookId; onSaveBooks(books.map { if (it.id == bm.bookId) it.copy(currentPage = bm.pageIndex) else it }); shelf = false; bmOpen = false
}) {
Row(Modifier.padding(10.dp), SB, CV) {
Column(Modifier.weight(1f)) {
Text("🔖 ${bm.bookTitle} · ${bm.pageIndex + 1}쪽", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CW, maxLines = 1, overflow = TextOverflow.Ellipsis)
Text(bm.snippet, fontSize = ui.subSp, color = CM, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
Text("✕", fontSize = (ui.subSp.value + 1).sp, color = Color(0xFFEF4444), modifier = Modifier.clickable {
val nx = bms.filterNot { it.bookId == bm.bookId && it.pageIndex == bm.pageIndex }; bms = nx; saveBookmarks(ctx, nx)
}.padding(6.dp))
}
}
}
}
}
}
}
if (cfgOpen) {
Surface(color = Color(0xFF14161F), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CG), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).heightIn(max = ui.panelMaxH)) {
Column(Modifier.verticalScroll(rememberScrollState()).padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("🎨 독서대 배경 테마 & 서체 설정", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
listOf("SEPIA" to "📜 양피지", "PAPER" to "📄 종이", "NIGHT" to "🌙 야간").forEach { (k, l) -> ChipBtn(l, cfg.readerTheme == k) { onCfg(cfg.copy(readerTheme = k)) } }
ChipBtn(if (cfg.readerUseSerif) "서체: 명조체" else "서체: 고딕체", true, CA) { onCfg(cfg.copy(readerUseSerif = !cfg.readerUseSerif)) }
}
UiScaleFontSection(ctx, aiCfg, ui) { updated ->
aiCfg = updated
onCfg(cfg.copy(readerFontSizeSp = updated.readerFontSp))
}
Text("🏷️ 서재 도서 분류 관리 (탭하여 삭제)", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
bCats.forEach { c -> ChipBtn(if (bCats.size > 1) "$c ✕" else c) { if (bCats.size > 1) { val u = bCats.filterNot { it == c }; bCats = u; SMC.saveBookCats(ctx, u) } } }
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 도서 분류 추가", fontSize = ui.subSp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !bCats.contains(t)) { val u = bCats + t; bCats = u; SMC.saveBookCats(ctx, u); newCat = "" } }) { Text("+ 추가", fontSize = ui.subSp, maxLines = 1, softWrap = false) }
}
}
}
}
if (shelf || act == null) {
LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
items(books, key = { it.id }) { b ->
val bmPages = bms.filter { it.bookId == b.id }.map { "${it.pageIndex + 1}쪽" }
Surface(color = mut.copy(alpha = 0.14f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
if (editId == b.id) {
Column(Modifier.padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = editT, onValueChange = { editT = it }, label = { Text("도서 제목") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
bCats.forEach { bc -> ChipBtn(bc, editC == bc) { editC = bc } }
}
Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
TextButton(onClick = { editId = null }) { Text("취소", color = mut) }
Button(onClick = { if (editT.isNotBlank()) { onSaveBooks(books.map { if (it.id == b.id) it.copy(title = editT.trim(), category = editC.ifBlank { "에세이" }) else it }); editId = null } }) { Text("저장") }
}
}
} else {
Column(Modifier.padding(ui.cardPad).fillMaxWidth().clickable { selId = b.id; shelf = false }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
Text("[${b.category}] ${b.title}", fontSize = ui.bodySp, fontWeight = FontWeight.Bold, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
Row(Modifier.fillMaxWidth(), SB, CV) {
val bmStr = if (bmPages.isNotEmpty()) " · 🔖 ${bmPages.joinToString(",")}" else ""
Text("${b.author} · ${b.currentPage + 1}/${b.pages.size}쪽$bmStr", fontSize = ui.subSp, color = mut, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn("✏️ 수정", false, CG) { editId = b.id; editT = b.title; editC = b.category }
ChipBtn("🗑️ 삭제", false, Color(0xFFEF4444)) { val nx = books.filterNot { it.id == b.id }; onSaveBooks(nx); if (selId == b.id) selId = nx.firstOrNull()?.id ?: 0L }
}
}
}
}
}
}
}
} else {
val idx = act.currentPage.coerceIn(0, (act.pages.size - 1).coerceAtLeast(0))
val content = act.pages.getOrElse(idx) { "" }
val isBm = bms.any { it.bookId == act.id && it.pageIndex == idx }
val readSp = ui.readerSp
Surface(color = mut.copy(alpha = 0.10f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
Text("[${act.category}] ${act.title}", fontSize = ui.bodySp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn(if (isBm) "🔖 북마크됨 ★" else "🔖 북마크 표시", isBm, CA) {
val ex = bms.any { it.bookId == act.id && it.pageIndex == idx }
val nx = if (ex) bms.filterNot { it.bookId == act.id && it.pageIndex == idx }
else listOf(BookmarkEntry(act.id, act.title, idx, content.replace("\n", " ").take(45))) + bms
bms = nx; saveBookmarks(ctx, nx)
}
ChipBtn("가-", false) { val u = aiCfg.copy(readerFontSp = (readSp - 1).coerceAtLeast(14)); aiCfg = u; saveAiCfg(ctx, u); onCfg(cfg.copy(readerFontSizeSp = u.readerFontSp)) }
Text("${readSp}pt", fontSize = ui.subSp, color = fg, fontWeight = FontWeight.Bold)
ChipBtn("가+", false) { val u = aiCfg.copy(readerFontSp = (readSp + 1).coerceAtMost(30)); aiCfg = u; saveAiCfg(ctx, u); onCfg(cfg.copy(readerFontSizeSp = u.readerFontSp)) }
ChipBtn("✏️ 정보수정", false) { editId = act.id; editT = act.title; editC = act.category; shelf = true }
ChipBtn("🗑️ 삭제", false, Color(0xFFEF4444)) { val nx = books.filterNot { it.id == act.id }; onSaveBooks(nx); selId = nx.firstOrNull()?.id ?: 0L; shelf = true }
}
}
}
Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
Text(content, fontSize = readSp.sp, lineHeight = (readSp * 1.65f).sp, fontFamily = if (cfg.readerUseSerif) FontFamily.Serif else FontFamily.SansSerif, color = fg)
}
Row(Modifier.fillMaxWidth(), SB, CV) {
ChipBtn("← 이전 쪽", false) { if (idx > 0) onSaveBooks(books.map { if (it.id == act.id) it.copy(currentPage = idx - 1) else it }) }
Text((if (isBm) "🔖 " else "") + "${idx + 1} / ${act.pages.size} 쪽", fontSize = (ui.subSp.value + 1).sp, fontWeight = if (isBm) FontWeight.Bold else FontWeight.Normal, color = if (isBm) CA else mut)
ChipBtn("다음 쪽 →", false) { if (idx < act.pages.size - 1) onSaveBooks(books.map { if (it.id == act.id) it.copy(currentPage = idx + 1) else it }) }
}
Spacer(Modifier.height(6.dp))
Button(onClick = { quoteConfirmText = "『${act.title}』 (${idx + 1}쪽) 발췌:\n\"${content.take(180)}\"\n\n메모: " }, modifier = Modifier.fillMaxWidth()) {
Text("📝 이 문장 인용하여 메모장에 기록하기", fontSize = ui.bodySp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
}
}
quoteConfirmText?.let { draft ->
var edDraft by remember(draft) { mutableStateOf(draft) }
AlertDialog(
onDismissRequest = { quoteConfirmText = null },
title = { Text("📝 메모장에 기록하시겠습니까?", fontSize = ui.titleSp) },
text = {
Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("선택한 독서대 구절을 메모장으로 가져갑니다. 내용을 확인하거나 메모를 덧붙인 뒤 [확인]을 눌러 주세요.", fontSize = (ui.subSp.value + 1).sp, color = CM)
OutlinedTextField(value = edDraft, onValueChange = { edDraft = it }, singleLine = false, minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
}
},
confirmButton = { Button(onClick = { val q = edDraft; quoteConfirmText = null; onQuote(q) }) { Text("확인 (메모장으로 이동)") } },
dismissButton = { TextButton(onClick = { quoteConfirmText = null }) { Text("취소") } }
)
}
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerSheet(apps: List<AppEntry>, onClose: () -> Unit, onLaunch: (AppEntry) -> Unit, onFav: (AppEntry) -> Unit, onInt: (AppEntry) -> Unit) {
val ctx = LocalContext.current
val ui = rememberUi(loadAiCfg(ctx).uiScale)
var q by remember { mutableStateOf("") }
val flt = remember(apps, q) { apps.filter { KoreanChosungMatcher.matches(it.label, q) } }
LaunchedEffect(flt, q) { if (q.trim().isNotEmpty() && !q.startsWith(" ") && flt.size == 1 && !flt.first().isInterruptEnabled) { val a = flt.first(); q = ""; onLaunch(a) } }
Column(Modifier.fillMaxSize().background(Color(0xFA08080A)).padding(horizontal = ui.hPad + 4.dp, vertical = ui.vPad + 4.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) { Text("↑ 앱 서랍 (초성 검색)", fontSize = ui.titleSp, fontWeight = FontWeight.Bold, color = CW); ChipBtn("↓ 닫기", false) { onClose() } }
Spacer(Modifier.height(6.dp))
OutlinedTextField(value = q, onValueChange = { q = it }, placeholder = { Text("초성 검색 (예: ㅋㅋㅇㅌ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Spacer(Modifier.height(8.dp))
LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
items(flt, key = { it.packageName }) { a ->
Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onLaunch(a) }, onLongClick = { onFav(a) }).padding(vertical = 8.dp), SB, CV) {
Text(a.label + (if (a.isFavorite) " ★" else ""), fontSize = ui.appSp, color = Color(0xFFE5E7EB), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
Text(if (a.isInterruptEnabled) "⏳ 차단중" else "⏳", fontSize = (ui.subSp.value + 1).sp, color = if (a.isInterruptEnabled) CA else Color(0xFF4B5563), modifier = Modifier.clickable { onInt(a) }.padding(6.dp))
}
}
}
}
}
@Composable
fun SettingsSheet(cfg: LauncherConfig, isDefHome: Boolean, ai: LocalAiNoteEngine, onAskHome: () -> Unit, onSysHome: () -> Unit, onCfg: (LauncherConfig) -> Unit, onClose: () -> Unit) {
val ctx = LocalContext.current
val tick = AiResidentManager.uiTick
var aiCfg by remember(tick) { mutableStateOf(loadAiCfg(ctx)) }
val ui = rememberUi(aiCfg.uiScale)
var cats by remember { mutableStateOf(SMC.loadMemoCats(ctx)) }
var newCat by remember { mutableStateOf("") }
Column(Modifier.fillMaxSize().background(Color(0xFA08080A)).verticalScroll(rememberScrollState()).padding(horizontal = ui.hPad + 4.dp, vertical = ui.vPad + 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) { Text("⚙️ 전체 런처 · 화면/글자 크기 · AI 설정", fontSize = ui.titleSp, fontWeight = FontWeight.Bold, color = CW); ChipBtn("닫기 ✕", true, CG) { onClose() } }
Surface(color = CS, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
UiScaleFontSection(ctx, aiCfg, ui) { updated ->
aiCfg = updated
onCfg(cfg.copy(readerFontSizeSp = updated.readerFontSp))
}
Text("🎨 배경 테마 & 홈 정렬 방식", fontWeight = FontWeight.Bold, fontSize = ui.bodySp, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
listOf("AMOLED" to "블랙", "SLATE" to "슬레이트", "FOREST" to "포레스트").forEach { (k, l) -> ChipBtn(l, cfg.themeName == k) { onCfg(cfg.copy(themeName = k)) } }
listOf("LEFT" to "왼쪽 정렬", "CENTER" to "가운데", "RIGHT" to "오른쪽").forEach { (k, l) -> ChipBtn(l, cfg.textAlign == k) { onCfg(cfg.copy(textAlign = k)) } }
}
}
}
Surface(color = CS, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Text("2. AI 상주 모델 & 기본 프롬프트 · RAG 설정", fontWeight = FontWeight.Bold, fontSize = ui.bodySp, color = CW)
AiPromptRagPanel(ctx, aiCfg, ui, onUpdate = { aiCfg = it }, onPickModel = {})
}
}
Surface(color = CS, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Text("3. 메모 카테고리표 및 기본 홈 권한", fontWeight = FontWeight.Bold, fontSize = ui.bodySp, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
cats.forEach { c -> ChipBtn(if (cats.size > 1) "$c ✕" else c) { if (cats.size > 1) { val nx = cats.filterNot { it == c }; cats = nx; SMC.saveMemoCats(ctx, nx) } } }
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 카테고리 추가", fontSize = ui.subSp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !cats.contains(t)) { val nx = cats + t; cats = nx; SMC.saveMemoCats(ctx, nx); newCat = "" } }) { Text("추가", fontSize = ui.subSp) }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
ChipBtn("🏠 기본 홈 권한 요청", false, CG) { onAskHome() }
ChipBtn("⚙️ 시스템 기본 앱 설정", false, CA) { onSysHome() }
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
}}
}
