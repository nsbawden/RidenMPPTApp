package com.example.ridenmppt

import com.example.ridenmppt.ui.MpptController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class EnergyStatus(
    val whToday: Double = 0.0,
    val whSinceReset: Double = 0.0,
)

object MpptBus {
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _uiStatus = MutableStateFlow<MpptController.UiStatus?>(null)
    val uiStatus = _uiStatus.asStateFlow()

    private val _energy = MutableStateFlow(EnergyStatus())
    val energy = _energy.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs = _logs.asSharedFlow()

    fun setRunning(v: Boolean) { _running.value = v }
    fun setUiStatus(v: MpptController.UiStatus?) { _uiStatus.value = v }
    fun setEnergy(whToday: Double, whSinceReset: Double) { _energy.value = EnergyStatus(whToday, whSinceReset) }
    fun log(line: String) { _logs.tryEmit(line) }
}
