package com.ai.assistance.operit.core.avatar.common.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarActionTagParserTest {

    @Test
    fun `parses emotion and action tags`() {
        val directives = AvatarActionTagParser.parse("你好呀 [emotion:happy][action:wave]")
        assertEquals(AvatarEmotion.HAPPY, directives.emotion)
        assertEquals("wave", directives.action)
        assertFalse(directives.isEmpty)
    }

    @Test
    fun `last tag wins like the legacy mood parser`() {
        val directives = AvatarActionTagParser.parse("[emotion:sad] 嗯 [emotion:happy]")
        assertEquals(AvatarEmotion.HAPPY, directives.emotion)
    }

    @Test
    fun `tolerates whitespace and casing`() {
        val directives = AvatarActionTagParser.parse("[ Emotion : Surprised ]")
        assertEquals(AvatarEmotion.SURPRISED, directives.emotion)
    }

    @Test
    fun `accepts chinese synonyms`() {
        assertEquals(AvatarEmotion.HAPPY, AvatarActionTagParser.emotionFromKey("开心"))
        assertEquals(AvatarEmotion.SAD, AvatarActionTagParser.emotionFromKey("难过"))
        assertEquals(AvatarEmotion.THINKING, AvatarActionTagParser.emotionFromKey("思考"))
    }

    @Test
    fun `unknown emotion yields null instead of guessing`() {
        val directives = AvatarActionTagParser.parse("[emotion:banana]")
        assertNull(directives.emotion)
        assertNull(directives.action)
        assertTrue(directives.isEmpty)
    }

    @Test
    fun `blank text is safe`() {
        assertTrue(AvatarActionTagParser.parse(null).isEmpty)
        assertTrue(AvatarActionTagParser.parse("   ").isEmpty)
        assertEquals("", AvatarActionTagParser.strip(null))
    }

    @Test
    fun `strip removes tags and collapses spaces`() {
        val stripped = AvatarActionTagParser.strip("你好  [emotion:happy]  世界 [action:wave]")
        assertEquals("你好 世界", stripped)
    }
}