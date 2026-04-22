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
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages both on-device and drone SD card recording simultaneously.
 * Fully instrumented with RecordingDebugLog for on-screen diagnostics.
 */
class RecordingManager(private val context: Context) {

    companion object {
        private const val TAG = "RecordingManager"
    }

    private val _isOnDeviceRecording = AtomicBoolean(false)
    private val _isDroneRecording = AtomicBoolean(false)

    private var mp4Muxer: Mp4Muxer? = null
    private var currentFilePath: String = ""

    // Codec config — auto-detected from first parameter NALs received
    private enum class CodecType { UNKNOWN, H265, H264 }
    @Volatile private var codecType = CodecType.UNKNOWN

    // H.265 parameter sets
    @Volatile private var vpsData: ByteArray? = null
    @Volatile private var spsData: ByteArray? = null
    @Volatile private var ppsData: ByteArray? = null
    @Volatile private var vpsFound = false
    @Volatile private var spsFound = false
    @Volatile private var ppsFound = false

    // H.264 parameter sets
    @Volatile private var avcSpsData: ByteArray? = null
    @Volatile private var avcPpsData: ByteArray? = null
    @Volatile private var avcSpsFound = false
    @Volatile private var avcPpsFound = false

    // Debug counters
    private val rawFrameCount = AtomicLong(0)

    // Last completed recording
    @Volatile private var _lastRecordingPath: String = ""
    @Volatile private var _lastRecordingUri: Uri? = null
    val lastRecordingPath: String get() = _lastRecordingPath
    val lastRecordingUri: Uri? get() = _lastRecordingUri

    var onStateChanged: ((onDevice: Boolean, onDrone: Boolean, filePath: String) -> Unit)? = null

    // --- Controller Record Button ---

    /**
     * Listen for the physical record button on the RC controller.
     * KeyIsRecording fires when user presses record on the controller.
     * We sync our app recording state to match.
     */
    private var controllerListenerActive = false

    fun listenForControllerRecordButton(
        onStartRequested: () -> Unit,
        onStopRequested: () -> Unit
    ) {
        if (controllerListenerActive) return
        try {
            val key = KeyTools.createKey(DJICameraKey.KeyIsRecording)
            KeyManager.getInstance().listen(key, this) { _, isRecording ->
                if (isRecording == true) {
                    RecordingDebugLog.log("Controller REC button: START")
                    if (!_isOnDeviceRecording.get()) {
                        onStartRequested()
                    }
                } else if (isRecording == false) {
                    RecordingDebugLog.log("Controller REC button: STOP")
                    if (_isOnDeviceRecording.get()) {
                        onStopRequested()
                    }
                }
            }
            controllerListenerActive = true
            RecordingDebugLog.log("Controller record button listener active")
        } catch (e: Exception) {
            RecordingDebugLog.log("FAIL: Controller button listen: ${e.message}")
        }
    }

    // --- On-Device Recording ---

    fun startOnDeviceRecording() {
        if (_isOnDeviceRecording.get()) {
            RecordingDebugLog.log("WARN: On-device recording already active")
            return
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val fileName = "capture_${timestamp}.mp4"
        val outputFile = createOutputFile(fileName)

        currentFilePath = outputFile.absolutePath
        RecordingDebugLog.log("Output file: ${outputFile.absolutePath}")
        RecordingDebugLog.log("Dir exists: ${outputFile.parentFile?.exists()}, writable: ${outputFile.parentFile?.canWrite()}")

        // Reset codec state
        codecType = CodecType.UNKNOWN
        vpsFound = false; spsFound = false; ppsFound = false
        vpsData = null; spsData = null; ppsData = null
        avcSpsFound = false; avcPpsFound = false
        avcSpsData = null; avcPpsData = null
        rawFrameCount.set(0)

        RecordingDebugLog.updateStatus {
            copy(streamListenerActive = false, rawFramesReceived = 0,
                vpsFound = false, spsFound = false, ppsFound = false,
                muxerStarted = false, muxerFrames = 0, lastNalType = -1,
                lastError = null, fileSize = 0)
        }


        val muxer = Mp4Muxer(outputFile)
        mp4Muxer = muxer
        RecordingDebugLog.log("Mp4Muxer created")

        // Attach raw stream listener
        VideoStreamManager.addRawStreamListener(rawStreamListener)
        RecordingDebugLog.updateStatus { copy(streamListenerActive = true) }
        RecordingDebugLog.log("Raw stream listener attached — waiting for NAL units...")

        _isOnDeviceRecording.set(true)
        notifyStateChanged()
        Log.i(TAG, "On-device recording started: $currentFilePath")
    }

    fun stopOnDeviceRecording() {
        if (!_isOnDeviceRecording.compareAndSet(true, false)) return

        VideoStreamManager.removeRawStreamListener()
        RecordingDebugLog.updateStatus { copy(streamListenerActive = false) }

        val muxer = mp4Muxer
        mp4Muxer = null
        muxer?.stop()

        val file = File(currentFilePath)
        val fileSize = if (file.exists()) file.length() else 0L
        RecordingDebugLog.log("Recording stopped. File: ${file.name}, size: ${fileSize} bytes, frames: ${rawFrameCount.get()}")
        RecordingDebugLog.updateStatus { copy(fileSize = fileSize) }

        if (fileSize == 0L) {
            RecordingDebugLog.log("WARNING: File is 0 bytes! Muxer started=${muxer?.isStarted}, VPS=$vpsFound SPS=$spsFound PPS=$ppsFound")
        }

        _lastRecordingPath = currentFilePath
        publishToMediaStore(currentFilePath)
        notifyStateChanged()
        Log.i(TAG, "On-device recording stopped: $currentFilePath")
    }

    private fun createOutputFile(fileName: String): File {
        val outputDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "DroneCaptures")
        if (!outputDir.exists()) {
            val created = outputDir.mkdirs()
            RecordingDebugLog.log("Created dir: $created — ${outputDir.absolutePath}")
        }
        return File(outputDir, fileName)
    }

