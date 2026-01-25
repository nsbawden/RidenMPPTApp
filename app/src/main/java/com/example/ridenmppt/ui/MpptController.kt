package com.example.ridenmppt.ui

import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.round

class MpptController(
    private val io: ModbusIo,
    private val emit: (String) -> Unit,
    private val onUiStatus: ((UiStatus) -> Unit)? = null,
) {

    companion object {
        private const val REG_BASE = 0x0008
        private const val REG_VSET = 0x0008
        private const val REG_ISET = 0x0009
        private const val REG_ON1 = 0x0011
        private const val REG_ON2 = 0x0012

        private const val STATE_BATT_FULL = "FUL"
        private const val STATE_CONST_V = "---"
        private const val STATE_MAX_R = "RUN"
    }

    data class Status(
        val vset: Double,
        val iset: Double,
        val outv: Double,
        val iout: Double,
        val pout: Double,
        val vin: Double,
        val on: Boolean,
    )

    data class UiStatus(
        val vset: Double,
        val outv: Double,
        val iout: Double,
        val pout: Double,
        val vin: Double,
        val targetVin: Double,
        val isetCmd: Double,
        val band: String,
        val mode: String,
        val hccOffset: Double,
    )

    private fun quantAmps(a: Double): Double = round((a + 1e-9) * 100.0) / 100.0 // 0.01A resolution
    private fun quantVolts(v: Double): Double = round((v + 1e-9) * 100.0) / 100.0 // 0.01V resolution

    private fun clamp(x: Double, lo: Double, hi: Double): Double {
        if (x < lo) return lo
        if (x > hi) return hi
        return x
    }

    private suspend fun readStatus(slave: Int): Status {
        val r = io.readHoldingRegs(slave, REG_BASE, 8)

        val vset = r[0] / 100.0
        val iset = r[1] / 100.0
        val outv = r[2] / 100.0
        val iout = r[3] / 100.0
        val pout = r[5] / 100.0
        val vin = r[6] / 100.0

        val f = io.readHoldingRegs(slave, REG_ON1, 2)
        val on = (f[0] != 0) || (f[1] != 0)

        return Status(vset, iset, outv, iout, pout, vin, on)
    }

    private suspend fun setVset(slave: Int, volts: Double) {
        io.writeSingleReg(slave, REG_VSET, (quantVolts(volts) * 100.0).toInt())
    }

    private suspend fun setIset(slave: Int, amps: Double) {
        io.writeSingleReg(slave, REG_ISET, (quantAmps(amps) * 100.0).toInt())
    }

    private suspend fun setOutput(slave: Int, enabled: Boolean) {
        val v = if (enabled) 1 else 0
        io.writeSingleReg(slave, REG_ON1, v)
        io.writeSingleReg(slave, REG_ON2, v)
    }

    suspend fun run(vtarget: Double, slave: Int, stopRequested: () -> Boolean) {
        val VSET_DROP_BATT_FULL = 0.60

        val VIN_BAND = 0.00
        val HARD_DROP = 5.0

        val I_MIN = 0.01
        val I_MAX = 24.0
        val I_OVER = 0.5

        val LOOP_DELAY_S = 0.20

        val HCC_STEP_V = 0.10
        val HCC_OFFSET_MIN_V = -2.00
        val HCC_OFFSET_MAX_V = 2.00
        val HCC_QUIET_S = 90.0
        val HCC_DECAY_STABLE_EPS = 0.50

        val RECOVERY_WINDOW_S = 6.0
        val RECOVERY_ERR_V = 1.50
        val RECOVERY_GAIN = 2.0
        val RECOVERY_MIN_POUT_W = 100.0

        val V1 = 0.25
        val V2 = 0.50
        val V3 = 1.00
        val V4 = 1.50

        val HUGE_STEP_UP = 0.80
        val HUGE_STEP_DN = 0.80
        val FAR_STEP_UP = 0.20
        val FAR_STEP_DN = 0.20
        val MID_STEP_UP = 0.10
        val MID_STEP_DN = 0.10
        val NEAR_STEP_UP = 0.05
        val NEAR_STEP_DN = 0.05
        val FINE_STEP_UP = 0.01
        val FINE_STEP_DN = 0.01

        val FULL_I = 4.00
        val REDUCED_DISABLE_I = FULL_I + 8.0

        val CONSTV_ENTER_EPS = 0.05
        val CONSTV_EXIT_EPS = 0.10

        val HIGH_CURRENT_I = FULL_I + 8.0

        var isVLtd = false

        try {
            readStatus(slave)
        } catch (e: Exception) {
            emit("ERR initial readStatus: ${e.message}")
        }

        try {
            setOutput(slave, true)
        } catch (e: Exception) {
            emit("ERR setOutput: ${e.message}")
        }

        try {
            setVset(slave, 15.0)
            delay(100)
        } catch (e: Exception) {
            emit("ERR init VSET: ${e.message}")
        }

        var isetCmd = 0.01
        try {
            setIset(slave, isetCmd)
            delay(100)
        } catch (e: Exception) {
            emit("ERR init ISET: ${e.message}")
        }

        val T_USER = vtarget

        var hccOffset = 0.0
        var lastNoHccMs = System.currentTimeMillis()
        var doingHcc = false
        var firstHccIgnored = false
        var lastHccExitMs = Long.MIN_VALUE

        var targetVin = quantVolts(T_USER + hccOffset)

        var vsetBase: Double? = null
        var vsetReduced = false

        emit("START T_USER=${"%.2f".format(T_USER)}V (TARGET_VIN=${"%.2f".format(targetVin)}V) LOOP=${"%.2f".format(LOOP_DELAY_S)}s")

        while (!stopRequested()) {
            val st = try {
                readStatus(slave)
            } catch (e: Exception) {
                emit("ERR readStatus: ${e.message}")
                delay(500)
                continue
            }

            if (vsetBase == null) vsetBase = st.vset

            if (isVLtd) {
                if (st.outv <= (st.vset - CONSTV_EXIT_EPS)) isVLtd = false
            } else {
                if (st.outv >= (st.vset - CONSTV_ENTER_EPS)) isVLtd = true
            }

            val isSoaking = isVLtd && (st.iout < FULL_I)
            val isHighCurrent = st.iout >= HIGH_CURRENT_I

            val statusNow = when {
                isSoaking -> STATE_BATT_FULL
                isVLtd -> STATE_CONST_V
                else -> STATE_MAX_R
            }

            if (!isVLtd) {
                targetVin = quantVolts(T_USER + hccOffset)
            } else {
                lastNoHccMs = System.currentTimeMillis()
            }

            val hccTrigger = if (!isVLtd) st.vin < (targetVin - HARD_DROP) else false

            if (hccTrigger && !doingHcc) {
                doingHcc = true
                lastNoHccMs = System.currentTimeMillis()

                if (firstHccIgnored) {
                    hccOffset = clamp(hccOffset + HCC_STEP_V, HCC_OFFSET_MIN_V, HCC_OFFSET_MAX_V)
                } else {
                    firstHccIgnored = true
                }

                targetVin = quantVolts(T_USER + hccOffset)
            }

            if (!hccTrigger && doingHcc) {
                doingHcc = false
                lastNoHccMs = System.currentTimeMillis()
                lastHccExitMs = System.currentTimeMillis()
            }

            val nowMs = System.currentTimeMillis()
            if (!isVLtd && !doingHcc && (nowMs - lastNoHccMs) >= (HCC_QUIET_S * 1000.0).toLong()) {
                if (st.vin >= (targetVin - HCC_DECAY_STABLE_EPS)) {
                    hccOffset = clamp(hccOffset - HCC_STEP_V, HCC_OFFSET_MIN_V, HCC_OFFSET_MAX_V)
                    lastNoHccMs = nowMs
                    targetVin = quantVolts(T_USER + hccOffset)
                }
            }

            var band = ""

            val errVin = st.vin - targetVin
            val absErr = abs(errVin)

            val iceil = quantAmps(clamp(st.iout + I_OVER, I_MIN, I_MAX))

            if (hccTrigger) {
                band = "RE"
                val newIset = quantAmps(clamp(isetCmd * 0.7, I_MIN, I_MAX))

                try {
                    setIset(slave, newIset)
                    isetCmd = newIset
                } catch (e: Exception) {
                    emit("ERR setIset: ${e.message}")
                    delay(500)
                    continue
                }

                emit(
                    "FAST RECOVER TGT=${"%.2f".format(targetVin)} HCC=${"%+.2f".format(hccOffset)} " +
                            "IOUT=${"%.2f".format(st.iout)}A PWR=${"%.1f".format(st.pout)}W " +
                            "VIN=${"%.2f".format(st.vin)} ISET->${"%.2f".format(isetCmd)} $statusNow"
                )
            } else {
                var stepUp: Double
                var stepDn: Double

                if (absErr > V4) {
                    stepUp = HUGE_STEP_UP
                    stepDn = HUGE_STEP_DN
                    band = "HU"
                } else if (absErr > V3) {
                    stepUp = FAR_STEP_UP
                    stepDn = FAR_STEP_DN
                    band = "FA"
                } else if (absErr > V2) {
                    stepUp = MID_STEP_UP
                    stepDn = MID_STEP_DN
                    band = "MI"
                } else if (absErr > V1) {
                    stepUp = NEAR_STEP_UP
                    stepDn = NEAR_STEP_DN
                    band = "NE"
                } else {
                    stepUp = FINE_STEP_UP
                    stepDn = FINE_STEP_DN
                    band = "FI"
                }

                if ((System.currentTimeMillis() - lastHccExitMs) <= (RECOVERY_WINDOW_S * 1000.0).toLong()) {
                    if (errVin > RECOVERY_ERR_V && st.pout >= RECOVERY_MIN_POUT_W) {
                        stepUp = minOf(stepUp * RECOVERY_GAIN, HUGE_STEP_UP)
                    }
                }

                stepUp = quantAmps(stepUp)
                stepDn = quantAmps(stepDn)

                var stepUsed = 0.0
                var newIset = when {
                    errVin > VIN_BAND -> {
                        stepUsed = stepUp
                        isetCmd + stepUp
                    }
                    errVin < -VIN_BAND -> {
                        stepUsed = -stepDn
                        isetCmd - stepDn
                    }
                    else -> {
                        stepUsed = 0.0
                        isetCmd
                    }
                }

                newIset = quantAmps(clamp(newIset, I_MIN, I_MAX))

                if (newIset > iceil && isVLtd) {
                    newIset = iceil
                    stepUsed = 9.99
                }

                if (newIset != isetCmd) {
                    try {
                        setIset(slave, newIset)
                        isetCmd = newIset
                    } catch (e: Exception) {
                        emit("ERR setIset: ${e.message}")
                        delay(500)
                        continue
                    }
                }

                if (!isHighCurrent) {
                    if (isSoaking) {
                        if (!vsetReduced) {
                            val base = vsetBase ?: st.vset
                            val vsetFull = quantVolts(maxOf(0.0, base - VSET_DROP_BATT_FULL))
                            try {
                                setVset(slave, vsetFull)
                                vsetReduced = true
                            } catch (e: Exception) {
                                emit("ERR setVset: ${e.message}")
                            }
                        }
                    } else {
                        if (vsetReduced && st.iout > REDUCED_DISABLE_I) {
                            try {
                                setVset(slave, quantVolts(vsetBase ?: st.vset))
                            } catch (e: Exception) {
                                emit("ERR setVset: ${e.message}")
                            }
                            vsetReduced = false
                            vsetBase = st.vset
                        } else {
                            vsetBase = st.vset
                        }
                    }
                }

                val hccTag = if (!isVLtd) " HCC=${"%+.2f".format(hccOffset)}" else ""

//                emit(
//                    "VSET=${"%.2f".format(st.vset)}V OUT ${"%.2f".format(st.outv)}V ${"%.2f".format(st.iout)}A | " +
//                            "PWR=${"%.1f".format(st.pout)}W VIN=${"%.2f".format(st.vin)} " +
//                            "TGT=${"%.2f".format(targetVin)} ISET=${"%.2f".format(isetCmd)} ${"%+.2f".format(stepUsed)} " +
//                            (if (isVLtd) "[${band}]" else " $band ") + " $statusNow$hccTag"
//                )
            }

            onUiStatus?.invoke(
                UiStatus(
                    vset = st.vset,
                    outv = st.outv,
                    iout = st.iout,
                    pout = st.pout,
                    vin = st.vin,
                    targetVin = targetVin,
                    isetCmd = isetCmd,
                    band = band.trim(),
                    mode = statusNow,
                    hccOffset = hccOffset,
                )
            )

            delay((LOOP_DELAY_S * 1000.0).toLong())
        }
    }
}
