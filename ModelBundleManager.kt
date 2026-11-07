package app.prolauncher.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
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
