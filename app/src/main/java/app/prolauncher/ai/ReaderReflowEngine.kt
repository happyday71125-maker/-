package app.prolauncher.ai
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

@Composable
fun SupertonicControlSection(ctx: Context, ui: ResponsiveUi, compact: Boolean = false) {
SideEffect { SupertonicTtsEngine.init(ctx) }
val stepPresets = if (compact) listOf(4 to "⚡4", 8 to "⚖️8", 12 to "💎12", 16 to "👑16HD", 24 to "✨24", 32 to "🔥32")
else listOf(4 to "⚡4(초고속)", 8 to "⚖️8(경량권장)", 12 to "💎12(표준)", 16 to "👑16(HD)", 24 to "✨24(울트라)", 32 to "🔥32(마스터)")
val spdPresets = if (compact) listOf(0.8f to "0.8x", 0.9f to "0.9x", 1.0f to "1.0x", 1.1f to "1.1x", 1.25f to "1.25x", 1.5f to "1.5x")
else listOf(0.75f to "0.75x", 0.9f to "0.90x", 1.0f to "1.00x(기본)", 1.1f to "1.10x", 1.25f to "1.25x", 1.5f to "1.50x")
if (compact) {
Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn(if (SupertonicTtsEngine.enabled) "🔊Supertonic ON" else "📱기본TTS", SupertonicTtsEngine.enabled, CG) { SupertonicTtsEngine.setMode(ctx, !SupertonicTtsEngine.enabled) }
Text("💎스텝(${SupertonicTtsEngine.steps}):", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG)
ChipBtn("➖", false, CM) { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, (SupertonicTtsEngine.steps - 2).coerceAtLeast(2), SupertonicTtsEngine.speed) }
ChipBtn("➕", false, CG) { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, (SupertonicTtsEngine.steps + 2).coerceAtMost(32), SupertonicTtsEngine.speed) }
stepPresets.forEach { (st, lb) -> ChipBtn(lb, SupertonicTtsEngine.steps == st, CA) { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, st, SupertonicTtsEngine.speed) } }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
Text("⏩속도(${String.format(java.util.Locale.US, "%.2fx", SupertonicTtsEngine.speed)}):", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CA)
ChipBtn("-0.05x", false, CM) { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, SupertonicTtsEngine.steps, ((SupertonicTtsEngine.speed * 20f).toInt() - 1).coerceIn(12, 36) / 20f) }
ChipBtn("+0.05x", false, CA) { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, SupertonicTtsEngine.steps, ((SupertonicTtsEngine.speed * 20f).toInt() + 1).coerceIn(12, 36) / 20f) }
spdPresets.forEach { (sp, lb) -> ChipBtn(lb, kotlin.math.abs(SupertonicTtsEngine.speed - sp) < 0.03f, CW) { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, SupertonicTtsEngine.steps, sp) } }
}
}
} else {
Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("💎 추론 스텝 (음질·속도): ${SupertonicTtsEngine.steps}스텝", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG)
Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = CV) {
ChipBtn("➖", false, CM) { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, (SupertonicTtsEngine.steps - 2).coerceAtLeast(2), SupertonicTtsEngine.speed) }
ChipBtn("➕", false, CG) { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, (SupertonicTtsEngine.steps + 2).coerceAtMost(32), SupertonicTtsEngine.speed) }
}
}
Slider(value = SupertonicTtsEngine.steps.toFloat(), onValueChange = { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, it.toInt().coerceIn(2, 32), SupertonicTtsEngine.speed) }, valueRange = 2f..32f, steps = 14, modifier = Modifier.fillMaxWidth())
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
stepPresets.forEach { (st, lb) -> ChipBtn(lb, SupertonicTtsEngine.steps == st, CA) { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, st, SupertonicTtsEngine.speed) } }
}
Box(Modifier.fillMaxWidth().height(0.8.dp).background(Color(0xFF1C2940)))
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("⏩ 말하기 속도: ${String.format(java.util.Locale.US, "%.2fx", SupertonicTtsEngine.speed)}", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CA)
Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = CV) {
ChipBtn("-0.05x", false, CM) { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, SupertonicTtsEngine.steps, ((SupertonicTtsEngine.speed * 20f).toInt() - 1).coerceIn(12, 36) / 20f) }
ChipBtn("+0.05x", false, CA) { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, SupertonicTtsEngine.steps, ((SupertonicTtsEngine.speed * 20f).toInt() + 1).coerceIn(12, 36) / 20f) }
}
}
Slider(value = SupertonicTtsEngine.speed, onValueChange = { val r = ((it * 20f).toInt().coerceIn(12, 36)) / 20f; SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, SupertonicTtsEngine.steps, r) }, valueRange = 0.6f..1.8f, steps = 23, modifier = Modifier.fillMaxWidth())
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
spdPresets.forEach { (sp, lb) -> ChipBtn(lb, kotlin.math.abs(SupertonicTtsEngine.speed - sp) < 0.03f, CW) { SupertonicTtsEngine.setVoiceCfg(ctx, SupertonicTtsEngine.voice, SupertonicTtsEngine.steps, sp) } }
}
Box(Modifier.fillMaxWidth().height(0.8.dp).background(Color(0xFF1C2940)))
Text("🗣️ 보이스 스타일 & 음향 마스터링 (현재: ${SupertonicTtsEngine.voice})", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
listOf("F1" to "F1(차분)", "F2" to "F2(밝음)", "F3" to "F3(아나운서)", "F4" to "F4(또렷)", "F5" to "F5(부드러움)", "M1" to "M1(차분)", "M2" to "M2(중후)", "M3" to "M3(친근)", "M4" to "M4(신뢰)", "M5" to "M5(따뜻)").forEach { (v, lb) ->
ChipBtn(lb, SupertonicTtsEngine.voice == v, CG) { SupertonicTtsEngine.setVoiceCfg(ctx, v, SupertonicTtsEngine.steps, SupertonicTtsEngine.speed) }
}
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
ChipBtn(if (SupertonicTtsEngine.clarityOn) "✨ 고음명료도 ON" else "✨ 고음명료도 OFF", SupertonicTtsEngine.clarityOn, CG) { SupertonicTtsEngine.setAudioExtra(ctx, !SupertonicTtsEngine.clarityOn, SupertonicTtsEngine.pauseMs) }
listOf(80L to "쉼0.1초", 220L to "쉼0.2초(기본)", 450L to "쉼0.5초", 750L to "쉼0.8초").forEach { (pm, lb) ->
ChipBtn(lb, SupertonicTtsEngine.pauseMs == pm, CA) { SupertonicTtsEngine.setAudioExtra(ctx, SupertonicTtsEngine.clarityOn, pm) }
}
}
Text("🔒 백그라운드 미디어 포그라운드 적용: 화면이 꺼져도 낭독이 멈추지 않습니다.", fontSize = (ui.subSp.value - 0.5f).sp, color = CG)
}
}
}

