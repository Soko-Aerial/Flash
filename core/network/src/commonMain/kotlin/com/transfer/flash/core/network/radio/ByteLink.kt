package com.transfer.flash.core.network.radio

/**
 * A bidirectional byte stream to a radio: a COM port on desktop, an RFCOMM socket on Android, an in-memory pipe in tests.
 *
 * Deliberately tiny so every platform can implement it in a few dozen lines and every higher layer ([KissTncDriver]) is
 * testable without hardware. Not a socket abstraction for Flash-to-Flash traffic (that goes through the session layer, ADR-101).
 */
public interface ByteLink {
    /** Human-readable name for logs: `COM5 @115200`, `rfcomm 38:D2:00:...`. Never contains key material. */
    public val description: String

    /**
     * Reads at least one byte into [buffer] or gives up after [timeoutMs].
     *
     * @return the number of bytes read (> 0); 0 when [timeoutMs] elapsed with nothing to read; -1 when the link is closed
     * (the peer went away or [close] was called).
     * @throws LinkException on an I/O error. After an exception or -1 the link is dead and must be re-opened.
     */
    public suspend fun read(buffer: ByteArray, timeoutMs: Long): Int

    /** Writes all of [data]. @throws LinkException if the link is dead. */
    public suspend fun write(data: ByteArray)

    /** Closes the link; idempotent; wakes a pending [read] with -1 or an exception. */
    public fun close()
}

/** An I/O failure on a [ByteLink]. */
public class LinkException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What kind of device a [PortInfo] looks like. Heuristic, for display and sorting only. */
public enum class PortKind { BLUETOOTH, USB_SERIAL, OTHER }

/** One serial port the OS reports. */
public class PortInfo(
    /** The name to open: `COM5`, `/dev/rfcomm0`, `/dev/ttyACM0`. */
    public val systemName: String,
    /** The driver's description, e.g. `Standard Serial over Bluetooth link (COM5)`. May be empty. */
    public val description: String,
    /** Best guess from [description]. */
    public val kind: PortKind,
) {
    override fun toString(): String = "$systemName: $description [$kind]"
}

/** Parity setting. */
public enum class SerialParity { NONE, EVEN, ODD }

/** Serial line settings. The default is 8-N-1 at 115200, what the radio plan expects for the Bluetooth virtual port. */
public class SerialSettings(
    public val baud: Int = 115200,
    public val dataBits: Int = 8,
    public val parity: SerialParity = SerialParity.NONE,
    public val stopBits: Int = 1,
) {
    init {
        require(baud > 0 && dataBits in 5..8 && stopBits in 1..2)
    }

    override fun toString(): String = "$baud ${dataBits}${parity.name.first()}$stopBits"
}

/** Lists and opens serial ports for the platform. */
public interface SerialPortCatalog {
    /** The ports the OS reports now. Empty (not an error) when there are none. */
    public fun listPorts(): List<PortInfo>

    /** Opens [systemName] with [settings]. @throws LinkException if it cannot be opened (busy, missing, access denied). */
    public fun open(systemName: String, settings: SerialSettings): ByteLink
}

/** Classifies a port from its driver description. */
public fun classifyPort(systemName: String, description: String): PortKind {
    val d = description.lowercase()
    val n = systemName.lowercase()
    return when {
        "bluetooth" in d || "rfcomm" in n || "rfcomm" in d -> PortKind.BLUETOOTH
        "usb" in d || "ttyusb" in n || "ttyacm" in n || "cdc" in d || "ch340" in d || "cp210" in d || "ftdi" in d -> PortKind.USB_SERIAL
        else -> PortKind.OTHER
    }
}
