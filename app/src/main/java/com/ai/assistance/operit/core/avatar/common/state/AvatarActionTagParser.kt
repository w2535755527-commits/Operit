package com.ai.assistance.operit.core.avatar.common.state

/**
 * Operit patch: parses avatar control tags out of an AI reply.
 *
 * The model is expected to append directives such as:
 *
 *     [emotion:happy][action:wave]
 *
 * Both tags are optional and may appear multiple times; the **last** occurrence wins, which
 * matches how the existing `<mood>` handling behaves. Unknown emotions/actions are ignored so
 * a hallucinated tag can never break rendering.
 *
 * The parser is intentionally separate from [com.ai.assistance.operit.ui.floating.ui.pet.AvatarEmotionManager]
 * because that class already owns `<mood>` parsing and the avatar renderers must not depend on a
 * UI-layer object.
 */
object AvatarActionTagParser {

    /** A single parsed directive. Both fields are null when the text had no usable tag. */
    data class Directives(
        val emotion: AvatarEmotion?,
        val action: String?
    ) {
        val isEmpty: Boolean get() = emotion == null && action == null
    }

    private val EMOTION_REGEX =
        Regex("\\[\\s*emotion\\s*:\\s*([^\\]\\r\\n]+)\\]", RegexOption.IGNORE_CASE)

    private val ACTION_REGEX =
        Regex("\\[\\s*action\\s*:\\s*([^\\]\\r\\n]+)\\]", RegexOption.IGNORE_CASE)

    /** Extracts the directives from [text]; the last tag of each kind wins. */
    fun parse(text: String?): Directives {
        if (text.isNullOrBlank()) return Directives(null, null)
        val emotion = EMOTION_REGEX.findAll(text)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.let { emotionFromKey(it) }
        val action = ACTION_REGEX.findAll(text)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return Directives(emotion, action)
    }

    /** Removes `[emotion:...]` / `[action:...]` tags so they are never shown to the user. */
    fun strip(text: String?): String {
        if (text.isNullOrBlank()) return text.orEmpty()
        return text
            .replace(EMOTION_REGEX, "")
            .replace(ACTION_REGEX, "")
            .replace(Regex("[ \t]{2,}"), " ")
            .trim()
    }

    /**
     * Maps a tag value onto an [AvatarEmotion]. Accepts the English enum names plus a few
     * common synonyms so a model that writes `[emotion:joy]` still works.
     */
    fun emotionFromKey(raw: String): AvatarEmotion? {
        val key = raw.trim().lowercase()
        if (key.isEmpty()) return null
        return when (key) {
            "idle", "neutral", "平静", "默认" -> AvatarEmotion.IDLE
            "listening", "listen", "倾听", "聆听" -> AvatarEmotion.LISTENING
            "thinking", "think", "思考", "想" -> AvatarEmotion.THINKING
            "happy", "joy", "smile", "开心", "高兴", "愉快" -> AvatarEmotion.HAPPY
            "sad", "sorrow", "难过", "伤心", "悲伤" -> AvatarEmotion.SAD
            "confused", "puzzled", "困惑", "疑惑" -> AvatarEmotion.CONFUSED
            "surprised", "surprise", "惊讶", "吃惊" -> AvatarEmotion.SURPRISED
            else -> AvatarEmotion.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
        }
    }
}