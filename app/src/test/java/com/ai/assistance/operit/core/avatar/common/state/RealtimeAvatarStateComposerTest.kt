package com.ai.assistance.operit.core.avatar.common.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeAvatarStateComposerTest {
    @Test
    fun `speaking maps audio level to mouth opening`() {
        val quiet = RealtimeAvatarStateComposer.fromEmotion(
            emotion = AvatarEmotion.HAPPY,
            speaking = true,
            audioLevel = 0f
        )
        val loud = RealtimeAvatarStateComposer.fromEmotion(
            emotion = AvatarEmotion.HAPPY,
            speaking = true,
            audioLevel = 1f
        )

        assertEquals(0.12f, quiet.mouthOpen, 0.001f)
        assertEquals(1f, loud.mouthOpen, 0.001f)
    }

    @Test
    fun `silent state closes mouth and clamps controls`() {
        val state = RealtimeAvatarStateComposer.fromEmotion(
            emotion = AvatarEmotion.SAD,
            speaking = false,
            audioLevel = 2f,
            gazeX = 4f,
            gazeY = -4f
        )

        assertEquals(0f, state.mouthOpen, 0.001f)
        assertEquals(1f, state.gazeX, 0.001f)
        assertEquals(-1f, state.gazeY, 0.001f)
    }

    @Test
    fun `smooth interpolates continuous fields`() {
        val previous = RealtimeAvatarState(mouthOpen = 0f, gazeX = -1f)
        val target = RealtimeAvatarState(mouthOpen = 1f, gazeX = 1f)

        val result = RealtimeAvatarStateComposer.smooth(previous, target, 0.25f)

        assertEquals(0.25f, result.mouthOpen, 0.001f)
        assertEquals(-0.5f, result.gazeX, 0.001f)
        assertTrue(result.emotion == AvatarEmotion.IDLE)
    }
}
