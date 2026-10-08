@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop.linux

import com.transfer.flash.core.common.logging.FlashLog
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/** The one method of `org.freedesktop.Notifications` Flash uses. Specification: https://specifications.freedesktop.org/notification-spec/latest/ */
@DBusInterfaceName("org.freedesktop.Notifications")
@JvmSuppressWildcards
internal interface FreedesktopNotifications : DBusInterface {
    // Not called `notify`: that is Object.notify(), which a Kotlin interface cannot redeclare.
    @DBusMemberName("Notify")
    fun post(
        appName: String,
        replacesId: UInt32,
        appIcon: String,
        summary: String,
        body: String,
        actions: List<String>,
        hints: Map<String, Variant<*>>,
        expireTimeout: Int,
    ): UInt32
}

/**
 * Native Linux notifications (Linux plan L2, ADR-093): the desktop's own notification service over D-Bus, so a message
 * looks like every other banner and is kept in the shade. The Compose tray path (`TrayIcon.displayMessage`) shows an
 * unstyled "Java" popup, and nothing at all where the desktop has no tray (GNOME without an extension).
 *
 * Falls back to the `notify-send` command, and then reports `false` so the caller can use the tray path.
 * **Not run on a real desktop yet**: device test `LNX-04`.
 */
internal class LinuxNotifier(
    private val postDbus: (summary: String, body: String, critical: Boolean) -> Boolean = DbusNotificationPoster()::post,
    private val postCli: (summary: String, body: String, critical: Boolean) -> Boolean = ::notifySend,
) {
    /** `true` when a notification service accepted it. */
    fun send(title: String, message: String, critical: Boolean = false): Boolean {
        val summary = title.ifBlank { APP_NAME }
        val body = escapeBody(message)
        if (runCatching { postDbus(summary, body, critical) }
                .onFailure { FlashLog.i(TAG, "D-Bus notification failed: ${it.message}") }
                .getOrDefault(false)
        ) {
            return true
        }
        return runCatching { postCli(summary, body, critical) }
            .onFailure { FlashLog.i(TAG, "notify-send failed: ${it.message}") }
            .getOrDefault(false)
    }

    companion object {
        const val APP_NAME = "Flash"
        const val ICON_NAME = "flash"
        private const val TAG = "DesktopNotify"

        /**
         * The body is markup in the specification (`<b>`, `<i>`, `<u>`, `<a>`, `&`): a message with `<` or `&` in it
         * would otherwise be cut short or rejected. Only these three characters need escaping.
         */
        fun escapeBody(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun notifySend(summary: String, body: String, critical: Boolean): Boolean {
            val process = ProcessBuilder(
                "notify-send", "-a", APP_NAME, "-i", ICON_NAME, "-u", if (critical) "critical" else "normal",
                "--", summary, body,
            ).redirectErrorStream(true).start()
            return process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0
        }
    }
}

/** Posts over one lazily opened session-bus connection, reopened after a failure. */
internal class DbusNotificationPoster {
    private var connection: DBusConnection? = null
    private var service: FreedesktopNotifications? = null

    @Synchronized
    fun post(summary: String, body: String, critical: Boolean): Boolean {
        try {
            val proxy = service ?: open()
            val hints = HashMap<String, Variant<*>>()
            hints["desktop-entry"] = Variant("flash")
            hints["urgency"] = Variant((if (critical) 2 else 1).toByte())
            proxy.post(
                LinuxNotifier.APP_NAME, UInt32(0), LinuxNotifier.ICON_NAME, summary, body, emptyList(), hints, -1,
            )
            return true
        } catch (t: Throwable) {
            closeConnection()
            throw t
        }
    }

    private fun open(): FreedesktopNotifications {
        val bus = DBusConnectionBuilder.forSessionBus().build()
        connection = bus
        return bus.getRemoteObject(
            "org.freedesktop.Notifications", "/org/freedesktop/Notifications", FreedesktopNotifications::class.java,
        ).also { service = it }
    }

    private fun closeConnection() {
        runCatching { connection?.close() }
        connection = null
        service = null
    }
}
