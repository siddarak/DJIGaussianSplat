package com.example.drones

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.drones.ui.theme.DronesTheme
import dji.v5.manager.SDKManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private var videoCapture: VideoCapture? = null
    private var muxer: Mp4Muxer? = null
    private var isRecording = false
    private var lastOutputFile = ""

    private val permissionsRequired = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        Log.d("MainActivity", "Permissions: $permissions")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request permissions on app start
        requestPermissions()

        setContent {
            DronesTheme {
                RecorderScreen(
                    onRecord = { startRecording() },
                    onStop = { stopRecording() },
                    isRecording = { isRecording },
                    lastFile = { lastOutputFile }
                )
            }
        }
    }

    private fun requestPermissions() {
        val notGranted = permissionsRequired.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startRecording() {
        if (isRecording) return

        val outputDir = File(getExternalFilesDir(null), "DroneCaptures")
        if (!outputDir.exists()) outputDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val outputFile = File(outputDir, "drone_capture_$timestamp.mp4")

        muxer = Mp4Muxer(outputFile)
        videoCapture = VideoCapture(muxer!!)

        try {
            videoCapture?.start()
            isRecording = true
            lastOutputFile = outputFile.absolutePath
            Log.d("MainActivity", "Recording started")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start: ${e.message}")
            isRecording = false
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        try {
            videoCapture?.stop()
            muxer?.stop()
            isRecording = false
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to stop: ${e.message}")
        }
    }
}

@Composable
fun RecorderScreen(
    onRecord: () -> Unit,
    onStop: () -> Unit,
    isRecording: () -> Boolean,
    lastFile: () -> String
) {
    var droneStatus by remember { mutableStateOf("Initializing...") }
    var outputPath by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val sdkManager = SDKManager.getInstance()
        droneStatus = if (sdkManager.isRegistered) "Ready" else "Registering..."
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().statusBarsPadding()
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("DJI Video Capture", fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("System Status", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("MSDK: $droneStatus", fontSize = 12.sp)
                    Text("Recording: ${if (isRecording()) "YES" else "NO"}",
                         fontSize = 12.sp, color = if (isRecording()) Color.Red else Color.Green)
                }
            }

            Button(
                onClick = {
                    if (isRecording()) {
                        onStop()
                        outputPath = lastFile()
                    } else {
                        onRecord()
                    }
                },
                modifier = Modifier.height(56.dp).fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = if (isRecording()) Color.Red else Color.Green)
            ) {
                Text(if (isRecording()) "STOP RECORDING" else "START RECORDING", color = Color.White)
            }

            if (outputPath.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Last Recording", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(outputPath, fontSize = 10.sp, maxLines = 3)
                    }
                }
            }
        }
    }
}
