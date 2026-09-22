package com.ai.assistance.mmd

import android.content.res.AssetManager

object MmdNative {

    init {
        MmdLibraryLoader.loadLibraries()
    }

    @JvmStatic external fun nativeIsAvailable(): Boolean

    @JvmStatic external fun nativeGetUnavailableReason(): String

    @JvmStatic external fun nativeGetLastError(): String

    @JvmStatic external fun nativeReadModelName(pathModel: String): String?

    @JvmStatic external fun nativeReadModelSummary(pathModel: String): LongArray?

    @JvmStatic external fun nativeReadMotionModelName(pathMotion: String): String?

    @JvmStatic external fun nativeReadMotionSummary(pathMotion: String): LongArray?

    @JvmStatic external fun nativeReadMotionMaxFrame(pathMotion: String): Int

    @JvmStatic external fun nativeCreateRenderer(): Long

    @JvmStatic external fun nativeDestroyRenderer(handle: Long)

    @JvmStatic external fun nativeOnSurfaceCreated(handle: Long, assetManager: AssetManager)

    @JvmStatic external fun nativeOnSurfaceChanged(handle: Long, width: Int, height: Int)

    @JvmStatic external fun nativeRender(handle: Long): Boolean

    @JvmStatic external fun nativePause(handle: Long)

    @JvmStatic external fun nativeResume(handle: Long)

    @JvmStatic external fun nativeSetModelPath(handle: Long, pathModel: String?)

    @JvmStatic external fun nativeSetAnimationState(
        handle: Long,
        animationName: String?,
        isLooping: Boolean
    )

    @JvmStatic external fun nativeSetModelRotation(
        handle: Long,
        rotationX: Float,
        rotationY: Float,
        rotationZ: Float
    )

    @JvmStatic external fun nativeSetCameraDistanceScale(handle: Long, scale: Float)

    @JvmStatic external fun nativeSetCameraTargetHeight(handle: Long, height: Float)
    @JvmStatic external fun nativeSetLookAt(handle: Long, x: Float, y: Float)
    @JvmStatic external fun nativeSetAutoBlink(handle: Long, enable: Boolean)
    @JvmStatic external fun nativeSetAutoGlance(handle: Long, enable: Boolean)

    // === Operit patch: morph (expression) control ===
    @JvmStatic external fun nativeGetMorphCount(handle: Long): Int

    @JvmStatic external fun nativeGetMorphNames(handle: Long): Array<String>?

    @JvmStatic external fun nativeSetMorphWeight(handle: Long, name: String, weight: Float)

    @JvmStatic external fun nativeSetMorphWeights(
        handle: Long,
        names: Array<String>,
        weights: FloatArray
    )

    @JvmStatic external fun nativeClearMorphOverrides(handle: Long)

    // === Operit patch: bone (node) rotation control ===
    @JvmStatic external fun nativeSetNodeRotation(
        handle: Long,
        name: String,
        rx: Float,
        ry: Float,
        rz: Float
    )

    @JvmStatic external fun nativeClearNodeRotations(handle: Long)

    @JvmStatic external fun nativeGetRendererLastError(handle: Long): String
}