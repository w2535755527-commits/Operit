package com.ai.assistance.operit.core.avatar.common.state

/** Deterministic state fusion used by every avatar renderer. */
object RealtimeAvatarStateComposer {
    fun fromEmotion(
        emotion: AvatarEmotion,
        intensity: Float = 0.65f,
        speaking: Boolean = false,
        audioLevel: Float = 0f,
        gazeX: Float = 0f,
        gazeY: Float = 0f
    ): RealtimeAvatarState {
        val level = audioLevel.coerceIn(0f, 1f)
        val strength = intensity.coerceIn(0f, 1f)
        val mouth = if (speaking) (0.12f + level * 0.88f).coerceIn(0f, 1f) else 0f
        val body = when (emotion) {
            AvatarEmotion.HAPPY, AvatarEmotion.SURPRISED -> 0.65f
            AvatarEmotion.THINKING, AvatarEmotion.CONFUSED -> 0.4f
            AvatarEmotion.SAD -> 0.2f
            else -> 0.3f
        }
        return RealtimeAvatarState(
            emotion = emotion,
            emotionIntensity = strength,
            isSpeaking = speaking,
            mouthOpen = mouth,
            gazeX = gazeX,
            gazeY = gazeY,
            headYaw = gazeX * 0.45f,
            headPitch = gazeY * 0.3f,
            blink = 0f,
            breathing = 0.5f + strength * 0.2f,
            bodyMotion = body
        ).normalized()
    }

    fun smooth(previous: RealtimeAvatarState, target: RealtimeAvatarState, amount: Float): RealtimeAvatarState {
        val t = amount.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * t
        return target.copy(
            emotionIntensity = mix(previous.emotionIntensity, target.emotionIntensity),
            mouthOpen = mix(previous.mouthOpen, target.mouthOpen),
            gazeX = mix(previous.gazeX, target.gazeX),
            gazeY = mix(previous.gazeY, target.gazeY),
            headYaw = mix(previous.headYaw, target.headYaw),
            headPitch = mix(previous.headPitch, target.headPitch),
            headRoll = mix(previous.headRoll, target.headRoll),
            blink = mix(previous.blink, target.blink),
            breathing = mix(previous.breathing, target.breathing),
            bodyMotion = mix(previous.bodyMotion, target.bodyMotion)
        ).normalized()
    }
}