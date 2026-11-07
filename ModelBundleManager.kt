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
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChipBtn(text: String, sel: Boolean = false, col: Color = CG, onClick: () -> Unit) {
Surface(
color = if (sel) col else Color(0xFF1E2433),
shape = RoundedCornerShape(10.dp),
modifier = Modifier.clickable { onClick() }
) {
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
var bookCats by remember { mutableStateOf(SmartMemoClassifier.loadBookCats(ctx)) }
var selId by remember { mutableStateOf(books.firstOrNull()?.id ?: 1001L) }
var shelfMode by remember { mutableStateOf(false) }
var tabCfgOpen by remember { mutableStateOf(false) }
var newBookCat by remember { mutableStateOf("") }
var inlineEditBookId by remember { mutableStateOf<Long?>(null) }
var editBookTitle by remember { mutableStateOf("") }
var editBookCat by remember { mutableStateOf("") }
val active = books.find { it.id == selId } ?: books.firstOrNull()
val scope = rememberCoroutineScope()
val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
if (uri != null) scope.launch(Dispatchers.IO) { repo.importBookFromUri(uri)?.let { b -> withContext(Dispatchers.Main) { onSaveBooks(listOf(b) + books); selId = b.id; shelfMode = false } } }
}
val bg = when (cfg.readerTheme) { "SEPIA" -> Color(0xFFF4ECD8); "PAPER" -> Color(0xFFFAF8F5); else -> Color(0xFF0A0B0E) }
val fg = when (cfg.readerTheme) { "SEPIA" -> Color(0xFF2C221E); "PAPER" -> Color(0xFF1C1917); else -> Color(0xFFE5E7EB) }
val mut = when (cfg.readerTheme) { "SEPIA" -> Color(0xFF6E5A4F); "PAPER" -> Color(0xFF78716C); else -> CM }

Column(Modifier.fillMaxSize().background(bg).padding(18.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
TextButton(onClick = { shelfMode = !shelfMode }) { Text(if (shelfMode) "📖 본문 보기" else "📚 서재 (${books.size})", color = fg, fontWeight = FontWeight.Bold) }
Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = CV) {
OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("📂 파일 추가", fontSize = 11.sp, color = fg) }
OutlinedButton(onClick = { tabCfgOpen = !tabCfgOpen }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text(if (tabCfgOpen) "⚙️ 닫기" else "⚙️ 독서대 설정", fontSize = 11.sp, color = fg) }
TextButton(onClick = onHome) { Text("홈 →", fontSize = 12.sp, color = mut) }
}
}
if (tabCfgOpen) {
Surface(color = Color(0xFF14161F), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CG), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("⚙️ 독서대 설정 (배경·글꼴·도서 카테고리표 수정)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CW)
Row(Modifier.fillMaxWidth(), SB, CV) {
Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
listOf("SEPIA" to "양피지", "PAPER" to "종이", "NIGHT" to "야간").forEach { (k, l) -> ChipBtn(l, cfg.readerTheme == k) { onCfg(cfg.copy(readerTheme = k)) } }
}
Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = CV) {
ChipBtn(if (cfg.readerUseSerif) "명조체" else "고딕체", true) { onCfg(cfg.copy(readerUseSerif = !cfg.readerUseSerif)) }
ChipBtn("가-") { onCfg(cfg.copy(readerFontSizeSp = (cfg.readerFontSizeSp - 2).coerceAtLeast(14))) }
Text("${cfg.readerFontSizeSp}pt", fontSize = 11.sp, color = CG)
ChipBtn("가+") { onCfg(cfg.copy(readerFontSizeSp = (cfg.readerFontSizeSp + 2).coerceAtMost(28))) }
}
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
bookCats.forEach { c ->
ChipBtn(if (bookCats.size > 1) "$c ✕" else c) {
if (bookCats.size > 1) { val u = bookCats.filterNot { it == c }; bookCats = u; SmartMemoClassifier.saveBookCats(ctx, u) }
}
}
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = newBookCat, onValueChange = { newBookCat = it }, placeholder = { Text("새 도서 카테고리 추가", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newBookCat.trim(); if (t.isNotEmpty() && !bookCats.contains(t)) { val u = bookCats + t; bookCats = u; SmartMemoClassifier.saveBookCats(ctx, u); newBookCat = "" } }) { Text("+ 추가", fontSize = 11.sp) }
}
}
}
}
if (shelfMode || active == null) {
if (books.isEmpty()) {
Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
Text("서재에 등록된 책이나 문서가 없습니다.", fontSize = 14.sp, color = mut)
Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("📂 새 책/문서 파일 열기 (.pdf/.txt/.epub)") }
}
} else {
LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
items(books, key = { it.id }) { b ->
Surface(color = mut.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
if (inlineEditBookId == b.id) {
Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = editBookTitle, onValueChange = { editBookTitle = it }, label = { Text("도서 제목") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
bookCats.forEach { bc -> ChipBtn(bc, editBookCat == bc) { editBookCat = bc } }
}
Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
TextButton(onClick = { inlineEditBookId = null }) { Text("취소", color = mut) }
Button(onClick = {
if (editBookTitle.isNotBlank()) {
onSaveBooks(books.map { if (it.id == b.id) it.copy(title = editBookTitle.trim(), category = editBookCat.ifBlank { "에세이" }) else it })
inlineEditBookId = null
}
}) { Text("저장") }
}
}
} else {
Row(Modifier.padding(14.dp).fillMaxWidth().clickable { selId = b.id; shelfMode = false }, SB, CV) {
Column(Modifier.weight(1f)) {
Text("[${b.category}] ${b.title}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = fg)
Text("${b.author} · ${b.currentPage + 1}/${b.pages.size}쪽", fontSize = 12.sp, color = mut)
}
Row {
TextButton(onClick = { inlineEditBookId = b.id; editBookTitle = b.title; editBookCat = b.category }, contentPadding = PaddingValues(4.dp)) { Text("수정", fontSize = 12.sp, color = CG) }
TextButton(onClick = { val next = books.filterNot { it.id == b.id }; onSaveBooks(next); if (selId == b.id) selId = next.firstOrNull()?.id ?: 0L }, contentPadding = PaddingValues(4.dp)) { Text("🗑️ 삭제", fontSize = 12.sp, color = Color(0xFFEF4444)) }
}
}
}
}
}
}
} else {
val idx = active.currentPage.coerceIn(0, (active.pages.size - 1).coerceAtLeast(0))
val content = active.pages.getOrElse(idx) { "" }
Row(Modifier.fillMaxWidth().padding(top = 4.dp), SB, CV) {
Text("[${active.category}] ${active.title}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = mut, modifier = Modifier.weight(1f))
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
Text("✏️ 수정", fontSize = 11.sp, color = CG, modifier = Modifier.clickable { inlineEditBookId = active.id; editBookTitle = active.title; editBookCat = active.category; shelfMode = true }.padding(4.dp))
Text("🗑️ 삭제", fontSize = 11.sp, color = Color(0xFFEF4444), modifier = Modifier.clickable { val next = books.filterNot { it.id == active.id }; onSaveBooks(next); selId = next.firstOrNull()?.id ?: 0L; shelfMode = true }.padding(4.dp))
}
}
Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
Text(content, fontSize = cfg.readerFontSizeSp.sp, lineHeight = (cfg.readerFontSizeSp * 1.65f).sp, fontFamily = if (cfg.readerUseSerif) FontFamily.Serif else FontFamily.SansSerif, color = fg)
}
Row(Modifier.fillMaxWidth(), SB, CV) {
TextButton(onClick = { if (idx > 0) onSaveBooks(books.map { if (it.id == active.id) it.copy(currentPage = idx - 1) else it }) }, enabled = idx > 0) { Text("← 이전 쪽", color = fg) }
Text("${idx + 1} / ${active.pages.size} 쪽", fontSize = 12.sp, color = mut)
TextButton(onClick = { if (idx < active.pages.size - 1) onSaveBooks(books.map { if (it.id == active.id) it.copy(currentPage = idx + 1) else it }) }, enabled = idx < active.pages.size - 1) { Text("다음 쪽 →", color = fg) }
}
Button(onClick = { onQuote("『${active.title}』 (${idx + 1}쪽) 발췌:\n\"${content.take(180)}\"\n\n메모: ") }, modifier = Modifier.fillMaxWidth()) {
Text("📝 이 문장 인용하여 메모장에 기록하기")
}
}
}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerSheet(apps: List<AppEntry>, onClose: () -> Unit, onLaunch: (AppEntry) -> Unit, onFav: (AppEntry) -> Unit, onInt: (AppEntry) -> Unit) {
var q by remember { mutableStateOf("") }
val filtered = remember(apps, q) { apps.filter { KoreanChosungMatcher.matches(it.label, q) } }
LaunchedEffect(filtered, q) {
if (q.trim().isNotEmpty() && !q.startsWith(" ") && filtered.size == 1 && !filtered.first().isInterruptEnabled) { val a = filtered.first(); q = ""; onLaunch(a) }
}
Column(Modifier.fillMaxSize().background(Color(0xFA08080A)).padding(24.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("↑ 앱 서랍 (초성 검색)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CW)
TextButton(onClick = onClose) { Text("↓ 닫기", color = CM) }
}
OutlinedTextField(value = q, onValueChange = { q = it }, placeholder = { Text("초성 검색 (예: ㅋㅋㅇㅌ) · 공백 시작 시 자동실행 해제") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Spacer(Modifier.height(8.dp))
LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
items(filtered, key = { it.packageName }) { a ->
Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onLaunch(a) }, onLongClick = { onFav(a) }).padding(vertical = 10.dp), SB, CV) {
Text(a.label + (if (a.isFavorite) " ★" else ""), fontSize = 18.sp, color = Color(0xFFE5E7EB))
Text(if (a.isInterruptEnabled) "⏳ 차단중" else "⏳", fontSize = 12.sp, color = if (a.isInterruptEnabled) CA else Color(0xFF4B5563), modifier = Modifier.clickable { onInt(a) }.padding(6.dp))
}
}
}
}
}

