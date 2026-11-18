package app.prolauncher.ai
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

data class AppEntry(
val label: String,
val packageName: String,
val isFavorite: Boolean,
val isInterruptEnabled: Boolean
)

data class NoteItem(
val id: Long,
val content: String,
val summary: String,
val tags: List<String>,
val createdAt: String,
val aiEngine: String = "LocalAI"
)

data class BookItem(
val id: Long,
val title: String,
val author: String,
val category: String,
val pages: List<String>,
val currentPage: Int = 0
)

data class LauncherConfig(
val themeName: String = "AMOLED",
val textAlign: String = "LEFT",
val interruptSeconds: Int = 6,
val readerTheme: String = "SEPIA",
val readerFontSizeSp: Int = 18,
val readerUseSerif: Boolean = true,
val ragIncludeBooks: Boolean = true
)

class LauncherRepository(private val context: Context) {
private val prefs = context.getSharedPreferences("pl_ai_v2_prefs", Context.MODE_PRIVATE)

fun isDefaultHomeApp(): Boolean {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
val rm = context.getSystemService(RoleManager::class.java)
if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
return rm.isRoleHeld(RoleManager.ROLE_HOME)
}
}
val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
val res = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
return res?.activityInfo?.packageName == context.packageName
}

fun buildRequestDefaultHomeIntent(): Intent {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
val rm = context.getSystemService(RoleManager::class.java)
if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
return rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
}
}
return Intent(Settings.ACTION_HOME_SETTINGS)
}

