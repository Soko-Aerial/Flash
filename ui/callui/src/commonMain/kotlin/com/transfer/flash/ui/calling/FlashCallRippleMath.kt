package com.transfer.flash.ui.calling

/**
 * Task 3.5: Pure calculations for dynamic speaking ripples and audio energy feedback.
 *
 * Free of Compose types so vocal dynamic scaling and border modulation are unit-testable.
 */
public object FlashCallRippleMath {
    public const val RESTING_SCALE: Float = 1.0f
    public const val MAX_SPEAKING_SCALE: Float = 1.22f
    public const val MID_SPEAKING_SCALE: Float = 1.14f

    public const val RESTING_ALPHA_ACTIVE: Float = 0.05f
    public const val RESTING_ALPHA_RINGING: Float = 0.08f
    public const val SPEAKING_ALPHA: Float = 0.16f

    /**
     * Calculates the modulated outer glow scale during speaking.
     * When speaking, expands up to 1.22f scaled with base pulse.
     */
    public fun computeOuterGlowScale(isSpeaking: Boolean, pulseValue: Float): Float {
        val base = pulseValue.coerceIn(1.0f, 1.08f)
        return if (isSpeaking) {
            // Scales between ~1.14f and 1.22f with natural vocal bounce
            1.14f + (base - 1.0f) * (MAX_SPEAKING_SCALE - 1.14f) / 0.08f
        } else {
            base * 1.06f
        }
    }

    /**
     * Calculates the middle glow scale.
     */
    public fun computeMiddleGlowScale(isSpeaking: Boolean, pulseValue: Float): Float {
        val base = pulseValue.coerceIn(1.0f, 1.08f)
        return if (isSpeaking) {
            1.06f + (base - 1.0f) * (MID_SPEAKING_SCALE - 1.06f) / 0.08f
        } else {
            base
        }
    }

    /**
     * Computes the speaker border ring alpha in group video tiles.
     */
    public fun speakerBorderAlpha(isSpeaking: Boolean, animationFraction: Float): Float {
        if (!isSpeaking) return 0f
        return (0.45f + 0.55f * animationFraction.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }
}
