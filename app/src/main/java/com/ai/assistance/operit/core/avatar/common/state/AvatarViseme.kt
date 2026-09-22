package com.ai.assistance.operit.core.avatar.common.state

/**
 * Operit patch: renderer-independent viseme slot used for phoneme-driven lip sync.
 *
 * [NONE] means "do not drive a vowel mouth shape", which renderers should treat as a
 * closed mouth. Keeping this enum free of any MMD-specific type lets every renderer
 * consume the same realtime state.
 */
enum class AvatarViseme {
    NONE,
    A,
    I,
    U,
    E,
    O,
    N
}
