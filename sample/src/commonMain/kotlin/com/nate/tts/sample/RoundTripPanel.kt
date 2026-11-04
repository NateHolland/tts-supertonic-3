package com.nate.tts.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nate.tts.api.TtsSdk
import kotlinx.coroutines.launch

/**
 * Sample UI for the acoustic TTS → STT round-trip rig. Plays the phrase corpus through the
 * speaker and reports what the mic heard back, so the garble bug can be measured before/after
 * the playback fix. Needs mic permission granted.
 */
@Composable
fun RoundTripPanel(tts: TtsSdk, voiceStyle: String?) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<RoundTripSummary?>(null) }
    val results: SnapshotStateList<RoundTripResult> = remember { mutableStateListOf() }

    fun launch(rapid: Boolean) {
        running = true
        mode = if (rapid) "Rapid (stress)" else "Sequential"
        summary = null
        results.clear()
        scope.launch {
            val rig = RoundTripRig(tts)
            summary = if (rapid) {
                rig.runRapid(ROUND_TRIP_PHRASES, voiceStyle) { results.add(it) }
            } else {
                rig.runSequential(ROUND_TRIP_PHRASES, voiceStyle) { results.add(it) }
            }
            running = false
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("TTS → STT round trip", style = MaterialTheme.typography.titleSmall)
            Text(
                "Speaks each phrase and checks the mic hears it back. Grant mic permission first.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { launch(rapid = false) },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) { Text("Sequential") }
                OutlinedButton(
                    onClick = { launch(rapid = true) },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) { Text("Rapid (stress)") }
            }

            when {
                running -> Text(
                    "Running ${mode ?: ""}… ${results.size}/${ROUND_TRIP_PHRASES.size}",
                    style = MaterialTheme.typography.bodySmall,
                )
                summary != null -> {
                    val s = summary!!
                    Text(
                        "${mode ?: ""}: matched ${s.matched}/${s.total}  •  match rate ${pct(s.matchRate)}  •  mean WER ${pct(s.meanWer)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (s.matchRate >= 0.9) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    )
                }
            }

            results.forEach { r ->
                Column {
                    Text(
                        "${if (r.matched) "✓" else "✗"} ${r.sent}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (r.matched) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "   heard: ${r.heard.ifBlank { if (r.timedOut) "— (nothing)" else "—" }}  •  WER ${pct(r.wordErrorRate)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
            }
        }
    }
}

private fun pct(x: Double): String = "${(x * 100 + 0.5).toInt()}%"
