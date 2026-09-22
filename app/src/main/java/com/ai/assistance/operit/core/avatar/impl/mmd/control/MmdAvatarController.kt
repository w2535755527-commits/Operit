package com.ai.assistance.operit.core.avatar.impl.mmd.control

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.ai.assistance.mmd.MmdNative
import com.ai.assistance.mmd.MmdMorphLibrary
import com.ai.assistance.operit.core.avatar.common.control.AvatarController
import com.ai.assistance.operit.core.avatar.common.control.AvatarSettingKeys
import com.ai.assistance.operit.core.avatar.common.state.AvatarEmotion
import com.ai.assistance.operit.core.avatar.common.state.AvatarMoodTypes
import com.ai.assistance.operit.core.avatar.common.state.AvatarState
import com.ai.assistance.operit.core.avatar.common.state.AvatarViseme
import com.ai.assistance.operit.core.avatar.common.state.RealtimeAvatarState
import java.io.File
import kotlin.math.roundToLong
import com.ai.assistance.operit.core.avatar.impl.mmd.model.MmdAvatarModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Operit patch: one frame of renderer-ready expression data.
 *
 * The controller stays renderer-agnostic: it only publishes semantic morph weights
 * (already resolved to names that exist in the loaded model) plus head bone angles.
 */
data class MmdExpressionFrame(
    val morphWeights: Map<String, Float> = emptyMap(),
    val headYawDeg: Float = 0f,
    val headPitchDeg: Float = 0f
)

