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

private val DEF_MEMO_CATS_E = listOf("일상", "업무", "할일", "아이디어", "독서")
private val DEF_BOOK_CATS_E = listOf("에세이", "인문/교양", "시/문학", "업무/문서")
private fun extPrefs(ctx: Context) = ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE)

fun SmartMemoClassifier.loadMemoCats(ctx: Context): List<String> = extPrefs(ctx).getString("memo_cats", null)?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { DEF_MEMO_CATS_E } ?: DEF_MEMO_CATS_E
fun SmartMemoClassifier.saveMemoCats(ctx: Context, cats: List<String>) { extPrefs(ctx).edit().putString("memo_cats", cats.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("|")).apply() }
fun SmartMemoClassifier.loadBookCats(ctx: Context): List<String> = extPrefs(ctx).getString("book_cats", null)?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { DEF_BOOK_CATS_E } ?: DEF_BOOK_CATS_E
fun SmartMemoClassifier.saveBookCats(ctx: Context, cats: List<String>) { extPrefs(ctx).edit().putString("book_cats", cats.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("|")).apply() }
fun SmartMemoClassifier.loadBtnCfg(ctx: Context): QuickBtnConfig { val p = extPrefs(ctx); return QuickBtnConfig(p.getBoolean("b_check", true), p.getBoolean("b_bullet", true), p.getBoolean("b_date", true), p.getBoolean("b_div", true), p.getBoolean("b_nl", true), p.getBoolean("b_autosum", false), p.getString("b_clabel", "📌 템플릿") ?: "📌 템플릿", p.getString("b_ctext", "[메모]\n☐ 항목: ") ?: "[메모]\n☐ 항목: ") }
fun SmartMemoClassifier.saveBtnCfg(ctx: Context, c: QuickBtnConfig) { extPrefs(ctx).edit().putBoolean("b_check", c.showCheck).putBoolean("b_bullet", c.showBullet).putBoolean("b_date", c.showDate).putBoolean("b_div", c.showDivider).putBoolean("b_nl", c.showNewline).putBoolean("b_autosum", c.autoSumLong).putString("b_clabel", c.customLabel).putString("b_ctext", c.customText).apply() }
fun SmartMemoClassifier.isImportant(n: NoteItem): Boolean = !n.tags.contains("일반") && (n.tags.contains("중요") || n.summary.contains("★중요") || n.content.contains("[중요]"))
fun SmartMemoClassifier.toggleImportant(n: NoteItem): NoteItem { val next = !isImportant(n); val base = n.tags.filterNot { it == "중요" || it == "일반" }; return n.copy(tags = if (next) listOf("중요") + base else base + listOf("일반")) }
fun SmartMemoClassifier.guessCategory(text: String, cats: List<String>): String {
val low = text.lowercase()
val rules = mapOf("업무" to listOf("회의","미팅","보고서","기획","마감","발표","시안","피드백","출근","거래처","메일","일정","프로젝트","업무"), "할일" to listOf("☐","☑","사기","구매","장보기","우유","원두","마트","택배","청소","세탁","은행","병원","예약","결제","체크"), "독서" to listOf("독서","책","시집","윤동주","김소월","나태주","명상록","인용","발췌","구절","문장","작가","에세이","쪽"), "아이디어" to listOf("아이디어","생각","구상","영감","브레인스토밍","컨셉","기능","디자인","개선"))
var best = cats.firstOrNull() ?: "일상"; var maxSc = 0
for (c in cats) { val sc = (rules[c] ?: listOf(c)).count { low.contains(it) }; if (sc > maxSc) { maxSc = sc; best = c } }
return best
}
fun SmartMemoClassifier.categoryOf(n: NoteItem, cats: List<String>): String {
for (c in cats) if (n.tags.contains(c)) return c
return n.tags.firstOrNull { it !in listOf("중요","일반","메모","할일") } ?: guessCategory(n.content, cats)
}
fun SmartMemoClassifier.toggleCheckLine(n: NoteItem, idx: Int): NoteItem {
val ls = n.content.lines().toMutableList()
if (idx in ls.indices) { val l = ls[idx]; ls[idx] = when { l.trimStart().startsWith("☐ ") -> l.replaceFirst("☐ ", "☑ "); l.trimStart().startsWith("☑ ") -> l.replaceFirst("☑ ", "☐ "); else -> l } }
return n.copy(content = ls.joinToString("\n"))
}
fun SmartMemoClassifier.summarizeByLength(raw: String, force: Boolean = false): String {
val clean = raw.lines().map { it.replace(Regex("#[^\\s#]+"), "").replace("☐", "").replace("☑", "").replace("•", "").trim() }.filter { it.isNotEmpty() }
val chars = clean.sumOf { it.length }
if (!force && clean.size <= 2 && chars < 75) return ""
val sents = raw.replace("\n", ". ").split(Regex("[.!?]+")).map { it.replace(Regex("#[^\\s#]+"), "").replace("☐", "").replace("☑", "").trim() }.filter { it.length >= 4 }
if (sents.size <= 1 && chars < 75) return if (force) "1~2줄 이내의 짧은 메모는 요약 없이 원문 그대로 표시됩니다." else ""
val target = when { chars < 160 && sents.size <= 3 -> 1; chars < 320 && sents.size <= 6 -> 2; else -> 3 }
return sents.mapIndexed { i, s -> Triple(i, s, (if (i == 0) 4 else 0) + (s.length.coerceAtMost(45) / 10)) }.sortedByDescending { it.third }.take(target).sortedBy { it.first }.joinToString("\n") { (_, s, _) -> val cut = if (s.length > 46) s.take(46) + "…" else s; if (target == 1) cut else "• $cut" }
}

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
val ctx = LocalContext.current
val tick = AiResidentManager.uiTick
val sp = remember(tick) { ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE) }
val pct = sp.getInt("ui_menu_pct", 100).coerceIn(75, 140) / 100f
val mSp = (sp.getInt("ui_menu_sp", 11).coerceIn(9, 18) * pct).coerceIn(9f, 20f).sp
val hPad = (9f * pct).coerceIn(6f, 16f).dp
val vPad = (5f * pct).coerceIn(3f, 10f).dp
Surface(color = if (sel) col else Color(0xFF1E2433), shape = RoundedCornerShape(10.dp), modifier = Modifier.clickable { onClick() }) {
Text(text = text, fontSize = mSp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = if (sel) Color.Black else CW, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = hPad, vertical = vPad))
}
}

