package com.ai.assistance.operit.core.avatar.common.state

/**
 * Operit patch: the single meeting point between the audio pipeline and the avatar renderer.
 *
 * The audio side (a TTS provider that owns the PCM stream) publishes:
 *
 *  * the [AvatarPhonemeTimeline] built from the phonemes of the sentence being spoken, and
 *  * the playback position, so the timeline advances with the *audio clock* rather than a
 *    free-running timer;
 *
 * while the avatar side polls [currentViseme] / [currentMouthOpen] / [currentLevel] once per
 * frame. Neither side needs a reference to the other, which keeps the voice providers free of
 * Compose/UI dependencies and lets any renderer (MMD today, something else tomorrow) consume
 * the same data.
 *
 * All state is volatile / synchronized, so [publishPositionMillis] may be called from the audio
 * thread while the render loop reads.
 */
object AvatarLipSyncBus {

    @Volatile
    private var timeline: AvatarPhonemeTimeline? = null

    /** The timeline currently being spoken, or null when nothing is playing. */
    fun activeTimeline(): AvatarPhonemeTimeline? = timeline

    /** True while a timeline is installed (i.e. speech is in progress). */
    val isActive: Boolean get() = timeline != null

    /**
     * Installs a timeline built from a real G2P pass. Called by providers that know the
     * phoneme string (local VITS, which already runs espeak-ng to feed the model).
     */
    fun publishTimeline(newTimeline: AvatarPhonemeTimeline?) {
        timeline = newTimeline?.takeIf { it.isUsable }
        if (timeline == null) {
            AvatarAudioMeter.reset()
        }
    }

    /** Convenience overload that builds the timeline from an IPA string. */
    fun publishIpa(ipa: String?, rate: Float = 1.0f) {
        publishTimeline(AvatarPhonemeTimeline.fromIpa(ipa, rate))
    }

    /**
     * Installs an approximate timeline derived from the reply text. Used by playback paths
     * that hand raw audio to a [android.media.MediaPlayer] and therefore never expose PCM
     * samples or phonemes (cloud TTS over HTTP).
     */
    fun publishApproximateText(text: String?, rate: Float = 1.0f, durationMillis: Long = 0L) {
        publishTimeline(AvatarPhonemeTimeline.approximateFromText(text, rate, durationMillis))
    }

    /** Advances the cursor. Called with the audio clock, in milliseconds. */
    fun publishPositionMillis(positionMillis: Long) {
        timeline?.seekMillis(positionMillis)
    }

    /** The viseme that should be visible right now. */
    fun currentViseme(): AvatarViseme = timeline?.currentViseme() ?: AvatarViseme.NONE

    /** Mouth openness (0..1) implied by the current viseme. */
    fun currentMouthOpen(): Float = timeline?.currentMouthOpen() ?: 0f

    /**
     * Live amplitude (0..1) from [AvatarAudioMeter], i.e. the real PCM energy when the audio
     * path could report it.
     */
    fun currentLevel(): Float = AvatarAudioMeter.currentLevel()

    /** Clears the bus, e.g. when playback stops or is interrupted. */
    fun reset() {
        timeline = null
        AvatarAudioMeter.reset()
    }
}