@Composable
fun SettingsSheet(
cfg: LauncherConfig, isDefHome: Boolean, ai: LocalAiNoteEngine,
onAskHome: () -> Unit, onSysHome: () -> Unit, onCfg: (LauncherConfig) -> Unit, onClose: () -> Unit
) {
val ctx = LocalContext.current
val scope = rememberCoroutineScope()
var embStatus by remember { mutableStateOf(ai.modelLabel) }
var memoCats by remember { mutableStateOf(SmartMemoClassifier.loadMemoCats(ctx)) }
var newCat by remember { mutableStateOf("") }
var btnCfg by remember { mutableStateOf(SmartMemoClassifier.loadBtnCfg(ctx)) }
val embPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
if (uri != null) scope.launch { ai.loadModelUri(uri).onSuccess { embStatus = it }.onFailure { embStatus = "⚠️ 실패: ${it.message}" } }
}
Column(Modifier.fillMaxSize().background(Color(0xFA08080A)).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("⚙️ 전체 런처 설정", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CW)
TextButton(onClick = onClose) { Text("닫기", color = CG) }
}
Surface(color = CS, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("1. 기본 홈 화면(런처) 권한", fontWeight = FontWeight.Bold, color = CW)
Text(if (isDefHome) "✅ 기본 홈 런처로 설정됨" else "⚠️ 기본 홈 런처 미지정", fontSize = 12.sp, color = CG)
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
Button(onClick = onAskHome) { Text("🏠 기본 홈 권한 요청", fontSize = 12.sp) }
OutlinedButton(onClick = onSysHome) { Text("시스템 기본 앱 설정", fontSize = 12.sp) }
}
}
}
Surface(color = CS, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("2. 홈 화면 테마 & 정렬", fontWeight = FontWeight.Bold, color = CW)
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
listOf("AMOLED" to "블랙", "SLATE" to "슬레이트", "FOREST" to "포레스트").forEach { (k, l) -> ChipBtn(l, cfg.themeName == k) { onCfg(cfg.copy(themeName = k)) } }
}
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
listOf("LEFT" to "왼쪽 정렬", "CENTER" to "가운데", "RIGHT" to "오른쪽").forEach { (k, l) -> ChipBtn(l, cfg.textAlign == k) { onCfg(cfg.copy(textAlign = k)) } }
}
}
}
Surface(color = CS, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("3. 메모 카테고리표 및 기능버튼 설정", fontWeight = FontWeight.Bold, color = CW)
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
memoCats.forEach { c ->
ChipBtn(if (memoCats.size > 1) "$c ✕" else c) {
if (memoCats.size > 1) { val nx = memoCats.filterNot { it == c }; memoCats = nx; SmartMemoClassifier.saveMemoCats(ctx, nx) }
}
}
}
Row(Modifier.fillMaxWidth(), verticalAlignment = CV, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
OutlinedTextField(value = newCat, onValueChange = { newCat = it }, placeholder = { Text("새 카테고리 추가", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
Button(onClick = { val t = newCat.trim(); if (t.isNotEmpty() && !memoCats.contains(t)) { val nx = memoCats + t; memoCats = nx; SmartMemoClassifier.saveMemoCats(ctx, nx); newCat = "" } }) { Text("추가", fontSize = 11.sp) }
}
Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
ChipBtn("☑ 체크박스", btnCfg.showCheck) { btnCfg = btnCfg.copy(showCheck = !btnCfg.showCheck); SmartMemoClassifier.saveBtnCfg(ctx, btnCfg) }
ChipBtn("• 글머리", btnCfg.showBullet) { btnCfg = btnCfg.copy(showBullet = !btnCfg.showBullet); SmartMemoClassifier.saveBtnCfg(ctx, btnCfg) }
ChipBtn("📅 시간", btnCfg.showDate) { btnCfg = btnCfg.copy(showDate = !btnCfg.showDate); SmartMemoClassifier.saveBtnCfg(ctx, btnCfg) }
ChipBtn("── 구분선", btnCfg.showDivider) { btnCfg = btnCfg.copy(showDivider = !btnCfg.showDivider); SmartMemoClassifier.saveBtnCfg(ctx, btnCfg) }
}
}
}
Surface(color = CS, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("4. 온디바이스 AI & 검색 설정", fontWeight = FontWeight.Bold, color = CG)
Text("AI 엔진 상태: $embStatus", fontSize = 12.sp, color = CG)
OutlinedButton(onClick = { embPicker.launch(arrayOf("*/*")) }) { Text("📂 외부 AI 모델 파일 연결 (.litertlm)", fontSize = 12.sp) }
Row(Modifier.fillMaxWidth(), SB, CV) {
Text("검색 시 왼쪽 독서대 본문 포함", fontSize = 12.sp, color = CW)
Switch(checked = cfg.ragIncludeBooks, onCheckedChange = { onCfg(cfg.copy(ragIncludeBooks = it)) })
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
TextButton(onClick = { if (left == 0) onGo() }, enabled = left == 0) {
Text(if (left > 0) "${left}초 후 열기 가능..." else "그래도 ${app.label} 열기")
}
}
}
}
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

