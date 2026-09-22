package com.ai.assistance.mmd

/**
 * Operit patch: MMD morph (expression) name resolution.
 *
 * Different PMX models name their morphs differently (Japanese / English / Chinese).
 * This library resolves a semantic expression key to the morph name that actually
 * exists in the loaded model, so the caller never has to hard-code model-specific names.
 */
object MmdMorphLibrary {

    /** Semantic expression slots that the avatar layer can drive. */
    enum class Slot {
        BLINK,
        SMILE,
        ANGRY,
        SAD,
        SURPRISED,
        CONFUSED,
        THINKING,
        WINK_LEFT,
        WINK_RIGHT
    }

    /** Vowel visemes used for phoneme-driven lip sync. */
    enum class Vowel {
        /** Operit patch: no vowel shape; the renderer should close the mouth. */
        NONE,
        A,
        I,
        U,
        E,
        O,
        N
    }

    private val BLINK_KEYS = listOf("まばたき", "目パチ", "瞬き", "blink", "眨眼")
    private val SMILE_KEYS = listOf("にこり", "笑い", "smile", "微笑", "笑颜", "笑顔")
    private val ANGRY_KEYS = listOf("怒り", "angry", "愤怒", "生气")
    private val SAD_KEYS = listOf("悲しみ", "困った", "sad", "悲伤", "难过")
    private val SURPRISED_KEYS = listOf("びっくり", "おどろき", "驚き", "surprised", "惊讶")
    private val CONFUSED_KEYS = listOf("困惑", "困る", "confused", "疑问")
    private val THINKING_KEYS = listOf("じと目", "思考", "thinking")
    private val WINK_LEFT_KEYS = listOf("ウィンク右", "ウィンク２", "wink_l", "winkleft")
    private val WINK_RIGHT_KEYS = listOf("ウィンク", "ウィンク左", "wink_r", "winkright")

    private val VOWEL_A_KEYS = listOf("あ", "あ２", "a", "ア")
    private val VOWEL_I_KEYS = listOf("い", "i", "イ")
    private val VOWEL_U_KEYS = listOf("う", "u", "ウ")
    private val VOWEL_E_KEYS = listOf("え", "e", "エ")
    private val VOWEL_O_KEYS = listOf("お", "o", "オ")
    private val VOWEL_N_KEYS = listOf("ん", "n", "ン")

    /**
     * Normalizes a morph name for comparison:
     * strips whitespace, unifies full-width ASCII, and lowercases latin letters.
     */
    private fun normalize(raw: String): String {
        val builder = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch.isWhitespace() -> Unit
                ch.code in 0xFF01..0xFF5E -> builder.append((ch.code - 0xFEE0).toChar())
                else -> builder.append(ch)
            }
        }
        return builder.toString().lowercase()
    }

    /**
     * Resolves the first key that matches an existing morph name.
     * Matching order: exact match, then prefix match, then contains match.
     * Shorter morph names win on ties so that "あ" beats "あ２" for the plain A viseme.
     */
    private fun resolve(keys: List<String>, available: List<String>): String? {
        if (available.isEmpty()) {
            return null
        }
        val index = available.map { it to normalize(it) }

        for (key in keys) {
            val nk = normalize(key)
            index.firstOrNull { (_, norm) -> norm == nk }?.let { return it.first }
        }
        for (key in keys) {
            val nk = normalize(key)
            index
                .filter { (_, norm) -> norm.startsWith(nk) }
                .minByOrNull { (_, norm) -> norm.length }
                ?.let { return it.first }
        }
        for (key in keys) {
            val nk = normalize(key)
            index
                .filter { (_, norm) -> norm.contains(nk) }
                .minByOrNull { (_, norm) -> norm.length }
                ?.let { return it.first }
        }
        return null
    }

    /** Builds a full slot -> morph name map for the given model morph list. */
    fun resolveSlots(available: List<String>): Map<Slot, String> {
        val result = LinkedHashMap<Slot, String>()
        resolve(BLINK_KEYS, available)?.let { result[Slot.BLINK] = it }
        resolve(SMILE_KEYS, available)?.let { result[Slot.SMILE] = it }
        resolve(ANGRY_KEYS, available)?.let { result[Slot.ANGRY] = it }
        resolve(SAD_KEYS, available)?.let { result[Slot.SAD] = it }
        resolve(SURPRISED_KEYS, available)?.let { result[Slot.SURPRISED] = it }
        resolve(CONFUSED_KEYS, available)?.let { result[Slot.CONFUSED] = it }
        resolve(THINKING_KEYS, available)?.let { result[Slot.THINKING] = it }
        resolve(WINK_LEFT_KEYS, available)?.let { result[Slot.WINK_LEFT] = it }
        resolve(WINK_RIGHT_KEYS, available)?.let { result[Slot.WINK_RIGHT] = it }
        return result
    }

    /** Builds a vowel -> morph name map for the given model morph list. */
    fun resolveVowels(available: List<String>): Map<Vowel, String> {
        val result = LinkedHashMap<Vowel, String>()
        resolve(VOWEL_A_KEYS, available)?.let { result[Vowel.A] = it }
        resolve(VOWEL_I_KEYS, available)?.let { result[Vowel.I] = it }
        resolve(VOWEL_U_KEYS, available)?.let { result[Vowel.U] = it }
        resolve(VOWEL_E_KEYS, available)?.let { result[Vowel.E] = it }
        resolve(VOWEL_O_KEYS, available)?.let { result[Vowel.O] = it }
        resolve(VOWEL_N_KEYS, available)?.let { result[Vowel.N] = it }
        return result
    }

    /** Head / neck bone candidates used for look-at and head sway. */
    val HEAD_BONE_CANDIDATES = listOf("頭", "头", "head", "HEAD", "Head")
    val NECK_BONE_CANDIDATES = listOf("首", "neck", "NECK", "Neck")

    /** Resolves the head bone name that exists in the model node list. */
    fun resolveHeadBone(available: List<String>): String? = resolve(HEAD_BONE_CANDIDATES, available)

    /** Resolves the neck bone name that exists in the model node list. */
    fun resolveNeckBone(available: List<String>): String? = resolve(NECK_BONE_CANDIDATES, available)
}
