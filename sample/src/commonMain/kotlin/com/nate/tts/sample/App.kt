package com.nate.tts.sample

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nate.tts.api.TtsSdk
import com.nate.tts.callback.ttsCallback
import com.nate.tts.config.TtsConfig
import com.nate.tts.engine.getBundledModelPath
import com.nate.tts.model.SpeakRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MODEL_ID = "Supertone/supertonic-2"

/** Platform-specific initialization (e.g. Android context setup). */
expect fun platformInit()


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(autoRun: String? = null, modelDirOverride: String? = null) {
    val scope = rememberCoroutineScope()

    var sdk by remember { mutableStateOf<TtsSdk?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("Loading model...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var inputText by remember { mutableStateOf("Hello, this is a test of the text to speech SDK.") }
    var selectedVoiceIndex by remember { mutableIntStateOf(0) }
    var language by remember { mutableStateOf("en") }
    var speechRate by remember { mutableFloatStateOf(1.0f) }
    var isRecording by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            // A `-e model_dir <abs path>` launch extra points straight at an already-present
            // model dir (e.g. one copied from the Kima app), skipping asset extraction.
            statusMessage = "Extracting model from assets..."
            val modelPath = modelDirOverride ?: withContext(Dispatchers.Default) {
                getBundledModelPath(MODEL_ID)
            }

            if (modelPath == null) {
                loadError = "Bundled model not found. Ensure supertonic-2 model files are included."
                isLoading = false
                return@LaunchedEffect
            }

            statusMessage = "Loading ONNX models..."
            val config = TtsConfig(modelDir = modelPath, defaultVoiceStyle = "M2")
            val newSdk = TtsSdk(config)
            withContext(Dispatchers.Default) { newSdk.initialize() }

            newSdk.setCallback(ttsCallback {
                onStart { id -> statusMessage = "Speaking: $id" }
                onDone { id -> statusMessage = "Done: $id" }
                onError { id, error ->
                    statusMessage = "Error"
                    errorMessage = "Error ($id): ${error.message}"
                }
            })

            sdk = newSdk
            statusMessage = "Ready"
            isLoading = false
        } catch (e: Exception) {
            loadError = "Failed to load model: ${e.message}"
            isLoading = false
        }
    }

    // Headless driver: launch with `-e roundtrip sequential|rapid|both` to run the rig once
    // the model is ready and log results to logcat (grep "KimaRig|"). No taps needed.
    LaunchedEffect(sdk, autoRun) {
        val ready = sdk ?: return@LaunchedEffect
        val mode = autoRun ?: return@LaunchedEffect
        val rig = RoundTripRig(ready)
        when (mode.lowercase()) {
            "sequential" -> rig.runSequential(ROUND_TRIP_PHRASES, voiceStyle = null)
            "rapid" -> rig.runRapid(ROUND_TRIP_PHRASES, voiceStyle = null)
            "both" -> {
                rig.runSequential(ROUND_TRIP_PHRASES, voiceStyle = null)
                rig.runRapid(ROUND_TRIP_PHRASES, voiceStyle = null)
            }
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("TTS Supertonic 2") }) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isLoading) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                } else if (loadError != null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Model Load Failed",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            loadError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    val currentSdk = sdk ?: return@Scaffold
                    val voices by currentSdk.availableVoices.collectAsState()
                    val isSpeaking by currentSdk.isSpeaking.collectAsState()

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Model: supertonic-2", style = MaterialTheme.typography.titleSmall)
                            Text("Engine: ONNX Runtime", style = MaterialTheme.typography.bodySmall)
                            Text("Voices: ${voices.size}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Available: ${voices.joinToString { "${it.id} (${it.gender ?: "?"})" }}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = { Text("Text to speak") },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        maxLines = 5,
                    )

                    AnimatedVisibility(visible = voices.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it }
                        ) {
                            OutlinedTextField(
                                value = voices.getOrNull(selectedVoiceIndex)
                                    ?.let { "${it.id} (${it.gender ?: "?"})" } ?: "-",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Voice") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                voices.forEachIndexed { index, voice ->
                                    DropdownMenuItem(
                                        text = { Text("${voice.id} (${voice.gender ?: "?"})") },
                                        onClick = {
                                            selectedVoiceIndex = index
                                            currentSdk.setVoice(voice)
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    run {
                        val languages = listOf(
                            "en" to "English",
                            "ko" to "Korean",
                            "es" to "Spanish",
                            "pt" to "Portuguese",
                            "fr" to "French",
                        )
                        var langExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = langExpanded,
                            onExpandedChange = { langExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = languages.firstOrNull { it.first == language }
                                    ?.let { "${it.second} (${it.first})" } ?: language,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Language") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(langExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                            )
                            ExposedDropdownMenu(
                                expanded = langExpanded,
                                onDismissRequest = { langExpanded = false }
                            ) {
                                languages.forEach { (code, name) ->
                                    DropdownMenuItem(
                                        text = { Text("$name ($code)") },
                                        onClick = {
                                            language = code
                                            langExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Column {
                        val rateText = "${(speechRate * 10).toInt() / 10.0}x"
                        Text("Speech Rate: $rateText", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = speechRate,
                            onValueChange = { speechRate = it },
                            valueRange = 0.5f..2.0f,
                            steps = 15
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                val voice = voices.getOrNull(selectedVoiceIndex)
                                scope.launch {
                                    currentSdk.speak(
                                        SpeakRequest(
                                            text = inputText,
                                            voiceStyle = voice?.id,
                                            language = language.ifBlank { null },
                                            speechRate = speechRate,
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = inputText.isNotBlank() && !isSpeaking && !isRecording,
                        ) { Text("Speak") }

                        OutlinedButton(
                            onClick = {
                                val voice = voices.getOrNull(selectedVoiceIndex)
                                isRecording = true
                                errorMessage = null
                                statusMessage = "Saving to file..."
                                scope.launch {
                                    try {
                                        val outputPath = getRecordingOutputPath()
                                        val request = SpeakRequest(
                                            text = inputText,
                                            voiceStyle = voice?.id,
                                            language = language.ifBlank { null },
                                            speechRate = speechRate,
                                        )
                                        withContext(Dispatchers.Default) {
                                            currentSdk.synthesiseToFile(request, outputPath)
                                        }
                                        statusMessage = "Saved! Opening player..."
                                        openInMediaPlayer(outputPath)
                                        statusMessage = "Saved to file"
                                    } catch (e: Exception) {
                                        errorMessage = "Save failed: ${e.message}"
                                        statusMessage = "Error"
                                    } finally {
                                        isRecording = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = inputText.isNotBlank() && !isSpeaking && !isRecording,
                        ) { Text(if (isRecording) "Saving..." else "Save") }

                        Button(
                            onClick = { currentSdk.stop() },
                            modifier = Modifier.weight(1f),
                            enabled = isSpeaking,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSpeaking) MaterialTheme.colorScheme.error else Color.Gray
                            ),
                        ) { Text("Stop") }
                    }

                    Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    errorMessage?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    RoundTripPanel(
                        tts = currentSdk,
                        voiceStyle = voices.getOrNull(selectedVoiceIndex)?.id,
                    )
                }
            }
        }
    }
}
