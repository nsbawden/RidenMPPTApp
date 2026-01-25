package com.example.ridenmppt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.example.ridenmppt.ui.MpptController
import com.example.ridenmppt.ui.ModbusRtu
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val actionUsbPermission = "com.example.ridenmppt.USB_PERMISSION"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Freeze UI in portrait (belt + suspenders; manifest also enforces).
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        setContent { MpptApp() }
    }

    private fun startKeepAlive() {
        val i = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun stopKeepAlive() {
        stopService(Intent(this, KeepAliveService::class.java))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MpptApp() {
        val ctx = this
        val usbManager = remember { getSystemService(Context.USB_SERVICE) as UsbManager }
        val cfg = LocalConfiguration.current
        val isLandscape = (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE)

        var drivers by remember { mutableStateOf(listOf<UsbSerialDriver>()) }
        var selected by remember { mutableStateOf<UsbSerialDriver?>(null) }

        var slaveText by remember { mutableStateOf("1") }
        var targetText by remember { mutableStateOf("32.5") }

        val logs = remember { mutableStateListOf<String>() }
        fun emit(s: String) {
            logs.add(s)
            if (logs.size > 600) {
                // cap memory + keep recent
                repeat(200) { if (logs.isNotEmpty()) logs.removeAt(0) }
            }
        }

        var uiStatus by remember { mutableStateOf<MpptController.UiStatus?>(null) }

        fun copyLogsToClipboard() {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = logs.joinToString("\n")
            cm.setPrimaryClip(ClipData.newPlainText("RidenMPPT logs", text))
            emit("Copied ${logs.size} lines to clipboard")
        }

        var running by remember { mutableStateOf(false) }
        var job by remember { mutableStateOf<Job?>(null) }

        val permissionResult = remember { mutableStateOf<Pair<UsbDevice, Boolean>?>(null) }

        val receiver = remember {
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != actionUsbPermission) return
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) permissionResult.value = device to granted
                }
            }
        }

        DisposableEffect(Unit) {
            val filter = IntentFilter(actionUsbPermission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            onDispose {
                unregisterReceiver(receiver)
                // If the UI is leaving and the controller is still marked running, stop cleanly.
                // (If you want it to survive UI teardown, we can move the controller into the service.)
                if (running) {
                    running = false
                    job?.cancel()
                    job = null
                    stopKeepAlive()
                }
            }
        }

        fun refreshDevices() {
            val found = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            drivers = found
            if (selected != null && found.none { it.device.deviceId == selected?.device?.deviceId }) selected = null
        }

        LaunchedEffect(Unit) { refreshDevices() }

        fun requestPermission(device: UsbDevice) {
            val pi = PendingIntent.getBroadcast(
                ctx,
                0,
                Intent(actionUsbPermission),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            usbManager.requestPermission(device, pi)
        }

        fun stopController() {
            running = false
            job?.cancel()
            job = null
            stopKeepAlive()
        }

        fun startController(driver: UsbSerialDriver, slave: Int, target: Double) {
            if (running) return

            val device = driver.device

            if (!usbManager.hasPermission(device)) {
                emit("Requesting USB permission VID=${device.vendorId} PID=${device.productId}")
                requestPermission(device)
                return
            }

            val conn = usbManager.openDevice(device)
            if (conn == null) {
                emit("ERR openDevice failed")
                return
            }

            val port: UsbSerialPort = driver.ports.firstOrNull() ?: run {
                emit("ERR no serial ports")
                return
            }

            try {
                port.open(conn)
                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                // Android/CH340 quirks: explicitly assert control lines + settle
                try { port.dtr = true } catch (_: Exception) { }
                try { port.rts = true } catch (_: Exception) { }
                try { Thread.sleep(200) } catch (_: Exception) { }
            } catch (e: Exception) {
                emit("ERR open/setParameters: ${e.message}")
                try { port.close() } catch (_: Exception) { }
                return
            }

            running = true
            startKeepAlive()
            emit("Connected VID=${device.vendorId} PID=${device.productId} @115200 (keep-alive ON)")

            // IMPORTANT:
            // ModbusRtu debug output is OFF by default (too spammy for phone UI).
            // We keep controller emits + errors instead.
            val modbus = ModbusRtu(port, emit = null)
            val controller = MpptController(
                io = modbus,
                emit = { line -> emit(line) },
                onUiStatus = { st -> uiStatus = st }
            )

            job = CoroutineScope(Dispatchers.Main).launch {
                try {
                    controller.run(target, slave) { !running }
                } catch (e: Exception) {
                    emit("ERR controller: ${e.message}")
                } finally {
                    running = false
                    try { port.close() } catch (_: Exception) { }
                    stopKeepAlive()
                    emit("Stopped")
                }
            }
        }

        LaunchedEffect(permissionResult.value) {
            val res = permissionResult.value ?: return@LaunchedEffect
            permissionResult.value = null

            val (dev, granted) = res
            val sel = selected ?: return@LaunchedEffect
            if (sel.device.deviceId != dev.deviceId) return@LaunchedEffect

            if (!granted) {
                emit("ERR USB permission denied")
                return@LaunchedEffect
            }

            val slave = slaveText.toIntOrNull() ?: run { emit("ERR bad slave"); return@LaunchedEffect }
            val target = targetText.toDoubleOrNull() ?: run { emit("ERR bad target"); return@LaunchedEffect }
            startController(sel, slave, target)
        }

        val listState = rememberLazyListState()
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
        }

        @Composable
        fun ValueBox(label: String, value: String, modifier: Modifier = Modifier) {
            Surface(tonalElevation = 2.dp, modifier = modifier) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                }
            }
        }

        @Composable
        fun StatusPanel(modifier: Modifier = Modifier) {
            val st = uiStatus
            val mode = st?.mode ?: "-"
            val band = st?.band ?: "-"
            val hcc = st?.hccOffset?.let { "%+.2f".format(it) } ?: "-"

            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ValueBox("VIN", st?.vin?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f))
                    ValueBox("TGT", st?.targetVin?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f))
                    ValueBox("MODE", "$mode $band", Modifier.weight(1f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ValueBox("VOUT", st?.outv?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f))
                    ValueBox("IOUT", st?.iout?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f))
                    ValueBox("PWR", st?.pout?.let { "%.1f".format(it) } ?: "-", Modifier.weight(1f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ValueBox("VSET", st?.vset?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f))
                    ValueBox("ISET", st?.isetCmd?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f))
                    ValueBox("HCC", hcc, Modifier.weight(1f))
                }
            }
        }

        @Composable
        fun ControlsPanel(modifier: Modifier = Modifier) {
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { refreshDevices() }, enabled = !running) { Text("RD USB") }

                    Button(
                        onClick = {
                            val sel = selected ?: run { emit("ERR no device selected"); return@Button }
                            val slave = slaveText.toIntOrNull() ?: run { emit("ERR bad slave"); return@Button }
                            val target = targetText.toDoubleOrNull() ?: run { emit("ERR bad target"); return@Button }
                            startController(sel, slave, target)
                        },
                        enabled = !running
                    ) { Text("Start") }

                    Button(onClick = { stopController() }, enabled = running) { Text("Stop") }

                    Button(onClick = { copyLogsToClipboard() }) { Text("Cpy") }
                }

                var expanded by remember { mutableStateOf(false) }
                val selectedLabel = selected?.let { d ->
                    val dev = d.device
                    val name = dev.productName ?: "USB Serial"
                    "$name  VID=${dev.vendorId} PID=${dev.productId}"
                } ?: "Select USB device"

                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("USB device") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        drivers.forEach { d ->
                            val dev = d.device
                            val name = dev.productName ?: "USB Serial"
                            val label = "$name  VID=${dev.vendorId} PID=${dev.productId}"
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { selected = d; expanded = false; emit("Selected: $label") }
                            )
                        }
                        if (drivers.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No devices found (need OTG + cable)") },
                                onClick = { }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = slaveText,
                        onValueChange = { slaveText = it },
                        label = { Text("Slave") },
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { targetText = it },
                        label = { Text("Target VIN") },
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        @Composable
        fun EventsLog(modifier: Modifier = Modifier) {
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Events", style = MaterialTheme.typography.titleMedium)
                Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs) { line ->
                            Text(line, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        MaterialTheme {
            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxHeight().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ControlsPanel()
                        StatusPanel()
                    }
                    EventsLog(modifier = Modifier.fillMaxHeight().weight(1.1f))
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ControlsPanel()
                    StatusPanel()
                    EventsLog(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * KeepAliveService
 *
 * Foreground service + PARTIAL_WAKE_LOCK so the CPU keeps running even with screen off.
 * This is the standard Android approach to "keep running until the battery runs out"
 * (still subject to force-stop, OEM battery killers, and extreme low-memory situations).
 */
class KeepAliveService : Service() {

    companion object {
        private const val CH_ID = "ridenmppt_keepalive"
        private const val CH_NAME = "RidenMPPT"
        private const val NOTIF_ID = 1001
        private const val WAKE_TAG = "RidenMPPT:KeepAlive"
    }

    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CH_ID, CH_NAME, NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }

        val notif = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("RidenMPPT running")
            .setContentText("Keeping CPU awake while screen is off")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIF_ID, notif)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        try { wl?.release() } catch (_: Exception) { }
        wl = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If killed, ask system to recreate (best-effort).
        return START_STICKY
    }
}
