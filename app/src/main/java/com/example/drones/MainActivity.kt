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
import dji.v5.manager.aircraft.product.ProductType
import dji.v5.manager.datacenter.MediaDataCenter
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private var videoCapture: VideoCapture? = null
    private var muxer: Mp4Muxer? = null
    private var isRecording = false
    private var lastOutputFile = ""

    private val permissionsRequired = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        Log.d("MainActivity", "Permissions: $permissions")
        if (allGranted) {
            Log.d("MainActivity", "✓ All permissions granted")
        } else {
            Log.e("MainActivity", "✗ Some permissions denied")
        }
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
        if (isRecording) {
            Log.w("MainActivity", "Already recording")
            return
        }

        // Create output directory
        val outputDir = File(getExternalFilesDir(null), "DroneCaptures")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        // Create timestamped filename
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val outputFile = File(outputDir, "drone_capture_$timestamp.mp4")

        muxer = Mp4Muxer(outputFile)
        videoCapture = VideoCapture(muxer!!)

        try {
            videoCapture?.start()
            isRecording = true
            lastOutputFile = outputFile.absolutePath
            Log.d("MainActivity", "Recording started → $lastOutputFile")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start recording: ${e.message}", e)
            isRecording = false
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            Log.w("MainActivity", "Not recording")
            return
        }

        try {
            videoCapture?.stop()
            muxer?.stop()
            isRecording = false
            Log.d("MainActivity", "Recording stopped → $lastOutputFile")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to stop recording: ${e.message}", e)
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
    var droneStatus by remember { mutableStateOf("Initializing MSDK...") }
    var recordingState by remember { mutableStateOf(false) }
    var outputPath by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // Monitor drone connection status
        val sdkManager = SDKManager.getInstance()
        // Status updates via callbacks in DronesApplication
        droneStatus = "Ready"
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "DJI Video Capture",
                fontSize = 28.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )

            // Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("System Status", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("MSDK: $droneStatus", fontSize = 12.sp)
                    Text(
                        "Recording: ${if (isRecording()) "YES" else "NO"}",
                        fontSize = 12.sp,
                        color = if (isRecording()) Color.Red else Color.Green
                    )
                }
            }

            // Record Button
            Button(
                onClick = {
                    if (isRecording()) {
                        onStop()
                        recordingState = false
                        outputPath = lastFile()
                    } else {
                        onRecord()
                        recordingState = true
                    }
                },
                modifier = Modifier
                    .height(56.dp)
                    .fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording()) Color.Red else Color.Green
                )
            ) {
                Text(
                    if (isRecording()) "STOP RECORDING" else "START RECORDING",
                    fontSize = 16.sp,
                    color = Color.White
                )
            }

            // Output File Display
            if (outputPath.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9E6))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Last Recording", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            outputPath,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 3
                        )
                    }
                }
            }

            // Info
            Text(
                "Connect RC-N3 controller via USB\nPower on drone before recording",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}