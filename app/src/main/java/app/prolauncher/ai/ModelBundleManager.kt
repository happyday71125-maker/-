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
buildList {
for (i in 0 until arr.length()) {
val o = arr.getJSONObject(i)
add(BookmarkEntry(o.getLong("bid"), o.optString("title", "도서"), o.getInt("page"), o.optString("snip", "")))
}
}
}.getOrElse { emptyList() }

fun saveBookmarks(ctx: Context, list: List<BookmarkEntry>) {
val arr = JSONArray()
list.forEach { b -> arr.put(JSONObject().apply { put("bid", b.bookId); put("title", b.bookTitle); put("page", b.pageIndex); put("snip", b.snippet) }) }
ctx.getSharedPreferences("pl_ai_v4_custom", Context.MODE_PRIVATE).edit().putString("bookmarks_json", arr.toString()).apply()
}

@Composable
fun ChipBtn(text: String, sel: Boolean = false, col: Color = CG, onClick: () -> Unit) {
Surface(color = if (sel) col else Color(0xFF1E2433), shape = RoundedCornerShape(10.dp), modifier = Modifier.clickable { onClick() }) {
Text(text, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = if (sel) Color.Black else CW, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
}
}

@Composable
fun ReaderView(
books: List<BookItem>, cfg: LauncherConfig, repo: LauncherRepository,
onSaveBooks: (List<BookItem>) -> Unit, onCfg: (LauncherConfig) -> Unit,
onQuote: (String) -> Unit, onHome: () -> Unit
) {
val ctx = LocalContext.current
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

Column(Modifier.fillMaxSize().background(bg).padding(18.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = CV) {
TextButton(onClick = { shelf = !shelf; bmOpen = false }) { Text(if (shelf) "📖 본문" else "📚 서재 (${books.size})", color = fg, fontWeight = FontWeight.Bold) }
OutlinedButton(onClick = { bmOpen = !bmOpen; if (bmOpen) shelf = false }) {
Text("🔖 북마크 (${bms.size})", fontSize = 11.sp, color = if (bmOpen) CA else fg)
}
}
Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = CV) {
OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("📂 추가", fontSize = 11.sp, color = fg) }
OutlinedButton(onClick = { cfgOpen = !cfgOpen }) { Text("⚙️ 설정", fontSize = 11.sp, color = fg) }
TextButton(onClick = onHome) { Text("홈 →", fontSize = 12.sp, color = mut) }
}
}
if (bmOpen) {
Surface(color = Color(0xFF14161F), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CA), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).heightIn(max = 210.dp)) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("🔖 북마크 목록 (탭하면 해당 쪽으로 바로 이동)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CA)
Text("닫기 ✕", fontSize = 11.sp, color = CM, modifier = Modifier.clickable { bmOpen = false })
}
if (bms.isEmpty()) Text("저장된 북마크가 없습니다. 본문 상단의 [🔖 북마크 표시]를 눌러 추가해 보세요.", fontSize = 11.sp, color = CM)
else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
items(bms, key = { "${it.bookId}_${it.pageIndex}" }) { bm ->
Surface(color = CS, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable {
selId = bm.bookId; onSaveBooks(books.map { if (it.id == bm.bookId) it.copy(currentPage = bm.pageIndex) else it }); shelf = false; bmOpen = false
}) {
Row(Modifier.padding(10.dp), SB, CV) {
Column(Modifier.weight(1f)) {
Text("🔖 ${bm.bookTitle} · ${bm.pageIndex + 1}쪽", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CW)
Text(bm.snippet, fontSize = 11.sp, color = CM, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
Text("✕", fontSize = 12.sp, color = Color(0xFFEF4444), modifier = Modifier.clickable {
val nx = bms.filterNot { it.bookId == bm.bookId && it.pageIndex == bm.pageIndex }; bms = nx; saveBookmarks(ctx, nx)
}.padding(4.dp))
}
}
}
}
}
}
}
if (cfgOpen) {
Surface(color = Color(0xFF14161F), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CG), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
listOf("SEPIA" to "양피지", "PAPER" to "종이", "NIGHT" to "야간").forEach { (k, l) -> ChipBtn(l, cfg.readerTheme == k) { onCfg(cfg.copy(readerTheme = k)) } }
}
Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn(if (cfg.readerUseSerif) "명조" else "고딕", true) { onCfg(cfg.copy(readerUseSerif = !cfg.readerUseSerif)) }
ChipBtn("가-") { onCfg(cfg.copy(readerFontSizeSp = (cfg.readerFontSizeSp - 2).coerceAtLeast(14))) }
Text("${cfg.readerFontSizeSp}pt", fontSize = 11.sp, color = CG)
ChipBtn("가+") { onCfg(cfg.copy(readerFontSizeSp = (cfg.readerFontSizeSp + 2).coerceAtMost(28))) }
}
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
bCats.forEach { c -> ChipBtn(if (bCats.size > 1) "$c ✕" else c) { if (bCats.size > 1) { val u = bCats.filterNot { it == c }; bCats = u; SMC.saveBookCats(ctx, u) } } }
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 도서 분류 추가", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !bCats.contains(t)) { val u = bCats + t; bCats = u; SMC.saveBookCats(ctx, u); newCat = "" } }) { Text("+ 추가", fontSize = 11.sp) }
}
}
}
}
if (shelf || act == null) {
LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
items(books, key = { it.id }) { b ->
val bmPages = bms.filter { it.bookId == b.id }.map { "${it.pageIndex + 1}쪽" }
Surface(color = mut.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
if (editId == b.id) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = editT, onValueChange = { editT = it }, label = { Text("제목") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
bCats.forEach { bc -> ChipBtn(bc, editC == bc) { editC = bc } }
}
Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
TextButton(onClick = { editId = null }) { Text("취소", color = mut) }
Button(onClick = { if (editT.isNotBlank()) { onSaveBooks(books.map { if (it.id == b.id) it.copy(title = editT.trim(), category = editC.ifBlank { "에세이" }) else it }); editId = null } }) { Text("저장") }
}
}
} else {
Row(Modifier.padding(14.dp).fillMaxWidth().clickable { selId = b.id; shelf = false }, SB, CV) {
Column(Modifier.weight(1f)) {
Text("[${b.category}] ${b.title}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = fg)
val bmStr = if (bmPages.isNotEmpty()) " · 🔖 ${bmPages.joinToString(",")}" else ""
Text("${b.author} · ${b.currentPage + 1}/${b.pages.size}쪽$bmStr", fontSize = 12.sp, color = mut)
}
Row {
TextButton(onClick = { editId = b.id; editT = b.title; editC = b.category }) { Text("수정", fontSize = 12.sp, color = CG) }
TextButton(onClick = { val nx = books.filterNot { it.id == b.id }; onSaveBooks(nx); if (selId == b.id) selId = nx.firstOrNull()?.id ?: 0L }) { Text("🗑️ 삭제", fontSize = 12.sp, color = Color(0xFFEF4444)) }
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
Row(Modifier.fillMaxWidth().padding(top = 4.dp), SB, CV) {
Text("[${act.category}] ${act.title}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = mut, modifier = Modifier.weight(1f))
Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn(if (isBm) "🔖 북마크됨 ★" else "🔖 북마크 표시", isBm, CA) {
val ex = bms.any { it.bookId == act.id && it.pageIndex == idx }
val nx = if (ex) bms.filterNot { it.bookId == act.id && it.pageIndex == idx }
else listOf(BookmarkEntry(act.id, act.title, idx, content.replace("\n", " ").take(45))) + bms
bms = nx; saveBookmarks(ctx, nx)
}
Text("✏️ 수정", fontSize = 11.sp, color = CG, modifier = Modifier.clickable { editId = act.id; editT = act.title; editC = act.category; shelf = true }.padding(4.dp))
Text("🗑️ 삭제", fontSize = 11.sp, color = Color(0xFFEF4444), modifier = Modifier.clickable { val nx = books.filterNot { it.id == act.id }; onSaveBooks(nx); selId = nx.firstOrNull()?.id ?: 0L; shelf = true }.padding(4.dp))
}
}
Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
Text(content, fontSize = cfg.readerFontSizeSp.sp, lineHeight = (cfg.readerFontSizeSp * 1.65f).sp, fontFamily = if (cfg.readerUseSerif) FontFamily.Serif else FontFamily.SansSerif, color = fg)
}
Row(Modifier.fillMaxWidth(), SB, CV) {
TextButton(onClick = { if (idx > 0) onSaveBooks(books.map { if (it.id == act.id) it.copy(currentPage = idx - 1) else it }) }, enabled = idx > 0) { Text("← 이전 쪽", color = fg) }
Text((if (isBm) "🔖 " else "") + "${idx + 1} / ${act.pages.size} 쪽", fontSize = 12.sp, fontWeight = if (isBm) FontWeight.Bold else FontWeight.Normal, color = if (isBm) CA else mut)
TextButton(onClick = { if (idx < act.pages.size - 1) onSaveBooks(books.map { if (it.id == act.id) it.copy(currentPage = idx + 1) else it }) }, enabled = idx < act.pages.size - 1) { Text("다음 쪽 →", color = fg) }
}
Button(onClick = { quoteConfirmText = "『${act.title}』 (${idx + 1}쪽) 발췌:\n\"${content.take(180)}\"\n\n메모: " }, modifier = Modifier.fillMaxWidth()) { Text("📝 이 문장 인용하여 메모장에 기록하기") }
}
}
quoteConfirmText?.let { draft ->
var edDraft by remember(draft) { mutableStateOf(draft) }
AlertDialog(
onDismissRequest = { quoteConfirmText = null },
title = { Text("📝 메모장에 기록하시겠습니까?") },
text = {
Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("선택한 독서대 구절을 메모장으로 가져갑니다. 내용을 확인하거나 메모를 덧붙인 뒤 [확인]을 눌러 주세요.", fontSize = 12.sp, color = CM)
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
var q by remember { mutableStateOf("") }
val flt = remember(apps, q) { apps.filter { KoreanChosungMatcher.matches(it.label, q) } }
LaunchedEffect(flt, q) { if (q.trim().isNotEmpty() && !q.startsWith(" ") && flt.size == 1 && !flt.first().isInterruptEnabled) { val a = flt.first(); q = ""; onLaunch(a) } }
Column(Modifier.fillMaxSize().background(Color(0xFA08080A)).padding(24.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) { Text("↑ 앱 서랍 (초성 검색)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CW); TextButton(onClick = onClose) { Text("↓ 닫기", color = CM) } }
OutlinedTextField(value = q, onValueChange = { q = it }, placeholder = { Text("초성 검색 (예: ㅋㅋㅇㅌ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Spacer(Modifier.height(8.dp))
LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
items(flt, key = { it.packageName }) { a ->
Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onLaunch(a) }, onLongClick = { onFav(a) }).padding(vertical = 10.dp), SB, CV) {
Text(a.label + (if (a.isFavorite) " ★" else ""), fontSize = 18.sp, color = Color(0xFFE5E7EB))
Text(if (a.isInterruptEnabled) "⏳ 차단중" else "⏳", fontSize = 12.sp, color = if (a.isInterruptEnabled) CA else Color(0xFF4B5563), modifier = Modifier.clickable { onInt(a) }.padding(6.dp))
}
}
}
}
}

@Composable
fun SettingsSheet(cfg: LauncherConfig, isDefHome: Boolean, ai: LocalAiNoteEngine, onAskHome: () -> Unit, onSysHome: () -> Unit, onCfg: (LauncherConfig) -> Unit, onClose: () -> Unit) {
val ctx = LocalContext.current
var cats by remember { mutableStateOf(SMC.loadMemoCats(ctx)) }
var newCat by remember { mutableStateOf("") }
var bCfg by remember { mutableStateOf(SMC.loadBtnCfg(ctx)) }
Column(Modifier.fillMaxSize().background(Color(0xFA08080A)).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) { Text("⚙️ 전체 런처 설정", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CW); TextButton(onClick = onClose) { Text("닫기", color = CG) } }
Surface(color = CS, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("1. 기본 홈 화면 권한", fontWeight = FontWeight.Bold, color = CW)
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onAskHome) { Text("🏠 기본 홈 권한 요청", fontSize = 12.sp) }; OutlinedButton(onClick = onSysHome) { Text("시스템 설정", fontSize = 12.sp) } }
}
}
Surface(color = CS, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("2. 홈 화면 테마 & 정렬", fontWeight = FontWeight.Bold, color = CW)
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("AMOLED" to "블랙", "SLATE" to "슬레이트", "FOREST" to "포레스트").forEach { (k, l) -> ChipBtn(l, cfg.themeName == k) { onCfg(cfg.copy(themeName = k)) } } }
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("LEFT" to "왼쪽", "CENTER" to "가운데", "RIGHT" to "오른쪽").forEach { (k, l) -> ChipBtn(l, cfg.textAlign == k) { onCfg(cfg.copy(textAlign = k)) } } }
}
}
Surface(color = CS, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("3. 메모 카테고리표 및 기능버튼 설정", fontWeight = FontWeight.Bold, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
cats.forEach { c -> ChipBtn(if (cats.size > 1) "$c ✕" else c) { if (cats.size > 1) { val nx = cats.filterNot { it == c }; cats = nx; SMC.saveMemoCats(ctx, nx) } } }
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 카테고리 추가", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !cats.contains(t)) { val nx = cats + t; cats = nx; SMC.saveMemoCats(ctx, nx); newCat = "" } }) { Text("추가", fontSize = 11.sp) }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("☑ 체크박스", bCfg.showCheck) { bCfg = bCfg.copy(showCheck = !bCfg.showCheck); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("• 글머리", bCfg.showBullet) { bCfg = bCfg.copy(showBullet = !bCfg.showBullet); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("📅 시간", bCfg.showDate) { bCfg = bCfg.copy(showDate = !bCfg.showDate); SMC.saveBtnCfg(ctx, bCfg) }
ChipBtn("── 구분선", bCfg.showDivider) { bCfg = bCfg.copy(showDivider = !bCfg.showDivider); SMC.saveBtnCfg(ctx, bCfg) }
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

data class ChatMsg(val id: Long, val isUser: Boolean, val text: String, val usedMemo: Boolean = false)

suspend fun buildAiChatReply(qRaw: String, useMemo: Boolean, notes: List<NoteItem>, books: List<BookItem>, cats: List<String>, ai: LocalAiNoteEngine): String {
val q = qRaw.trim(); val low = q.lowercase()
if (!useMemo) {
if (low.contains("안녕") || low.contains("도움")) return "안녕하세요! 온디바이스 AI 채팅입니다. 현재 [일반 대화 모드]이며 요약·아이디어 구상을 도와드립니다. 내 메모와 연동하려면 상단 [🔗 내 메모 연동]을 켜주세요."
if (low.contains("요약") || q.lines().size >= 3 || q.length >= 80) return "✨ 입력 내용 요약:\n${SMC.summarizeByLength(q, true)}"
return "💬 [AI 답변]\n'$q'에 대한 정리 포인트입니다:\n• 핵심 주제를 먼저 1~3줄로 요약해 보세요.\n• 실행할 일은 메모 탭의 ☑ 체크리스트 버튼으로 나누어 적어보세요."
}
if (notes.isEmpty()) return "저장된 메모가 없습니다. [📝 메모] 탭에서 메모를 먼저 작성해 주세요."
if (low.contains("요약") || low.contains("정리") || low.contains("브리핑") || low.contains("전체")) {
val imps = notes.filter { SMC.isImportant(it) }
return buildString {
append("🗂️ [내 메모 연동 브리핑 · 총 ${notes.size}건]\n")
if (imps.isNotEmpty()) { append("★ 중요 메모 (${imps.size}건):\n"); imps.take(3).forEach { n -> append("• [${SMC.categoryOf(n, cats)}] ${n.content.replace("\n", " ").take(55)}\n") } }
append("📌 최근 메모 핵심 요약:\n")
notes.take(4).forEach { n -> val s = SMC.summarizeByLength(n.content, false).ifBlank { n.content.replace("\n", " ").take(55) }; append("• [${SMC.categoryOf(n, cats)}] $s\n") }
}.trim()
}
if (low.contains("할일") || low.contains("할 일") || low.contains("체크") || low.contains("일정") || low.contains("미팅")) {
val unc = notes.flatMap { n -> n.content.lines().filter { it.trimStart().startsWith("☐ ") }.map { "[${SMC.categoryOf(n, cats)}] $it" } }
val wNotes = notes.filter { SMC.categoryOf(it, cats) in listOf("업무", "할일") || SMC.isImportant(it) }.take(3)
return buildString {
append("✅ [내 메모 할 일 & 일정 브리핑]\n")
if (unc.isNotEmpty()) { append("• 미완료 체크리스트 (${unc.size}건):\n"); unc.take(5).forEach { append("  $it\n") } }
if (wNotes.isNotEmpty()) { append("• 주요 일정 메모:\n"); wNotes.forEach { n -> append("  - [${SMC.categoryOf(n, cats)}] ${n.content.replace("\n", " ").take(60)}\n") } }
if (unc.isEmpty() && wNotes.isEmpty()) append("미완료된 체크리스트(☐)나 일정 메모가 없습니다.")
}.trim()
}
val kw = notes.filter { n -> q.split(" ").filter { it.length >= 2 }.any { k -> n.content.contains(k, true) || n.tags.any { t -> t.contains(k, true) } } }
if (kw.isNotEmpty()) return "🔗 관련 내 메모 ${kw.size}건:\n" + kw.take(3).joinToString("\n") { "• [${SMC.categoryOf(it, cats)} · ${it.createdAt}] ${it.content.replace("\n", " ").take(75)}" }
return ai.askRag(q, notes, books, true)
}
