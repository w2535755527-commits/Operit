package com.ai.assistance.operit.api.voice

import android.content.Context
import java.io.File
import java.io.IOException

internal object EspeakPhonemizer {
    private const val ASSET_ROOT = "espeak-ng-data"
    private const val DATA_VERSION = "1.52.0.1-piper-cmn"
    private const val MARKER_NAME = ".complete"

    init {
        System.loadLibrary("operit_espeak")
    }

    @Synchronized
    fun initialize(context: Context) {
        val parent = File(context.filesDir, "espeak/$DATA_VERSION")
        val dataDir = File(parent, ASSET_ROOT)
        val marker = File(dataDir, MARKER_NAME)
        if (!marker.isFile || marker.readText(Charsets.UTF_8) != DATA_VERSION) {
            val staging = File(parent, "$ASSET_ROOT.staging")
            staging.deleteRecursively()
            copyAssetTree(context, ASSET_ROOT, staging)
            File(staging, MARKER_NAME).writeText(DATA_VERSION, Charsets.UTF_8)
            dataDir.deleteRecursively()
            if (!staging.renameTo(dataDir)) {
                staging.deleteRecursively()
                throw IOException("Unable to install eSpeak-ng data")
            }
        }

        val error = nativeInitialize(parent.absolutePath)
        if (error != null) {
            throw IOException(error)
        }
    }

    fun phonemize(text: String, voice: String): String {
        return nativePhonemize(text, voice)
            ?: throw IllegalArgumentException("eSpeak-ng phonemization failed")
    }

    private fun copyAssetTree(context: Context, assetPath: String, destination: File) {
        val children = context.assets.list(assetPath)
            ?: throw IOException("Unable to list asset path: $assetPath")
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }

        if (!destination.mkdirs() && !destination.isDirectory) {
            throw IOException("Unable to create eSpeak-ng data directory: $destination")
        }
        children.forEach { child ->
            copyAssetTree(context, "$assetPath/$child", File(destination, child))
        }
    }

    private external fun nativeInitialize(dataParentPath: String): String?
    private external fun nativePhonemize(text: String, voice: String): String?
}