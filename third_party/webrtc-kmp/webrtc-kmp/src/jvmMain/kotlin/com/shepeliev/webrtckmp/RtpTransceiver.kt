package com.shepeliev.webrtckmp

import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCRtpTransceiverDirection

actual class RtpTransceiver(
    val native: RTCRtpTransceiver,
    private val senderTrack: MediaStreamTrack?,
    private val receiverTrack: MediaStreamTrack?,
    // Flash: the connection that disposes the sender/receiver instances made below
    // (webrtc-java 0.18+; see PeerConnection.own). Null only for a caller-built wrapper.
    private val owner: PeerConnection? = null,
) {

    actual var direction: RtpTransceiverDirection
        get() = native.direction.asCommon()
        set(value) {
            native.direction = value.asNative()
        }

    actual val currentDirection: RtpTransceiverDirection?
        get() = native.currentDirection?.asCommon()

    actual val mid: String
        get() = native.mid

    actual val sender: RtpSender
        get() = RtpSender(native.sender.let { owner?.own(it) ?: it }, senderTrack)

    actual val receiver: RtpReceiver
        get() = RtpReceiver(native.receiver.let { owner?.own(it) ?: it }, receiverTrack)

    actual val stopped: Boolean
        get() = native.stopped()

    actual fun stop() = native.stop()
}

private fun RTCRtpTransceiverDirection.asCommon(): RtpTransceiverDirection {
    return when (this) {
        RTCRtpTransceiverDirection.SEND_RECV -> RtpTransceiverDirection.SendRecv
        RTCRtpTransceiverDirection.SEND_ONLY -> RtpTransceiverDirection.SendOnly
        RTCRtpTransceiverDirection.RECV_ONLY -> RtpTransceiverDirection.RecvOnly
        RTCRtpTransceiverDirection.INACTIVE -> RtpTransceiverDirection.Inactive
        RTCRtpTransceiverDirection.STOPPED -> RtpTransceiverDirection.Stopped
    }
}

internal fun RtpTransceiverDirection.asNative(): RTCRtpTransceiverDirection {
    return when (this) {
        RtpTransceiverDirection.SendRecv -> RTCRtpTransceiverDirection.SEND_RECV
        RtpTransceiverDirection.SendOnly -> RTCRtpTransceiverDirection.SEND_ONLY
        RtpTransceiverDirection.RecvOnly -> RTCRtpTransceiverDirection.RECV_ONLY
        RtpTransceiverDirection.Inactive -> RTCRtpTransceiverDirection.INACTIVE
        RtpTransceiverDirection.Stopped -> RTCRtpTransceiverDirection.INACTIVE
    }
}
