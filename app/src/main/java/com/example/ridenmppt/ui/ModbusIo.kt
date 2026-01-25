package com.example.ridenmppt.ui

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

interface ModbusIo {
    suspend fun readHoldingRegs(slave: Int, start: Int, qty: Int): IntArray
    suspend fun writeSingleReg(slave: Int, addr: Int, value: Int)
}

class ModbusRtu(
    private val port: UsbSerialPort,
    private val emit: ((String) -> Unit)? = null, // optional debug output
) : ModbusIo {

    // -------------------------------------------------------------------------------------------------
    // CRC16 (standard Modbus RTU)
    // -------------------------------------------------------------------------------------------------
    private fun crc16(data: ByteArray, len: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else (crc ushr 1)
            }
        }
        return crc and 0xFFFF
    }

    private fun crcOk(frame: ByteArray, len: Int): Boolean {
        if (len < 4) return false
        val crcRx = (frame[len - 2].toInt() and 0xFF) or ((frame[len - 1].toInt() and 0xFF) shl 8)
        return crcRx == crc16(frame, len - 2)
    }

    private fun hex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * 3)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            if (i != 0) sb.append(' ')
            sb.append(v.toString(16).padStart(2, '0'))
        }
        return sb.toString()
    }

    private fun slice(src: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArray(len)
        System.arraycopy(src, off, out, 0, len)
        return out
    }

    // -------------------------------------------------------------------------------------------------
    // TRANSPORT HELPERS
    // -------------------------------------------------------------------------------------------------
    private suspend fun readSome(max: Int, timeoutMs: Int): ByteArray = withContext(Dispatchers.IO) {
        val buf = ByteArray(max)
        val r = try { port.read(buf, timeoutMs) } catch (_: Exception) { 0 }
        if (r <= 0) ByteArray(0) else buf.copyOf(r)
    }

    private suspend fun writeFrame(frame: ByteArray) = withContext(Dispatchers.IO) {
        try {
            port.write(frame, 250)
        } catch (e: Exception) {
            throw RuntimeException("writeFrame failed: ${e.message}")
        }
    }

    private suspend fun drainInput() {
        // IMPORTANT:
        // Draining too aggressively can eat late replies.
        // Keep this light: a couple short reads only.
        withContext(Dispatchers.IO) {
            val tmp = ByteArray(256)
            repeat(2) {
                try { port.read(tmp, 20) } catch (_: Exception) { }
            }
        }
    }

    private suspend fun readBurst(totalTimeoutMs: Int, maxBytes: Int): ByteArray {
        // Read whatever arrives for up to totalTimeoutMs, up to maxBytes.
        // This works well for Android USB bursts (whole frames often arrive at once).
        val out = ByteArray(maxBytes)
        var off = 0
        var remainingMs = totalTimeoutMs

        while (remainingMs > 0 && off < maxBytes) {
            val t = min(80, remainingMs)
            val chunk = readSome(min(256, maxBytes - off), t)
            if (chunk.isNotEmpty()) {
                System.arraycopy(chunk, 0, out, off, chunk.size)
                off += chunk.size
                // after we get data, keep going briefly to catch trailing bytes
                remainingMs = min(200, remainingMs)
            } else {
                remainingMs -= t
            }
        }

        return if (off == 0) ByteArray(0) else out.copyOf(off)
    }

    private fun findFrameFc03OrExc(buf: ByteArray, slave: Int, func: Int, expectedByteCount: Int): ByteArray? {
        val s = slave and 0xFF
        val f = func and 0xFF
        val fe = f or 0x80

        var i = 0
        while (i + 5 <= buf.size) {
            val b0 = buf[i].toInt() and 0xFF
            if (b0 == s) {
                val b1 = buf[i + 1].toInt() and 0xFF

                // normal: [slave][func][bc][data...][crcLo][crcHi]
                if (b1 == f) {
                    val bc = buf[i + 2].toInt() and 0xFF
                    if (bc == expectedByteCount) {
                        val frameLen = 3 + bc + 2
                        if (i + frameLen <= buf.size) {
                            val fr = slice(buf, i, frameLen)
                            if (crcOk(fr, fr.size)) return fr
                        }
                    }
                }

                // exception: [slave][func|0x80][exc][crcLo][crcHi]
                if (b1 == fe) {
                    if (i + 5 <= buf.size) {
                        val fr = slice(buf, i, 5)
                        if (crcOk(fr, fr.size)) {
                            val exc = fr[2].toInt() and 0xFF
                            throw RuntimeException("Modbus exception code=0x${exc.toString(16)}")
                        }
                    }
                }
            }
            i += 1
        }
        return null
    }

    private fun findFrameFc06OrExc(buf: ByteArray, slave: Int, func: Int): ByteArray? {
        val s = slave and 0xFF
        val f = func and 0xFF
        val fe = f or 0x80

        var i = 0
        while (i + 5 <= buf.size) {
            val b0 = buf[i].toInt() and 0xFF
            if (b0 == s) {
                val b1 = buf[i + 1].toInt() and 0xFF

                // normal FC06 echo is 8 bytes
                if (b1 == f) {
                    if (i + 8 <= buf.size) {
                        val fr = slice(buf, i, 8)
                        if (crcOk(fr, fr.size)) return fr
                    }
                }

                // exception is 5 bytes
                if (b1 == fe) {
                    if (i + 5 <= buf.size) {
                        val fr = slice(buf, i, 5)
                        if (crcOk(fr, fr.size)) {
                            val exc = fr[2].toInt() and 0xFF
                            throw RuntimeException("Modbus exception code=0x${exc.toString(16)}")
                        }
                    }
                }
            }
            i += 1
        }

        return null
    }

    // -------------------------------------------------------------------------------------------------
    // MODBUS
    // -------------------------------------------------------------------------------------------------
    override suspend fun readHoldingRegs(slave: Int, start: Int, qty: Int): IntArray {
        val func = 0x03

        val pdu = byteArrayOf(
            slave.toByte(),
            func.toByte(),
            ((start ushr 8) and 0xFF).toByte(),
            (start and 0xFF).toByte(),
            ((qty ushr 8) and 0xFF).toByte(),
            (qty and 0xFF).toByte(),
        )

        val crc = crc16(pdu, pdu.size)
        val frame = pdu + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())

        emit?.invoke("TX FC03 start=0x${start.toString(16)} qty=$qty : ${hex(frame)}")

        val expectedByteCount = qty * 2

        repeat(6) { attempt ->
            drainInput()
            writeFrame(frame)

            val raw = readBurst(totalTimeoutMs = 600, maxBytes = 256)
            if (raw.isNotEmpty()) {
                val fr = try {
                    findFrameFc03OrExc(raw, slave, func, expectedByteCount)
                } catch (e: Exception) {
                    emit?.invoke("RX EXC a=${attempt + 1}/6 raw(${raw.size}): ${hex(raw)}")
                    throw e
                }

                if (fr != null) {
                    val regs = IntArray(qty)
                    for (i in 0 until qty) {
                        val hi = fr[3 + 2 * i].toInt() and 0xFF
                        val lo = fr[3 + 2 * i + 1].toInt() and 0xFF
                        regs[i] = (hi shl 8) or lo
                    }
                    return regs
                }
            }

            emit?.invoke("RX fail a=${attempt + 1}/6 raw(${raw.size}): ${hex(raw)}")
        }

        throw RuntimeException("Modbus read failed start=0x${start.toString(16)} qty=$qty")
    }

    override suspend fun writeSingleReg(slave: Int, addr: Int, value: Int) {
        val func = 0x06
        val v = value and 0xFFFF

        val pdu = byteArrayOf(
            slave.toByte(),
            func.toByte(),
            ((addr ushr 8) and 0xFF).toByte(),
            (addr and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte(),
        )

        val crc = crc16(pdu, pdu.size)
        val frame = pdu + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())

        emit?.invoke("TX FC06 addr=0x${addr.toString(16)} val=$v : ${hex(frame)}")

        repeat(6) { attempt ->
            drainInput()
            writeFrame(frame)

            val raw = readBurst(totalTimeoutMs = 600, maxBytes = 256)
            if (raw.isNotEmpty()) {
                val fr = try {
                    findFrameFc06OrExc(raw, slave, func)
                } catch (e: Exception) {
                    emit?.invoke("RX EXC FC06 a=${attempt + 1}/6 raw(${raw.size}): ${hex(raw)}")
                    throw e
                }

                if (fr != null) {
                    // FC06 normal response echoes request (first 6 bytes match; last 2 are CRC)
                    var match = true
                    for (i in 0 until 6) {
                        val rxv = fr[i].toInt() and 0xFF
                        val txv = pdu[i].toInt() and 0xFF
                        if (rxv != txv) { match = false; break }
                    }
                    if (match) return
                }
            }

            emit?.invoke("RX fail FC06 a=${attempt + 1}/6 raw(${raw.size}): ${hex(raw)}")
        }

        throw RuntimeException("Modbus write failed addr=0x${addr.toString(16)}")
    }
}
