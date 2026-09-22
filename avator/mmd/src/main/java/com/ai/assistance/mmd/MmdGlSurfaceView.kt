package com.ai.assistance.mmd

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MmdGlSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    companion object {
        private const val TAG = "MmdGlSurfaceView"
        private val NEXT_INSTANCE_ID = AtomicInteger(1)
    }

    private val instanceId = NEXT_INSTANCE_ID.getAndIncrement()
    private var destroyLogged = false
    private val renderer = NativeMmdRenderer(context.applicationContext, instanceId)

    init {
        Log.i(TAG, "create instance#$instanceId")
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 8)
        setZOrderOnTop(true)
        setBackgroundColor(Color.TRANSPARENT)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        requestHighRefreshRateIfSupported()
        // Operit patch: gaze is fully autonomous (random wander), no touch wiring
    }

    fun setModelPath(path: String) {
        queueEvent {
            renderer.setModelPath(path)
        }
    }

    fun setAnimationState(animationName: String?, isLooping: Boolean) {
        queueEvent {
            renderer.setAnimationState(animationName, isLooping)
        }
    }

    fun setModelRotation(rotationX: Float, rotationY: Float, rotationZ: Float) {
        queueEvent {
            renderer.setModelRotation(rotationX, rotationY, rotationZ)
        }
    }

    fun setCameraDistanceScale(scale: Float) {
        queueEvent {
            renderer.setCameraDistanceScale(scale)
        }
    }

    fun setCameraTargetHeight(height: Float) {
        queueEvent {
            renderer.setCameraTargetHeight(height)
        }
    }

    fun setLookAt(x: Float, y: Float) {
        queueEvent {
            renderer.setLookAt(x, y)
        }
    }
    fun setAutoBlink(enable: Boolean) {
        queueEvent {
            renderer.setAutoBlink(enable)
        }
    }

    // === Operit patch: expression (morph) control ===
    fun setAutoGlance(enable: Boolean) {
        queueEvent {
            renderer.setAutoGlance(enable)
        }
    }

    fun setMorphWeight(name: String, weight: Float) {
        queueEvent {
            renderer.setMorphWeight(name, weight)
        }
    }

    fun setMorphWeights(names: Array<String>, weights: FloatArray) {
        queueEvent {
            renderer.setMorphWeights(names, weights)
        }
    }

    fun clearMorphOverrides() {
        queueEvent {
            renderer.clearMorphOverrides()
        }
    }

    fun getMorphNames(): Array<String> {
        return renderer.getMorphNames()
    }

    fun getMorphCount(): Int {
        return renderer.getMorphCount()
    }

    /**
     * Operit patch: thread-safe morph name query.
     * Runs on the GL thread and delivers the result back on the main thread,
     * so it never races with the render loop.
     */
    fun requestMorphNames(callback: (Array<String>) -> Unit) {
        queueEvent {
            val names = renderer.getMorphNames()
            renderer.postToMain { callback(names) }
        }
    }

    // === Operit patch: bone (node) rotation control ===
    fun setNodeRotation(name: String, rx: Float, ry: Float, rz: Float) {
        queueEvent {
            renderer.setNodeRotation(name, rx, ry, rz)
        }
    }

    fun clearNodeRotations() {
        queueEvent {
            renderer.clearNodeRotations()
        }
    }


    private fun installLookAtTouchListener() {
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val w = view.width.coerceAtLeast(1)
                    val h = view.height.coerceAtLeast(1)
                    val nx = ((event.x / w) * 2f - 1f).coerceIn(-1f, 1f)
                    val ny = -(((event.y / h) * 2f - 1f)).coerceIn(-1f, 1f)
                    setLookAt(nx, ny)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    setLookAt(0f, 0f)
                }
            }
            true
        }
    }

    fun setOnRenderErrorListener(listener: ((String) -> Unit)?) {
        renderer.setOnErrorListener(listener)
    }

    override fun onResume() {
        super.onResume()
        queueEvent {
            renderer.resumeRenderer()
        }
        requestHighRefreshRateIfSupported()
    }

    override fun onPause() {
        queueEvent {
            renderer.pauseRenderer()
        }
        super.onPause()
    }

    override fun onDetachedFromWindow() {
        if (!destroyLogged) {
            destroyLogged = true
            Log.i(TAG, "destroy instance#$instanceId")
        }
        queueEvent {
            renderer.releaseRenderer()
        }
        super.onDetachedFromWindow()
    }

    private fun requestHighRefreshRateIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }

        val surface = holder.surface ?: return
        if (!surface.isValid) {
            return
        }

        try {
            val setFrameRateMethod =
                surface.javaClass.getMethod(
                    "setFrameRate",
                    Float::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!
                )
            setFrameRateMethod.invoke(surface, 120f, 0)
        } catch (_: Throwable) {
        }
    }
}