class MmdAvatarController(
    private val model: MmdAvatarModel
) : AvatarController {

    private val _state = MutableStateFlow(AvatarState())
    override val state: StateFlow<AvatarState> = _state.asStateFlow()

    private val _scale = MutableStateFlow(1.0f)
    val scale: StateFlow<Float> = _scale.asStateFlow()

    private val _translateX = MutableStateFlow(0.0f)
    val translateX: StateFlow<Float> = _translateX.asStateFlow()

    private val _translateY = MutableStateFlow(0.0f)
    val translateY: StateFlow<Float> = _translateY.asStateFlow()

    private val _initialRotationX = MutableStateFlow(0.0f)
    val initialRotationX: StateFlow<Float> = _initialRotationX.asStateFlow()

    private val _initialRotationY = MutableStateFlow(0.0f)
    val initialRotationY: StateFlow<Float> = _initialRotationY.asStateFlow()

    private val _initialRotationZ = MutableStateFlow(0.0f)
    val initialRotationZ: StateFlow<Float> = _initialRotationZ.asStateFlow()

    private val _cameraDistanceScale = MutableStateFlow(1.0f)
    val cameraDistanceScale: StateFlow<Float> = _cameraDistanceScale.asStateFlow()

    private val _cameraTargetHeight = MutableStateFlow(0.0f)
    val cameraTargetHeight: StateFlow<Float> = _cameraTargetHeight.asStateFlow()

    // === Operit patch: realtime expression pipeline ===
    private val _expressionFrame = MutableStateFlow(MmdExpressionFrame())
    /** Latest renderer-ready expression frame, consumed by the MMD renderer. */
    val expressionFrame: StateFlow<MmdExpressionFrame> = _expressionFrame.asStateFlow()

    private val _morphCatalog = MutableStateFlow<List<String>>(emptyList())
    /** Morph names discovered from the loaded model, or empty until discovered. */
    val morphCatalog: StateFlow<List<String>> = _morphCatalog.asStateFlow()

    private var slotMap: Map<MmdMorphLibrary.Slot, String> = emptyMap()
    private var vowelMap: Map<MmdMorphLibrary.Vowel, String> = emptyMap()
    private var headBoneName: String? = null

    @Volatile
    private var gazeX: Float = 0f

    @Volatile
    private var gazeY: Float = 0f

    override val availableAnimations: List<String>
        get() = model.displayMotionNames

    private var emotionAnimationMapping: Map<AvatarEmotion, String> = emptyMap()
    private var triggerAnimationMapping: Map<String, String> = emptyMap()

    override fun setEmotion(newEmotion: AvatarEmotion) {
        playEmotion(newEmotion, loop = 0)
    }

    override fun playEmotion(emotion: AvatarEmotion, loop: Int) {
        _state.value = _state.value.copy(emotion = emotion)

        resolveAnimationForEmotion(emotion)?.let { animationName ->
            playAnimation(animationName, loop)
        }
    }

    override fun playTrigger(triggerName: String, loop: Int): Boolean {
        val normalizedTrigger = AvatarMoodTypes.normalizeKey(triggerName)
        val animationName = resolveAnimationForTrigger(normalizedTrigger) ?: return false
        _state.value =
            _state.value.copy(
                emotion = AvatarMoodTypes.builtInFallbackEmotion(normalizedTrigger) ?: _state.value.emotion
            )
        playAnimation(animationName, loop)
        return true
    }

    override fun estimateEmotionDurationMillis(emotion: AvatarEmotion): Long? {
        val animationName = resolveAnimationForEmotion(emotion) ?: return null
        val motionPath = File(model.basePath, animationName).absolutePath
        val maxFrame = MmdNative.nativeReadMotionMaxFrame(motionPath)
        if (maxFrame <= 0) {
            return null
        }

        return ((maxFrame / 30f) * 1000f).roundToLong().coerceAtLeast(1L)
    }

    override fun estimateTriggerDurationMillis(triggerName: String): Long? {
        val animationName =
            resolveAnimationForTrigger(AvatarMoodTypes.normalizeKey(triggerName)) ?: return null
        val motionPath = File(model.basePath, animationName).absolutePath
        val maxFrame = MmdNative.nativeReadMotionMaxFrame(motionPath)
        if (maxFrame <= 0) {
            return null
        }

        return ((maxFrame / 30f) * 1000f).roundToLong().coerceAtLeast(1L)
    }

    override fun playAnimation(animationName: String, loop: Int) {
        if (!availableAnimations.contains(animationName)) {
            return
        }

        _state.value = _state.value.copy(
            currentAnimation = null,
            isLooping = false
        )
        _state.value = _state.value.copy(
            currentAnimation = animationName,
            isLooping = loop == 0
        )
    }

    override fun lookAt(x: Float, y: Float) {
        // Operit patch: remember the gaze target so the renderer can drive the head bone.
        gazeX = x.coerceIn(-1f, 1f)
        gazeY = y.coerceIn(-1f, 1f)
    }

    override fun applyRealtimeState(realtimeState: RealtimeAvatarState) {
        val state = realtimeState.normalized()
        gazeX = state.gazeX
        gazeY = state.gazeY
        _expressionFrame.value = composeExpression(state)
    }

    /**
     * Operit patch: converts a renderer-independent realtime state into concrete
     * morph weights + head angles, using only morphs that exist in the loaded model.
     */
    private fun composeExpression(state: RealtimeAvatarState): MmdExpressionFrame {
        val morphs = LinkedHashMap<String, Float>()

        // --- lip sync ---
        // Operit patch: the phoneme timeline owns the *shape* (viseme) while the live audio
        // amplitude owns the *strength*. When no timeline is available (cloud TTS that never
        // exposes phonemes) we fall back to the old graded opening so the mouth still moves.
        if (state.isSpeaking && state.mouthOpen > 0.01f) {
            val vowel = if (state.viseme != AvatarViseme.NONE) {
                visemeToVowel(state.viseme)
            } else {
                pickVowel(state.mouthOpen)
            }
            vowelMap[vowel]?.let { morphs[it] = state.mouthOpen }
        }

        // --- blink (auto blink stays enabled; only drive when a manual value is given) ---
        if (state.blink > 0.01f) {
            slotMap[MmdMorphLibrary.Slot.BLINK]?.let { morphs[it] = state.blink }
        }

        // --- emotion morph, blended by intensity ---
        val intensity = state.emotionIntensity
        if (intensity > 0.01f) {
            val slot = emotionToSlot(state.emotion)
            if (slot != null) {
                slotMap[slot]?.let { morphs[it] = intensity }
            }
        }

        return MmdExpressionFrame(
            morphWeights = morphs,
            headYawDeg = state.headYaw * 28f,
            headPitchDeg = state.headPitch * 18f
        )
    }

    private fun pickVowel(mouthOpen: Float): MmdMorphLibrary.Vowel {
        // Fallback used only when the phoneme timeline has no data (cloud TTS paths).
        // A graded opening still reads as natural speech.
        return when {
            mouthOpen < 0.2f -> MmdMorphLibrary.Vowel.I
            mouthOpen < 0.4f -> MmdMorphLibrary.Vowel.U
            mouthOpen < 0.6f -> MmdMorphLibrary.Vowel.E
            mouthOpen < 0.8f -> MmdMorphLibrary.Vowel.O
            else -> MmdMorphLibrary.Vowel.A
        }
    }

    /** Operit patch: maps a renderer-independent viseme onto an MMD vowel morph slot. */
    private fun visemeToVowel(viseme: AvatarViseme): MmdMorphLibrary.Vowel = when (viseme) {
        AvatarViseme.A -> MmdMorphLibrary.Vowel.A
        AvatarViseme.I -> MmdMorphLibrary.Vowel.I
        AvatarViseme.U -> MmdMorphLibrary.Vowel.U
        AvatarViseme.E -> MmdMorphLibrary.Vowel.E
        AvatarViseme.O -> MmdMorphLibrary.Vowel.O
        AvatarViseme.N -> MmdMorphLibrary.Vowel.N
        AvatarViseme.NONE -> MmdMorphLibrary.Vowel.NONE
    }

    private fun emotionToSlot(emotion: AvatarEmotion): MmdMorphLibrary.Slot? = when (emotion) {
        AvatarEmotion.HAPPY -> MmdMorphLibrary.Slot.SMILE
        AvatarEmotion.SAD -> MmdMorphLibrary.Slot.SAD
        AvatarEmotion.SURPRISED -> MmdMorphLibrary.Slot.SURPRISED
        AvatarEmotion.CONFUSED -> MmdMorphLibrary.Slot.CONFUSED
        AvatarEmotion.THINKING -> MmdMorphLibrary.Slot.THINKING
        else -> null
    }

    /**
     * Operit patch: called once the model has been loaded so that morph names can be
     * resolved against the actual model contents.
     */
    fun onMorphCatalogAvailable(morphNames: List<String>, nodeNames: List<String> = emptyList()) {
        _morphCatalog.value = morphNames
        slotMap = MmdMorphLibrary.resolveSlots(morphNames)
        vowelMap = MmdMorphLibrary.resolveVowels(morphNames)
        headBoneName = if (nodeNames.isEmpty()) {
            null
        } else {
            MmdMorphLibrary.resolveHeadBone(nodeNames)
        }
    }

    /** Operit patch: the head bone resolved for the loaded model, if any. */
    fun resolvedHeadBone(): String? = headBoneName

    override fun updateSettings(settings: Map<String, Any>) {
        settings[AvatarSettingKeys.SCALE]?.let { if (it is Number) _scale.value = it.toFloat() }
        settings[AvatarSettingKeys.TRANSLATE_X]?.let { if (it is Number) _translateX.value = it.toFloat() }
        settings[AvatarSettingKeys.TRANSLATE_Y]?.let { if (it is Number) _translateY.value = it.toFloat() }

        settings[AvatarSettingKeys.MMD_INITIAL_ROTATION_X]?.let {
            if (it is Number) {
                _initialRotationX.value = it.toFloat()
            }
        }
        settings[AvatarSettingKeys.MMD_INITIAL_ROTATION_Y]?.let {
            if (it is Number) {
                _initialRotationY.value = it.toFloat()
            }
        }
        settings[AvatarSettingKeys.MMD_INITIAL_ROTATION_Z]?.let {
            if (it is Number) {
                _initialRotationZ.value = it.toFloat()
            }
        }

        settings[AvatarSettingKeys.MMD_CAMERA_DISTANCE_SCALE]?.let {
            if (it is Number) {
                _cameraDistanceScale.value = it.toFloat().coerceIn(0.02f, 12.0f)
            }
        }
        settings[AvatarSettingKeys.MMD_CAMERA_TARGET_HEIGHT]?.let {
            if (it is Number) {
                _cameraTargetHeight.value = it.toFloat().coerceIn(-2.0f, 2.0f)
            }
        }
    }

    override fun updateEmotionAnimationMapping(mapping: Map<AvatarEmotion, String>) {
        emotionAnimationMapping = mapping
            .mapValues { (_, animationName) -> animationName.trim() }
            .filterValues { animationName -> animationName.isNotBlank() }
    }

    override fun updateTriggerAnimationMapping(mapping: Map<String, String>) {
        triggerAnimationMapping =
            mapping.entries.mapNotNull { (rawKey, rawAnimationName) ->
                val key = AvatarMoodTypes.normalizeKey(rawKey)
                val animationName = rawAnimationName.trim()
                if (key.isBlank() || animationName.isBlank()) {
                    return@mapNotNull null
                }
                key to animationName
            }.toMap()
    }

    private fun resolveAnimationForEmotion(emotion: AvatarEmotion): String? {
        val preferred = emotionAnimationMapping[emotion]
        if (!preferred.isNullOrBlank() && availableAnimations.contains(preferred)) {
            return preferred
        }

        val idleFallback = emotionAnimationMapping[AvatarEmotion.IDLE]
        if (!idleFallback.isNullOrBlank() && availableAnimations.contains(idleFallback)) {
            return idleFallback
        }

        return null
    }

    private fun resolveAnimationForTrigger(triggerName: String): String? {
        val preferred = triggerAnimationMapping[triggerName]
        if (!preferred.isNullOrBlank() && availableAnimations.contains(preferred)) {
            return preferred
        }

        return availableAnimations.firstOrNull { animationName ->
            animationName.equals(triggerName, ignoreCase = true)
        }
    }
}

@Composable
fun rememberMmdAvatarController(model: MmdAvatarModel): MmdAvatarController {
    return remember(model) { MmdAvatarController(model) }
}