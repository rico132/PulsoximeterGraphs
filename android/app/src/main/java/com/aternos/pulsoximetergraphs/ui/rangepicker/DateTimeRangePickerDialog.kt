package com.aternos.pulsoximetergraphs.ui.rangepicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Material 3 [DateRangePicker] for start/end dates, composed with a [TimePicker] for
 * start/end time-of-day (calendar-meeting-style range selection across three steps: pick
 * dates, pick start time, pick end time), emitting a single `ClosedRange<Instant>` on confirm.
 *
 * [DateRangePicker] operates on UTC-midnight epoch millis internally; the calendar day it
 * reports is then combined with the chosen time-of-day and interpreted in the device's
 * current default zone (same documented-limitation assumption as CsvParser — none of this
 * app's data carries timezone info to begin with).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeRangePickerDialog(
    initialRange: ClosedRange<Instant>,
    onDismiss: () -> Unit,
    onConfirm: (ClosedRange<Instant>) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val initialStart = initialRange.start.atZone(zone)
    val initialEnd = initialRange.endInclusive.atZone(zone)

    val dateRangeState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart.toLocalDate().toEpochMilliUtc(),
        initialSelectedEndDateMillis = initialEnd.toLocalDate().toEpochMilliUtc(),
    )
    val startTimeState = rememberTimePickerState(
        initialHour = initialStart.hour,
        initialMinute = initialStart.minute,
        is24Hour = true,
    )
    val endTimeState = rememberTimePickerState(
        initialHour = initialEnd.hour,
        initialMinute = initialEnd.minute,
        is24Hour = true,
    )

    var step by remember { mutableIntStateOf(0) }

    fun confirmAndClose() {
        val startMillis = dateRangeState.selectedStartDateMillis ?: initialStart.toLocalDate().toEpochMilliUtc()
        val endMillis = dateRangeState.selectedEndDateMillis ?: initialEnd.toLocalDate().toEpochMilliUtc()
        val startDate = startMillis.utcMillisToLocalDate()
        val endDate = endMillis.utcMillisToLocalDate()

        val startInstant = startDate
            .atTime(LocalTime.of(startTimeState.hour, startTimeState.minute))
            .atZone(zone)
            .toInstant()
        val endInstant = endDate
            .atTime(LocalTime.of(endTimeState.hour, endTimeState.minute))
            .atZone(zone)
            .toInstant()

        val range = if (!startInstant.isAfter(endInstant)) startInstant..endInstant else endInstant..startInstant
        onConfirm(range)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp)) {
            // DateRangePicker renders its calendar months in its own internal LazyColumn, which
            // refuses to be measured with the infinite height that Modifier.verticalScroll()
            // hands its child — so the date-range step must NOT be wrapped in verticalScroll
            // (that combination throws immediately on layout: "Vertically scrollable component
            // was measured with an infinity maximum height constraints"). TimePicker has no such
            // internal lazy layout, so it's safe to make scrollable for smaller screens.
            when (step) {
                0 -> {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text("Select date range", modifier = Modifier.padding(16.dp))
                        DateRangePicker(state = dateRangeState, modifier = Modifier.fillMaxWidth())
                    }
                }
                1 -> {
                    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        Text("Start time", modifier = Modifier.padding(16.dp))
                        TimePicker(state = startTimeState, modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
                else -> {
                    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        Text("End time", modifier = Modifier.padding(16.dp))
                        TimePicker(state = endTimeState, modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                if (step > 0) {
                    OutlinedButton(onClick = { step -= 1 }) { Text("Back") }
                }
                Button(
                    onClick = {
                        if (step < 2) step += 1 else confirmAndClose()
                    },
                ) {
                    Text(if (step < 2) "Next" else "Apply")
                }
            }
        }
    }
}

private fun LocalDate.toEpochMilliUtc(): Long =
    this.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.utcMillisToLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
