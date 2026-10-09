package com.transfer.flash.core.network.radio

import com.fazecast.jSerialComm.SerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SerialPortCatalog] on jSerialComm (Apache-2.0 OR LGPL-3.0, see `gradle/libs.versions.toml`). Covers Windows COM ports
 * (a Bluetooth SPP radio appears as "Standard Serial over Bluetooth link (COMn)"), Linux `/dev/ttyACM*`, `/dev/ttyUSB*` and
 * `/dev/rfcomm*` (after `rfcomm bind`), and macOS `cu.*` devices.
 *
 * Status: compiles and is exercised only for "no ports / bad name" paths in unit tests; **never opened against a real
 * radio** (BT-00).
 */
public class JvmSerialPortCatalog : SerialPortCatalog {

    override fun listPorts(): List<PortInfo> =
        try {
            SerialPort.getCommPorts().map {
                val desc = (it.portDescription ?: it.descriptivePortName ?: "").trim()
                PortInfo(it.systemPortName, desc, classifyPort(it.systemPortName, desc))
            }.sortedWith(compareBy({ it.kind != PortKind.BLUETOOTH }, { it.systemName }))
        } catch (_: Throwable) {
            emptyList() // native library could not load: report "no ports", the tool shows the reason elsewhere
        }

    override fun open(systemName: String, settings: SerialSettings): ByteLink {
        val port = try {
            SerialPort.getCommPorts().firstOrNull { it.systemPortName.equals(systemName, ignoreCase = true) }
                ?: SerialPort.getCommPort(systemName)
        } catch (t: Throwable) {
            throw LinkException("cannot resolve port $systemName: ${t.message}", t)
        }
        port.setComPortParameters(
            settings.baud,
            settings.dataBits,
            if (settings.stopBits == 2) SerialPort.TWO_STOP_BITS else SerialPort.ONE_STOP_BIT,
            when (settings.parity) {
                SerialParity.NONE -> SerialPort.NO_PARITY
                SerialParity.EVEN -> SerialPort.EVEN_PARITY
                SerialParity.ODD -> SerialPort.ODD_PARITY
            },
        )
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING, 250, 2000)
        if (!port.openPort()) {
            throw LinkException("cannot open $systemName (busy, missing, or access denied; error code ${port.lastErrorCode})")
        }
        return JvmSerialLink(port, "$systemName @${settings.baud}")
    }
}

private class JvmSerialLink(private val port: SerialPort, override val description: String) : ByteLink {
    @Volatile
    private var closed = false
    private var readTimeoutMs = 250L

    override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int = withContext(Dispatchers.IO) {
        if (closed) return@withContext -1
        if (timeoutMs != readTimeoutMs) {
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING, timeoutMs.toInt(), 2000)
            readTimeoutMs = timeoutMs
        }
        val n = port.readBytes(buffer, buffer.size)
        when {
            n >= 0 -> n
            closed -> -1
            else -> throw LinkException("read failed on $description (error code ${port.lastErrorCode})")
        }
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            if (closed) throw LinkException("link closed")
            var off = 0
            while (off < data.size) {
                val n = port.writeBytes(data.copyOfRange(off, data.size), data.size - off)
                if (n < 0) throw LinkException("write failed on $description (error code ${port.lastErrorCode})")
                if (n == 0) throw LinkException("write timed out on $description")
                off += n
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { port.closePort() }
    }
}
