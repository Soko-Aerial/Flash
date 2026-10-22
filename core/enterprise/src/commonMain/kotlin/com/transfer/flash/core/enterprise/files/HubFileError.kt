package com.transfer.flash.core.enterprise.files

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashError

/**
 * Why a sealed-file call or transfer did not succeed (ADR-215, `docs/plans/HUB-FILES-PLAN.md` sections 2 and 6).
 *
 * [transient] decides what the transfer engine does: a transient error is retried with backoff and, when the retry budget
 * runs out, ends the run as *interrupted* with the progress kept on disk, so the next run (a new network, a later time)
 * continues where this one stopped. A non-transient error ends the transfer and removes its local state.
 */
@FlashInternalApi
public sealed interface HubFileError {
    public val transient: Boolean

    /** No answer: no network, DNS, TLS, a reset, or a request that took longer than the policy's timeout. */
    public data object Offline : HubFileError {
        override val transient: Boolean = true
    }

    /** The hub asked to slow down (`429 ERR_RATE_LIMIT`). */
    public data class RateLimited(public val retryAfterSeconds: Int) : HubFileError {
        override val transient: Boolean = true
    }

    /** The hub knows the file but not its bytes yet (`503`, a restore without `data/files`). */
    public data object Unavailable : HubFileError {
        override val transient: Boolean = true
    }

    /** A 5xx, or a success status whose body did not parse. */
    public data class Server(public val status: Int) : HubFileError {
        override val transient: Boolean = true
    }

    /** A recipient asked before the sender finished (`409` on a read). The key message can arrive first. */
    public data object NotReady : HubFileError {
        override val transient: Boolean = true
    }

    /** There is not enough free space on this device for the rest of the file. Freeing space and retrying continues it. */
    public data class NoSpace(public val neededBytes: Long, public val usableBytes: Long) : HubFileError {
        override val transient: Boolean = true
    }

    /** The URL is not the hub this device is enrolled with. */
    public data object NotThisHub : HubFileError {
        override val transient: Boolean = false
    }

    /** Cleartext without the debug opt-in, or not a URL. */
    public data object InsecureAddress : HubFileError {
        override val transient: Boolean = false
    }

    /** No bearer, or `401` after a refresh: unenrolled or revoked. */
    public data object SignedOut : HubFileError {
        override val transient: Boolean = false
    }

    /** The hub carries no files (`maxFileBytes == 0`, an older hub, or no `/api/v1/files`). */
    public data object HubTooOld : HubFileError {
        override val transient: Boolean = false
    }

    /** Larger than the hub accepts. [limitBytes] is the plaintext limit when known. */
    public data class TooLarge(public val limitBytes: Long?) : HubFileError {
        override val transient: Boolean = false
    }

    /** An empty file: the LAN path handles it (situation 11). */
    public data object Empty : HubFileError {
        override val transient: Boolean = false
    }

    /** A device or hub storage allowance, or the file count, is used up (`429 ERR_QUOTA_EXCEEDED`). */
    public data object HubFull : HubFileError {
        override val transient: Boolean = false
    }

    /** The named device or group is not in this organisation. */
    public data object UnknownRecipient : HubFileError {
        override val transient: Boolean = false
    }

    /** `403`: not a recipient (or no longer, after a group removal), or not the sender of this upload. */
    public data object Forbidden : HubFileError {
        override val transient: Boolean = false
    }

    /** `404`: the file is not (or no longer) on the hub: expired, cancelled, or never completed. */
    public data object NotFound : HubFileError {
        override val transient: Boolean = false
    }

    /** `409` the engine could not resolve: the id exists with other details, or a chunk with other bytes. */
    public data object Conflict : HubFileError {
        override val transient: Boolean = false
    }

    /** `400`: a request this client should never have built. [code] is the hub's error code, for the log. */
    public data class Refused(public val code: String) : HubFileError {
        override val transient: Boolean = false
    }

    /**
     * What arrived is not what the sender sealed: a chunk that does not open, a body of the wrong size, metadata that
     * disagrees with the sealed message, or a whole-file hash mismatch after one full re-download.
     */
    public data object Integrity : HubFileError {
        override val transient: Boolean = false
    }

    /** The file being sent changed on disk between two runs of the same upload. */
    public data object SourceChanged : HubFileError {
        override val transient: Boolean = false
    }

    /** The file could not be read, or written, on this device. [what] names the operation for the log, never a path. */
    public data class LocalStorage(public val what: String, override val transient: Boolean = false) : HubFileError {
    }

