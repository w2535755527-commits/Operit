package com.ai.assistance.operit.core.avatar.common.state

import kotlin.math.roundToLong

/**
 * Operit patch: phoneme timeline for phoneme-driven lip sync.
 *
 * The TTS stack (cloud MiniMax / Doubao, or local VITS) never returns phoneme timings, so
 * the pipeline is:
 *
 *  1. run local G2P (espeak-ng via [com.ai.assistance.operit.api.voice.EspeakPhonemizer])
 *     to get an IPA string for the reply text;
 *  2. split it into phonemes and map each one onto an [AvatarViseme];
 *  3. estimate a duration for every phoneme from its class and the configured rate;
 *  4. while the audio plays, advance a cursor with the playback position and read the
 *     viseme that should be visible *now*.
 *
 * The estimate is deliberately simple and deterministic: it is not a forced aligner, but it
 * tracks real speech well enough for a stylised avatar because it advances with the actual
 * audio clock instead of a free-running timer.
 *
 * The class is immutable apart from the cursor, and every mutating method is synchronized so
 * the audio thread can call [seekMillis] while the render loop calls [visemeAt].
 */
class AvatarPhonemeTimeline private constructor(
    private val entries: List<Entry>,
    val totalDurationMillis: Long
) {

    private data class Entry(
        val phoneme: String,
        val viseme: AvatarViseme,
        val startMillis: Long,
        val durationMillis: Long,
        val stressed: Boolean
    )

    private val lock = Any()
    private var cursorMillis: Long = 0L

    /** Number of phonemes in the timeline. */
    val phonemeCount: Int get() = entries.size

    /** Whether the timeline carries any mouth movement at all. */
    val isUsable: Boolean get() = entries.isNotEmpty()

    /**
     * Moves the playback cursor. Called with the audio clock (e.g. `AudioTrack.playbackHeadPosition`
     * converted to millis) so the mouth stays locked to the voice.
     */
    fun seekMillis(positionMillis: Long) {
        synchronized(lock) {
            cursorMillis = positionMillis.coerceIn(0L, totalDurationMillis)
        }
    }

    /** Returns the viseme that should be visible at [positionMillis]. */
    fun visemeAt(positionMillis: Long): AvatarViseme {
        if (entries.isEmpty()) return AvatarViseme.NONE
        val t = positionMillis.coerceIn(0L, totalDurationMillis)
        // Linear scan is fine: a sentence has tens of phonemes, not thousands.
        for (entry in entries) {
            val end = entry.startMillis + entry.durationMillis
            if (t < end) {
                return entry.viseme
            }
        }
        return AvatarViseme.NONE
    }

    /** Returns the viseme for the current cursor position. */
    fun currentViseme(): AvatarViseme = synchronized(lock) { visemeAt(cursorMillis) }

    /**
     * Returns the mouth openness (0..1) implied by the viseme, so callers that only have an
     * amplitude-based renderer still get a sensible value.
     */
    fun currentMouthOpen(): Float = mouthOpenFor(currentViseme())

    /** Resets the cursor to the start. */
    fun reset() = seekMillis(0L)

    /**
     * Returns a copy whose total length matches [targetDurationMillis], scaling every entry.
     *
     * Used by the text-approximation path: the guessed durations are only proportional, so once
     * the player reports the real audio length the whole timeline is stretched to fit. A no-op
     * when [targetDurationMillis] is not positive or already matches.
     */
    fun scaledTo(targetDurationMillis: Long): AvatarPhonemeTimeline {
        if (targetDurationMillis <= 0L || entries.isEmpty() || totalDurationMillis <= 0L) {
            return this
        }
        if (targetDurationMillis == totalDurationMillis) {
            return this
        }
        val factor = targetDurationMillis.toDouble() / totalDurationMillis.toDouble()
        val scaled = ArrayList<Entry>(entries.size)
        var cursor = 0L
        for (entry in entries) {
            val duration = (entry.durationMillis * factor).roundToLong().coerceAtLeast(20L)
            scaled.add(
                Entry(
                    phoneme = entry.phoneme,
                    viseme = entry.viseme,
                    startMillis = cursor,
                    durationMillis = duration,
                    stressed = entry.stressed
                )
            )
            cursor += duration
        }
        return AvatarPhonemeTimeline(scaled, cursor)
    }

    companion object {

        /** Average speaking rate assumption used when the caller has no explicit rate. */
        private const val DEFAULT_RATE = 1.0f

        /** Base phoneme durations in milliseconds, before rate scaling. */
        private const val DUR_VOWEL = 90L
        private const val DUR_VOWEL_LONG = 130L
        private const val DUR_DIPHTHONG = 140L
        private const val DUR_PLOSIVE = 55L
        private const val DUR_FRICATIVE = 80L
        private const val DUR_NASAL = 75L
        private const val DUR_LIQUID = 65L
        private const val DUR_SILENCE = 90L
        private const val DUR_UNKNOWN = 60L

        /** Stress lengthens the vowel it marks. */
        private const val STRESS_FACTOR = 1.35f

        /**
         * Builds a timeline from an IPA string produced by espeak-ng.
         *
         * @param ipa the raw phoneme string (espeak output, e.g. `h@l'oU`)
         * @param rate speaking rate multiplier; >1 means faster speech
         */
        fun fromIpa(ipa: String?, rate: Float = DEFAULT_RATE): AvatarPhonemeTimeline {
            if (ipa.isNullOrBlank()) {
                return AvatarPhonemeTimeline(emptyList(), 0L)
            }
            val phonemes = tokenizeIpa(ipa)
            if (phonemes.isEmpty()) {
                return AvatarPhonemeTimeline(emptyList(), 0L)
            }
            val safeRate = if (rate.isFinite() && rate > 0f) rate else DEFAULT_RATE
            val entries = ArrayList<Entry>(phonemes.size)
            var cursor = 0L
            for (token in phonemes) {
                val base = baseDurationFor(token.phoneme)
                var duration = if (token.stressed) (base * STRESS_FACTOR).roundToLong() else base
                duration = (duration / safeRate).roundToLong().coerceAtLeast(20L)
                entries.add(
                    Entry(
                        phoneme = token.phoneme,
                        viseme = visemeFor(token.phoneme),
                        startMillis = cursor,
                        durationMillis = duration,
                        stressed = token.stressed
                    )
                )
                cursor += duration
            }
            return AvatarPhonemeTimeline(entries, cursor)
        }

        /**
         * Builds a *rough* timeline straight from the reply text, for playback paths that never
         * see phonemes (cloud TTS handed to a `MediaPlayer`).
         *
         * This is a deliberate approximation and is documented as such: latin vowels are mapped
         * directly, other letters become short closures, and CJK characters get a deterministic
         * pseudo-vowel derived from their code point so the mouth keeps moving instead of holding
         * one shape. When [durationMillis] is known (the player reports it) the whole timeline is
         * stretched to match the real audio length, which keeps it from drifting badly.
         */
        fun approximateFromText(
            text: String?,
            rate: Float = DEFAULT_RATE,
            durationMillis: Long = 0L
        ): AvatarPhonemeTimeline {
            if (text.isNullOrBlank()) {
                return AvatarPhonemeTimeline(emptyList(), 0L)
            }
            val safeRate = if (rate.isFinite() && rate > 0f) rate else DEFAULT_RATE
            val entries = ArrayList<Entry>()
            var cursor = 0L
            for (ch in text) {
                if (ch.isWhitespace()) {
                    cursor += DUR_SILENCE
                    continue
                }
                val lower = ch.lowercaseChar()
                val viseme = when {
                    lower in 'a'..'z' -> when (lower) {
                        'a' -> AvatarViseme.A
                        'i', 'y' -> AvatarViseme.I
                        'u', 'w' -> AvatarViseme.U
                        'e' -> AvatarViseme.E
                        'o' -> AvatarViseme.O
                        'm', 'n' -> AvatarViseme.N
                        // Consonants: a brief near-closed shape keeps the motion readable.
                        else -> AvatarViseme.I
                    }
                    // CJK / everything else: deterministic pseudo-vowel so speech keeps moving.
                    else -> PSEUDO_VISEMES[(ch.code % PSEUDO_VISEMES.size + PSEUDO_VISEMES.size) % PSEUDO_VISEMES.size]
                }
                val base = if (ch.code in 0x4E00..0x9FFF) DUR_VOWEL else baseDurationFor(ch.toString())
                val duration = (base / safeRate).roundToLong().coerceAtLeast(20L)
                entries.add(
                    Entry(
                        phoneme = ch.toString(),
                        viseme = viseme,
                        startMillis = cursor,
                        durationMillis = duration,
                        stressed = false
                    )
                )
                cursor += duration
            }
            if (entries.isEmpty()) {
                return AvatarPhonemeTimeline(emptyList(), 0L)
            }
            return AvatarPhonemeTimeline(entries, cursor).scaledTo(durationMillis)
        }

        /** Viseme rotation used for characters with no derivable vowel (CJK, emoji, digits). */
        private val PSEUDO_VISEMES = listOf(
            AvatarViseme.A, AvatarViseme.E, AvatarViseme.O, AvatarViseme.I, AvatarViseme.U
        )

        /** Builds a timeline that holds a single viseme for [durationMillis] (fallback path). */
        fun singleViseme(viseme: AvatarViseme, durationMillis: Long): AvatarPhonemeTimeline {
            if (viseme == AvatarViseme.NONE || durationMillis <= 0L) {
                return AvatarPhonemeTimeline(emptyList(), 0L)
            }
            val entry = Entry("", viseme, 0L, durationMillis, false)
            return AvatarPhonemeTimeline(listOf(entry), durationMillis)
        }

        /**
         * One phoneme plus its stress flag. Internal (not private) because [tokenizeIpa] is
         * internal and must not expose a less-visible type.
         */
        internal data class Token(val phoneme: String, val stressed: Boolean)

        /**
         * Splits an espeak IPA string into phoneme tokens.
         *
         * espeak separates words with spaces, marks primary stress with `'` and secondary
         * stress with `,`, and uses length marks (`ː`, `:`) plus combining diacritics
         * (U+0300..U+036F) which are folded into the preceding phoneme.
         */
        internal fun tokenizeIpa(ipa: String): List<Token> {
            val tokens = ArrayList<Token>()
            var pendingStress = false
            var i = 0
            while (i < ipa.length) {
                val ch = ipa[i]
                when {
                    ch.isWhitespace() -> i++
                    ch == '\'' -> {
                        pendingStress = true
                        i++
                    }
                    ch == ',' -> {
                        pendingStress = true
                        i++
                    }
                    // Combining diacritics attach to the previous phoneme.
                    ch.code in 0x0300..0x036F -> i++
                    // Length marks extend the previous phoneme.
                    ch == 'ː' || ch == ':' -> {
                        if (tokens.isNotEmpty()) {
                            val last = tokens.removeAt(tokens.size - 1)
                            tokens.add(last.copy(phoneme = last.phoneme + ch))
                        }
                        i++
                    }
                    else -> {
                        val start = i
                        i++
                        // Consume trailing diacritics / length marks as part of this phoneme.
                        while (i < ipa.length) {
                            val next = ipa[i]
                            if (next.code in 0x0300..0x036F || next == 'ː' || next == ':') {
                                i++
                            } else {
                                break
                            }
                        }
                        tokens.add(Token(ipa.substring(start, i), pendingStress))
                        pendingStress = false
                    }
                }
            }
            return tokens
        }

        private fun baseDurationFor(phoneme: String): Long {
            val core = phoneme.trimEnd('ː', ':')
            return when {
                core.isEmpty() -> DUR_UNKNOWN
                core == "_" || core == "#" -> DUR_SILENCE
                isDiphthong(core) -> DUR_DIPHTHONG
                isLongVowel(core) -> DUR_VOWEL_LONG
                isVowel(core) -> DUR_VOWEL
                isPlosive(core) -> DUR_PLOSIVE
                isFricative(core) -> DUR_FRICATIVE
                isNasal(core) -> DUR_NASAL
                isLiquid(core) -> DUR_LIQUID
                else -> DUR_UNKNOWN
            }
        }

        /**
         * Maps an IPA phoneme (optionally with a length mark) to a viseme.
         *
         * espeak emits ASCII stand-ins for some IPA symbols (`@`, `3`, `A`, `Q`, `V`, `I`,
         * `U`, `E`, `O`) so both spellings are handled.
         */
        internal fun visemeFor(phoneme: String): AvatarViseme {
            if (phoneme.isEmpty()) return AvatarViseme.NONE
            val core = phoneme.trimEnd('ː', ':')
            return when (core) {
                // Silence / word separator
                "_", "#", "-" -> AvatarViseme.NONE

                // Open / central vowels -> A
                "a", "ɑ", "A", "ʌ", "V", "ɐ", "æ", "ɶ", "ɒ" -> AvatarViseme.A

                // Front close vowels -> I
                "i", "I", "ɪ", "y", "ʏ", "ɨ" -> AvatarViseme.I

                // Back close vowels -> U
                "u", "U", "ʊ", "ɯ", "ʉ" -> AvatarViseme.U

                // Mid front vowels -> E
                "e", "E", "ɛ", "ø", "ɘ", "ə", "ɚ", "ɜ", "3", "@" -> AvatarViseme.E

                // Mid back / open back vowels -> O
                "o", "O", "ɔ", "ɵ", "ɤ", "Q" -> AvatarViseme.O

                // Nasals -> N
                "m", "n", "ŋ", "ɲ", "ɳ", "ɴ" -> AvatarViseme.N

                // Diphthongs: choose the opening that dominates visually
                "eɪ", "aɪ", "ɔɪ", "aʊ", "oʊ", "əʊ", "ɪə", "eə", "ʊə",
                "EI", "aI", "OI", "aU", "oU", "@U", "I@", "e@", "U@" -> AvatarViseme.A

                // Syllabic consonants behave like vowels visually
                "l̩", "n̩", "m̩" -> AvatarViseme.E

                else -> AvatarViseme.NONE
            }
        }

        private fun isVowel(core: String): Boolean = visemeFor(core) in VOWELS

        private fun isLongVowel(core: String): Boolean = core.endsWith("ː") || core.endsWith(":")

        private fun isDiphthong(core: String): Boolean = core.length >= 2 && core in DIPHTHONGS

        private fun isPlosive(core: String): Boolean =
            core.isNotEmpty() && core[0] in "pbtdkgcqɢʔ"

        private fun isFricative(core: String): Boolean =
            core.isNotEmpty() && (core[0] in "fszʃʒθðhxχħʕvwɸβ" || core.startsWith("tʃ") || core.startsWith("dʒ"))

        private fun isNasal(core: String): Boolean =
            core.isNotEmpty() && core[0] in "mnŋɲɳɴ"

        private fun isLiquid(core: String): Boolean =
            core.isNotEmpty() && core[0] in "lrɹɻʀʁʋɭʎjɥw"

        private val VOWELS = setOf(
            AvatarViseme.A, AvatarViseme.I, AvatarViseme.U, AvatarViseme.E, AvatarViseme.O
        )

        private val DIPHTHONGS = setOf(
            "eɪ", "aɪ", "ɔɪ", "aʊ", "oʊ", "əʊ", "ɪə", "eə", "ʊə",
            "EI", "aI", "OI", "aU", "oU", "@U", "I@", "e@", "U@"
        )

        /** Maps a viseme to a mouth openness used when only amplitude-free data is available. */
        fun mouthOpenFor(viseme: AvatarViseme): Float = when (viseme) {
            AvatarViseme.NONE -> 0f
            AvatarViseme.I -> 0.25f
            AvatarViseme.U -> 0.35f
            AvatarViseme.E -> 0.5f
            AvatarViseme.O -> 0.7f
            AvatarViseme.A -> 0.95f
            AvatarViseme.N -> 0.2f
        }
    }
}