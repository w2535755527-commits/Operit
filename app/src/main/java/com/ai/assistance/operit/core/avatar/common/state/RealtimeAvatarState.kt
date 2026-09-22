package com.ai.assistance.operit.core.avatar.common.state

/**
 * Continuous, renderer-independent avatar controls.
 * Values are normalized to [-1, 1] or [0, 1] and are safe to sample every frame.
 */
data class RealtimeAvatarState(
    val emotion: AvatarEmotion = AvatarEmotion.IDLE,
    val emotionIntensity: Float = 0f,
    val isSpeaking: Boolean = false,
    val mouthOpen: Float = 0f,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val headYaw: Float = 0f,
    val headPitch: Float = 0f,
    val headRoll: Float = 0f,
    val blink: Float = 0f,
    val breathing: Float = 0.5f,
    val bodyMotion: Float = 0.25f
) {
    fun normalized(): RealtimeAvatarState = copy(
        emotionIntensity = emotionIntensity.coerceIn(0f, 1f),
        mouthOpen = mouthOpen.coerceIn(0f, 1f),
        gazeX = gazeX.coerceIn(-1f, 1f),
        gazeY = gazeY.coerceIn(-1f, 1f),
        headYaw = headYaw.coerceIn(-1f, 1f),
        headPitch = headPitch.coerceIn(-1f, 1f),
        headRoll = headRoll.coerceIn(-1f, 1f),
        blink = blink.coerceIn(0f, 1f),
        breathing = breathing.coerceIn(0f, 1f),
        bodyMotion = bodyMotion.coerceIn(0f, 1f)
    )
}
