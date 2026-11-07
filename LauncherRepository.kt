package app.prolauncher.ai

import android.app.Activity
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
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.BufferedInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

data class AppEntry(
    val label: String,
    val packageName: String,
    val isFavorite: Boolean = false,
    val isInterruptEnabled: Boolean = false
)

data class NoteItem(
    val id: Long,
    val content: String,
    val summary: String,
    val tags: List<String>,
    val createdAt: String,
    val engineUsed: String
)

data class TodoItem(
    val id: Long,
    val title: String,
    val dueHint: String,
    val priority: String,
    val isDone: Boolean = false
)

data class BookItem(
    val id: Long,
    val title: String,
    val category: String, // "시", "동기부여", "성경", "내 파일"
    val author: String,
    val pages: List<String>,
    val currentPage: Int = 0,
    val isBookmarked: Boolean = false
)

data class LauncherConfig(
    val themeName: String = "AMOLED", // AMOLED, SLATE, FOREST, PAPER
    val textAlign: String = "LEFT",   // LEFT, CENTER, RIGHT
    val interruptSeconds: Int = 5,
    val readerTheme: String = "SEPIA", // SEPIA, NIGHT, PAPER
    val readerFontSizeSp: Int = 18,
    val readerUseSerif: Boolean = true,
    val ragIncludeBooks: Boolean = true,
    val ragMrlDim: Int = 256
)

class LauncherRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("pro_launcher_ai_prefs_v2", Context.MODE_PRIVATE)

    /**
     * Checks if Pro Launcher AI is currently set as the system default Home Launcher.
     */
    fun isDefaultHomeApp(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName == context.packageName
    }

    /**
     * Builds the system intent to request Default Home Screen permission (RoleManager ROLE_HOME or Home Settings).
     */
    fun buildRequestDefaultHomeIntent(): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME) && !roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }
        return Intent(Settings.ACTION_HOME_SETTINGS)
    }

    fun openSystemDefaultAppsSettings() {
        try {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    fun loadInstalledApps(): List<AppEntry> {
        val pm: PackageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val favorites = getStringSet("favorites", setOf("전화", "메시지", "카메라", "카카오톡", "브라우저"))
        val interrupts = getStringSet("interrupts", setOf("YouTube", "유튜브", "Instagram", "인스타그램", "TikTok", "틱톡"))

        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg == context.packageName) return@mapNotNull null
                val label = resolveInfo.loadLabel(pm).toString()
                AppEntry(
                    label = label,
                    packageName = pkg,
                    isFavorite = favorites.contains(pkg) || favorites.contains(label),
                    isInterruptEnabled = interrupts.contains(pkg) || interrupts.contains(label)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun launchApp(packageName: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    }

    fun toggleFavorite(packageName: String) {
        val set = getStringSet("favorites", emptySet()).toMutableSet()
        if (!set.add(packageName)) set.remove(packageName)
        prefs.edit().putStringSet("favorites", set).apply()
    }

    fun toggleInterrupt(packageName: String) {
        val set = getStringSet("interrupts", emptySet()).toMutableSet()
        if (!set.add(packageName)) set.remove(packageName)
        prefs.edit().putStringSet("interrupts", set).apply()
    }

    fun loadConfig(): LauncherConfig {
        return LauncherConfig(
            themeName = prefs.getString("cfg_theme", "AMOLED") ?: "AMOLED",
            textAlign = prefs.getString("cfg_align", "LEFT") ?: "LEFT",
            interruptSeconds = prefs.getInt("cfg_interrupt_sec", 5),
            readerTheme = prefs.getString("cfg_reader_theme", "SEPIA") ?: "SEPIA",
            readerFontSizeSp = prefs.getInt("cfg_reader_font_sp", 18),
            readerUseSerif = prefs.getBoolean("cfg_reader_serif", true),
            ragIncludeBooks = prefs.getBoolean("cfg_rag_books", true),
            ragMrlDim = prefs.getInt("cfg_rag_dim", 256)
        )
    }

    fun saveConfig(cfg: LauncherConfig) {
        prefs.edit()
            .putString("cfg_theme", cfg.themeName)
            .putString("cfg_align", cfg.textAlign)
            .putInt("cfg_interrupt_sec", cfg.interruptSeconds)
            .putString("cfg_reader_theme", cfg.readerTheme)
            .putInt("cfg_reader_font_sp", cfg.readerFontSizeSp)
            .putBoolean("cfg_reader_serif", cfg.readerUseSerif)
            .putBoolean("cfg_rag_books", cfg.ragIncludeBooks)
            .putInt("cfg_rag_dim", cfg.ragMrlDim)
            .apply()
    }

    fun saveNotes(notes: List<NoteItem>) {
        val arr = JSONArray()
        for (n in notes) {
            arr.put(JSONObject().apply {
                put("id", n.id)
                put("content", n.content)
                put("summary", n.summary)
                put("tags", JSONArray(n.tags))
                put("createdAt", n.createdAt)
                put("engineUsed", n.engineUsed)
            })
        }
        prefs.edit().putString("notes_json", arr.toString()).apply()
    }

    fun loadNotes(): List<NoteItem> {
        val raw = prefs.getString("notes_json", null) ?: return defaultNotes()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val tagArr = obj.optJSONArray("tags") ?: JSONArray()
                val tags = (0 until tagArr.length()).map { tagArr.getString(it) }
                NoteItem(
                    id = obj.getLong("id"),
                    content = obj.getString("content"),
                    summary = obj.optString("summary", ""),
                    tags = tags,
                    createdAt = obj.optString("createdAt", ""),
                    engineUsed = obj.optString("engineUsed", "온디바이스 AI")
                )
            }
        } catch (_: Exception) {
            defaultNotes()
        }
    }

    fun saveTodos(todos: List<TodoItem>) {
        val arr = JSONArray()
        for (t in todos) {
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("dueHint", t.dueHint)
                put("priority", t.priority)
                put("isDone", t.isDone)
            })
        }
        prefs.edit().putString("todos_json", arr.toString()).apply()
    }

    fun loadTodos(): List<TodoItem> {
        val raw = prefs.getString("todos_json", null) ?: return defaultTodos()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TodoItem(
                    id = obj.getLong("id"),
                    title = obj.getString("title"),
                    dueHint = obj.optString("dueHint", "기한 없음"),
                    priority = obj.optString("priority", "보통"),
                    isDone = obj.optBoolean("isDone", false)
                )
            }
        } catch (_: Exception) {
            defaultTodos()
        }
    }

    fun saveBooks(books: List<BookItem>) {
        val arr = JSONArray()
        for (b in books) {
            arr.put(JSONObject().apply {
                put("id", b.id)
                put("title", b.title)
                put("category", b.category)
                put("author", b.author)
                put("pages", JSONArray(b.pages))
                put("currentPage", b.currentPage)
                put("isBookmarked", b.isBookmarked)
            })
        }
        prefs.edit().putString("books_json", arr.toString()).apply()
    }

    fun loadBooks(): List<BookItem> {
        val raw = prefs.getString("books_json", null) ?: return defaultBooks()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val pagesArr = obj.getJSONArray("pages")
                val pages = (0 until pagesArr.length()).map { pagesArr.getString(it) }
                BookItem(
                    id = obj.getLong("id"),
                    title = obj.getString("title"),
                    category = obj.optString("category", "내 파일"),
                    author = obj.optString("author", "사용자 문서"),
                    pages = pages,
                    currentPage = obj.optInt("currentPage", 0),
                    isBookmarked = obj.optBoolean("isBookmarked", false)
                )
            }
        } catch (_: Exception) {
            defaultBooks()
        }
    }

    /**
     * Imports user-provided book file (.txt, .md, .epub, .html) into the Google Books-style Reader.
     */
    fun importBookFromUri(uri: Uri): BookItem? {
        val fileName = queryDisplayName(uri) ?: "나만의 독서 노트.txt"
        val cleanTitle = fileName.substringBeforeLast(".")
        val lower = fileName.lowercase()

        if (lower.endsWith(".pdf")) {
            val pdfPages = extractPagesFromPdf(uri)
            if (pdfPages.isEmpty()) return null
            return BookItem(
                id = System.currentTimeMillis(),
                title = cleanTitle,
                category = "PDF 도서",
                author = "PDF 문서 ($fileName)",
                pages = pdfPages,
                currentPage = 0,
                isBookmarked = false
            )
        }

        val fullText = if (lower.endsWith(".epub")) {
            extractTextFromEpub(uri)
        } else {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        }

        if (fullText.isBlank()) return null
        val pages = paginateText(fullText, maxCharsPerPage = 520)
        return BookItem(
            id = System.currentTimeMillis(),
            title = cleanTitle,
            category = "내 파일",
            author = "직접 추가한 도서 ($fileName)",
            pages = pages,
            currentPage = 0,
            isBookmarked = false
        )
    }

    private fun extractPagesFromPdf(uri: Uri): List<String> {
        val extractedPages = mutableListOf<String>()
        try {
            PDFBoxResourceLoader.init(context)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    val stripper = PDFTextStripper()
                    val total = document.numberOfPages
                    for (pageIndex in 1..total) {
                        stripper.startPage = pageIndex
                        stripper.endPage = pageIndex
                        val pageText = stripper.getText(document).trim()
                        if (pageText.isNotBlank()) {
                            // Split long PDF pages into comfortable reading screens (~600 chars)
                            val subPages = paginateText(pageText, maxCharsPerPage = 600)
                            extractedPages.addAll(subPages)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return extractedPages
    }

    private fun extractTextFromEpub(uri: Uri): String {
        val sb = StringBuilder()
        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name.lowercase()
                        if (!entry.isDirectory && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))) {
                            val html = zis.bufferedReader(Charsets.UTF_8).readText()
                            val stripped = html
                                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
                                .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
                                .replace(Regex("<(br|p|div|h[1-6]|li)[^>]*>", RegexOption.IGNORE_CASE), "\n")
                                .replace(Regex("<[^>]+>"), "")
                                .replace("&nbsp;", " ")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .replace("&amp;", "&")
                                .trim()
                            if (stripped.isNotBlank()) {
                                sb.append(stripped).append("\n\n")
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (_: Exception) {
        }
        return sb.toString()
    }

    private fun paginateText(rawText: String, maxCharsPerPage: Int = 520): List<String> {
        val paragraphs = rawText.replace("\r\n", "\n").split(Regex("\n{2,}"))
        val pages = mutableListOf<String>()
        val current = StringBuilder()

        for (para in paragraphs) {
            val trimmed = para.trim()
            if (trimmed.isEmpty()) continue
            if (current.length + trimmed.length + 2 > maxCharsPerPage && current.isNotEmpty()) {
                pages.add(current.toString().trim())
                current.clear()
            }
            if (trimmed.length > maxCharsPerPage) {
                val lines = trimmed.split("\n")
                for (line in lines) {
                    if (current.length + line.length + 1 > maxCharsPerPage && current.isNotEmpty()) {
                        pages.add(current.toString().trim())
                        current.clear()
                    }
                    current.append(line).append("\n")
                }
            } else {
                current.append(trimmed).append("\n\n")
            }
        }
        if (current.isNotEmpty()) {
            pages.add(current.toString().trim())
        }
        return if (pages.isEmpty()) listOf(rawText.take(maxCharsPerPage)) else pages
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    private fun getStringSet(key: String, default: Set<String>): Set<String> {
        return prefs.getStringSet(key, default) ?: default
    }

    private fun defaultNotes(): List<NoteItem> {
        val now = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
        return listOf(
            NoteItem(
                id = 1L,
                content = "내일 오후 3시 디자인 시안 검토 미팅 준비하기.\n퇴근길에 우유랑 커피 원두 사기 #업무 #쇼핑",
                summary = "총 2건의 실행 할 일과 핵심 메모가 감지되었습니다.",
                tags = listOf("업무", "쇼핑"),
                createdAt = now,
                engineUsed = "EmbeddingGemma 2 + 온디바이스 AI"
            ),
            NoteItem(
                id = 2L,
                content = "마음이 조급할 때는 시편 23편과 아우렐리우스의 명상록 1장을 천천히 필사하며 호흡을 가다듬자. #묵상 #동기부여",
                summary = "조급할 때 시편 23편과 명상록 필사로 평안 유지",
                tags = listOf("묵상", "동기부여"),
                createdAt = now,
                engineUsed = "EmbeddingGemma 2 + 온디바이스 AI"
            )
        )
    }

    private fun defaultTodos(): List<TodoItem> {
        return listOf(
            TodoItem(101L, "디자인 시안 검토 미팅 준비하기", "내일 오후 3시", "높음", false),
            TodoItem(102L, "퇴근길에 우유랑 커피 원두 사기", "오늘", "보통", false)
        )
    }

    private fun defaultBooks(): List<BookItem> {
        return listOf(
            BookItem(
                id = 1001L,
                title = "시편 & 잠언 성경 묵상집",
                category = "성경",
                author = "구약 성경 (시편 23편 · 121편 · 잠언)",
                pages = listOf(
                    "【시편 23편】\n\n1. 여호와는 나의 목자시니 내게 부족함이 없으리로다\n2. 그가 나를 푸른 풀밭에 누이시며 쉴 만한 물 가로 인도하시는도다\n3. 내 영혼을 소생시키시고 자기 이름을 위하여 의의 길로 인도하시는도다\n4. 내가 사망의 음침한 골짜기로 다닐지라도 해를 두려워하지 않을 것은 주께서 나와 함께 하심이라 주의 지팡이와 막대기가 나를 안위하시나이다\n5. 주께서 내 원수의 목전에서 내게 상을 차려 주시고 기름을 내 머리에 부으셨으니 내 잔이 넘치나이다\n6. 내 평생에 선하심과 인자하심이 반드시 나를 따르리니 내가 여호와의 집에 영원히 살리로다",
                    "【시편 121편】\n\n1. 내가 산을 향하여 눈을 들리라 나의 도움이 어디서 올까\n2. 나의 도움은 천지를 지으신 여호와에게서로다\n3. 여호와께서 너를 실족하지 아니하게 하시며 너를 지키시는 이가 졸지 아니하시리로다\n4. 이스라엘을 지키시는 이는 졸지도 아니하시고 주무시지도 아니하시리로다\n5. 여호와는 너를 지키시는 이시라 여호와께서 네 오른쪽에서 네 그늘이 되시나니\n6. 낮의 해가 너를 상하게 하지 아니하며 밤의 달도 너를 해치지 아니하리로다\n7. 여호와께서 너를 지켜 모든 환난을 면하게 하시며 또 네 영혼을 지키시리로다\n8. 여호와께서 너의 출입을 지금부터 영원까지 지키시리로다",
                    "【잠언 3장 & 4장 핵심 구절】\n\n• 너는 마음을 다하여 여호와를 신뢰하고 네 명철을 의지하지 말라 (잠언 3:5)\n• 너는 범사에 그를 인정하라 그리하면 네 길을 지도하시리라 (잠언 3:6)\n• 모든 지킬 만한 것 중에 더욱 네 마음을 지키라 생명의 근원이 이에서 남이니라 (잠언 4:23)\n• 사람이 마음으로 자기의 길을 계획할지라도 그의 걸음을 인도하시는 이는 여호와시니라 (잠언 16:9)"
                ),
                currentPage = 0,
                isBookmarked = true
            ),
            BookItem(
                id = 1002L,
                title = "시의 정원 — 한국 대표 서정시선",
                category = "시",
                author = "윤동주 · 김소월 · 한용운",
                pages = listOf(
                    "【서시 (序詩) — 윤동주】\n\n죽는 날까지 하늘을 우러러\n한 점 부끄럼이 없기를,\n잎새에 이는 바람에도\n나는 괴로워했다.\n\n별을 노래하는 마음으로\n모든 죽어가는 것을 사랑해야지\n그리고 나한테 주어진 길을\n걸어가야겠다.\n\n오늘 밤에도 별이 바람에 스치운다.",
                    "【새로운 길 — 윤동주】\n\n내를 건너서 숲으로\n고개를 넘어서 마을로\n\n어제도 가고 오늘도 갈\n나의 길 새로운 길\n\n민들레가 피고 까치가 날고\n아가씨가 지나고 바람이 일고\n\n나의 길은 언제나 새로운 길\n오늘도...... 내일도......\n\n내를 건너서 숲으로\n고개를 넘어서 마을로",
                    "【진달래꽃 — 김소월】\n\n나 보기가 역겨워\n가실 때에는\n말없이 고이 보내 드리우리다\n\n영변에 약산\n진달래꽃\n아름 따다 가실 길에 뿌리오리다\n\n가시는 걸음걸음\n놓인 그 꽃을\n사뿐히 즈려밟고 가시옵소서\n\n나 보기가 역겨워\n가실 때에는\n죽어도 아니 눈물 흘리오리다"
                ),
                currentPage = 0,
                isBookmarked = false
            ),
            BookItem(
                id = 1003L,
                title = "내면을 깨우는 문장들 — 명상록 & 동기부여",
                category = "동기부여",
                author = "마르쿠스 아우렐리우스 · 제임스 알렌",
                pages = listOf(
                    "【명상록 (Meditations) — 마르쿠스 아우렐리우스】\n\n1. 사람들은 고요한 휴식처를 찾아 시골로, 바닷가로, 산으로 떠난다. 그러나 철학자에게는 언제든 자기 자신의 내면으로 물러나 평온을 얻을 수 있는 힘이 있다.\n\n2. 네가 통제할 수 없는 외부의 일 때문에 흔들리지 마라. 고통은 사건 그 자체에서 오는 것이 아니라, 그 사건에 대한 네 해석에서 비롯된다.\n\n3. 오늘 하루를 인생의 마지막 날인 것처럼 집중하되, 결코 조급하거나 흔들리지 말고 고요하게 네 할 일을 수행하라.",
                    "【생각하는 대로 이루어진다 — 제임스 알렌】\n\n1. 사람의 마음은 정원과 같다. 정원에 지혜로운 씨앗을 심고 가꾸든, 잡초가 자라도록 방치하든 정원은 반드시 그 열매를 맺는다.\n\n2. 고결하고 분명한 목표를 마음에 품은 사람은 외부의 소음에 쉽게 휩쓸리지 않는다. 매일의 작은 집중이 쌓여 흔들리지 않는 성품을 만든다.\n\n3. 평온함은 지혜의 보석이다. 자기 자신을 다스리는 법을 배운 사람만이 진정한 고요에 이른다."
                ),
                currentPage = 0,
                isBookmarked = false
            )
        )
    }
}
