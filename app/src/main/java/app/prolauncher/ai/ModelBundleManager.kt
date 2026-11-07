package app.prolauncher.ai
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
var bCats by remember { mutableStateOf(SmartMemoClassifier.loadBookCats(ctx)) }
var selId by remember { mutableStateOf(books.firstOrNull()?.id ?: 1001L) }
var shelf by remember { mutableStateOf(false) }
var cfgOpen by remember { mutableStateOf(false) }
var newCat by remember { mutableStateOf("") }
var editId by remember { mutableStateOf<Long?>(null) }
var editT by remember { mutableStateOf("") }
var editC by remember { mutableStateOf("") }
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
TextButton(onClick = { shelf = !shelf }) { Text(if (shelf) "📖 본문" else "📚 서재 (${books.size})", color = fg, fontWeight = FontWeight.Bold) }
Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = CV) {
OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("📂 파일 추가", fontSize = 11.sp, color = fg) }
OutlinedButton(onClick = { cfgOpen = !cfgOpen }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("⚙️ 독서대 설정", fontSize = 11.sp, color = fg) }
TextButton(onClick = onHome) { Text("홈 →", fontSize = 12.sp, color = mut) }
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
bCats.forEach { c -> ChipBtn(if (bCats.size > 1) "$c ✕" else c) { if (bCats.size > 1) { val u = bCats.filterNot { it == c }; bCats = u; SmartMemoClassifier.saveBookCats(ctx, u) } } }
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 도서 분류 추가", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !bCats.contains(t)) { val u = bCats + t; bCats = u; SmartMemoClassifier.saveBookCats(ctx, u); newCat = "" } }) { Text("+ 추가", fontSize = 11.sp) }
}
}
}
}
if (shelf || act == null) {
LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
items(books, key = { it.id }) { b ->
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
Text("${b.author} · ${b.currentPage + 1}/${b.pages.size}쪽", fontSize = 12.sp, color = mut)
}
Row {
TextButton(onClick = { editId = b.id; editT = b.title; editC = b.category }, contentPadding = PaddingValues(4.dp)) { Text("수정", fontSize = 12.sp, color = CG) }
TextButton(onClick = { val nx = books.filterNot { it.id == b.id }; onSaveBooks(nx); if (selId == b.id) selId = nx.firstOrNull()?.id ?: 0L }, contentPadding = PaddingValues(4.dp)) { Text("🗑️ 삭제", fontSize = 12.sp, color = Color(0xFFEF4444)) }
}
}
}
}
}
}
} else {
val idx = act.currentPage.coerceIn(0, (act.pages.size - 1).coerceAtLeast(0))
val content = act.pages.getOrElse(idx) { "" }
Row(Modifier.fillMaxWidth().padding(top = 4.dp), SB, CV) {
Text("[${act.category}] ${act.title}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = mut, modifier = Modifier.weight(1f))
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
Text("✏️ 수정", fontSize = 11.sp, color = CG, modifier = Modifier.clickable { editId = act.id; editT = act.title; editC = act.category; shelf = true }.padding(4.dp))
Text("🗑️ 삭제", fontSize = 11.sp, color = Color(0xFFEF4444), modifier = Modifier.clickable { val nx = books.filterNot { it.id == act.id }; onSaveBooks(nx); selId = nx.firstOrNull()?.id ?: 0L; shelf = true }.padding(4.dp))
}
}
Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
Text(content, fontSize = cfg.readerFontSizeSp.sp, lineHeight = (cfg.readerFontSizeSp * 1.65f).sp, fontFamily = if (cfg.readerUseSerif) FontFamily.Serif else FontFamily.SansSerif, color = fg)
}
Row(Modifier.fillMaxWidth(), SB, CV) {
TextButton(onClick = { if (idx > 0) onSaveBooks(books.map { if (it.id == act.id) it.copy(currentPage = idx - 1) else it }) }, enabled = idx > 0) { Text("← 이전 쪽", color = fg) }
Text("${idx + 1} / ${act.pages.size} 쪽", fontSize = 12.sp, color = mut)
TextButton(onClick = { if (idx < act.pages.size - 1) onSaveBooks(books.map { if (it.id == act.id) it.copy(currentPage = idx + 1) else it }) }, enabled = idx < act.pages.size - 1) { Text("다음 쪽 →", color = fg) }
}
Button(onClick = { onQuote("『${act.title}』 (${idx + 1}쪽) 발췌:\n\"${content.take(180)}\"\n\n메모: ") }, modifier = Modifier.fillMaxWidth()) { Text("📝 이 문장 인용하여 메모장에 기록하기") }
}
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
var cats by remember { mutableStateOf(SmartMemoClassifier.loadMemoCats(ctx)) }
var newCat by remember { mutableStateOf("") }
var bCfg by remember { mutableStateOf(SmartMemoClassifier.loadBtnCfg(ctx)) }
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
cats.forEach { c -> ChipBtn(if (cats.size > 1) "$c ✕" else c) { if (cats.size > 1) { val nx = cats.filterNot { it == c }; cats = nx; SmartMemoClassifier.saveMemoCats(ctx, nx) } } }
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 카테고리 추가", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !cats.contains(t)) { val nx = cats + t; cats = nx; SmartMemoClassifier.saveMemoCats(ctx, nx); newCat = "" } }) { Text("추가", fontSize = 11.sp) }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("☑ 체크박스", bCfg.showCheck) { bCfg = bCfg.copy(showCheck = !bCfg.showCheck); SmartMemoClassifier.saveBtnCfg(ctx, bCfg) }
ChipBtn("• 글머리", bCfg.showBullet) { bCfg = bCfg.copy(showBullet = !bCfg.showBullet); SmartMemoClassifier.saveBtnCfg(ctx, bCfg) }
ChipBtn("📅 시간", bCfg.showDate) { bCfg = bCfg.copy(showDate = !bCfg.showDate); SmartMemoClassifier.saveBtnCfg(ctx, bCfg) }
ChipBtn("── 구분선", bCfg.showDivider) { bCfg = bCfg.copy(showDivider = !bCfg.showDivider); SmartMemoClassifier.saveBtnCfg(ctx, bCfg) }
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
