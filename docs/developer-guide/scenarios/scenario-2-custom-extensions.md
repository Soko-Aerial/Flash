# Scenario 2: Custom Implementations & Architecture Extensions

> **Verified against the code 2026-10-09.** The previous revision used signatures that do not exist (`StreamChannel.close()`,
> `StreamChannelFactory.open(channelIndex, ...)`, `writeAt(offset, source, sourceOffset, byteCount)`, a four-method `FlashCrypto`,
> a public `LocalFlashColors`, `FlashColors(brandPrimary = ...)`). This page now shows the real interfaces. The snippets are
> illustrations; nothing here is compile-checked (only `sample/consumer` is) and none of it has been run on a device.
>
> **Important:** `Flash.create` / `FlashDesktop.create` do **not** take a transport, a sink or a crypto object. Every seam below is
> a constructor parameter of a core class, so using one means wiring the engine yourself the way the real hosts do
> (`app/.../debug/DiscoveryEngineHolder.kt` on Android, `desktop/.../DesktopEngine.kt` on desktop).

---

## 1. A custom transport for file chunks

The transfer engine only needs `StreamChannelFactory` / `StreamChannel`
([source](../../../core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/StreamChannel.kt)):

```kotlin
public interface StreamChannel {
    public val id: Int                                             // stable id for telemetry, ACK routing
    public suspend fun sendFrame(frameBytes: ByteArray): Boolean   // true = handed to the transport
}

public fun interface StreamChannelFactory {
    public suspend fun open(channelId: Int, peerDeviceId: String?): StreamChannel?   // null = no more streams
}
```

Rules from the KDoc: `sendFrame` is called by one dispatcher worker at a time per channel; returning `false` or throwing marks only
that channel dead and its un-ACKed chunks go back to the shared pool; at least one channel must open or the transfer fails.
There is no `close()` on the channel. ACKs come back as `ACK_BATCH` frames on the session, not from `sendFrame`.

```kotlin
class BluetoothStreamChannel(
    override val id: Int,
    private val out: java.io.OutputStream,
) : StreamChannel {
    override suspend fun sendFrame(frameBytes: ByteArray): Boolean = try {
        withContext(Dispatchers.IO) { out.write(frameBytes); out.flush() }
        true
    } catch (e: java.io.IOException) { false }
}

val factory = StreamChannelFactory { channelId, peerDeviceId ->
    if (channelId > 0 || peerDeviceId == null) null            // one stream only on a serial link
    else socketFor(peerDeviceId)?.let { BluetoothStreamChannel(channelId, it.outputStream) }
}
```

The factory goes to `RealFlashTransferRepository(streamChannelFactory = factory, fileSourceOpener = ..., ...)`. `MultiStreamDispatcher` is
`internal`; you never construct it. Both real hosts pass a lambda that sends on the existing authenticated WebSocket session
(`sessionChannel` in `DesktopEngine`, `openStreamChannel` in `Flash.kt`).

**What a transport must also provide:** the receiving side needs the same frames delivered to `FlashInboundRouter`, chat and
control frames travel on the same session, and pairing/identity checks happen in `:core:network`, not in the transfer module. A
new link is therefore a new *session* type, not just a channel factory. The radio / Bluetooth work is specified in
`docs/network/RADIO-WIRE-FORMAT.md` (ADR-101, PROPOSED, not built).

---

## 2. A custom sink for received files

`RandomAccessSinkHandle`
([source](../../../core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/policy/RandomAccessSinkHandle.kt)) is:

```kotlin
public interface RandomAccessSinkHandle : AutoCloseable {
    public fun writeAt(byteOffset: Long, data: ByteArray)
    public fun flush()
    public val isOpen: Boolean
}
```

The shipped implementation is `FileRandomAccessSinkHandle(file, totalBytes)` (okio `FileHandle`, one code path for Android and
desktop). Chunks arrive out of order, so `writeAt` must accept any offset and tolerate a re-sent chunk. An in-memory example,
only suitable for small files because it holds everything in RAM (AGENTS.md section 18 forbids that for real transfers):

```kotlin
class InMemorySinkHandle(totalBytes: Int) : RandomAccessSinkHandle {
    private val buffer = ByteArray(totalBytes)
    @Volatile override var isOpen = true
        private set
    override fun writeAt(byteOffset: Long, data: ByteArray) {
        if (isOpen) data.copyInto(buffer, byteOffset.toInt())
    }
    override fun flush() {}
    override fun close() { isOpen = false }
}
```

It is plugged in where the host builds its receive sink: `RandomAccessChunkSink(handle, start.chunkSize)` inside the
`FlashInboundRouter` wiring (see `Flash.kt` around the `FileRandomAccessSinkHandle` line, and the desktop equivalent). That is host
wiring, not a `FlashConfig` option. Keep the path-traversal check that precedes it in those hosts.

---

## 3. Hardware-backed keys (custom `FlashCrypto`)

`FlashCrypto` ([source](../../../core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/FlashCrypto.kt)) is the
identity and session-key seam. Android's real implementation is `KeystoreFlashCrypto` (AndroidKeyStore); the JVM one persists a
software key (`PersistedFlashCrypto`, `@FlashInternalApi`; Linux seals it with the Secret Service keyring, ADR-092). The contract:

```kotlin
public interface FlashCrypto {
    public val identityPublicKeyEncoded: ByteArray                       // X.509 SPKI, P-256
    public fun sign(data: ByteArray): ByteArray                          // SHA256withECDSA
    public fun verify(signature: ByteArray, data: ByteArray, peerPublicKey: ByteArray): Boolean
    public fun generateEphemeralEcdhKeyPair(): FlashEcKeyPair
    public fun ecdhSessionKey(selfEphemeral: FlashEcKeyPair, peerEphemeralPublicKey: ByteArray): ByteArray  // 32 bytes, HKDF-SHA256
}
```

The **identity key signs; the session key comes from an ephemeral ECDH pair**, so an HSM only has to implement `sign` and expose the
public key. The ephemeral pair and HKDF can delegate to the software helpers the two shipped classes share. What matters: `verify`
must return `false` (never throw) on malformed input, and the private identity key must never leave the module. As with the other
seams, `Flash.kt` creates `KeystoreFlashCrypto(appContext)` itself, so `Flash.create` cannot take a replacement; use the
hand-wired path.

Changing the identity key changes the device id, so every existing pairing is lost. Treat it as a new install.

---

## 4. Re-skinning the UI

`FlashTheme` accepts the palette, typography and motion directly
([source](../../../ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashTheme.kt)); the composition local is private, so
pass the values as parameters. `FlashColors` is a data class, so start from `FlashColors.dark()` / `light()` and `copy(...)`:

```kotlin
val brand = FlashColors.dark().copy(
    accentPrimary = Color(0xFF0284C7),
    accentSecondary = Color(0xFF38BDF8),
    chatBgOutgoing = Color(0xFF0284C7),
)

@Composable
fun EnterpriseApp() {
    FlashTheme(darkTheme = true, colors = brand) {
        // FlashChatListScreen, FlashConversationScreen, ...
    }
}
```

`FlashColors` has about 60 semantic tokens (`accent*`, `text*`, `background*`, `border*`, `chat*`, `composer*`, avatar palettes, status
colours); see [ui-theme](../modules/ui/ui-theme.md). `dynamicAccent = true` takes the accent from Material You on Android 12+.
Icons come from `FlashIcons` (drawable-backed specs); replacing the icon set means replacing those resources.