private class NativeMmdRenderer(
    private val appContext: Context,
    private val instanceId: Int
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "MmdGlRenderer"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var onErrorListener: ((String) -> Unit)? = null

    private var rendererHandle: Long = MmdNative.nativeCreateRenderer()
    private var requestedModelPath: String? = null
    private var requestedAnimationName: String? = null
    private var requestedAnimationLooping: Boolean = false
    private var rotationX: Float = 0f
    private var rotationY: Float = 0f
    private var rotationZ: Float = 0f
    private var cameraDistanceScale: Float = 1f
    private var cameraTargetHeight: Float = 0f
    private var lookAtX: Float = 0f
    private var lookAtY: Float = 0f
    private var autoBlinkEnabled: Boolean = true
    private var autoGlanceEnabled: Boolean = true
    private val morphOverrides = LinkedHashMap<String, Float>()
    private val nodeRotationOverrides = LinkedHashMap<String, FloatArray>()
    private var lastRenderError: String? = null

    fun setOnErrorListener(listener: ((String) -> Unit)?) {
        onErrorListener = listener
    }

    /** Operit patch: hop back to the main thread from the GL thread. */
    fun postToMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    fun setModelPath(path: String) {
        val normalizedPath = path.trim().takeIf { it.isNotEmpty() } ?: return
        if (requestedModelPath == normalizedPath) {
            return
        }
        requestedModelPath = normalizedPath
        Log.i(TAG, "instance#$instanceId apply model path=$normalizedPath")
        if (rendererHandle != 0L) {
            MmdNative.nativeSetModelPath(rendererHandle, normalizedPath)
        }
    }

    fun setAnimationState(animationName: String?, isLooping: Boolean) {
        val normalizedAnimationName = animationName?.trim()?.takeIf { it.isNotEmpty() }
        if (requestedAnimationName == normalizedAnimationName &&
            requestedAnimationLooping == isLooping
        ) {
            return
        }
        requestedAnimationName = normalizedAnimationName
        requestedAnimationLooping = isLooping
        Log.i(
            TAG,
            "instance#$instanceId apply animation=${requestedAnimationName ?: "<none>"} looping=$requestedAnimationLooping"
        )
        if (rendererHandle != 0L) {
            MmdNative.nativeSetAnimationState(
                rendererHandle,
                requestedAnimationName,
                requestedAnimationLooping
            )
        }
    }

    fun setModelRotation(rotationX: Float, rotationY: Float, rotationZ: Float) {
        this.rotationX = rotationX
        this.rotationY = rotationY
        this.rotationZ = rotationZ
        if (rendererHandle != 0L) {
            MmdNative.nativeSetModelRotation(rendererHandle, rotationX, rotationY, rotationZ)
        }
    }

    fun setCameraDistanceScale(scale: Float) {
        cameraDistanceScale = scale.coerceIn(0.02f, 12.0f)
        if (rendererHandle != 0L) {
            MmdNative.nativeSetCameraDistanceScale(rendererHandle, cameraDistanceScale)
        }
    }

    fun setCameraTargetHeight(height: Float) {
        cameraTargetHeight = height.coerceIn(-2.0f, 2.0f)
        if (rendererHandle != 0L) {
            MmdNative.nativeSetCameraTargetHeight(rendererHandle, cameraTargetHeight)
        }
    }

    fun setLookAt(x: Float, y: Float) {
        lookAtX = x
        lookAtY = y
        if (rendererHandle != 0L) {
            MmdNative.nativeSetLookAt(rendererHandle, x, y)
        }
    }

    fun setAutoBlink(enable: Boolean) {
        autoBlinkEnabled = enable
        if (rendererHandle != 0L) {
            MmdNative.nativeSetAutoBlink(rendererHandle, enable)
        }
    }

    // === Operit patch: expression (morph) control ===
    fun setAutoGlance(enable: Boolean) {
        autoGlanceEnabled = enable
        if (rendererHandle != 0L) {
            MmdNative.nativeSetAutoGlance(rendererHandle, enable)
        }
    }

    fun setMorphWeight(name: String, weight: Float) {
        val key = name.trim()
        if (key.isEmpty()) {
            return
        }
        val w = weight.coerceIn(0f, 1f)
        morphOverrides[key] = w
        if (rendererHandle != 0L) {
            MmdNative.nativeSetMorphWeight(rendererHandle, key, w)
        }
    }

    fun setMorphWeights(names: Array<String>, weights: FloatArray) {
        val count = minOf(names.size, weights.size)
        if (count <= 0) {
            return
        }
        val cleanNames = ArrayList<String>(count)
        val cleanWeights = ArrayList<Float>(count)
        for (i in 0 until count) {
            val key = names[i].trim()
            if (key.isEmpty()) {
                continue
            }
            val w = weights[i].coerceIn(0f, 1f)
            cleanNames.add(key)
            cleanWeights.add(w)
            morphOverrides[key] = w
        }
        if (cleanNames.isEmpty()) {
            return
        }
        if (rendererHandle != 0L) {
            MmdNative.nativeSetMorphWeights(
                rendererHandle,
                cleanNames.toTypedArray(),
                cleanWeights.toFloatArray()
            )
        }
    }

    fun clearMorphOverrides() {
        morphOverrides.clear()
        if (rendererHandle != 0L) {
            MmdNative.nativeClearMorphOverrides(rendererHandle)
        }
    }

    fun getMorphNames(): Array<String> {
        if (rendererHandle == 0L) {
            return emptyArray()
        }
        return MmdNative.nativeGetMorphNames(rendererHandle) ?: emptyArray()
    }

    fun getMorphCount(): Int {
        if (rendererHandle == 0L) {
            return 0
        }
        return MmdNative.nativeGetMorphCount(rendererHandle)
    }

    // === Operit patch: bone (node) rotation control ===
    fun setNodeRotation(name: String, rx: Float, ry: Float, rz: Float) {
        val key = name.trim()
        if (key.isEmpty()) {
            return
        }
        nodeRotationOverrides[key] = floatArrayOf(rx, ry, rz)
        if (rendererHandle != 0L) {
            MmdNative.nativeSetNodeRotation(rendererHandle, key, rx, ry, rz)
        }
    }

    fun clearNodeRotations() {
        nodeRotationOverrides.clear()
        if (rendererHandle != 0L) {
            MmdNative.nativeClearNodeRotations(rendererHandle)
        }
    }

    fun pauseRenderer() {
        if (rendererHandle != 0L) {
            MmdNative.nativePause(rendererHandle)
        }
    }

    fun resumeRenderer() {
        if (rendererHandle != 0L) {
            MmdNative.nativeResume(rendererHandle)
        }
    }

    fun releaseRenderer() {
        if (rendererHandle == 0L) {
            return
        }
        MmdNative.nativeDestroyRenderer(rendererHandle)
        rendererHandle = 0L
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (rendererHandle == 0L) {
            rendererHandle = MmdNative.nativeCreateRenderer()
        }
        if (rendererHandle == 0L) {
            dispatchError("Failed to create native MMD renderer.")
            return
        }

        Log.i(TAG, "instance#$instanceId surface created handle=$rendererHandle")
        MmdNative.nativeOnSurfaceCreated(rendererHandle, appContext.assets)
        syncRequestedState()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (rendererHandle == 0L) {
            return
        }
        Log.i(TAG, "instance#$instanceId surface changed ${width}x$height")
        MmdNative.nativeOnSurfaceChanged(rendererHandle, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (rendererHandle == 0L) {
            return
        }

        val renderSuccess = MmdNative.nativeRender(rendererHandle)
        if (!renderSuccess) {
            val latestError = MmdNative.nativeGetRendererLastError(rendererHandle).ifBlank {
                "Failed to render MMD frame."
            }
            if (latestError != lastRenderError) {
                dispatchError(latestError)
                lastRenderError = latestError
            }
        } else {
            lastRenderError = null
        }
    }

    private fun syncRequestedState() {
        if (rendererHandle == 0L) {
            return
        }

        Log.i(
            TAG,
            "instance#$instanceId sync state model=${requestedModelPath ?: "<none>"} animation=${requestedAnimationName ?: "<none>"}"
        )
        MmdNative.nativeSetModelRotation(rendererHandle, rotationX, rotationY, rotationZ)
        MmdNative.nativeSetCameraDistanceScale(rendererHandle, cameraDistanceScale)
        MmdNative.nativeSetCameraTargetHeight(rendererHandle, cameraTargetHeight)
        MmdNative.nativeSetAutoBlink(rendererHandle, autoBlinkEnabled)
        MmdNative.nativeSetAutoGlance(rendererHandle, autoGlanceEnabled)
        MmdNative.nativeSetLookAt(rendererHandle, lookAtX, lookAtY)
        MmdNative.nativeSetModelPath(rendererHandle, requestedModelPath)
        MmdNative.nativeSetAnimationState(
            rendererHandle,
            requestedAnimationName,
            requestedAnimationLooping
        )
        // Operit patch: replay expression / bone overrides after surface rebuild
        if (morphOverrides.isNotEmpty()) {
            val names = morphOverrides.keys.toTypedArray()
            val weights = FloatArray(names.size)
            names.forEachIndexed { index, name -> weights[index] = morphOverrides[name] ?: 0f }
            MmdNative.nativeSetMorphWeights(rendererHandle, names, weights)
        }
        if (nodeRotationOverrides.isNotEmpty()) {
            nodeRotationOverrides.forEach { (name, rotation) ->
                if (rotation.size >= 3) {
                    MmdNative.nativeSetNodeRotation(
                        rendererHandle,
                        name,
                        rotation[0],
                        rotation[1],
                        rotation[2]
                    )
                }
            }
        }
    }

    private fun dispatchError(message: String) {
        if (message.isBlank()) {
            return
        }
        Log.e(TAG, message)
        mainHandler.post {
            onErrorListener?.invoke(message)
        }
    }
}