enum class OnDeviceModelFormat(val label: String, val runtimeName: String) {
    LITERT_LM("Google AI Edge LiteRT-LM (.litertlm)", "LiteRT-LM / MediaPipe GPU-CPU"),
    MEDIAPIPE_TASK("MediaPipe GenAI Task Bundle (.task / .bin)", "MediaPipe LlmInference"),
    GGUF("GGUF Quantized Model (.gguf)", "llama.cpp On-Device Engine"),
    UNKNOWN("알 수 없는 형식", "내장 한국어 NLP 폴백")
}

data class LoadedModelMetadata(
    val fileName: String,
    val localPath: String,
    val sizeBytes: Long,
    val format: OnDeviceModelFormat,
    val architectureHint: String,
    val bundledEntries: List<String> = emptyList()
)

/**
 * Automatically imports, unpacks, and inspects user-supplied on-device LLM files:
 * - `.litertlm` (Google AI Edge Gallery format for Gemma 4 E2B / E4B, Gemma 3n E2B)
 * - `.task` / `.bin` / `.tflite` (MediaPipe LLM Inference bundle)
 * - `.gguf` (llama.cpp quantized models: Q4_K_M, Q4_0, IQ4_XS, etc.)
 * - `.zip` archives wrapping any of the above (automatically unzipped on-device)
 */
class ModelBundleManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "ondevice_models").apply { mkdirs() }

    /**
     * Imports a model file selected via Android SAF file picker (content:// Uri),
     * automatically unzips if compressed, inspects binary magic bytes, and prepares it for inference.
     */
    suspend fun importModelFromUri(uri: Uri): Result<LoadedModelMetadata> = withContext(Dispatchers.IO) {
        try {
            val originalName = queryDisplayName(uri) ?: "imported_model.litertlm"
            val lower = originalName.lowercase()

            // If user selected a .zip archive containing a model, unpack the inner model file automatically
            if (lower.endsWith(".zip")) {
                val extractedFile = extractModelFromZipUri(uri)
                    ?: return@withContext Result.failure(IllegalArgumentException("ZIP 내부에서 지원 모델(.litertlm, .task, .gguf, .bin)을 찾지 못했습니다."))
                return@withContext Result.success(inspectModelFile(extractedFile))
            }

            val destFile = File(modelsDir, originalName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return@withContext Result.failure(IllegalStateException("파일 스트림을 열 수 없습니다."))

            Result.success(inspectModelFile(destFile))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Scans internal app storage and common Download/AI Edge Gallery paths for existing models.
     */
    suspend fun findAvailableModels(): List<LoadedModelMetadata> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LoadedModelMetadata>()
        val candidateDirs = listOf(
            modelsDir,
            File("/data/local/tmp/llm"),
            File("/sdcard/Download")
        )
        val supportedExts = listOf(".litertlm", ".task", ".gguf", ".bin")

        for (dir in candidateDirs) {
            if (!dir.exists() || !dir.canRead()) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile && supportedExts.any { file.name.lowercase().endsWith(it) }) {
                    results.add(inspectModelFile(file))
                }
            }
        }
        results
    }

    /**
     * Inspects binary header (GGUF magic vs PK Zip/Task bundle vs FlatBuffer) and extracts metadata.
     */
    fun inspectModelFile(file: File): LoadedModelMetadata {
        val nameLower = file.name.lowercase()
        val header = ByteArray(16)
        val readBytes = try {
            FileInputStream(file).use { it.read(header) }
        } catch (_: Exception) {
            0
        }

        val isGgufMagic = readBytes >= 4 &&
            header[0] == 'G'.code.toByte() &&
            header[1] == 'G'.code.toByte() &&
            header[2] == 'U'.code.toByte() &&
            header[3] == 'F'.code.toByte()

        val isPkZipBundle = readBytes >= 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()

        val format = when {
            isGgufMagic || nameLower.endsWith(".gguf") -> OnDeviceModelFormat.GGUF
            nameLower.endsWith(".litertlm") -> OnDeviceModelFormat.LITERT_LM
            nameLower.endsWith(".task") || nameLower.endsWith(".bin") -> OnDeviceModelFormat.MEDIAPIPE_TASK
            isPkZipBundle -> OnDeviceModelFormat.LITERT_LM
            else -> OnDeviceModelFormat.UNKNOWN
        }

        // If the .litertlm or .task file is a ZIP-based bundle, list its internal components (e.g., tokenizer, tflite weights)
        val innerEntries = if (isPkZipBundle) listZipEntries(file) else emptyList()

        val archHint = when {
            nameLower.contains("gemma-4") || nameLower.contains("gemma4") -> {
                if (nameLower.contains("e4b")) "Gemma 4 E4B (On-Device)" else "Gemma 4 E2B (AI Edge Gallery)"
            }
            nameLower.contains("gemma-3n") || nameLower.contains("gemma3n") -> "Gemma 3n E2B (Multimodal Edge)"
            nameLower.contains("gemma") -> "Gemma On-Device"
            nameLower.contains("qwen") -> "Qwen Local"
            nameLower.contains("llama") -> "Llama Local"
            format == OnDeviceModelFormat.GGUF -> {
                val ggufVer = if (readBytes >= 8) {
                    ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                } else 3
                "GGUF v$ggufVer Quantized LLM"
            }
            else -> "Custom On-Device LLM"
        }

        return LoadedModelMetadata(
            fileName = file.name,
            localPath = file.absolutePath,
            sizeBytes = file.length(),
            format = format,
            architectureHint = archHint,
            bundledEntries = innerEntries
        )
    }

    private fun extractModelFromZipUri(uri: Uri): File? {
        val supportedExts = listOf(".litertlm", ".task", ".gguf", ".bin", ".tflite")
        context.contentResolver.openInputStream(uri)?.use { rawIn ->
            ZipInputStream(BufferedInputStream(rawIn)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = File(entry.name).name
                    if (!entry.isDirectory && supportedExts.any { entryName.lowercase().endsWith(it) }) {
                        val outFile = File(modelsDir, entryName)
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos, bufferSize = 64 * 1024)
                        }
                        return outFile
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return null
    }

    private fun listZipEntries(file: File): List<String> {
        val list = mutableListOf<String>()
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null && list.size < 8) {
                    list.add(entry.name)
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }
}
