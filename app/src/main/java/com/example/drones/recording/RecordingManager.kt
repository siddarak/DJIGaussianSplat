package com.example.drones.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.drones.sdk.VideoStreamManager
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.interfaces.ICameraStreamManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages both on-device and drone SD card recording simultaneously.
 *
 * Thread safety model:
 * - On-device booleans use AtomicBoolean — rawStreamListener fires on MSDK thread
 * - SPS/PPS state uses @Volatile — written once, read many times
 * - Mp4Muxer is itself synchronized internally
 * - Drone recording state uses AtomicBoolean for callback-thread safety
 */
class RecordingManager(private val context: Context) {

    companion object {
        private const val TAG = "RecordingManager"
    }

    // AtomicBooleans for flags accessed from both UI thread and MSDK callback thread
    private val _isOnDeviceRecording = AtomicBoolean(false)
    private val _isDroneRecording = AtomicBoolean(false)

    private var mp4Muxer: Mp4Muxer? = null
    private var currentFilePath: String = ""

    // Volatile — written once per recording session on MSDK callback thread, read on many
    // H.265 codec config: VPS (32), SPS (33), PPS (34) — Mini 4 Pro streams H.265
    @Volatile private var vpsData: ByteArray? = null
    @Volatile private var spsData: ByteArray? = null
    @Volatile private var ppsData: ByteArray? = null
    @Volatile private var vpsFound = false
    @Volatile private var spsFound = false
    @Volatile private var ppsFound = false

    // Last completed recording — for playback/viewing
    @Volatile private var _lastRecordingPath: String = ""
    @Volatile private var _lastRecordingUri: Uri? = null
    val lastRecordingPath: String get() = _lastRecordingPath
    val lastRecordingUri: Uri? get() = _lastRecordingUri

    // State callbacks to ViewModel — called after any state change
    var onStateChanged: ((onDevice: Boolean, onDrone: Boolean, filePath: String) -> Unit)? = null

    // --- On-Device Recording ---

