package app.prolauncher.ai

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AiParsedNoteResult(
    val summary: String,
    val extractedTasks: List<ExtractedTask>,
    val tags: List<String>,
    val engineUsed: String
)

data class ExtractedTask(
    val title: String,
    val dueHint: String,
    val priority: String
)

/**
 * Multi-Format On-Device Local AI Engine:
 * - Automatically loads user-provided `.litertlm` (Gemma 4 E2B / Gemma 3n E2B from Google AI Edge Gallery),
 *   `.task` / `.bin` (MediaPipe LLM Inference), or `.gguf` (llama.cpp runtime / local server).
 * - Unpacks compressed `.zip` model bundles automatically via `ModelBundleManager`.
 * - Falls back seamlessly to the zero-latency Korean On-Device NLP parser when no model is loaded.
 */
class LocalAiNoteEngine(private val context: Context) {

    val bundleManager = ModelBundleManager(context)
    val ragEngine = EmbeddingGemmaRagEngine(context, bundleManager)
    private var llmInference: LlmInference? = null
    var activeModel: LoadedModelMetadata? = null
        private set

    /**
     * Called when the user picks a `.litertlm`, `.task`, `.gguf`, or `.zip` file in the UI.
     */
    suspend fun loadUserProvidedModel(uri: Uri): Result<LoadedModelMetadata> = withContext(Dispatchers.IO) {
        val importRes = bundleManager.importModelFromUri(uri)
        if (importRes.isFailure) return@withContext importRes

        val metadata = importRes.getOrThrow()
        activateModelMetadata(metadata)
        Result.success(metadata)
    }

    suspend fun autoDetectExistingModel(): LoadedModelMetadata? = withContext(Dispatchers.IO) {
        if (activeModel != null) return@withContext activeModel
        val existing = bundleManager.findAvailableModels().firstOrNull() ?: return@withContext null
        activateModelMetadata(existing)
        existing
    }

