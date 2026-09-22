package com.ai.assistance.operit.core.avatar.common.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarPhonemeTimelineTest {

    @Test
    fun `empty input yields an unusable timeline`() {
        val timeline = AvatarPhonemeTimeline.fromIpa(null)
        assertFalse(timeline.isUsable)
        assertEquals(0, timeline.phonemeCount)
        assertEquals(AvatarViseme.NONE, timeline.currentViseme())
    }

    @Test
    fun `maps espeak ipa onto visemes`() {
        // "hello" in espeak IPA: h @ l 'oU
        val timeline = AvatarPhonemeTimeline.fromIpa("h@l'oU")
        assertTrue(timeline.isUsable)
        assertTrue(timeline.totalDurationMillis > 0L)
        assertEquals(AvatarViseme.E, AvatarPhonemeTimeline.visemeFor("@"))
        assertEquals(AvatarViseme.A, AvatarPhonemeTimeline.visemeFor("oU"))
        assertEquals(AvatarViseme.A, AvatarPhonemeTimeline.visemeFor("a"))
        assertEquals(AvatarViseme.I, AvatarPhonemeTimeline.visemeFor("i"))
        assertEquals(AvatarViseme.U, AvatarPhonemeTimeline.visemeFor("u"))
        assertEquals(AvatarViseme.O, AvatarPhonemeTimeline.visemeFor("O"))
        assertEquals(AvatarViseme.N, AvatarPhonemeTimeline.visemeFor("n"))
        assertEquals(AvatarViseme.NONE, AvatarPhonemeTimeline.visemeFor("#"))
    }

    @Test
    fun `cursor follows the audio clock`() {
        val timeline = AvatarPhonemeTimeline.fromIpa("a i u")
        assertTrue(timeline.isUsable)
        timeline.seekMillis(0L)
        assertEquals(AvatarViseme.A, timeline.currentViseme())
        // Past the end of the timeline the mouth must close.
        timeline.seekMillis(timeline.totalDurationMillis + 5000L)
        assertEquals(AvatarViseme.NONE, timeline.currentViseme())
        // Seeking backwards must work too (no one-way cursor).
        timeline.seekMillis(0L)
        assertEquals(AvatarViseme.A, timeline.currentViseme())
    }

    @Test
    fun `stress lengthens the vowel and rate shortens it`() {
        val plain = AvatarPhonemeTimeline.fromIpa("a")
        val stressed = AvatarPhonemeTimeline.fromIpa("'a")
        assertTrue(stressed.totalDurationMillis > plain.totalDurationMillis)
        val fast = AvatarPhonemeTimeline.fromIpa("a", rate = 2f)
        assertTrue(fast.totalDurationMillis < plain.totalDurationMillis)
    }

    @Test
    fun `length marks and diacritics stay attached`() {
        val tokens = AvatarPhonemeTimeline.tokenizeIpa("aː b")
        assertEquals(2, tokens.size)
        assertEquals("aː", tokens[0].phoneme)
    }

    @Test
    fun `approximate timeline from text keeps the mouth moving`() {
        val timeline = AvatarPhonemeTimeline.approximateFromText("你好世界")
        assertTrue(timeline.isUsable)
        assertEquals(4, timeline.phonemeCount)
        assertTrue(timeline.totalDurationMillis > 0L)
    }

    @Test
    fun `approximate timeline stretches to the real audio length`() {
        val guessed = AvatarPhonemeTimeline.approximateFromText("hello world")
        val stretched = guessed.scaledTo(3000L)
        assertTrue(stretched.totalDurationMillis > guessed.totalDurationMillis)
        assertTrue(stretched.totalDurationMillis >= 2500L)
    }

    @Test
    fun `mouth opening increases with vowel openness`() {
        assertEquals(0f, AvatarPhonemeTimeline.mouthOpenFor(AvatarViseme.NONE), 0.001f)
        assertTrue(
            AvatarPhonemeTimeline.mouthOpenFor(AvatarViseme.A) >
                AvatarPhonemeTimeline.mouthOpenFor(AvatarViseme.I)
        )
    }
}