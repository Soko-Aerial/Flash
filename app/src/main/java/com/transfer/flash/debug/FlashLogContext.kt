@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.debug

import android.os.Build
import android.os.SystemClock
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.di.AppEngine
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Fills [FlashProbe]'s export header and snapshot for this process (docs/testing/PROBES.md, "session header").
 *
 * The header pins what a log reader cannot otherwise know: which build and phone wrote the file, this device's id, the
 * clock offset, and the switches that change behaviour (swarm). The snapshot is the live state at the moment of export:
 * each connected peer with the features it advertised and whether it is paired, and each group with this device's role
 * and every member's paired / vouched / online state. ERROR-117 could not be settled from the logs because exactly these
 * facts (peer `sw1`, who was paired with whom) were missing.
 *
 * Ids are shortened by [FlashProbe.short]; no key, secret, fingerprint or message text is read here.
 */
internal object FlashLogContext {

    /** The header and snapshot lines for an export. Blocks (it reads Room and DataStore), so call it off the main thread. */
    fun exportLines(engine: AppEngine, versionName: String, wallClockMs: Long): List<String> {
        install(engine, versionName)
        return FlashProbe.exportHeader(wallClockMs)
    }

    private fun install(engine: AppEngine, versionName: String) {
        val swarmOn = runCatching { runBlocking { engine.settingsStore.groupSwarmEnabled.first() } }.getOrNull()
        FlashProbe.setHeaderFields(
            listOf(
                "app" to "android",
                "build" to versionName,
                "os" to Build.VERSION.RELEASE,
                "api" to Build.VERSION.SDK_INT,
                "device" to (Build.MANUFACTURER + " " + Build.MODEL),
                "local" to FlashProbe.short(engine.localDeviceId),
                "tzOffsetMin" to (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000),
                "processUptimeMs" to (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()),
                "swarm" to swarmOn,
            ),
        )
        FlashProbe.setSnapshotProvider { snapshot(engine) }
    }

    private fun snapshot(engine: AppEngine): List<String> {
        val lines = mutableListOf<String>()
        val paired = engine.pairing?.trustedPeers?.value.orEmpty().mapTo(HashSet()) { it.id }
        val sessions = engine.network?.activeSessions?.value.orEmpty()
        lines += FlashProbe.format("snapshot.sessions", listOf("count" to sessions.size, "paired" to paired.size))
        for ((id, session) in sessions) {
            lines += FlashProbe.format(
                "snapshot.session",
                listOf(
                    "peer" to FlashProbe.short(id.value),
                    "paired" to (id.value in paired),
                    "features" to session.peer.features.sorted().joinToString(",").ifEmpty { null },
                ),
            )
        }
        val chats = engine.chats ?: return lines
        val groups = chats.chatListState.value.let { it.items + it.archivedItems }.filter { it.isGroup }
        lines += FlashProbe.format("snapshot.groups", listOf("count" to groups.size))
        for (group in groups) {
            val members = runBlocking { chats.groupMembers(group.id) }
            val me = members.firstOrNull { it.id == engine.localDeviceId }
            lines += FlashProbe.format(
                "snapshot.group",
                listOf(
                    "group" to FlashProbe.short(group.id),
                    "proto" to (if (group.id.startsWith("g2-")) "v2" else "legacy"),
                    "me" to me?.role?.name,
                    "members" to members.size,
                    "online" to members.count { it.isOnline },
                    "vouched" to members.count { it.introducedBy != null },
                ),
            )
            for (member in members) {
                if (member.id == engine.localDeviceId) continue
                lines += FlashProbe.format(
                    "snapshot.member",
                    listOf(
                        "group" to FlashProbe.short(group.id),
                        "member" to FlashProbe.short(member.id),
                        "role" to member.role.name,
                        "paired" to (member.id in paired),
                        "vouched" to (member.introducedBy != null),
                        "online" to member.isOnline,
                        "session" to (FlashDeviceId(member.id) in sessions),
                    ),
                )
            }
        }
        return lines
    }
}
