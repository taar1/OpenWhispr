package com.edib.openwhispr

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class ModelArchitecture { NEMO_CTC, WHISPER, NEMO_TRANSDUCER, MOONSHINE }

data class Model(
    val name: String,
    val archive: String,
    val sizeMb: Int,
    val qualityResId: Int,
    val architecture: ModelArchitecture,
    // SHA-256 of the .tar.bz2 archive; the download is rejected if it
    // doesn't match, so a swapped or corrupted archive never gets extracted.
    val sha256: String,
    val hotwordModelingUnit: String? = null,
    val recommended: Boolean = false,
    /** Language codes the model transcribes. English-only models list just "en". */
    val languages: Set<String> = setOf("en"),
)

private val PARAKEET_V3_LANGUAGES = setOf(
    "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it", "lv", "lt",
    "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk",
)

private val WHISPER_LANGUAGES = TranscriptionLanguages.CODES.toSet()

// Hashes: GitHub's published asset digests where available (Parakeet
// models), otherwise pinned from the archive as served on 2026-09-27
// (multilingual Whisper models: 2026-10-17).
val MODEL_CATALOG = listOf(
    Model("Parakeet 0.6B", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        465, R.string.model_quality_best, ModelArchitecture.NEMO_TRANSDUCER,
        "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf",
        hotwordModelingUnit = "cjkchar", recommended = true, languages = PARAKEET_V3_LANGUAGES),
    Model("Parakeet 110M", "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        100, R.string.model_quality_best_value, ModelArchitecture.NEMO_CTC,
        "17f945007b52ccd8b7200ffc7c5652e9e8e961dfdf479cefcabd06cf5703630b"),
    Model("Whisper Base", "sherpa-onnx-whisper-base.en",
        199, R.string.model_quality_stars, ModelArchitecture.WHISPER,
        "475bc7052ce299c007f6d5d5407ba8601f819a2867f6eecee510ed17df581542"),
    Model("Moonshine Tiny", "sherpa-onnx-moonshine-tiny-en-int8",
        103, R.string.model_quality_fast, ModelArchitecture.MOONSHINE,
        "d5fe6ec4334fef36255b2a4010412cad4c007e33103fec62fb5d17cad88086f2"),
    Model("Whisper Base (multilingual)", "sherpa-onnx-whisper-base",
        207, R.string.model_quality_stars, ModelArchitecture.WHISPER,
        "911b2083efd7c0dca2ac3b358b75222660dc09fb716d64fbfc417ba6c99ff3de", languages = WHISPER_LANGUAGES),
    Model("Whisper Small (multilingual)", "sherpa-onnx-whisper-small",
        639, R.string.model_quality_best, ModelArchitecture.WHISPER,
        "486a46afbb7ba798507190ffe02fea2dd726049af212e774537efac6afb210a6", languages = WHISPER_LANGUAGES),
)

sealed class DownloadState {
    data class Downloading(val progress: Float) : DownloadState()
    object Extracting : DownloadState()
    object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

object ModelDownloader {
    private const val BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS).build()

    fun modelDir(ctx: Context, model: Model) =
        File(ctx.filesDir, "models/${model.archive}")

    fun isInstalled(ctx: Context, model: Model) =
        modelDir(ctx, model).exists()

    /** Download and extract model. Callbacks fire on background thread. */
    fun download(ctx: Context, model: Model, onState: (DownloadState) -> Unit) {
        val url = "$BASE_URL/${model.archive}.tar.bz2"
        val tmpFile = File(ctx.cacheDir, "${model.archive}.tar.bz2")
        // Extract into a staging dir first and only move the finished model
        // into models/, so an interrupted extraction never leaves a
        // half-written directory that isInstalled() would treat as usable.
        val stagingDir = File(ctx.cacheDir, "model-staging")
        val outDir = File(ctx.filesDir, "models")

        Thread {
            try {
                downloadFile(url, tmpFile, ctx.getString(R.string.empty_download_response), onState)
                val actual = sha256(tmpFile)
                if (!actual.equals(model.sha256, ignoreCase = true)) {
                    throw IOException("Checksum mismatch -- download rejected")
                }
                onState(DownloadState.Extracting)
                stagingDir.deleteRecursively()
                extractTarBz2(tmpFile, stagingDir)
                val extracted = File(stagingDir, model.archive)
                if (!extracted.isDirectory) throw IOException("Unexpected archive layout")
                outDir.mkdirs()
                val target = modelDir(ctx, model)
                target.deleteRecursively()
                if (!extracted.renameTo(target)) throw IOException("Couldn't install model")
                onState(DownloadState.Done)
            } catch (e: Exception) {
                onState(DownloadState.Error(e.message ?: ctx.getString(R.string.unknown_error)))
            } finally {
                tmpFile.delete()
                stagingDir.deleteRecursively()
            }
        }.start()
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(65536)
            var n: Int
            while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun delete(ctx: Context, model: Model) =
        modelDir(ctx, model).deleteRecursively()

    private fun downloadFile(
        url: String, dest: File, emptyResponseMessage: String, onState: (DownloadState) -> Unit
    ) {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body ?: throw IOException(emptyResponseMessage)
        val total = body.contentLength()
        var downloaded = 0L

        body.byteStream().use { src ->
            FileOutputStream(dest).use { dst ->
                val buf = ByteArray(16384)
                var n: Int
                while (src.read(buf).also { n = it } != -1) {
                    dst.write(buf, 0, n)
                    downloaded += n
                    if (total > 0)
                        onState(DownloadState.Downloading(downloaded.toFloat() / total))
                }
            }
        }
    }

    /** Extract tar.bz2 to outDir. Validates paths to prevent traversal. */
    fun extractTarBz2(archive: File, outDir: File) {
        outDir.mkdirs()
        val root = outDir.canonicalPath + File.separator
        val bzIn = BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        TarArchiveInputStream(bzIn).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                val dest = File(outDir, entry.name)
                require(dest.canonicalPath.startsWith(root)) {
                    "Path traversal: ${entry.name}"
                }
                // Only plain files and directories are expected in a model
                // archive; skip links and device entries outright.
                if (!entry.isDirectory && !entry.isFile) return@forEach
                if (entry.isDirectory) dest.mkdirs()
                else {
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { tar.copyTo(it) }
                }
            }
        }
    }
}
