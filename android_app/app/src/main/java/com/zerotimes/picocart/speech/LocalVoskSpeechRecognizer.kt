package com.zerotimes.picocart.speech

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File

internal class LocalVoskSpeechRecognizer private constructor(
    private val model: Model,
) : Closeable {
    fun transcribeWake(pcmBytes: ByteArray): String = transcribe(pcmBytes, WAKE_GRAMMAR)

    fun transcribeCommand(pcmBytes: ByteArray): String = transcribe(pcmBytes, grammar = null)

    override fun close() {
        model.close()
    }

    private fun transcribe(pcmBytes: ByteArray, grammar: String?): String {
        val recognizer = if (grammar == null) {
            Recognizer(model, SAMPLE_RATE.toFloat())
        } else {
            Recognizer(model, SAMPLE_RATE.toFloat(), grammar)
        }
        recognizer.use {
            it.acceptWaveForm(pcmBytes, pcmBytes.size)
            return parseText(it.finalResult)
        }
    }

    private fun parseText(json: String): String = runCatching {
        JSONObject(json).optString("text").trim()
    }.getOrDefault("")

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MODEL_ASSET_NAME = "model-cn"
        private const val MODEL_CACHE_NAME = "model-cn"
        private const val WAKE_GRAMMAR = "[\"曼波\", \"漫波\", \"mambo\", \"[unk]\"]"

        fun create(context: Context): Result<LocalVoskSpeechRecognizer> {
            val modelDir = runCatching { resolveModelDir(context.applicationContext) }
                .getOrElse { return Result.failure(it) }
                ?: return Result.failure(
                    IllegalStateException(
                        "未安装本地语音模型，请把 vosk-model-small-cn-0.22 解压为 assets/model-cn 或推送到 App 外部文件目录 vosk/model-cn",
                    ),
                )
            return runCatching { LocalVoskSpeechRecognizer(Model(modelDir.absolutePath)) }
        }

        private fun resolveModelDir(context: Context): File? {
            val externalModelDir = context.getExternalFilesDir(null)
                ?.resolve("vosk")
                ?.resolve(MODEL_CACHE_NAME)
            if (externalModelDir?.isValidVoskModel() == true) {
                return externalModelDir
            }

            val cachedModelDir = File(context.filesDir, "vosk/$MODEL_CACHE_NAME")
            if (cachedModelDir.isValidVoskModel()) {
                return cachedModelDir
            }

            if (!context.assets.assetDirExists(MODEL_ASSET_NAME)) {
                return null
            }
            cachedModelDir.deleteRecursively()
            cachedModelDir.mkdirs()
            context.assets.copyAssetDir(MODEL_ASSET_NAME, cachedModelDir)
            return cachedModelDir.takeIf { it.isValidVoskModel() }
        }

        private fun File.isValidVoskModel(): Boolean =
            isDirectory &&
                resolve("am/final.mdl").isFile &&
                resolve("conf/model.conf").isFile &&
                resolve("graph").isDirectory

        private fun AssetManager.assetDirExists(path: String): Boolean =
            runCatching { list(path)?.isNotEmpty() == true }.getOrDefault(false)

        private fun AssetManager.copyAssetDir(assetPath: String, targetDir: File) {
            val children = list(assetPath).orEmpty()
            if (children.isEmpty()) {
                targetDir.parentFile?.mkdirs()
                open(assetPath).use { input ->
                    targetDir.outputStream().use { output -> input.copyTo(output) }
                }
                return
            }
            targetDir.mkdirs()
            for (child in children) {
                val childAssetPath = "$assetPath/$child"
                val childTarget = File(targetDir, child)
                copyAssetDir(childAssetPath, childTarget)
            }
        }
    }
}