@Composable
fun UiScaleFontSection(ctx: Context, ui: ResponsiveUi, onReaderSpChange: (Int) -> Unit = {}) {
val tick = AiResidentManager.uiTick
val sp = remember { ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE) }
var scaleMode by remember(tick) { mutableStateOf(sp.getString("ui_scale_mode", "AUTO") ?: "AUTO") }
var menuPct by remember(tick) { mutableStateOf(sp.getInt("ui_menu_pct", 100)) }
var menuSp by remember(tick) { mutableStateOf(sp.getInt("ui_menu_sp", 11)) }
var memoSp by remember(tick) { mutableStateOf(sp.getInt("ui_memo_sp", 14)) }
var homeSp by remember(tick) { mutableStateOf(sp.getInt("ui_home_sp", 21)) }
var readerSp by remember(tick) { mutableStateOf(sp.getInt("ui_reader_sp", 18)) }
fun applyPrefs() {
sp.edit().putString("ui_scale_mode", scaleMode).putInt("ui_menu_pct", menuPct).putInt("ui_menu_sp", menuSp).putInt("ui_memo_sp", memoSp).putInt("ui_home_sp", homeSp).putInt("ui_reader_sp", readerSp).apply()
AiResidentManager.uiTick++
onReaderSpChange(readerSp)
}
Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
Text("📐 화면 해상도 배율 · 각 부분별 메뉴 & 글자 크기 조절", fontSize = (ui.subSp.value + 1).sp, fontWeight = FontWeight.Bold, color = CG)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
listOf("AUTO" to "자동(해상도맞춤)", "COMPACT" to "컴팩트(작게)", "NORMAL" to "표준", "LARGE" to "크게").forEach { (k, l) -> ChipBtn(l, scaleMode == k) { scaleMode = k; applyPrefs() } }
ChipBtn("🔄 기본크기 복원", false, CA) { scaleMode = "AUTO"; menuPct = 100; menuSp = 11; memoSp = 14; homeSp = 21; readerSp = 18; applyPrefs() }
}
Surface(color = Color(0xFF0C1017), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color(0xFF263244)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) { Text("🔘 메뉴·버튼 크기 (${menuPct}%)", fontSize = ui.subSp, color = CW); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ChipBtn("작게 -", false) { menuPct = (menuPct - 5).coerceAtLeast(80); applyPrefs() }; ChipBtn("100%", menuPct == 100) { menuPct = 100; applyPrefs() }; ChipBtn("크게 +", false) { menuPct = (menuPct + 5).coerceAtMost(135); applyPrefs() } } }
Row(Modifier.fillMaxWidth(), SB, CV) { Text("🏷️ 상단메뉴·탭 글자 (${menuSp}pt)", fontSize = ui.subSp, color = CW); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ChipBtn("가 -", false) { menuSp = (menuSp - 1).coerceAtLeast(9); applyPrefs() }; ChipBtn("11pt", menuSp == 11) { menuSp = 11; applyPrefs() }; ChipBtn("가 +", false) { menuSp = (menuSp + 1).coerceAtMost(17); applyPrefs() } } }
Row(Modifier.fillMaxWidth(), SB, CV) { Text("📝 메모·AI채팅 글자 (${memoSp}pt)", fontSize = ui.subSp, color = CW); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ChipBtn("가 -", false) { memoSp = (memoSp - 1).coerceAtLeast(11); applyPrefs() }; ChipBtn("14pt", memoSp == 14) { memoSp = 14; applyPrefs() }; ChipBtn("가 +", false) { memoSp = (memoSp + 1).coerceAtMost(22); applyPrefs() } } }
Row(Modifier.fillMaxWidth(), SB, CV) { Text("🏠 홈 시계·앱목록 글자 (${homeSp}pt)", fontSize = ui.subSp, color = CW); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ChipBtn("가 -", false) { homeSp = (homeSp - 1).coerceAtLeast(15); applyPrefs() }; ChipBtn("21pt", homeSp == 21) { homeSp = 21; applyPrefs() }; ChipBtn("가 +", false) { homeSp = (homeSp + 1).coerceAtMost(28); applyPrefs() } } }
Row(Modifier.fillMaxWidth(), SB, CV) { Text("📖 독서대(서재) 본문 글자 (${readerSp}pt)", fontSize = ui.subSp, color = CW); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ChipBtn("가 -", false) { readerSp = (readerSp - 1).coerceAtLeast(14); applyPrefs() }; ChipBtn("18pt", readerSp == 18) { readerSp = 18; applyPrefs() }; ChipBtn("가 +", false) { readerSp = (readerSp + 1).coerceAtMost(30); applyPrefs() } } }
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
val ctx = LocalContext.current
val tick = AiResidentManager.uiTick
val sp = remember(tick) { ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE) }
val aiCfg = remember(tick) { loadAiCfg(ctx) }
val ui = rememberUi(aiCfg.uiScale)
var readSp by remember(tick, cfg.readerFontSizeSp) { mutableStateOf(sp.getInt("ui_reader_sp", cfg.readerFontSizeSp).coerceIn(14, 30)) }
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
UiScaleFontSection(ctx, ui) { newR -> readSp = newR; onCfg(cfg.copy(readerFontSizeSp = newR)) }
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
ChipBtn("가-", false) { val nx = (readSp - 1).coerceAtLeast(14); readSp = nx; sp.edit().putInt("ui_reader_sp", nx).apply(); AiResidentManager.uiTick++; onCfg(cfg.copy(readerFontSizeSp = nx)) }
Text("${readSp}pt", fontSize = ui.subSp, color = fg, fontWeight = FontWeight.Bold)
ChipBtn("가+", false) { val nx = (readSp + 1).coerceAtMost(30); readSp = nx; sp.edit().putInt("ui_reader_sp", nx).apply(); AiResidentManager.uiTick++; onCfg(cfg.copy(readerFontSizeSp = nx)) }
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
UiScaleFontSection(ctx, ui) { newR -> onCfg(cfg.copy(readerFontSizeSp = newR)) }
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
}
}