    /** The exact user-facing sentence (`docs/plans/HUB-FILES-PLAN.md` section 6), as a [FlashError.Domain]. */
    public fun toFlashError(senderName: String = ""): FlashError =
        FlashError.Domain(code = this::class.simpleName ?: "HUB_FILE", message = HubFileText.sentence(this, senderName), details = this)
}

/** The result of one [HubFilesApi] call. */
@FlashInternalApi
public sealed interface HubFileResult<out T> {
    public data class Ok<T>(public val value: T) : HubFileResult<T>

    public data class Err(public val error: HubFileError) : HubFileResult<Nothing>
}

/**
 * The sentences a user may see for a hub file (`docs/plans/HUB-FILES-PLAN.md` section 6, the only allowed copy). The UI
 * (P3) formats sizes and names; this object keeps the words in one place both hosts share.
 */
@FlashInternalApi
public object HubFileText {
    public const val SENDING: String = "Sending through the hub…"
    public const val WAITING: String = "Waiting for the hub to accept the file."
    public const val HUB_FULL: String = "The hub is full. Try again later, or ask an administrator to raise the storage limit."
    public const val NOT_A_RECIPIENT: String = "You are not on this file's recipient list."
    public const val KEY_NOT_YET: String = "The file details are still arriving."
    public const val HUB_OLD: String = "This hub cannot carry files yet. Ask an administrator to update it."

    public fun tooLarge(sizeText: String, limitText: String): String =
        "This file is $sizeText, and the hub accepts up to $limitText per file. An administrator can raise the limit in the hub console."

    /** `HUB_FILE_DOWNLOADING`; the bubble appends the percentage itself (deviation D15). */
    public const val DOWNLOADING: String = "Receiving through the hub…"

    /** The Transfers screen's connection label for a hub row (`HUB_FILE_VIA_HUB`). */
    public const val VIA_HUB: String = "Through the hub"

    public fun expired(senderName: String): String = "This file is no longer on the hub. Ask ${senderName.ifBlank { "the sender" }} to send it again."

    /** An upload that kept being interrupted for the hub's whole 24 hour upload window (C-07). */
    public const val UPLOAD_WINDOW_ENDED: String =
        "This file did not reach the hub within 24 hours, so the upload was stopped. Tap Retry to try again, or send it again."

    public fun failed(reason: String): String = "The hub could not take this file. $reason"

    public fun needsSealing(name: String): String = "Files can only go through the hub when content sealing is on for $name."

    /** The `{reason}` of [failed] for each error (plan section 6, `HUB_FILE_REASON_*`). */
    public fun reason(error: HubFileError): String = when (error) {
        HubFileError.Offline -> "The hub cannot be reached. It will continue when the connection is back."
        is HubFileError.RateLimited -> "The hub asked to slow down. It will continue in a moment."
        HubFileError.Unavailable, is HubFileError.Server -> "The hub had a problem. It will try again."
        HubFileError.NotReady -> KEY_NOT_YET
        is HubFileError.NoSpace -> "There is not enough free space on this device. Free some space and it will continue."
        HubFileError.NotThisHub, HubFileError.InsecureAddress -> "This device is not signed in to that hub."
        HubFileError.SignedOut -> "This device is no longer signed in to the hub."
        HubFileError.HubTooOld -> HUB_OLD
        is HubFileError.TooLarge -> "The file is larger than the hub accepts."
        HubFileError.Empty -> "The file is empty."
        HubFileError.HubFull -> HUB_FULL
        HubFileError.UnknownRecipient -> "The recipient is not part of this organisation."
        HubFileError.Forbidden -> NOT_A_RECIPIENT
        HubFileError.NotFound -> "The file is no longer on the hub."
        HubFileError.Conflict -> "The hub already has a different file with this id. Send it again."
        is HubFileError.Refused -> "The hub refused the request. Send it again."
        HubFileError.Integrity -> "The file that arrived is not the file that was sent, so it was discarded."
        HubFileError.SourceChanged -> "The file changed while it was being sent. Send it again."
        is HubFileError.LocalStorage -> "The file could not be read or saved on this device."
    }

    /** One sentence for [error] alone, for callers that have no richer formatting (sizes, names). */
    public fun sentence(error: HubFileError, senderName: String = ""): String = when (error) {
        HubFileError.HubTooOld -> HUB_OLD
        HubFileError.HubFull -> HUB_FULL
        HubFileError.Forbidden -> NOT_A_RECIPIENT
        HubFileError.NotReady -> KEY_NOT_YET
        HubFileError.NotFound -> expired(senderName)
        else -> failed(reason(error))
    }
}
