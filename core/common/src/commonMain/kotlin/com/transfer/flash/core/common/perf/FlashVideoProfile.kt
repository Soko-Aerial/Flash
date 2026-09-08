package com.transfer.flash.core.common.perf

/**
 * Camera capture and video-encode envelope for one [FlashPerformanceMode].
 *
 * ## Why capture size is a tier knob at all
 *
 * The stack already sheds video quality under load two independent ways: WebRTC's own
 * `MAINTAIN_FRAMERATE` degradation preference lowers resolution when congestion control or the
 * CPU quality-scaler says the target is unaffordable, and Flash's `CallQualityGovernor` trades
 * rungs of picture away to protect speech. Both act on the *encoder*, and both are reactive.
 *
 * Neither helps the device this tiering exists for, because on that device the expensive part
 * happens **before** the encoder: `getUserMedia` opens the camera at the requested size, the
 * capturer delivers frames at that size, and every frame is scaled and colour-converted on the
 * CPU on its way in. A 2 GB handset asked for 1920x1080 at 30 fps pays for 62 Mpixel/s of
 * capture-side work whether or not a single byte of it survives the encoder's decision to send
 * 360p. Capping the *request* is the only way to not pay it — and it costs nothing visually
 * here, because that handset's own display is 480x640.
 *
 * So the ladder is: this profile sets the ceiling, and the two adaptive mechanisms operate
 * underneath it exactly as before.
 *
 * @param captureWidth requested capture width in pixels. The camera enumerator snaps this to
 *   the nearest format the hardware actually offers, so an unusual value degrades rather than
 *   fails.
 * @param captureHeight requested capture height in pixels.
 * @param captureFps requested capture frame rate, and the sender's `maxFramerate`.
 * @param maxBitrateKbps sender ceiling and `x-google-max-bitrate`. Congestion control stays
 *   free to use less.
 * @param minBitrateKbps the floor at which the encoder stops lowering bitrate and starts
 *   lowering resolution instead.
 * @param startBitrateKbps seeds the bandwidth estimate (`x-google-start-bitrate`) so the first
 *   second of a call is not a smear. Deliberately below [maxBitrateKbps]: the opening burst is
 *   shared with the voice stream it is supposed to leave room for.
 */
public data class FlashVideoProfile(
    public val captureWidth: Int,
    public val captureHeight: Int,
    public val captureFps: Int,
    public val maxBitrateKbps: Int,
    public val minBitrateKbps: Int,
    public val startBitrateKbps: Int,
) {
    /** Short form for logs and the settings subtitle, e.g. `"540p24"`. */
    public val label: String get() = "${captureHeight}p$captureFps"

    public companion object {
        /**
         * 360p15 at 350 kbit/s.
         *
         * Below the 540p the field report asked for, on purpose: this tier covers hardware with
         * no usable hardware encoder, where the ceiling that matters is pixels/second rather
         * than bits/second. 15 fps halves that again and is still readable for a talking head —
         * and `MAINTAIN_FRAMERATE` means the encoder will hold those 15 rather than stutter.
         */
        public val LOW: FlashVideoProfile = FlashVideoProfile(
            captureWidth = 480,
            captureHeight = 360,
            captureFps = 15,
            maxBitrateKbps = 350,
            minBitrateKbps = 100,
            startBitrateKbps = 200,
        )

        /**
         * 540p24 at 900 kbit/s — the ceiling named in the field report.
         *
         * 24 rather than 30 because the saving is real (20% fewer frames to capture, convert and
         * encode) and the difference is not visible on a video call. 900 kbit/s is sized for a
         * shared 2.4 GHz link with a voice stream on it, not for the camera.
         */
        public val MEDIUM: FlashVideoProfile = FlashVideoProfile(
            captureWidth = 960,
            captureHeight = 540,
            captureFps = 24,
            maxBitrateKbps = 900,
            minBitrateKbps = 250,
            startBitrateKbps = 500,
        )

        /**
         * 1080p30 at 2.5 Mbit/s — the stack's existing numbers, unchanged, so that this tier is
         * provably a no-op against the pre-tiering build.
         */
        public val HIGH: FlashVideoProfile = FlashVideoProfile(
            captureWidth = 1920,
            captureHeight = 1080,
            captureFps = 30,
            maxBitrateKbps = 2_500,
            minBitrateKbps = 600,
            startBitrateKbps = 1_200,
        )
    }
}
