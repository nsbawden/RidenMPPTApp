package com.example.ridenmppt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ridenmppt.ui.theme.RidenMPPTTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // will be cleared unless setting says otherwise
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT // manifest also enforces
        setContent { MpptApp() }
    }

    @Composable
    private fun MpptApp() {
        val ctx = this
        val cfg = LocalConfiguration.current
        val isLandscape = (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE)
        val focusManager = LocalFocusManager.current

        val running by MpptBus.running.collectAsState()
        val uiStatus by MpptBus.uiStatus.collectAsState()
        val energy by MpptBus.energy.collectAsState()

        val prefs = remember { getSharedPreferences("ridenmppt", Context.MODE_PRIVATE) }
        var keepScreenOnWhileRunning by remember { mutableStateOf(prefs.getBoolean("keep_screen_on_while_running", false)) }
        var showSettings by remember { mutableStateOf(false) }

        // Apply "keep screen on" setting only while running.
        LaunchedEffect(running, keepScreenOnWhileRunning) {
            if (running && keepScreenOnWhileRunning) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        // Draft text in boxes (user edits here). Values apply to controller only on commit.
        var slaveText by remember { mutableStateOf("1") }
        var targetText by remember { mutableStateOf("32.5") }
        var hccQuietText by remember { mutableStateOf("300") }

        val logs = remember { mutableStateListOf<String>() }
        fun emitLocal(s: String) {
            logs.add(s)
            if (logs.size > 600) {
                repeat(200) { if (logs.isNotEmpty()) logs.removeAt(0) }
            }
        }

        // Collect service logs into UI list
        LaunchedEffect(Unit) {
            MpptBus.logs.collect { line ->
                logs.add(line)
                if (logs.size > 600) {
                    repeat(200) { if (logs.isNotEmpty()) logs.removeAt(0) }
                }
            }
        }

        fun copyLogsToClipboard() {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = logs.joinToString(separator = "\n", postfix = "\n")
            cm.setPrimaryClip(ClipData.newPlainText("RidenMPPT logs", text))
            emitLocal("Copied ${logs.size} lines to clipboard")
        }

        fun startServiceCmd(intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent) else ctx.startService(intent)
        }

        fun sendUpdateIfRunning(target: Double?, hccQuiet: Double?) {
            if (!running) return
            val i = Intent(ctx, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_UPDATE
                if (target != null) putExtra(KeepAliveService.EXTRA_TARGET, target)
                if (hccQuiet != null) putExtra(KeepAliveService.EXTRA_HCC_QUIET, hccQuiet)
            }
            ctx.startService(i)
        }

        fun commitSlave(): Boolean {
            val v = slaveText.toIntOrNull()
            return if (v == null) {
                emitLocal("ERR bad slave")
                false
            } else true
        }

        fun commitTarget(): Boolean {
            val v = targetText.toDoubleOrNull()
            return if (v == null) {
                emitLocal("ERR bad target")
                false
            } else {
                sendUpdateIfRunning(target = v, hccQuiet = null)
                true
            }
        }

        fun commitHccQuiet(): Boolean {
            val v = hccQuietText.toDoubleOrNull()
            return if (v == null) {
                emitLocal("ERR bad HCC quiet")
                false
            } else {
                sendUpdateIfRunning(target = null, hccQuiet = v)
                true
            }
        }

        fun startPressed() {
            focusManager.clearFocus()

            if (!commitSlave()) return
            if (!commitTarget()) return
            if (!commitHccQuiet()) return

            val slave = slaveText.toIntOrNull() ?: return
            val target = targetText.toDoubleOrNull() ?: return
            val hccQuiet = hccQuietText.toDoubleOrNull() ?: return

            val i = Intent(ctx, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_START
                putExtra(KeepAliveService.EXTRA_SLAVE, slave)
                putExtra(KeepAliveService.EXTRA_TARGET, target)
                putExtra(KeepAliveService.EXTRA_HCC_QUIET, hccQuiet)
            }
            startServiceCmd(i)
        }

        fun stopPressed() {
            focusManager.clearFocus()
            val i = Intent(ctx, KeepAliveService::class.java).apply { action = KeepAliveService.ACTION_STOP }
            ctx.startService(i)
        }

        fun resetEnergyPressed() {
            val i = Intent(ctx, KeepAliveService::class.java).apply { action = KeepAliveService.ACTION_RESET_ENERGY }
            ctx.startService(i)
        }

        val listState = rememberLazyListState()
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
        }

        @Composable
        fun ValueBox(
            label: String,
            value: String,
            modifier: Modifier = Modifier,
            valueColor: androidx.compose.ui.graphics.Color? = null
        ) {
            Surface(tonalElevation = 2.dp, modifier = modifier) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(
                        value,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        color = valueColor ?: MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        @Composable
        fun StatusPanel(modifier: Modifier = Modifier) {
            val st = uiStatus
            val mode = st?.mode ?: "-"
            val band = st?.band ?: "-"
            val hcc = st?.hccOffset?.let { "%+.2f".format(it) } ?: "-"
            val whTodayText = "%.1f".format(energy.whToday)

            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ValueBox("VIN", st?.vin?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f))
                    ValueBox("TGT", st?.targetVin?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f))
                    ValueBox("MODE", "$mode $band", Modifier.weight(1f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ValueBox("VOUT", st?.outv?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f))
                    ValueBox("IOUT", st?.iout?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f))
                    ValueBox(
                        "PWR",
                        st?.pout?.let { "%.1f".format(it) } ?: "-",
                        Modifier.weight(1f),
                        valueColor = androidx.compose.ui.graphics.Color(0xFFFFC107) // gold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ValueBox("VSET", st?.vset?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f))
                    ValueBox("ISET", st?.isetCmd?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f))
                    ValueBox("HCC", hcc, Modifier.weight(1f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ValueBox("Wh Today", whTodayText, Modifier.weight(1f))
                }
            }
        }

        @Composable
        fun ControlsPanel(modifier: Modifier = Modifier) {
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { startPressed() }, enabled = !running) { Text("Start") }
                    Button(onClick = { stopPressed() }, enabled = running) { Text("Stop") }
                    Button(onClick = { copyLogsToClipboard() }) { Text("Cpy") }
                    Button(onClick = { showSettings = true }) { Text("⚙") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = slaveText,
                        onValueChange = { slaveText = it },
                        label = { Text("Slave") },
                        enabled = !running, // slave only allowed when stopped
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = {
                                if (commitSlave()) focusManager.moveFocus(FocusDirection.Next) else focusManager.clearFocus()
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { fs ->
                                if (!fs.isFocused && !running) commitSlave()
                            },
                    )

                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { targetText = it },
                        label = { Text("Target VIN") },
                        enabled = true,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = {
                                if (commitTarget()) focusManager.moveFocus(FocusDirection.Next) else focusManager.clearFocus()
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { fs ->
                                if (!fs.isFocused) commitTarget()
                            },
                    )

                    OutlinedTextField(
                        value = hccQuietText,
                        onValueChange = { hccQuietText = it },
                        label = { Text("HCC quiet (s)") },
                        enabled = true,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                commitHccQuiet()
                                focusManager.clearFocus()
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { fs ->
                                if (!fs.isFocused) commitHccQuiet()
                            },
                    )
                }

                if (showSettings) {
                    val whSinceResetText = "%.1f".format(energy.whSinceReset)

                    AlertDialog(
                        onDismissRequest = { showSettings = false },
                        confirmButton = {
                            Button(onClick = { showSettings = false }) { Text("Close") }
                        },
                        title = { Text("Settings") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Keep screen on while running")
                                    Switch(
                                        checked = keepScreenOnWhileRunning,
                                        onCheckedChange = { v ->
                                            keepScreenOnWhileRunning = v
                                            prefs.edit().putBoolean("keep_screen_on_while_running", v).apply()
                                        }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Wh this cycle")
                                    Text(whSinceResetText, fontFamily = FontFamily.Monospace)
                                }

                                Button(onClick = { resetEnergyPressed() }) {
                                    Text("Restart Wh cycle")
                                }
                            }
                        }
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

        RidenMPPTTheme(
            darkTheme = true,
            dynamicColor = false,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
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
}