data class PageSlice(val s: Int, val e: Int)
class BookReflowSource(val fullText: String, val rawStarts: IntArray, val rawEnds: IntArray) {
fun rawIdxOf(charOff: Int): Int {
if (rawStarts.isEmpty()) return 0
var lo = 0; var hi = rawStarts.lastIndex; var ans = 0
while (lo <= hi) {
val mid = (lo + hi) ushr 1
if (rawStarts[mid] <= charOff) { ans = mid; lo = mid + 1 } else hi = mid - 1
}
return ans
}
fun rawStartOf(idx: Int): Int = if (rawStarts.isEmpty()) 0 else rawStarts[idx.coerceIn(0, rawStarts.lastIndex)]
fun rawEndOf(idx: Int): Int = if (rawEnds.isEmpty()) fullText.length else rawEnds[idx.coerceIn(0, rawEnds.lastIndex)]
}
fun buildBookReflowSource(pages: List<String>): BookReflowSource {
if (pages.isEmpty()) return BookReflowSource("", IntArray(0), IntArray(0))
val starts = IntArray(pages.size)
val ends = IntArray(pages.size)
val sb = StringBuilder(pages.sumOf { it.length + 2 })
pages.forEachIndexed { i, raw ->
if (i > 0) sb.append("\n\n")
starts[i] = sb.length
sb.append(raw.trim())
ends[i] = sb.length
}
return BookReflowSource(sb.toString(), starts, ends)
}
fun paginateBookReflow(
full: String,
wPx: Int,
hPx: Float,
style: TextStyle,
tm: TextMeasurer
): List<PageSlice> {
if (full.isBlank() || wPx <= 40 || hPx <= 40f) return listOf(PageSlice(0, full.length))
val out = ArrayList<PageSlice>(64)
val n = full.length
var pos = 0
while (pos < n && (full[pos] == '\n' || full[pos] == '\r')) pos++
while (pos < n) {
var winEnd = minOf(n, pos + 2000)
if (winEnd < n) {
val sp = full.indexOfAny(charArrayOf('\n', ' '), winEnd)
if (sp in winEnd..minOf(n - 1, pos + 2400)) winEnd = sp + 1
}
var res = tm.measure(AnnotatedString(full.substring(pos, winEnd)), style = style, constraints = Constraints(maxWidth = wPx))
if (winEnd < n && res.lineCount > 0 && res.getLineBottom(res.lineCount - 1) <= hPx) {
winEnd = minOf(n, pos + 5200)
res = tm.measure(AnnotatedString(full.substring(pos, winEnd)), style = style, constraints = Constraints(maxWidth = wPx))
}
if (res.lineCount <= 0) break
var lastLn = 0
for (ln in 0 until res.lineCount) {
if (res.getLineBottom(ln) <= hPx) lastLn = ln else break
}
if (lastLn == res.lineCount - 1 && winEnd == n) {
out.add(PageSlice(pos, n))
break
}
val cut = res.getLineEnd(lastLn, false).coerceAtLeast(1)
out.add(PageSlice(pos, (pos + cut).coerceAtMost(n)))
pos += cut
while (pos < n && (full[pos] == '\n' || full[pos] == '\r')) pos++
}
return if (out.isEmpty()) listOf(PageSlice(0, n)) else out
}