    fun startOnDeviceRecording() {
        if (_isOnDeviceRecording.get()) {
            Log.w(TAG, "On-device recording already active")
            return
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val fileName = "capture_${timestamp}.mp4"
        val outputFile = createPublicOutputFile(fileName) ?: createPrivateOutputFile(fileName)

        currentFilePath = outputFile.absolutePath

        // Reset H.265 codec config — must happen before attaching listener
        vpsFound = false
        spsFound = false
        ppsFound = false
        vpsData = null
        spsData = null
        ppsData = null

        // Create muxer before setting flag to avoid partial-init race
        val muxer = Mp4Muxer(outputFile)
        mp4Muxer = muxer

        // Attach listener — from this point MSDK thread may call rawStreamListener
        VideoStreamManager.addRawStreamListener(rawStreamListener)

        _isOnDeviceRecording.set(true)
        notifyStateChanged()
        Log.i(TAG, "On-device recording started: $currentFilePath")
    }

    fun stopOnDeviceRecording() {
        if (!_isOnDeviceRecording.compareAndSet(true, false)) return

        // Remove listener first — stops new frames arriving before muxer is torn down
        VideoStreamManager.removeRawStreamListener()

        val muxer = mp4Muxer
        mp4Muxer = null
        muxer?.stop()

        _lastRecordingPath = currentFilePath
        registerWithMediaStore(currentFilePath)
        notifyStateChanged()
        Log.i(TAG, "On-device recording stopped: $currentFilePath")
    }

    private fun createPublicOutputFile(fileName: String): File? {
        return try {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val captureDir = File(moviesDir, "DroneCaptures")
            if (!captureDir.exists()) captureDir.mkdirs()
            File(captureDir, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create public output file: ${e.message}")
            null
        }
    }

    private fun createPrivateOutputFile(fileName: String): File {
        val outputDir = File(context.getExternalFilesDir(null), "DroneCaptures")
        if (!outputDir.exists()) outputDir.mkdirs()
        return File(outputDir, fileName)
    }

    private fun registerWithMediaStore(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists() || file.length() == 0L) {
                Log.w(TAG, "Skipping MediaStore registration — file empty or missing: $filePath")
                return
            }
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.SIZE, file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroneCaptures")
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, filePath)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            )
            _lastRecordingUri = uri
            Log.i(TAG, "Registered with MediaStore: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register with MediaStore: ${e.message}")
        }
    }

    /**
     * Raw stream callback — fires on MSDK internal thread.
     * Must be fast and non-blocking.
     */
    private val rawStreamListener = ICameraStreamManager.ReceiveStreamListener { data, offset, length, _ ->
        if (!_isOnDeviceRecording.get()) return@ReceiveStreamListener
        try {
            processNalData(data, offset, length)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing stream data: ${e.message}")
        }
    }

    /**
     * Parse NAL units from raw H.265 (HEVC) stream.
     *
     * H.265 NAL unit header: 2 bytes — type = (byte[4] >> 1) & 0x3F
     *   32 = VPS (Video Parameter Set)
     *   33 = SPS (Sequence Parameter Set)
     *   34 = PPS (Picture Parameter Set)
     *   19 = IDR_W_RADL (keyframe)
     *   20 = IDR_N_LP  (keyframe)
     *   all others = non-key frames
     *
     * Start code (0x00 0x00 0x00 0x01) occupies bytes [0..3],
     * so NAL header byte is at offset+4.
     *
     * Mini 4 Pro streams H.265 — confirmed by DJI MSDK documentation.
     */
    private fun processNalData(data: ByteArray, offset: Int, length: Int) {
        // Guard: need at least start code (4 bytes) + 2-byte NAL header
        if (length < 6 || offset + 5 >= data.size) return

        // H.265: nal_unit_type = (first_byte >> 1) & 0x3F
        val nalType = (data[offset + 4].toInt() shr 1) and 0x3F
        val timestampUs = System.nanoTime() / 1000L

        when (nalType) {
            32 -> { // VPS — store but not needed for muxer config; used as extra guard
                vpsData = data.copyOfRange(offset + 4, offset + length)
                vpsFound = true
            }
            33 -> { // SPS
                spsData = data.copyOfRange(offset + 4, offset + length)
                spsFound = true
                tryConfigureMuxer()
            }
            34 -> { // PPS
                ppsData = data.copyOfRange(offset + 4, offset + length)
                ppsFound = true
                tryConfigureMuxer()
            }
            19, 20 -> { // IDR keyframe (IDR_W_RADL or IDR_N_LP)
                mp4Muxer?.writeNalUnit(data, offset, length, timestampUs, isKeyFrame = true)
            }
            else -> {
                // Only write if muxer is ready (SPS/PPS received)
                if (mp4Muxer?.isStarted == true) {
                    mp4Muxer?.writeNalUnit(data, offset, length, timestampUs, isKeyFrame = false)
                }
            }
        }
    }

    private fun tryConfigureMuxer() {
        // Local copies for null-safety — volatile reads don't guarantee combined atomicity
        val vps = vpsData
        val sps = spsData
        val pps = ppsData
        // H.265 requires VPS + SPS + PPS all present before muxer can be configured.
        // Android MediaMuxer for HEVC needs all three concatenated in csd-0.
        if (vpsFound && spsFound && ppsFound && vps != null && sps != null && pps != null) {
            mp4Muxer?.configureHevcCsd(vps, sps, pps)
        }
    }

    // --- Drone SD Card Recording ---

    /**
     * Send start-record command to the drone.
     * Drone records in full quality to its SD card.
     */
    fun startDroneRecording() {
        if (_isDroneRecording.get()) {
            Log.w(TAG, "Drone recording already active")
            return
        }
        try {
            val key = KeyTools.createKey(DJICameraKey.KeyStartRecord)
            KeyManager.getInstance().performAction(key, EmptyMsg(),
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        _isDroneRecording.set(true)
                        notifyStateChanged()
                        Log.i(TAG, "Drone SD card recording started")
                    }
                    override fun onFailure(error: IDJIError) {
                        // State stays false — UI correctly reflects drone not recording
                        Log.e(TAG, "Failed to start drone recording: ${error.description()}")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error sending start record command: ${e.message}")
        }
    }

    /**
     * Send stop-record command to the drone.
     */
    fun stopDroneRecording() {
        if (!_isDroneRecording.get()) return
        try {
            val key = KeyTools.createKey(DJICameraKey.KeyStopRecord)
            KeyManager.getInstance().performAction(key, EmptyMsg(),
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        _isDroneRecording.set(false)
                        notifyStateChanged()
                        Log.i(TAG, "Drone SD card recording stopped")
                    }
                    override fun onFailure(error: IDJIError) {
                        // Force state to false — assume stopped to keep UI consistent
                        _isDroneRecording.set(false)
                        notifyStateChanged()
                        Log.e(TAG, "Failed to stop drone recording: ${error.description()}")
                    }
                }
            )
        } catch (e: Exception) {
            _isDroneRecording.set(false)
            notifyStateChanged()
            Log.e(TAG, "Error sending stop record command: ${e.message}")
        }
    }

    // --- Combined ---

    fun startBothRecording() {
        startOnDeviceRecording()
        startDroneRecording()
    }

    fun stopBothRecording() {
        stopOnDeviceRecording()
        stopDroneRecording()
    }

    fun cleanup() {
        stopOnDeviceRecording()
        stopDroneRecording()
    }

    val isRecording: Boolean get() = _isOnDeviceRecording.get() || _isDroneRecording.get()
    val isOnDevice: Boolean get() = _isOnDeviceRecording.get()
    val isDrone: Boolean get() = _isDroneRecording.get()
    val filePath: String get() = currentFilePath

    private fun notifyStateChanged() {
        onStateChanged?.invoke(
            _isOnDeviceRecording.get(),
            _isDroneRecording.get(),
            currentFilePath
        )
    }
}
