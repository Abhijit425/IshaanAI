package com.ishaan.ai.domain

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Optional on-device language model (Level 2). Runs a model file the parent downloads separately
 * (e.g. a Gemma .task file for MediaPipe) entirely on the phone — no API key, no network.
 *
 * The model never sees the database directly. The rule engine first retrieves the relevant records,
 * and only those are given to the model, with instructions to answer from them alone. The app shows
 * the retrieved records under every model answer so the parent can check it.
 */
class LocalLlm(private val context: Context) {
    private var engine: LlmInference? = null
    private var loadedPath: String? = null

    val modelDir = File(context.filesDir, "models").apply { mkdirs() }

    fun installedModel(name: String?): File? = name?.let { File(modelDir, it) }?.takeIf { it.exists() && it.length() > 0 }

    suspend fun importModel(uri: Uri, onProgress: (Long) -> Unit): String = withContext(Dispatchers.IO) {
        val target = File(modelDir, "local_model.task")
        val tmp = File(modelDir, "local_model.task.part")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20); var total = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); total += n; onProgress(total) }
            }
        }
        close()
        target.delete(); tmp.renameTo(target)
        target.name
    }

    fun remove() { close(); modelDir.listFiles()?.forEach { it.delete() } }

    suspend fun generate(modelFile: File, prompt: String): String = withContext(Dispatchers.Default) {
        val e = engine?.takeIf { loadedPath == modelFile.absolutePath } ?: run {
            close()
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(2048)
                .build()
            LlmInference.createFromOptions(context, opts).also { engine = it; loadedPath = modelFile.absolutePath }
        }
        e.generateResponse(prompt)
    }

    fun close() { runCatching { engine?.close() }; engine = null; loadedPath = null }

    companion object {
        fun buildPrompt(question: String, childName: String, retrieved: String): String {
            val records = retrieved.take(3500)
            return "<start_of_turn>user\n" +
                "You help a parent read their child's stored medical records. Rules:\n" +
                "1. Use ONLY the RECORDS below. If the answer is not in them, say: \"This is not in $childName's records.\"\n" +
                "2. Do not diagnose, do not say whether results are normal, and never suggest starting, stopping or changing medicines or doses.\n" +
                "3. Keep dates and numbers exactly as written. Be brief.\n" +
                "4. If the question is about an emergency, tell the parent to seek emergency care now.\n\n" +
                "RECORDS:\n$records\n\nQUESTION: $question<end_of_turn>\n<start_of_turn>model\n"
        }
    }
}
