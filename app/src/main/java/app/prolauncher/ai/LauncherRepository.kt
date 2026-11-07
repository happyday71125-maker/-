package app.prolauncher.ai

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
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
    val category: String,
    val author: String,
    val pages: List<String>,
    val currentPage: Int = 0
)

data class LauncherConfig(
    val themeName: String = "AMOLED",
    val textAlign: String = "LEFT",
    val interruptSeconds: Int = 5,
    val readerTheme: String = "SEPIA",
    val readerFontSizeSp: Int = 18,
    val readerUseSerif: Boolean = true,
    val ragIncludeBooks: Boolean = true
)

class LauncherRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("pro_launcher_ai_v2", Context.MODE_PRIVATE)

    fun isDefaultHomeApp(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return rm.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == context.packageName
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
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val favs = prefs.getStringSet("favorites", setOf("전화", "메시지", "카메라", "카카오톡", "브라우저")) ?: emptySet()
        val ints = prefs.getStringSet("interrupts", setOf("YouTube", "유튜브", "Instagram", "인스타그램")) ?: emptySet()
        return pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName) null else {
                val label = ri.loadLabel(pm).toString()
                AppEntry(label, pkg, favs.contains(pkg) || favs.contains(label), ints.contains(pkg) || ints.contains(label))
            }
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    fun launchApp(packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }

    fun toggleFavorite(packageName: String) {
        val s = (prefs.getStringSet("favorites", emptySet()) ?: emptySet()).toMutableSet()
        if (!s.add(packageName)) s.remove(packageName)
        prefs.edit().putStringSet("favorites", s).apply()
    }

    fun toggleInterrupt(packageName: String) {
        val s = (prefs.getStringSet("interrupts", emptySet()) ?: emptySet()).toMutableSet()
        if (!s.add(packageName)) s.remove(packageName)
        prefs.edit().putStringSet("interrupts", s).apply()
    }

    fun loadConfig(): LauncherConfig = LauncherConfig(
        themeName = prefs.getString("theme", "AMOLED") ?: "AMOLED",
        textAlign = prefs.getString("align", "LEFT") ?: "LEFT",
        interruptSeconds = prefs.getInt("int_sec", 5),
        readerTheme = prefs.getString("r_theme", "SEPIA") ?: "SEPIA",
        readerFontSizeSp = prefs.getInt("r_font", 18),
        readerUseSerif = prefs.getBoolean("r_serif", true),
        ragIncludeBooks = prefs.getBoolean("rag_books", true)
    )

    fun saveConfig(c: LauncherConfig) {
        prefs.edit()
            .putString("theme", c.themeName)
            .putString("align", c.textAlign)
            .putInt("int_sec", c.interruptSeconds)
            .putString("r_theme", c.readerTheme)
            .putInt("r_font", c.readerFontSizeSp)
            .putBoolean("r_serif", c.readerUseSerif)
            .putBoolean("rag_books", c.ragIncludeBooks)
            .apply()
    }

    fun saveNotes(notes: List<NoteItem>) {
        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(JSONObject().apply {
                put("id", n.id); put("c", n.content); put("s", n.summary)
                put("t", JSONArray(n.tags)); put("d", n.createdAt); put("e", n.engineUsed)
            })
        }
        prefs.edit().putString("notes_json", arr.toString()).apply()
    }

    fun loadNotes(): List<NoteItem> {
        val raw = prefs.getString("notes_json", null) ?: return defaultNotes()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ta = o.optJSONArray("t") ?: JSONArray()
                NoteItem(o.getLong("id"), o.getString("c"), o.optString("s"), (0 until ta.length()).map { ta.getString(it) }, o.optString("d"), o.optString("e"))
            }
        }.getOrElse { defaultNotes() }
    }

    fun saveTodos(todos: List<TodoItem>) {
        val arr = JSONArray()
        todos.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("t", t.title); put("d", t.dueHint); put("p", t.priority); put("ok", t.isDone)
            })
        }
        prefs.edit().putString("todos_json", arr.toString()).apply()
    }

    fun loadTodos(): List<TodoItem> {
        val raw = prefs.getString("todos_json", null) ?: return listOf(
            TodoItem(101L, "디자인 시안 검토 미팅 준비하기", "내일 오후 3시", "높음", false),
            TodoItem(102L, "퇴근길에 우유랑 원두 사기", "오늘", "보통", false)
        )
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TodoItem(o.getLong("id"), o.getString("t"), o.optString("d"), o.optString("p"), o.optBoolean("ok"))
            }
        }.getOrElse { emptyList() }
    }

    fun saveBooks(books: List<BookItem>) {
        val arr = JSONArray()
        books.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.id); put("t", b.title); put("c", b.category); put("a", b.author)
                put("p", JSONArray(b.pages)); put("cp", b.currentPage)
            })
        }
        prefs.edit().putString("books_json", arr.toString()).apply()
    }

    fun loadBooks(): List<BookItem> {
        val raw = prefs.getString("books_json", null) ?: return defaultBooks()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val pa = o.getJSONArray("p")
                BookItem(o.getLong("id"), o.getString("t"), o.optString("c", "도서"), o.optString("a", ""), (0 until pa.length()).map { pa.getString(it) }, o.optInt("cp", 0))
            }
        }.getOrElse { defaultBooks() }
    }

    fun importBookFromUri(uri: Uri): BookItem? {
        var name = "독서노트.pdf"
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i)
        }
        val title = name.substringBeforeLast(".")
        val low = name.lowercase()
        val pages = when {
            low.endsWith(".pdf") -> extractPdf(uri)
            low.endsWith(".epub") -> paginate(extractEpub(uri))
            else -> paginate(context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: "")
        }
        if (pages.isEmpty()) return null
        return BookItem(System.currentTimeMillis(), title, if (low.endsWith(".pdf")) "PDF 도서" else "내 파일", name, pages, 0)
    }

    private fun extractPdf(uri: Uri): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            PDFBoxResourceLoader.init(context)
            context.contentResolver.openInputStream(uri)?.use { inp ->
                PDDocument.load(inp).use { doc ->
                    val st = PDFTextStripper()
                    for (p in 1..doc.numberOfPages) {
                        st.startPage = p; st.endPage = p
                        val txt = st.getText(doc).trim()
                        if (txt.isNotBlank()) out.addAll(paginate(txt))
                    }
                }
            }
        }
        return out
    }

    private fun extractEpub(uri: Uri): String = buildString {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val n = e.name.lowercase()
                        if (!e.isDirectory && (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))) {
                            append(zis.bufferedReader().readText().replace(Regex("<[^>]+>"), " ").trim()).append("\n\n")
                        }
                        e = zis.nextEntry
                    }
                }
            }
        }
    }

    private fun paginate(txt: String): List<String> = txt.replace("\r\n", "\n").chunked(520).map { it.trim() }.filter { it.isNotEmpty() }

    private fun defaultNotes(): List<NoteItem> {
        val now = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
        return listOf(
            NoteItem(1L, "내일 오후 3시 디자인 시안 검토 미팅 준비하기.\n퇴근길에 우유랑 원두 사기 #업무 #쇼핑", "할 일 2건 및 핵심 메모 감지", listOf("업무", "쇼핑"), now, "EmbeddingGemma 2 RAG"),
            NoteItem(2L, "마음이 조급할 때는 시편 23편과 아우렐리우스의 명상록을 천천히 읽으며 호흡을 가다듬자. #묵상", "시편 23편과 명상록으로 내면 평안 유지", listOf("묵상"), now, "EmbeddingGemma 2 RAG")
        )
    }

    private fun defaultBooks(): List<BookItem> = listOf(
        BookItem(1001L, "시편 & 잠언 성경 묵상집", "성경", "시편 23편 · 121편 · 잠언", listOf(
            "【시편 23편】\n\n1. 여호와는 나의 목자시니 내게 부족함이 없으리로다\n2. 그가 나를 푸른 풀밭에 누이시며 쉴 만한 물 가로 인도하시는도다\n3. 내 영혼을 소생시키시고 자기 이름을 위하여 의의 길로 인도하시는도다\n4. 내가 사망의 음침한 골짜기로 다닐지라도 해를 두려워하지 않을 것은 주께서 나와 함께 하심이라 주의 지팡이와 막대기가 나를 안위하시나이다",
            "【시편 121편 & 잠언】\n\n• 내가 산을 향하여 눈을 들리라 나의 도움이 어디서 올까 나의 도움은 천지를 지으신 여호와에게서로다 (시편 121:1-2)\n• 모든 지킬 만한 것 중에 더욱 네 마음을 지키라 생명의 근원이 이에서 남이니라 (잠언 4:23)"
        )),
        BookItem(1002L, "시의 정원 — 한국 대표 서정시선", "시", "윤동주 · 김소월", listOf(
            "【서시 (序詩) — 윤동주】\n\n죽는 날까지 하늘을 우러러\n한 점 부끄럼이 없기를,\n잎새에 이는 바람에도\n나는 괴로워했다.\n\n별을 노래하는 마음으로\n모든 죽어가는 것을 사랑해야지\n그리고 나한테 주어진 길을\n걸어가야겠다.\n\n오늘 밤에도 별이 바람에 스치운다.",
            "【진달래꽃 — 김소월】\n\n나 보기가 역겨워 가실 때에는\n말없이 고이 보내 드리우리다\n영변에 약산 진달래꽃\n아름 따다 가실 길에 뿌리오리다"
        )),
        BookItem(1003L, "명상록 & 동기부여 문장집", "동기부여", "마르쿠스 아우렐리우스", listOf(
            "【명상록 — 마르쿠스 아우렐리우스】\n\n1. 지혜로운 이에게는 언제든 자기 내면으로 물러나 평온을 얻을 수 있는 고요한 성소가 있다.\n2. 외부의 일 때문에 흔들리지 마라. 고통은 사건 자체가 아니라 그 사건에 대한 네 해석에서 비롯된다."
        ))
    )
}

