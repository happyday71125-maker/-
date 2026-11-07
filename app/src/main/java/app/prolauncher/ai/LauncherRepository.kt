package app.prolauncher.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

class LauncherRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("pro_launcher_ai_prefs", Context.MODE_PRIVATE)

    fun loadInstalledApps(): List<AppEntry> {
        val pm: PackageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val favorites = getStringSet("favorites", setOf("전화", "메시지", "카메라", "브라우저"))
        val interrupts = getStringSet("interrupts", setOf("YouTube", "유튜브", "Instagram", "인스타그램"))

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

    private fun getStringSet(key: String, default: Set<String>): Set<String> {
        return prefs.getStringSet(key, default) ?: default
    }

    private fun defaultNotes(): List<NoteItem> {
        val now = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
        return listOf(
            NoteItem(
                id = 1L,
                content = "내일 오후 3시 디자인 시안 검토 미팅 준비하기. 퇴근길에 우유랑 원두 사기 #업무 #쇼핑",
                summary = "총 2건의 실행 할 일과 핵심 메모가 감지되었습니다.",
                tags = listOf("업무", "쇼핑"),
                createdAt = now,
                engineUsed = "내장 한국어 온디바이스 AI 엔진"
            )
        )
    }

    private fun defaultTodos(): List<TodoItem> {
        return listOf(
            TodoItem(101L, "디자인 시안 검토 미팅 준비하기", "내일 오후 3시", "높음", false),
            TodoItem(102L, "퇴근길에 우유랑 원두 사기", "오늘", "보통", false)
        )
    }
}
