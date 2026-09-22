package com.ai.assistance.operit.core.avatar.common.state

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarAudioMeterTest {

    @After
    fun tearDown() {
        AvatarAudioMeter.reset()
        AvatarLipSyncBus.reset()
    }

    @Test
    fun `publish clamps and rejects non-finite values`() {
        AvatarAudioMeter.publish(2f)
        assertEquals(1f, AvatarAudioMeter.currentLevel(), 0.001f)
        AvatarAudioMeter.publish(-1f)
        assertEquals(0f, AvatarAudioMeter.currentLevel(), 0.001f)
        AvatarAudioMeter.publish(Float.NaN)
        assertEquals(0f, AvatarAudioMeter.currentLevel(), 0.001f)
    }

    @Test
    fun `silence maps to zero and loud pcm maps high`() {
        val silent = ShortArray(256) { 0 }
        AvatarAudioMeter.publishPcm16(silent)
        assertEquals(0f, AvatarAudioMeter.currentLevel(), 0.001f)

        val loud = ShortArray(256) { 30000 }
        AvatarAudioMeter.publishPcm16(loud)
        assertTrue(AvatarAudioMeter.currentLevel() > 0.8f)
    }

    @Test
    fun `quiet pcm still produces a usable level`() {
        // ~ -40 dBFS: the gamma curve should lift this well above the silence floor.
        val quiet = ShortArray(256) { 330 }
        AvatarAudioMeter.publishPcm16(quiet)
        assertTrue(AvatarAudioMeter.currentLevel() > AvatarAudioMeter.SILENCE_FLOOR)
    }

    @Test
    fun `out of range pcm arguments are safe`() {
        val samples = ShortArray(8) { 1000 }
        AvatarAudioMeter.publishPcm16(samples, offset = 99, length = 10)
        assertEquals(0f, AvatarAudioMeter.currentLevel(), 0.001f)
        AvatarAudioMeter.publishPcm16(samples, offset = 0, length = 0)
        assertEquals(0f, AvatarAudioMeter.currentLevel(), 0.001f)
    }

    @Test
    fun `reset clears the meter`() {
        AvatarAudioMeter.publish(0.9f)
        AvatarAudioMeter.reset()
        assertEquals(0f, AvatarAudioMeter.currentLevel(), 0.001f)
    }

    @Test
    fun `lip sync bus exposes the published viseme and clears on reset`() {
        AvatarLipSyncBus.publishIpa("a i u")
        assertTrue(AvatarLipSyncBus.isActive)
        AvatarLipSyncBus.publishPositionMillis(0L)
        assertEquals(AvatarViseme.A, AvatarLipSyncBus.currentViseme())

        AvatarLipSyncBus.reset()
        assertTrue(!AvatarLipSyncBus.isActive)
        assertEquals(AvatarViseme.NONE, AvatarLipSyncBus.currentViseme())
        assertEquals(0f, AvatarLipSyncBus.currentMouthOpen(), 0.001f)
    }

    @Test
    fun `lip sync bus ignores unusable timelines`() {
        AvatarLipSyncBus.publishIpa("")
        assertTrue(!AvatarLipSyncBus.isActive)
        AvatarLipSyncBus.publishApproximateText(null)
        assertTrue(!AvatarLipSyncBus.isActive)
    }

    @Test
    fun `composer prefers the viseme shape over the raw amplitude`() {
        val withViseme = RealtimeAvatarStateComposer.fromEmotion(
            emotion = AvatarEmotion.IDLE,
            speaking = true,
            audioLevel = 1f,
            viseme = AvatarViseme.I
        )
        val withoutViseme = RealtimeAvatarStateComposer.fromEmotion(
            emotion = AvatarEmotion.IDLE,
            speaking = true,
            audioLevel = 1f
        )
        assertEquals(AvatarViseme.I, withViseme.viseme)
        // A closed vowel must not open as wide as the plain amplitude fallback.
        assertTrue(withViseme.mouthOpen < withoutViseme.mouthOpen)
        // A silent avatar keeps the mouth shut even when a viseme is known.
        assertEquals(
            0f,
            RealtimeAvatarStateComposer.fromEmotion(
                emotion = AvatarEmotion.IDLE,
                speaking = false,
                audioLevel = 1f,
                viseme = AvatarViseme.A
            ).mouthOpen,
            0.001f
        )
    }

    @Test
    fun `smooth keeps the discrete viseme from the target`() {
        val previous = RealtimeAvatarState(viseme = AvatarViseme.A)
        val target = RealtimeAvatarState(viseme = AvatarViseme.O)
        val result = RealtimeAvatarStateComposer.smooth(previous, target, 0.1f)
        assertEquals(AvatarViseme.O, result.viseme)
    }
}