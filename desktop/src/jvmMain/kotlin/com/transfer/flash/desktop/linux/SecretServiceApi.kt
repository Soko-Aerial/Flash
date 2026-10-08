package com.transfer.flash.desktop.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

/**
 * The slice of the freedesktop.org Secret Service API (`org.freedesktop.Secret.*`) that Flash needs to keep one
 * master key in the user's keyring. Specification: https://specifications.freedesktop.org/secret-service-spec/latest/
 * (checked 2026-10-08). Declared as dbus-java interfaces; method and signal names match the D-Bus member names.
 *
 * **Verified only by compiling and by unit tests against fakes.** No Secret Service was available on the machine that
 * wrote this; the first real run is device test `LNX-02`.
 */
internal object SecretServiceApi {

    const val BUS_NAME = "org.freedesktop.secrets"
    const val SERVICE_PATH = "/org/freedesktop/secrets"
    const val NO_OBJECT = "/"
    const val ITEM_LABEL = "org.freedesktop.Secret.Item.Label"
    const val ITEM_ATTRIBUTES = "org.freedesktop.Secret.Item.Attributes"

    /** `(oayays)`: session, parameters, value, content type. */
    @JvmSuppressWildcards
    class SecretStruct(
        @field:Position(0) val session: DBusPath,
        @field:Position(1) val parameters: ByteArray,
        @field:Position(2) val value: ByteArray,
        @field:Position(3) val contentType: String,
    ) : Struct()

    /** `OpenSession` outputs `(v, o)`. */
    @JvmSuppressWildcards
    class OpenSessionResult(
        @field:Position(0) val output: Variant<*>,
        @field:Position(1) val session: DBusPath,
    ) : Tuple()

    /** `SearchItems` outputs `(ao, ao)`. */
    @JvmSuppressWildcards
    class SearchResult(
        @field:Position(0) val unlocked: List<DBusPath>,
        @field:Position(1) val locked: List<DBusPath>,
    ) : Tuple()

    /** `Unlock` outputs `(ao, o)`. */
    @JvmSuppressWildcards
    class UnlockResult(
        @field:Position(0) val unlocked: List<DBusPath>,
        @field:Position(1) val prompt: DBusPath,
    ) : Tuple()

    /** `CreateItem` outputs `(o, o)`. */
    @JvmSuppressWildcards
    class CreateItemResult(
        @field:Position(0) val item: DBusPath,
        @field:Position(1) val prompt: DBusPath,
    ) : Tuple()

    @DBusInterfaceName("org.freedesktop.Secret.Service")
    @JvmSuppressWildcards
    interface Service : DBusInterface {
        @DBusMemberName("OpenSession")
        fun openSession(algorithm: String, input: Variant<*>): OpenSessionResult

        @DBusMemberName("SearchItems")
        fun searchItems(attributes: Map<String, String>): SearchResult

        @DBusMemberName("Unlock")
        fun unlock(objects: List<DBusPath>): UnlockResult

        @DBusMemberName("ReadAlias")
        fun readAlias(name: String): DBusPath
    }

    @DBusInterfaceName("org.freedesktop.Secret.Session")
    interface Session : DBusInterface {
        @DBusMemberName("Close")
        fun close()
    }

    @DBusInterfaceName("org.freedesktop.Secret.Collection")
    @JvmSuppressWildcards
    interface Collection : DBusInterface {
        @DBusMemberName("CreateItem")
        fun createItem(properties: Map<String, Variant<*>>, secret: SecretStruct, replace: Boolean): CreateItemResult
    }

    @DBusInterfaceName("org.freedesktop.Secret.Item")
    @JvmSuppressWildcards
    interface Item : DBusInterface {
        @DBusMemberName("GetSecret")
        fun getSecret(session: DBusPath): SecretStruct
    }

    @DBusInterfaceName("org.freedesktop.Secret.Prompt")
    interface Prompt : DBusInterface {
        @DBusMemberName("Prompt")
        fun prompt(windowId: String)

        /** Signal `Completed(b dismissed, v result)`. */
        class Completed(
            path: String,
            val dismissed: Boolean,
            val result: Variant<*>,
        ) : DBusSignal(path, dismissed, result)
    }
}