fun openSystemDefaultAppsSettings() {
runCatching {
context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.onFailure {
context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
}

fun loadInstalledApps(): List<AppEntry> {
val pm = context.packageManager
val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
val rawList = pm.queryIntentActivities(mainIntent, 0)
val favSet = getFavoritePackages()
val intSet = getInterruptPackages()
return rawList.mapNotNull { ri ->
val pkg = ri.activityInfo.packageName
if (pkg == context.packageName) null
else {
val label = ri.loadLabel(pm).toString()
AppEntry(
label = label,
packageName = pkg,
isFavorite = favSet.contains(pkg) || favSet.contains(label),
isInterruptEnabled = intSet.contains(pkg) || intSet.contains(label)
)
}
}.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}

fun launchApp(packageName: String) {
val intent = context.packageManager.getLaunchIntentForPackage(packageName)
if (intent != null) {
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
context.startActivity(intent)
}
}

fun getFavoritePackages(): Set<String> =
prefs.getStringSet("fav_pkgs", setOf("전화", "메시지", "카메라", "카카오톡", "캘린더", "브라우저")) ?: emptySet()

fun toggleFavorite(pkgOrLabel: String) {
val cur = getFavoritePackages().toMutableSet()
if (cur.contains(pkgOrLabel)) cur.remove(pkgOrLabel) else cur.add(pkgOrLabel)
prefs.edit().putStringSet("fav_pkgs", cur).apply()
}

fun getInterruptPackages(): Set<String> =
prefs.getStringSet("int_pkgs", setOf("YouTube", "유튜브", "Instagram", "인스타그램")) ?: emptySet()

fun toggleInterrupt(pkgOrLabel: String) {
val cur = getInterruptPackages().toMutableSet()
if (cur.contains(pkgOrLabel)) cur.remove(pkgOrLabel) else cur.add(pkgOrLabel)
prefs.edit().putStringSet("int_pkgs", cur).apply()
}

fun loadConfig(): LauncherConfig = LauncherConfig(
themeName = prefs.getString("cfg_theme", "AMOLED") ?: "AMOLED",
textAlign = prefs.getString("cfg_align", "LEFT") ?: "LEFT",
interruptSeconds = prefs.getInt("cfg_int_sec", 6),
readerTheme = prefs.getString("cfg_reader_theme", "SEPIA") ?: "SEPIA",
readerFontSizeSp = prefs.getInt("cfg_reader_font", 18),
readerUseSerif = prefs.getBoolean("cfg_reader_serif", true),
ragIncludeBooks = prefs.getBoolean("cfg_rag_books", true)
)

fun saveConfig(cfg: LauncherConfig) {
prefs.edit()
.putString("cfg_theme", cfg.themeName)
.putString("cfg_align", cfg.textAlign)
.putInt("cfg_int_sec", cfg.interruptSeconds)
.putString("cfg_reader_theme", cfg.readerTheme)
.putInt("cfg_reader_font", cfg.readerFontSizeSp)
.putBoolean("cfg_reader_serif", cfg.readerUseSerif)
.putBoolean("cfg_rag_books", cfg.ragIncludeBooks)
.apply()
}

fun loadNotes(): List<NoteItem> {
val raw = prefs.getString("notes_json", null) ?: return defaultNotes()
return runCatching {
val arr = JSONArray(raw)
buildList {
for (i in 0 until arr.length()) {
val o = arr.getJSONObject(i)
val tagsArr = o.optJSONArray("tags") ?: JSONArray()
val tags = buildList { for (j in 0 until tagsArr.length()) add(tagsArr.getString(j)) }
add(NoteItem(o.getLong("id"), o.getString("content"), o.optString("summary", ""), tags, o.optString("createdAt", "오늘"), o.optString("aiEngine", "LocalAI")))
}
}
}.getOrElse { defaultNotes() }
}

fun saveNotes(list: List<NoteItem>) {
val arr = JSONArray()
list.forEach { n ->
arr.put(JSONObject().apply {
put("id", n.id)
put("content", n.content)
put("summary", n.summary)
put("tags", JSONArray(n.tags))
put("createdAt", n.createdAt)
put("aiEngine", n.aiEngine)
})
}
prefs.edit().putString("notes_json", arr.toString()).apply()
}

fun loadBooks(): List<BookItem> {
val raw = prefs.getString("books_json", null) ?: return defaultBooks()
return runCatching {
val arr = JSONArray(raw)
buildList {
for (i in 0 until arr.length()) {
val o = arr.getJSONObject(i)
val pArr = o.getJSONArray("pages")
val pages = buildList { for (j in 0 until pArr.length()) add(pArr.getString(j)) }
add(BookItem(o.getLong("id"), o.getString("title"), o.getString("author"), o.getString("category"), pages, o.optInt("currentPage", 0)))
}
}
}.getOrElse { defaultBooks() }
}

fun saveBooks(list: List<BookItem>) {
val arr = JSONArray()
list.forEach { b ->
arr.put(JSONObject().apply {
put("id", b.id)
put("title", b.title)
put("author", b.author)
put("category", b.category)
put("pages", JSONArray(b.pages))
put("currentPage", b.currentPage)
})
}
prefs.edit().putString("books_json", arr.toString()).apply()
}

fun importBookFromUri(uri: Uri): BookItem? {
return runCatching {
var name = "불러온 도서.txt"
context.contentResolver.query(uri, null, null, null, null)?.use { c ->
val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
}
val lower = name.lowercase()
val text = when {
lower.endsWith(".pdf") -> extractPdfText(uri)
lower.endsWith(".epub") -> extractEpubText(uri)
else -> {
  val rawTxt = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
  EbookTextReflower.unwrapNaturalParagraphs(rawTxt, preserveStanzas = false)
}
}
val clean = EbookTextReflower.cleanControlChars(text).trim()
  if (clean.isEmpty()) return null
val pages = splitBookIntoNaturalSections(clean, 1400)
  val cat = when {
lower.endsWith(".pdf") -> "PDF 문서"
lower.endsWith(".epub") -> "전자책"
else -> "텍스트북"
}
BookItem(System.currentTimeMillis(), name.substringBeforeLast("."), "내 서재 파일", cat, pages, 0)
}.getOrNull()
}

private fun splitBookIntoNaturalSections(clean: String, targetLen: Int = 1100): List<String> {
val out = mutableListOf<String>()
var rem = clean.trim()
while (rem.isNotEmpty()) {
if (rem.length <= targetLen) {
out.add(rem)
break
}
val lim = minOf(rem.length, targetLen)
val minCut = (targetLen / 2).coerceAtLeast(300)
var cut = rem.lastIndexOf("\n\n", lim).takeIf { it >= minCut }?.let { it + 2 } ?: -1
if (cut == -1) {
for (i in lim - 1 downTo minCut) {
val c = rem[i]
if ((c == '.' || c == '!' || c == '?' || c == '。') && (i + 1 >= rem.length || rem[i + 1].isWhitespace() || rem[i + 1] in charArrayOf('"', '\'', '”', '’', ')'))) {
cut = i + 1
break
}
}
}
if (cut == -1) {
cut = rem.lastIndexOf('\n', lim).takeIf { it >= minCut }?.let { it + 1 }
?: rem.lastIndexOf(' ', lim).takeIf { it >= minCut }?.let { it + 1 }
?: lim
}
val part = rem.substring(0, cut).trim()
if (part.isNotEmpty()) out.add(part)
rem = rem.substring(cut).trimStart()
}
return out.ifEmpty { listOf(clean) }
}

private fun extractPdfText(uri: Uri): String = EbookTextReflower.extractCleanPdf(context, uri)

private fun extractEpubText(uri: Uri): String = EbookTextReflower.extractCleanEpub(context, uri)

private fun defaultNotes(): List<NoteItem> = listOf(
NoteItem(
id = 1L,
content = "내일 오후 3시 프로젝트 기획안 피드백 미팅\n☐ 발표 핵심 자료 3장 요약본 점검\n☐ 회의실 예약 확인",
summary = "",
tags = listOf("업무", "중요"),
createdAt = "오늘",
aiEngine = "LocalAI"
),
NoteItem(
id = 2L,
content = "퇴근길 장보기 체크리스트\n☐ 우유 및 원두 구매\n☐ 세탁소 들르기",
summary = "",
tags = listOf("할일", "일상"),
createdAt = "오늘",
aiEngine = "LocalAI"
)
)

private fun defaultBooks(): List<BookItem> = listOf(
BookItem(
id = 1001L,
title = "미니멀 집중과 생각 정리 노트",
author = "베이직 가이드",
category = "에세이",
pages = listOf(
"【단순함의 힘】\n\n하루의 시작을 복잡하지 않게 여세요. 가장 중요한 일 한두 가지만 홈 화면에 두고, 스쳐 지나가는 생각은 짧게 기록해 두는 것만으로도 머릿속이 훨씬 가벼워집니다.",
"【깊은 독서와 기록】\n\n좋은 문장을 발견하면 밑줄을 긋듯 메모장으로 인용해 나만의 생각을 덧붙여 보세요. 짧은 메모는 그대로 두고, 긴 글은 필요할 때만 AI 요약으로 핵심 1~3줄을 정리할 수 있습니다."
)
),
BookItem(
id = 1002L,
title = "한국 현대 서정시선",
author = "윤동주 · 나태주 · 김소월",
category = "시/문학",
pages = listOf(
"【서시 (序詩) — 윤동주】\n\n죽는 날까지 하늘을 우러러\n한 점 부끄럼이 없기를,\n잎새에 이는 바람에도\n나는 괴로워했다.\n\n별을 노래하는 마음으로\n모든 죽어가는 것을 사랑해야지\n그리고 나한테 주어진 길을\n걸어가야겠다.",
"【풀꽃 — 나태주】\n\n자세히 보아야 예쁘다\n오래 보아야 사랑스럽다\n너도 그렇다."
)
),
BookItem(
id = 1003L,
title = "명상록 (스토아 철학 노트)",
author = "마르쿠스 아우렐리우스",
category = "인문/교양",
pages = listOf(
"【내면의 평온】\n\n1. 사람들은 조용한 휴식처를 찾아 시골로, 바닷가로, 산으로 떠난다. 그러나 언제든 자기 내면으로 물러나 평온을 회복할 수 있다면 그곳이 가장 고요한 휴식처다.\n\n2. 외부의 사건 자체보다 그 사건을 바라보는 나의 관점이 마음의 평정을 결정한다."
)
)
)
}