    private fun publishToMediaStore(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists() || file.length() == 0L) {
                RecordingDebugLog.log("SKIP MediaStore: file empty/missing ($filePath)")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroneCaptures")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                ) ?: run {
                    RecordingDebugLog.log("ERROR: MediaStore insert returned null")
                    return
                }

                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }

                val update = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, update, null, null)

                _lastRecordingUri = uri
                RecordingDebugLog.log("Published to Gallery: $uri (${file.length()} bytes)")
            } else {
                @Suppress("DEPRECATION")
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val captureDir = File(moviesDir, "DroneCaptures")
                if (!captureDir.exists()) captureDir.mkdirs()
                val publicFile = File(captureDir, file.name)
                file.copyTo(publicFile, overwrite = true)

                val scanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                scanIntent.data = Uri.fromFile(publicFile)
                context.sendBroadcast(scanIntent)

                _lastRecordingUri = Uri.fromFile(publicFile)
                _lastRecordingPath = publicFile.absolutePath
                RecordingDebugLog.log("Copied to Movies: ${publicFile.absolutePath}")
            }
        } catch (e: Exception) {
            RecordingDebugLog.log("ERROR MediaStore: ${e.message}")
            Log.e(TAG, "Failed to publish to MediaStore: ${e.message}")
        }
    }

    /**
     * Raw stream callback — fires on MSDK internal thread.
     */
    private val rawStreamListener = ICameraStreamManager.ReceiveStreamListener { data, offset, length, _ ->
        if (!_isOnDeviceRecording.get()) return@ReceiveStreamListener

        val count = rawFrameCount.incrementAndGet()

        // Log first few frames and then every 100th
        if (count <= 5 || count % 100 == 0L) {
            RecordingDebugLog.log("RAW frame #$count: offset=$offset len=$length dataSize=${data.size}")
            RecordingDebugLog.updateStatus { copy(rawFramesReceived = count) }
        }

        try {
            processNalData(data, offset, length)
        } catch (e: Exception) {
            RecordingDebugLog.log("ERROR processNal: ${e.message}")
            RecordingDebugLog.updateStatus { copy(lastError = e.message) }
        }
    }

    /**
     * Parse NAL units from raw H.265 (HEVC) stream.
     *
     * IMPORTANT: DJI MSDK may deliver the stream in two ways:
     * 1. Individual NAL units with 4-byte start codes (0x00 0x00 0x00 0x01)
     * 2. Multiple NAL units concatenated in a single callback
     *
     * We handle both by scanning for start codes within the data.
     */
    private fun processNalData(data: ByteArray, offset: Int, length: Int) {
        if (length < 6 || offset + 5 >= data.size) {
            if (rawFrameCount.get() <= 3) {
                RecordingDebugLog.log("SKIP: too short len=$length offset=$offset")
            }
            return
        }

        // Check if data starts with a start code
        val hasStartCode = offset + 3 < data.size &&
                data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
                data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()

        if (!hasStartCode) {
            // Try 3-byte start code
            val has3ByteStart = offset + 2 < data.size &&
                    data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
                    data[offset + 2] == 1.toByte()

            if (rawFrameCount.get() <= 5) {
                val firstBytes = (0 until minOf(8, length)).map {
                    "%02X".format(data[offset + it])
                }.joinToString(" ")
                RecordingDebugLog.log("No 4-byte start code. 3-byte=$has3ByteStart First bytes: $firstBytes")
            }

            if (has3ByteStart) {
                // Handle 3-byte start code — NAL header at offset+3
                val nalHeaderByte = data[offset + 3].toInt()
                // H.265 NAL type: bits [9:1] of 16-bit header
                val nalType265 = (nalHeaderByte shr 1) and 0x3F
                // H.264 NAL type: lower 5 bits of first byte
                val nalType264 = nalHeaderByte and 0x1F
                val nalType = if (codecType == CodecType.H264) nalType264 else nalType265
                handleNalUnit(data, offset, length, nalType, startCodeLen = 3)
                return
            }

            // No start code — try as raw NAL unit (some DJI firmware variants omit them)
            val nalHeaderByte = data[offset].toInt()
            val nalType265 = (nalHeaderByte shr 1) and 0x3F
            val nalType264 = nalHeaderByte and 0x1F
            if (rawFrameCount.get() <= 5) {
                RecordingDebugLog.log("No start code — trying raw NAL. H265 type=$nalType265 H264 type=$nalType264")
            }
            // Pass with startCodeLen=0 — the entire buffer including the header byte is the NAL
            val nalType = if (codecType == CodecType.H264) nalType264 else nalType265
            handleNalUnit(data, offset, length, nalType, startCodeLen = 0)
            return
        }

        // Standard 4-byte start code path
        val nalHeaderByte = data[offset + 4].toInt()
        // Determine type: H.265 uses bits [9:1] of a 2-byte header; H.264 uses lower 5 bits
        // Before codec is known, try H.265 first (more common on Mini 4 Pro default settings)
        val nalType265 = (nalHeaderByte shr 1) and 0x3F
        val nalType264 = nalHeaderByte and 0x1F
        val nalType = if (codecType == CodecType.H264) nalType264 else nalType265
        handleNalUnit(data, offset, length, nalType, startCodeLen = 4)
    }

    private fun handleNalUnit(data: ByteArray, offset: Int, length: Int, nalType: Int, startCodeLen: Int) {
        val timestampUs = System.nanoTime() / 1000L
        RecordingDebugLog.updateStatus { copy(lastNalType = nalType) }

        when (nalType) {
            // H.265 parameter sets
            32 -> { // VPS — only present in H.265
                codecType = CodecType.H265
                vpsData = data.copyOfRange(offset + startCodeLen, offset + length)
                if (!vpsFound) RecordingDebugLog.log("H265 VPS found! size=${length - startCodeLen}")
                vpsFound = true
                RecordingDebugLog.updateStatus { copy(vpsFound = true) }
                tryConfigureMuxer()
            }
            33 -> { // H.265 SPS
                if (codecType == CodecType.UNKNOWN) codecType = CodecType.H265
                if (codecType == CodecType.H265) {
                    spsData = data.copyOfRange(offset + startCodeLen, offset + length)
                    if (!spsFound) RecordingDebugLog.log("H265 SPS found! size=${length - startCodeLen}")
                    spsFound = true
                    RecordingDebugLog.updateStatus { copy(spsFound = true) }
                    tryConfigureMuxer()
                }
            }
            34 -> { // H.265 PPS
                if (codecType == CodecType.H265) {
                    ppsData = data.copyOfRange(offset + startCodeLen, offset + length)
                    if (!ppsFound) RecordingDebugLog.log("H265 PPS found! size=${length - startCodeLen}")
                    ppsFound = true
                    RecordingDebugLog.updateStatus { copy(ppsFound = true) }
                    tryConfigureMuxer()
                }
            }
            // H.265 IDR frames
            19, 20 -> {
                mp4Muxer?.writeNalUnit(data, offset, length, timestampUs, isKeyFrame = true)
                val frames = mp4Muxer?.totalFrames ?: 0
                if (frames <= 1 || frames % 100 == 0L) RecordingDebugLog.log("H265 IDR written, frames: $frames")
                RecordingDebugLog.updateStatus { copy(muxerFrames = frames) }
            }
            // H.264 parameter sets
            7 -> { // H.264 SPS
                codecType = CodecType.H264
                avcSpsData = data.copyOfRange(offset + startCodeLen, offset + length)
                if (!avcSpsFound) RecordingDebugLog.log("H264 SPS found! size=${length - startCodeLen}")
                avcSpsFound = true
                RecordingDebugLog.updateStatus { copy(spsFound = true) }
                tryConfigureAvcMuxer()
            }
            8 -> { // H.264 PPS
                if (codecType == CodecType.H264) {
                    avcPpsData = data.copyOfRange(offset + startCodeLen, offset + length)
                    if (!avcPpsFound) RecordingDebugLog.log("H264 PPS found! size=${length - startCodeLen}")
                    avcPpsFound = true
                    RecordingDebugLog.updateStatus { copy(ppsFound = true) }
                    tryConfigureAvcMuxer()
                }
            }
            // H.264 IDR keyframe
            5 -> {
                mp4Muxer?.writeNalUnit(data, offset, length, timestampUs, isKeyFrame = true)
                val frames = mp4Muxer?.totalFrames ?: 0
                if (frames <= 1 || frames % 100 == 0L) RecordingDebugLog.log("H264 IDR written, frames: $frames")
                RecordingDebugLog.updateStatus { copy(muxerFrames = frames) }
            }
            else -> {
                // Non-parameter, non-IDR frame (P/B frames etc.)
                if (mp4Muxer?.isStarted == true) {
                    mp4Muxer?.writeNalUnit(data, offset, length, timestampUs, isKeyFrame = false)
                    val frames = mp4Muxer?.totalFrames ?: 0
                    if (frames % 300 == 0L) RecordingDebugLog.updateStatus { copy(muxerFrames = frames) }
                }
            }
        }
    }

    private fun tryConfigureMuxer() {
        val vps = vpsData; val sps = spsData; val pps = ppsData
        if (vpsFound && spsFound && ppsFound && vps != null && sps != null && pps != null) {
            RecordingDebugLog.log("All H265 CSD found! VPS=${vps.size}b SPS=${sps.size}b PPS=${pps.size}b")
            mp4Muxer?.configureHevcCsd(vps, sps, pps)
            val started = mp4Muxer?.isStarted == true
            RecordingDebugLog.log("HEVC muxer started: $started")
            RecordingDebugLog.updateStatus { copy(muxerStarted = started) }
            if (!started) RecordingDebugLog.log("ERROR: HEVC muxer failed to start!")
        }
    }

    private fun tryConfigureAvcMuxer() {
        val sps = avcSpsData; val pps = avcPpsData
        if (avcSpsFound && avcPpsFound && sps != null && pps != null) {
            RecordingDebugLog.log("All H264 CSD found! SPS=${sps.size}b PPS=${pps.size}b")
            mp4Muxer?.configureAvcCsd(sps, pps)
            val started = mp4Muxer?.isStarted == true
            RecordingDebugLog.log("AVC muxer started: $started")
            RecordingDebugLog.updateStatus { copy(muxerStarted = started) }
            if (!started) RecordingDebugLog.log("ERROR: AVC muxer failed to start!")
        }
    }

    // --- Drone SD Card Recording ---

    fun startDroneRecording() {
        if (_isDroneRecording.get()) return
        try {
            val key = KeyTools.createKey(DJICameraKey.KeyStartRecord)
            KeyManager.getInstance().performAction(key, EmptyMsg(),
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        _isDroneRecording.set(true)
                        notifyStateChanged()
                        RecordingDebugLog.log("Drone SD recording started")
                    }
                    override fun onFailure(error: IDJIError) {
                        RecordingDebugLog.log("Drone SD start FAILED: ${error.description()}")
                    }
                }
            )
        } catch (e: Exception) {
            RecordingDebugLog.log("Drone SD start exception: ${e.message}")
        }
    }

    fun stopDroneRecording() {
        if (!_isDroneRecording.get()) return
        try {
            val key = KeyTools.createKey(DJICameraKey.KeyStopRecord)
            KeyManager.getInstance().performAction(key, EmptyMsg(),
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        _isDroneRecording.set(false)
                        notifyStateChanged()
                        RecordingDebugLog.log("Drone SD recording stopped")
                    }
                    override fun onFailure(error: IDJIError) {
                        _isDroneRecording.set(false)
                        notifyStateChanged()
                        RecordingDebugLog.log("Drone SD stop FAILED: ${error.description()}")
                    }
                }
            )
        } catch (e: Exception) {
            _isDroneRecording.set(false)
            notifyStateChanged()
        }
    }

    // --- Combined ---

    fun startBothRecording() {
        RecordingDebugLog.log("=== START RECORDING ===")
        startOnDeviceRecording()
        startDroneRecording()
    }

    fun stopBothRecording() {
        RecordingDebugLog.log("=== STOP RECORDING ===")
        stopOnDeviceRecording()
        stopDroneRecording()
    }

    fun cleanup() {
        stopOnDeviceRecording()
        stopDroneRecording()
        try {
            KeyManager.getInstance().cancelListen(this)
        } catch (_: Exception) {}
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
