package com.transfer.flash.core.transfer.model

/**
 * High-level wait reason exposed to UI for queued transfers (§5.5, ADR-072).
 */
public enum class FlashTransferWaitReason {
    WaitingForSender,
    WaitingForHolders,
    WaitingForNetwork,
    WaitingForSpace,
    WaitingForStorage,
    WaitingForSystem,
    WaitingForSession,
}