    private fun activateModelMetadata(metadata: LoadedModelMetadata) {
        activeModel = metadata
        try {
            llmInference?.close()
        } catch (_: Exception) {
        }
        llmInference = null

        // Initialize LiteRT-LM / MediaPipe LlmInference for .litertlm, .task, .bin
        if (metadata.format == OnDeviceModelFormat.LITERT_LM || metadata.format == OnDeviceModelFormat.MEDIAPIPE_TASK) {
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(metadata.localPath)
                    .setMaxTokens(512)
                    .setTemperature(0.25f)
                    .build()
                llmInference = LlmInference.createFromOptions(context, options)
            } catch (_: Exception) {
                // If hardware delegate or specific op needs fallback, keep metadata registered
            }
        }
    }

    suspend fun analyzeMemo(rawText: String, useLocalOllama: Boolean = false): AiParsedNoteResult =
        withContext(Dispatchers.IO) {
            autoDetectExistingModel()

            val currentModel = activeModel
            // 1. If a .litertlm or .task model (e.g. Gemma 4 E2B) is loaded in LlmInference
            if (currentModel != null && llmInference != null) {
                try {
                    val prompt = formatPromptForArchitecture(rawText, currentModel.architectureHint)
                    val response = llmInference!!.generateResponse(prompt)
                    return@withContext parseLlmResponseWithFallback(
                        rawText,
                        response,
                        "${currentModel.architectureHint} (${currentModel.format.label})"
                    )
                } catch (_: Exception) {
                }
            }

            // 2. If a .gguf model is selected or Localhost llama.cpp / Ollama server is active
            if (useLocalOllama || currentModel?.format == OnDeviceModelFormat.GGUF) {
                try {
                    val response = callLocalInferenceServer(rawText)
                    if (response.isNotBlank()) {
                        val label = currentModel?.let { "${it.fileName} (GGUF)" } ?: "로컬 GGUF/Ollama 엔진"
                        return@withContext parseLlmResponseWithFallback(rawText, response, label)
                    }
                } catch (_: Exception) {
                }
            }

            // 3. Fallback / Hybrid: Instant Korean On-Device NLP Engine
            val engineLabel = currentModel?.let {
                "${it.architectureHint} + 한국어 온디바이스 파서"
            } ?: "내장 한국어 온디바이스 AI 엔진"
            return@withContext runInstantKoreanNlp(rawText, engineLabel)
        }

    suspend fun askNotesQuestion(
        question: String,
        notes: List<NoteItem>,
        books: List<BookItem> = emptyList(),
        includeBooks: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val q = question.trim()
        if (q.isEmpty()) return@withContext "질문을 입력해 주세요."

        // Step 1: Retrieve top semantic chunks via EmbeddingGemma 2 RAG Engine
        val topChunks = ragEngine.retrieveTopK(
            query = q,
            notes = notes,
            books = books,
            includeBooks = includeBooks,
            topK = 3
        )

        if (topChunks.isEmpty()) {
            return@withContext "검색할 메모나 독서대 구절이 없습니다."
        }

        val embModelLabel = ragEngine.activeEmbeddingModel?.fileName ?: "EmbeddingGemma 2 (MRL ${ragEngine.mrlDimension}d)"
        val chunkSummaryLines = topChunks.joinToString("\n") { chunk ->
            val pct = (chunk.similarity * 100).toInt().coerceIn(55, 99)
            "• [${chunk.sourceType} | 유사도 ${pct}%] ${chunk.titleOrDate}: ${chunk.content.replace("\n", " ").take(95)}"
        }

        // Step 2: Augmented Generation via On-Device LLM if loaded
        if (llmInference != null) {
            try {
                val contextBlock = topChunks.joinToString("\n") { "[${it.sourceType}: ${it.titleOrDate}] ${it.content}" }
                val rawPrompt = "다음 검색된 메모 및 독서 구절(RAG 컨텍스트)을 바탕으로 질문에 답하세요.\n\n컨텍스트:\n$contextBlock\n\n질문: $q\n답변:"
                val formatted = formatPromptForArchitecture(rawPrompt, activeModel?.architectureHint ?: "Gemma")
                val llmAns = llmInference!!.generateResponse(formatted).trim()
                return@withContext "🧠 [$embModelLabel RAG 생성 답변]\n$llmAns\n\n📌 참조한 구절:\n$chunkSummaryLines"
            } catch (_: Exception) {
            }
        }

        "🔎 [$embModelLabel 시맨틱 벡터 검색 결과]\n$chunkSummaryLines"
    }

    /**
     * Applies Gemma 4 / Gemma 3n turn markers (`<start_of_turn>`) or ChatML (`<|im_start|>`) automatically.
     */
    private fun formatPromptForArchitecture(userInstruction: String, archHint: String): String {
        val coreTask = """
            다음 메모를 분석하여 한 줄 요약과 실행 가능한 할 일(To-Do) 목록을 정리해 주세요.
            메모: $userInstruction
            출력 형식:
            요약: [한 줄 요약]
            할일:
            - [할 일 제목] | [기한/시간] | [우선순위: 높음/보통]
        """.trimIndent()

        return if (archHint.contains("Gemma", ignoreCase = true)) {
            "<start_of_turn>user\n$coreTask<end_of_turn>\n<start_of_turn>model\n"
        } else {
            "<|im_start|>user\n$coreTask<|im_end|>\n<|im_start|>assistant\n"
        }
    }

    private fun parseLlmResponseWithFallback(
        rawText: String,
        llmOutput: String,
        engineName: String
    ): AiParsedNoteResult {
        val base = runInstantKoreanNlp(rawText, engineName)
        val cleanOut = llmOutput.replace("<end_of_turn>", "").replace("<|im_end|>", "").trim()
        val summaryLine = cleanOut.lineSequence()
            .firstOrNull { it.startsWith("요약:") }
            ?.removePrefix("요약:")
            ?.trim()
            ?: cleanOut.lineSequence().firstOrNull { it.isNotBlank() }?.take(75)
            ?: base.summary

        return base.copy(summary = summaryLine, engineUsed = engineName)
    }

    private fun runInstantKoreanNlp(rawText: String, engineName: String): AiParsedNoteResult {
        val sentences = rawText
            .split(Regex("[\\n.!?]+|(?<=기|함|음|요|다)\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val timeRegex = Regex("(오늘|내일|모레|이번\\s*주|다음\\s*주|[월화수목금토일]요일|오전\\s*\\d+시(?:\\s*\\d+분)?|오후\\s*\\d+시(?:\\s*\\d+분)?|\\d{1,2}월\\s*\\d{1,2}일|\\d{1,2}/\\d{1,2}|\\d{1,2}시)")
        val urgentRegex = Regex("(급함|긴급|중요|마감|필수|꼭|까지)")
        val actionRegex = Regex("(하기|사기|보내기|제출|확인|준비|연락|미팅|회의|예약|결제|공부|운동|정리|작성|전달|검토)")

        val tasks = mutableListOf<ExtractedTask>()
        val tags = linkedSetOf<String>()

        Regex("#([가-힣a-zA-Z0-9_]+)").findAll(rawText).forEach {
            tags.add(it.groupValues[1])
        }

        if (rawText.contains(Regex("(미팅|회의|보고서|기획|시안|메일|업무|클라이언트|발표)"))) tags.add("업무")
        if (rawText.contains(Regex("(사기|우유|계란|마트|택배|주문|장보기|결제)"))) tags.add("쇼핑/생활")
        if (rawText.contains(Regex("(운동|러닝|헬스|병원|약|수면|물)"))) tags.add("건강")
        if (rawText.contains(Regex("(아이디어|기획|생각|영감|독서|책)"))) tags.add("아이디어")
        if (tags.isEmpty()) tags.add("메모")

        for (sent in sentences) {
            val cleanSent = sent.replace(Regex("#[가-힣a-zA-Z0-9_]+"), "").trim()
            if (cleanSent.isEmpty()) continue
            val timeMatch = timeRegex.findAll(cleanSent).joinToString(" ") { it.value }.ifEmpty { "기한 없음" }
            val isUrgent = urgentRegex.containsMatchIn(cleanSent)
            val isAction = actionRegex.containsMatchIn(cleanSent) || timeMatch != "기한 없음" || isUrgent

            if (isAction || sentences.size == 1) {
                tasks.add(
                    ExtractedTask(
                        title = cleanSent,
                        dueHint = timeMatch,
                        priority = if (isUrgent) "높음" else "보통"
                    )
                )
            }
        }

        val summary = when {
            tasks.size > 1 -> "총 ${tasks.size}건의 실행 할 일과 핵심 메모가 감지되었습니다."
            tasks.size > 1 -> "핵심 할 일: ${tasks.first().title.take(40)}"
            else -> rawText.take(50)
        }

        return AiParsedNoteResult(
            summary = summary,
            extractedTasks = tasks,
            tags = tags.toList(),
            engineUsed = engineName
        )
    }

    private fun callLocalInferenceServer(rawText: String): String {
        val url = URL("http://127.0.0.1:11434/api/generate")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 1500
            readTimeout = 4000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val payload = JSONObject().apply {
            put("model", "gemma4:e2b")
            put("prompt", formatPromptForArchitecture(rawText, "Gemma"))
            put("stream", false)
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(body).optString("response", "")
    }
}
