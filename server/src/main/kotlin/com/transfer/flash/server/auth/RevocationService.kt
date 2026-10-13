@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.auth

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.RevocationList

/**
 * Hub-side revocation state (SEC-05, ADR-110/111). The contract other server components code against.
 *
 * - [isRevoked]: authoritative check used by authentication, the bearer store and the relay. A null [serial] checks
 *   the device id only (a device that was revoked by id is revoked for every certificate it ever held).
 * - [current]: the latest SIGNED [RevocationList] (what `GET /api/v1/revocations` serves), or null when nothing was
 *   ever revoked / published.
 * - [onRevoked]: a handler invoked once per newly revoked device (the relay uses it to kick live sockets). Handlers
 *   run after the revocation is durable; an exception in one handler never prevents the others.
 */
interface RevocationService {
    fun isRevoked(serial: Long?, deviceId: String): Boolean

    fun current(): RevocationList?

    fun onRevoked(handler: suspend (deviceId: String, serial: Long?, reason: String) -> Unit)

    /**
     * True when the organisation root revoked the DELEGATION with [delegationSerial] (REV-04): the hub signing key it names is
     * compromised or retired, so nothing it signed is trusted and the hub must not issue under it.
     */
    fun isDelegationRevoked(delegationSerial: Long): Boolean = false

    /** A [RevocationService] that revokes nothing (tests, and the wiring before the real service exists). */
    object None : RevocationService {
        override fun isRevoked(serial: Long?, deviceId: String): Boolean = false
        override fun current(): RevocationList? = null
        override fun onRevoked(handler: suspend (deviceId: String, serial: Long?, reason: String) -> Unit) = Unit
    }
}
