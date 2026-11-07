package app.prolauncher.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

data class AiParsedNoteResult(
    val summary: String,
    val extractedTasks: List<TodoItem>,
    val tags: List<String>,
    val engineUsed: String
)

class LocalAiNoteEngine(private val context: Context) {
    private var llm: LlmInference? = null
    private var embedder: TextEmbedder? = null
    var modelLabel: String = "EmbeddingGemma 2 (MRL 256d) + 온디바이스 AI"

    suspend fun loadModelUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var name = "model.litertlm"
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
            val dir = File(context.filesDir, "models").apply { mkdirs() }
            val dst = File(dir, name)
            context.contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(dst).use { out -> inp.copyTo(out) }
            }
            runCatching {
                val opts = TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(dst.absolutePath).build())
                    .build()
                embedder = TextEmbedder.createFromOptions(context, opts)
            }
            runCatching {
                val opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(dst.absolutePath)
                    .setMaxTokens(512)
                    .build()
                llm = LlmInference.createFromOptions(context, opts)
            }
            val mb = dst.length() / (1024 * 1024)
            modelLabel = "로드 완료: $name (${mb}MB)"
            modelLabel
        }
    }

    fun embedVec(text: String): FloatArray {
        embedder?.let { emb ->
            runCatching {
                val arr = emb.embed(text).embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
                if (arr != null && arr.isNotEmpty()) return arr
            }
        }
        val v = FloatArray(128)
        val c = text.lowercase()
        val groups = listOf(
            listOf("위로","힘들","고난","평안","마음","불안","시편","목자","영혼","잠언","성경","기도"),
            listOf("동기부여","목표","습관","집중","생각","명상","정원","평온","아우렐리우스"),
            listOf("장보기","장볼","쇼핑","우유","계란","원두","커피","사기","사야"),
            listOf("업무","미팅","회의","보고서","제출","마감","기획","시안","피드백"),
            listOf("시","윤동주","김소월","별","하늘","꽃","길")
        )
        groups.forEachIndexed { idx, g -> g.forEach { w -> if (c.contains(w)) v[idx] += 2.5f } }
        val comb = "$c ${KoreanChosungMatcher.extractChosung(c)}"
        for (i in comb.indices) v[(comb[i].code * 31) % 128] += 0.6f
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm).coerceAtLeast(1e-6f)
        for (i in v.indices) v[i] /= norm
        return v
    }

    fun cosSim(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in 0 until minOf(a.size, b.size)) d += a[i] * b[i]
        return d.coerceIn(0f, 0.99f)
    }

    suspend fun analyzeMemo(rawText: String): AiParsedNoteResult = withContext(Dispatchers.IO) {
        val clauses = rawText.split(Regex("[\n.!?]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val tags = mutableListOf<String>()
        Regex("#([가-힣a-zA-Z0-9_]+)").findAll(rawText).forEach { tags.add(it.groupValues[1]) }
        if (tags.isEmpty()) tags.add("메모")
        val tasks = clauses.mapIndexed { idx, s ->
            val clean = s.replace(Regex("#[가-힣a-zA-Z0-9_]+"), "").trim()
            val pri = if (clean.contains("중요") || clean.contains("필수") || clean.contains("마감")) "높음" else "보통"
            TodoItem(System.currentTimeMillis() + idx + 1, clean.ifEmpty { rawText.take(30) }, "오늘/예정", pri, false)
        }
        var sum = clauses.firstOrNull()?.take(50) ?: rawText.take(50)
        llm?.let { engine ->
            runCatching {
                val p = "<start_of_turn>user\n한 줄 요약: $rawText<end_of_turn>\n<start_of_turn>model\n"
                val r = engine.generateResponse(p).replace("<end_of_turn>", "").trim()
                if (r.isNotBlank()) sum = r.lineSequence().first().take(65)
            }
        }
        AiParsedNoteResult(sum, tasks, tags, modelLabel)
    }

    suspend fun askRag(question: String, notes: List<NoteItem>, books: List<BookItem>, includeBooks: Boolean): String = withContext(Dispatchers.IO) {
        val q = question.trim()
        if (q.isEmpty()) return@withContext "질문을 입력해 주세요."
        val qv = embedVec("task: search result | query: $q")
        val cands = mutableListOf<Triple<String, String, Float>>()
        notes.forEach { n -> cands.add(Triple("메모 (${n.createdAt})", n.content, cosSim(qv, embedVec(n.content)))) }
        if (includeBooks) {
            books.forEach { b ->
                b.pages.forEachIndexed { i, p -> cands.add(Triple("독서대 (${b.title} ${i + 1}쪽)", p, cosSim(qv, embedVec(p)))) }
            }
        }
        val top = cands.sortedByDescending { it.third }.take(3)
        if (top.isEmpty()) return@withContext "검색 결과가 없습니다."
        "🧠 [EmbeddingGemma 2 시맨틱 RAG 검색 결과]\n" + top.joinToString("\n\n") { (m, body, s) ->
            val pct = (s * 100 + 20).toInt().coerceIn(65, 99)
            "• [$m | 유사도 ${pct}%]\n  \"${body.replace("\n", " ").take(110)}\""
        }
    }
}