@Composable
fun ReaderShelfSection(
ctx: Context, ui: ResponsiveUi, books: List<BookItem>, bCats: List<String>, allCats: List<String>,
selCat: String, onSelCat: (String) -> Unit, onOpenCatCfg: () -> Unit, cfgOpen: Boolean, rCfgTab: Int,
sortMode: String, onSetSort: (String) -> Unit, reorderMode: Boolean, onToggleReorder: () -> Unit,
bookQuery: String, onBookQuery: (String) -> Unit, bookMeta: Map<Long, BookSortMeta>, bms: List<BookmarkEntry>,
fg: Color, mut: Color, onSaveBooks: (List<BookItem>) -> Unit, onOpenBook: (BookItem, Boolean) -> Unit,
onTogglePin: (Long) -> Unit, onMoveOrder: (Long, Int) -> Unit, onDeleteBook: (Long) -> Unit
) {
var editId by remember { mutableStateOf<Long?>(null) }
var editT by remember { mutableStateOf("") }
var editC by remember { mutableStateOf("") }
fun metaOf(id: Long, defOrd: Int = 0) = bookMeta[id] ?: BookSortMeta(sortOrder = defOrd)
Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
allCats.forEach { c -> val cnt = if (c == "전체") books.size else books.count { (it.category.ifBlank { "기타" }) == c }; ChipBtn("$c($cnt)", selCat == c, CG) { onSelCat(c) } }
ChipBtn("➕분류", cfgOpen && rCfgTab == 2, CA) { onOpenCatCfg() }
}
Surface(color = mut.copy(alpha = 0.08f), shape = RoundedCornerShape(11.dp), border = BorderStroke(0.7.dp, mut.copy(alpha = 0.25f)), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = CV) {
Text("정렬:", fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = mut)
listOf("CUSTOM" to "✋직접정렬", "RECENT" to "🕒최근읽은순", "NEWEST" to "🆕등록순", "TITLE_ASC" to "🔤가나다순", "TITLE_DESC" to "🔤역순", "PROGRESS" to "📊진도율순", "LENGTH" to "📑분량순").forEach { (k, lb) ->
ChipBtn(lb, sortMode == k, CA) { onSetSort(k) }
}
ChipBtn(if (reorderMode) "✅순서편집 완료" else "↕️순서직접수정", reorderMode, CG) { onToggleReorder() }
}
OutlinedTextField(value = bookQuery, onValueChange = { onBookQuery(it) }, placeholder = { Text("🔎 도서 제목·저자·초성 검색...", fontSize = ui.subSp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
}
val filteredBooks = remember(books, selCat, sortMode, bookQuery, bookMeta) {
val ordMap = books.mapIndexed { idx, bk -> bk.id to idx }.toMap()
val byCat = if (selCat == "전체") books else books.filter { (it.category.ifBlank { "기타" }) == selCat }
val byQ = if (bookQuery.isBlank()) byCat else byCat.filter { KoreanChosungMatcher.matches(it.title + " " + it.author, bookQuery) }
val baseCmp = Comparator<BookItem> { a, b ->
val ma = bookMeta[a.id] ?: BookSortMeta(sortOrder = ordMap[a.id] ?: 0)
val mb = bookMeta[b.id] ?: BookSortMeta(sortOrder = ordMap[b.id] ?: 0)
if (ma.pinned != mb.pinned) (if (ma.pinned) -1 else 1)
else when (sortMode) {
"RECENT" -> mb.lastReadAt.compareTo(ma.lastReadAt).let { if (it != 0) it else b.id.compareTo(a.id) }
"NEWEST" -> b.id.compareTo(a.id)
"TITLE_ASC" -> a.title.compareTo(b.title, ignoreCase = true)
"TITLE_DESC" -> b.title.compareTo(a.title, ignoreCase = true)
"PROGRESS" -> ((b.currentPage + 1).toFloat() / b.pages.size.coerceAtLeast(1)).compareTo((a.currentPage + 1).toFloat() / a.pages.size.coerceAtLeast(1))
"LENGTH" -> b.pages.size.compareTo(a.pages.size)
else -> ma.sortOrder.compareTo(mb.sortOrder).let { if (it != 0) it else (ordMap[a.id] ?: 0).compareTo(ordMap[b.id] ?: 0) }
}
}
byQ.sortedWith(baseCmp)
}
if (filteredBooks.isEmpty()) Text("표시할 도서가 없습니다. 상단 [📂] 버튼으로 TXT·PDF·EPUB 도서를 추가해 보세요.", fontSize = ui.subSp, color = mut, modifier = Modifier.padding(8.dp))
LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
itemsIndexed(filteredBooks, key = { _, b -> b.id }) { idxInList, b ->
val bmCnt = bms.count { it.bookId == b.id }
val pct = (((b.currentPage + 1) * 100) / b.pages.size.coerceAtLeast(1)).coerceIn(1, 100); val isPin = metaOf(b.id).pinned
Surface(color = mut.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), border = if (isPin) BorderStroke(1.dp, CA.copy(alpha = 0.7f)) else BorderStroke(0.6.dp, mut.copy(alpha = 0.22f)), modifier = Modifier.fillMaxWidth()) {
if (editId == b.id) {
Column(Modifier.padding(ui.cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = editT, onValueChange = { editT = it }, label = { Text("도서 제목") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
(bCats + listOf("인문·철학", "소설", "에세이", "자기계발", "기타")).distinct().forEach { bc -> ChipBtn(bc, editC == bc, CG) { editC = bc } }
}
Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
TextButton(onClick = { editId = null }) { Text("✕", color = mut) }
Button(onClick = { if (editT.isNotBlank()) { onSaveBooks(books.map { if (it.id == b.id) it.copy(title = editT.trim(), category = editC.ifBlank { "에세이" }) else it }); editId = null } }) { Text("💾") }
}
}
} else {
Column(Modifier.padding(ui.cardPad).fillMaxWidth().clickable { onOpenBook(b, false) }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
Row(verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
Text("#${idxInList + 1}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CA)
if (isPin) Text("📌", fontSize = 11.sp)
Surface(color = CG.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp), border = BorderStroke(0.7.dp, CG.copy(alpha = 0.6f)), modifier = Modifier.clickable { editId = b.id; editT = b.title; editC = b.category }) { Text(b.category.ifBlank { "기타" }, fontSize = ui.subSp, fontWeight = FontWeight.Bold, color = CG, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)) }
Text(b.title, fontSize = ui.bodySp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
val bmStr = if (bmCnt > 0) " · 🔖 ${bmCnt}개" else ""
Text("${b.author} · 진도 ${pct}%$bmStr", fontSize = ui.subSp, color = mut)
}
Row(verticalAlignment = CV) {
TextButton(onClick = { onTogglePin(b.id) }) { Text(if (isPin) "📌" else "📍", fontSize = ui.subSp, color = if (isPin) CA else mut) }
TextButton(onClick = { onOpenBook(b, true) }) { Text("🔊", fontSize = ui.subSp, color = CA) }
TextButton(onClick = { editId = b.id; editT = b.title; editC = b.category }) { Text("✏️", fontSize = ui.subSp, color = CG) }
TextButton(onClick = { onDeleteBook(b.id) }) { Text("🗑️", fontSize = ui.subSp, color = Color(0xFFEF4444)) }
}
}
if (reorderMode || sortMode == "CUSTOM") {
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
Text("순서이동:", fontSize = 10.sp, color = mut)
ChipBtn("⏫ 맨위로", false, CA) { onMoveOrder(b.id, -2) }
ChipBtn("⬆️ 위로", false, CG) { onMoveOrder(b.id, -1) }
ChipBtn("⬇️ 아래로", false, CG) { onMoveOrder(b.id, 1) }
ChipBtn("⏬ 맨아래", false, CM) { onMoveOrder(b.id, 2) }
}
}
}
}
}
}
}
}
}
