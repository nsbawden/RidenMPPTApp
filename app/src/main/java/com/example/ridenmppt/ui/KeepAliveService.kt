package com.example.ridenmppt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.ridenmppt.ui.MpptController
import com.example.ridenmppt.ui.MpptRuntimeConfig
import com.example.ridenmppt.ui.ModbusRtu
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * KeepAliveService
 *
 * Foreground service + PARTIAL_WAKE_LOCK so the CPU keeps running even with screen off.
 * MPPT controller loop runs here (not in the Activity) so it can run all day with screen off.
 */
class KeepAliveService : Service() {

    companion object {
        private const val CH_ID = "ridenmppt_keepalive"
        private const val CH_NAME = "RidenMPPT"
        private const val NOTIF_ID = 1001
        private const val WAKE_TAG = "RidenMPPT:KeepAlive"

        const val ACTION_START = "com.example.ridenmppt.ACTION_START"
        const val ACTION_STOP = "com.example.ridenmppt.ACTION_STOP"
        const val ACTION_UPDATE = "com.example.ridenmppt.ACTION_UPDATE"
        const val ACTION_RESET_ENERGY = "com.example.ridenmppt.ACTION_RESET_ENERGY"

        const val EXTRA_SLAVE = "slave"
        const val EXTRA_TARGET = "target"
        const val EXTRA_HCC_QUIET = "hccQuiet"

        private const val ACTION_USB_PERMISSION = "com.example.ridenmppt.USB_PERMISSION"

        // Common CH340/CH341 VID/PID (keep it simple, prefer this if present).
        private const val VID_WCH = 0x1A86
        private const val PID_CH340 = 0x7523

        private const val PREFS = "ridenmppt"
        private const val PREF_ENERGY_TOTAL_WH = "energy_total_wh"
        private const val PREF_ENERGY_DAY_WH = "energy_day_wh"
        private const val PREF_ENERGY_YDAY_WH = "energy_yday_wh"
        private const val PREF_ENERGY_DAY_KEY = "energy_day_key"
        private const val PREF_ENERGY_LAST_SAVE_MS = "energy_last_save_ms"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var wl: PowerManager.WakeLock? = null
    private var runningJob: Job? = null
    private var alarmJob: Job? = null

    private val runtimeCfg = MpptRuntimeConfig(
        slave = 1,
        targetUserV = 32.5,
        hccQuietS = 300.0
    )

    // Energy accumulation (Wh)
    private var whToday = 0.0
    private var whYesterday = 0.0
    private var whSinceReset = 0.0
    private var dayKey = 0

    private var lastEnergyMs = 0L
    private var lastPersistMs = 0L

    private fun nowDayKey(): Int {
        val c = Calendar.getInstance()
        val y = c.get(Calendar.YEAR)
        val d = c.get(Calendar.DAY_OF_YEAR)
        return y * 1000 + d
    }

    private fun rolloverDayIfNeeded(newDayKey: Int): Boolean {
        if (dayKey == newDayKey) return false
        dayKey = newDayKey
        whYesterday = whToday
        whToday = 0.0
        return true
    }

    private fun loadEnergy() {
        dayKey = prefs.getInt(PREF_ENERGY_DAY_KEY, 0)
        whToday = prefs.getFloat(PREF_ENERGY_DAY_WH, 0.0f).toDouble()
        whYesterday = prefs.getFloat(PREF_ENERGY_YDAY_WH, 0.0f).toDouble()
        whSinceReset = prefs.getFloat(PREF_ENERGY_TOTAL_WH, 0.0f).toDouble()
        lastPersistMs = prefs.getLong(PREF_ENERGY_LAST_SAVE_MS, 0L)

        val k = nowDayKey()
        if (dayKey == 0) dayKey = k
        if (rolloverDayIfNeeded(k)) persistEnergy()

        MpptBus.setEnergy(whToday, whYesterday, whSinceReset)
    }

    private fun persistEnergy() {
        prefs.edit()
            .putInt(PREF_ENERGY_DAY_KEY, dayKey)
            .putFloat(PREF_ENERGY_DAY_WH, whToday.toFloat())
            .putFloat(PREF_ENERGY_YDAY_WH, whYesterday.toFloat())
            .putFloat(PREF_ENERGY_TOTAL_WH, whSinceReset.toFloat())
            .putLong(PREF_ENERGY_LAST_SAVE_MS, SystemClock.elapsedRealtime())
            .apply()
    }

    private fun resetEnergySinceReset() {
        whSinceReset = 0.0
        MpptBus.setEnergy(whToday, whYesterday, whSinceReset)
        persistEnergy()
        MpptBus.log("Energy reset (Wh since reset = 0)")
    }

    private fun updateEnergyFromPower(poutW: Double?) {
        val nowMs = SystemClock.elapsedRealtime()

        // Day rollover
        val k = nowDayKey()
        val rolled = rolloverDayIfNeeded(k)

        if (lastEnergyMs != 0L && poutW != null) {
            val dtMs = nowMs - lastEnergyMs
            if (dtMs > 0) {
                val dtHours = dtMs.toDouble() / 3_600_000.0
                val addWh = poutW * dtHours
                if (addWh > 0.0) {
                    whToday += addWh
                    whSinceReset += addWh
                }
            }
        }

        lastEnergyMs = nowMs
        MpptBus.setEnergy(whToday, whYesterday, whSinceReset)

        // Persist occasionally so it survives service restarts
        if (rolled || lastPersistMs == 0L || (nowMs - lastPersistMs) >= 20_000L) {
            lastPersistMs = nowMs
            persistEnergy()
        }
    }

    private fun startAlarmIfNeeded() {
        if (alarmJob != null) return
        alarmJob = scope.launch {
            val tg = try { ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (_: Exception) { null }
            try {
                while (isActive) {
                    repeat(3) {
                        try { tg?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250) } catch (_: Exception) { }
                        delay(350)
                    }
                    delay(3000)
                }
            } finally {
                try { tg?.release() } catch (_: Exception) { }
            }
        }
    }

    private fun stopAlarm() {
        alarmJob?.cancel()
        alarmJob = null
    }

    private fun closeQuietly(port: UsbSerialPort?, conn: UsbDeviceConnection?) {
        try { port?.close() } catch (_: Exception) { }
        try { conn?.close() } catch (_: Exception) { }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CH_ID, CH_NAME, NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun startForegroundNow() {
        ensureChannel()
        val notif = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("RidenMPPT running")
            .setContentText("MPPT service active (screen may be off)")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIF_ID, notif)
    }

    private fun acquireWakeLock() {
        if (wl != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try { wl?.release() } catch (_: Exception) { }
        wl = null
    }

    private fun requestPermission(device: UsbDevice) {
        // Limit broadcast to our package.
        val pi = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(device, pi)
    }

    private fun listDrivers(): List<UsbSerialDriver> {
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    }

    private fun pickDriver(found: List<UsbSerialDriver>): UsbSerialDriver? {
        if (found.isEmpty()) return null
        if (found.size == 1) return found[0]

        // Prefer CH340 if present (simple heuristic for your setup).
        val ch = found.firstOrNull { d ->
            val dev = d.device
            dev.vendorId == VID_WCH && dev.productId == PID_CH340
        }
        if (ch != null) return ch

        return found[0]
    }

    private fun findDriverByVidPid(vid: Int, pid: Int): UsbSerialDriver? {
        val found = listDrivers()
        return found.firstOrNull { it.device.vendorId == vid && it.device.productId == pid }
    }

    private fun startRunningIfNeeded() {
        if (runningJob != null) return

        startForegroundNow()
        acquireWakeLock()
        MpptBus.setRunning(true)

        runningJob = scope.launch {
            var hadGoodConnection = false
            var alarmedThisDisconnect = false

            // Pick an initial driver and then reconnect by VID/PID.
            var initial = pickDriver(listDrivers())
            if (initial == null) {
                MpptBus.log("ERR no USB serial devices found (need OTG + cable)")
            }

            val desiredVid = initial?.device?.vendorId ?: -1
            val desiredPid = initial?.device?.productId ?: -1

            if (initial != null) {
                val dev = initial.device
                val name = dev.productName ?: "USB Serial"
                MpptBus.log("Auto-selected: $name  VID=${dev.vendorId} PID=${dev.productId}")
            }

            try {
                while (MpptBus.running.value) {
                    val d = if (desiredVid >= 0 && desiredPid >= 0) {
                        findDriverByVidPid(desiredVid, desiredPid)
                    } else {
                        pickDriver(listDrivers())
                    }

                    if (d == null) {
                        if (hadGoodConnection) {
                            if (!alarmedThisDisconnect) {
                                alarmedThisDisconnect = true
                                MpptBus.log("ERR USB disconnected (device not found) - waiting for replug")
                            }
                            startAlarmIfNeeded()
                        }
                        delay(500)
                        continue
                    }

                    val device = d.device

                    if (!usbManager.hasPermission(device)) {
                        MpptBus.log("Requesting USB permission VID=${device.vendorId} PID=${device.productId}")
                        requestPermission(device)

                        var waitedMs = 0
                        while (MpptBus.running.value && !usbManager.hasPermission(device)) {
                            delay(250)
                            waitedMs += 250
                            if (findDriverByVidPid(device.vendorId, device.productId) == null) break
                            if (waitedMs >= 5000) break
                        }

                        continue
                    }

                    var conn: UsbDeviceConnection? = null
                    var port: UsbSerialPort? = null

                    try {
                        conn = usbManager.openDevice(device)
                        if (conn == null) {
                            if (hadGoodConnection) {
                                if (!alarmedThisDisconnect) {
                                    alarmedThisDisconnect = true
                                    MpptBus.log("ERR openDevice failed - waiting for replug")
                                }
                                startAlarmIfNeeded()
                            } else {
                                MpptBus.log("ERR openDevice failed")
                            }
                            delay(500)
                            continue
                        }

                        port = d.ports.firstOrNull()
                        if (port == null) {
                            if (hadGoodConnection) {
                                if (!alarmedThisDisconnect) {
                                    alarmedThisDisconnect = true
                                    MpptBus.log("ERR no serial ports - waiting for replug")
                                }
                                startAlarmIfNeeded()
                            } else {
                                MpptBus.log("ERR no serial ports")
                            }
                            closeQuietly(null, conn)
                            delay(500)
                            continue
                        }

                        port.open(conn)
                        port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                        try { port.dtr = true } catch (_: Exception) { }
                        try { port.rts = true } catch (_: Exception) { }
                        try { delay(200) } catch (_: Exception) { }

                        hadGoodConnection = true
                        alarmedThisDisconnect = false
                        stopAlarm()

                        MpptBus.log(
                            "Connected VID=${device.vendorId} PID=${device.productId} @115200 " +
                                    "(slave=${runtimeCfg.slave} target=${runtimeCfg.targetUserV} hccQuietS=${runtimeCfg.hccQuietS})"
                        )

                        val modbus = ModbusRtu(port, emit = null)
                        val controller = MpptController(
                            io = modbus,
                            emit = { line -> MpptBus.log(line) },
                            onUiStatus = { st ->
                                MpptBus.setUiStatus(st)
                                updateEnergyFromPower(st.pout)
                            },
                            runtime = runtimeCfg
                        )

                        controller.run { !MpptBus.running.value }
                    } catch (e: Exception) {
                        closeQuietly(port, conn)
                        port = null
                        conn = null

                        if (!MpptBus.running.value) break

                        if (hadGoodConnection) {
                            if (!alarmedThisDisconnect) {
                                alarmedThisDisconnect = true
                                MpptBus.log("ERR USB I/O - ${e.message} (waiting for replug)")
                            }
                            startAlarmIfNeeded()
                        } else {
                            MpptBus.log("ERR controller/USB - ${e.message}")
                        }

                        delay(500)
                        continue
                    } finally {
                        closeQuietly(port, conn)
                    }

                    if (!MpptBus.running.value) break
                    delay(500)
                }
            } finally {
                stopAlarm()
                MpptBus.setUiStatus(null)
            }
        }
    }

    private fun stopRunning() {
        MpptBus.setRunning(false)

        runningJob?.cancel()
        runningJob = null

        stopAlarm()
        releaseWakeLock()

        persistEnergy()

        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()

        MpptBus.log("Stopped")
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        loadEnergy()
    }

    override fun onDestroy() {
        stopAlarm()
        runningJob?.cancel()
        runningJob = null
        releaseWakeLock()
        persistEnergy()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val slave = intent.getIntExtra(EXTRA_SLAVE, runtimeCfg.slave)
                val target = intent.getDoubleExtra(EXTRA_TARGET, runtimeCfg.targetUserV)
                val hccQuiet = intent.getDoubleExtra(EXTRA_HCC_QUIET, runtimeCfg.hccQuietS)

                runtimeCfg.slave = slave
                runtimeCfg.targetUserV = target
                runtimeCfg.hccQuietS = hccQuiet

                MpptBus.log("START requested slave=$slave target=$target hccQuietS=$hccQuiet")
                MpptBus.setRunning(true)
                startRunningIfNeeded()
            }

            ACTION_UPDATE -> {
                if (MpptBus.running.value) {
                    if (intent.hasExtra(EXTRA_TARGET)) {
                        val t = intent.getDoubleExtra(EXTRA_TARGET, runtimeCfg.targetUserV)
                        runtimeCfg.targetUserV = t
                        MpptBus.log("UPDATE target=$t")
                    }
                    if (intent.hasExtra(EXTRA_HCC_QUIET)) {
                        val q = intent.getDoubleExtra(EXTRA_HCC_QUIET, runtimeCfg.hccQuietS)
                        runtimeCfg.hccQuietS = q
                        MpptBus.log("UPDATE hccQuietS=$q")
                    }
                }
            }

            ACTION_RESET_ENERGY -> resetEnergySinceReset()

            ACTION_STOP -> stopRunning()

            else -> { }
        }

        return START_STICKY
    }
}
