package com.transfer.flash.core.messaging

/**
 * Capability-based gate classification policy (Plan B, ADR-134).
 * Replaces blanket [isTrustedPeer] checks with distinct capability predicates:
 * - Direct 1:1 chat, reactions, receipts, calling rings admit ORG colleagues.
 * - High-privilege operations (PTT audio, group admin, legacy group creation, vouching) strictly require PAIRED.
 */
public object PeerTrustPolicy {

    /** Direct 1:1 chat text, delivery/read receipts, typing indicators, and inbound message storage. */
    public fun mayChat(isPaired: Boolean, isOrg: Boolean): Boolean = isPaired || isOrg

    /** Direct 1:1 reactions (emoji reactions on direct messages). */
    public fun mayReact(isPaired: Boolean, isOrg: Boolean): Boolean = isPaired || isOrg

    /** Inbound and outbound audio/video call ringing (shows Colleague chip; no auto-answer). */
    public fun mayRing(isPaired: Boolean, isOrg: Boolean): Boolean = isPaired || isOrg

    /** Push-to-talk audio transmission and playback. Strictly PAIRED only (PTT plays audio immediately). */
    public fun mayPushToTalk(isPaired: Boolean, isOrg: Boolean): Boolean = isPaired

    /** Creating legacy/local ad-hoc groups. Strictly PAIRED only (hub groups handled separately in Phase B4). */
    public fun mayCreateGroupWith(isPaired: Boolean, isOrg: Boolean): Boolean = isPaired

    /** Adding members to legacy/local ad-hoc groups. Strictly PAIRED only. */
    public fun mayAddGroupMember(isPaired: Boolean, isOrg: Boolean): Boolean = isPaired

    /** Group administration, join decisions, and invites. Strictly PAIRED only. */
    public fun mayAdministerGroup(isPaired: Boolean, isOrg: Boolean): Boolean = isPaired

    /** P2P Swarm serving and file push. PAIRED only (or vouched/active group member). */
    public fun mayServeSwarm(isPaired: Boolean, isOrg: Boolean): Boolean = isPaired

    /** Vouching for another peer's key. Strictly PAIRED only. */
    public fun mayVouch(isPaired: Boolean, isOrg: Boolean): Boolean = isPaired
}
