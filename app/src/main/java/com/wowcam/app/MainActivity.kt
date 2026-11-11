package com.wowcam.app

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Range
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import com.wowcam.app.audio.StudioAudioRecorder
import com.wowcam.app.databinding.ActivityMainBinding
import com.wowcam.app.effect.ColorFilters
import com.wowcam.app.effect.ColorMatrixEffect
import com.wowcam.app.effect.ColorMatrixSurfaceProcessor
import com.wowcam.app.effect.FilterPreset
import com.wowcam.app.media.Mp4Muxer
import com.wowcam.app.media.VideoDestination
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * wow cam - a single-screen, portrait-only camera.
 *
 * CameraX records the video track only. A parallel [StudioAudioRecorder] owns its own
 * AudioRecord session so NoiseSuppressor/AcousticEchoCanceler can be attached, and
 * [Mp4Muxer] merges both tracks without re-encoding at the end of every clip.
 */
@ExperimentalCamera2Interop
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    private val mainHandler = Handler(Looper.getMainLooper())
    // Do not call ContextCompat.getMainExecutor(this) at property-initialization time:
    // field initializers run before the Activity gets a base context, and the call
    // crashes with NullPointerException inside Context.getMainExecutor().
    private val mainExecutor: Executor = Executor { command -> mainHandler.post(command) }

    private val audioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WowCamAudioControl").apply { isDaemon = true }
    }
    // Heavy mux + commit runs here on Dispatchers.IO instead of the main thread, so the
    // UI stays responsive while the MP4 is assembled.
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Provider creation, camera capability probes and other one-off work. Never used for
    // per-frame data; audio and muxing have their own single-thread executors below.
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WowCamControl").apply { isDaemon = true }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var videoCapture: VideoCapture<Recorder>? = null
    private var cameraReady = false

    private var recording: Recording? = null
    private var audioRecorder: StudioAudioRecorder? = null
    private var destination: VideoDestination? = null
    private var tempVideoFile: File? = null

    private var audioResult: StudioAudioRecorder.Result? = null
    private var videoStartNs = 0L
    private var videoFinalized = false
    private var audioStopCompleted = false
    private var muxStarted = false
    private var tooShort = false

    private var savedUri: Uri? = null
    private var recordStartElapsed = 0L
    private var permissionAskedBefore = false
    private var cameraProviderRequested = false

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private var phase = Phase.IDLE

    private var selectedFps = DEFAULT_FPS
    private var effectiveFps = DEFAULT_FPS
    private var supports60Fps = false
    private var supportsEis = false
    private var supportsOis = false
    private var supportsUhd = false
    private var selectedQuality = VideoQuality.FHD
    private var isLowRamDevice = false
    private var selectedStorage = StorageMode.GALLERY
    private var sdTreeUri: Uri? = null

    // Hardware controls
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var torchOn = false
    private var hasFlashUnit = false
    private var switchingCamera = false

    // Zoom
    private var boundCamera: Camera? = null
    private var cameraControl: CameraControl? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var currentZoomRatio = 1f
    private var zoomMinRatio = 1f
    private var zoomMaxRatio = 5f
    private var zoomLinearMin = 0f
    private var zoomLinearMax = 1f
    private var sliderTracking = false
    private lateinit var scaleDetector: ScaleGestureDetector

    // Live cinematic look
    private var colorEffect: ColorMatrixEffect? = null
    private var effectActive = false
    private var effectDisabled = false
    private var rebindNeeded = false
    private var selectedFilter = FilterPreset.NORMAL

    // Countdown timer
    private var timerMode = TimerMode.OFF
    private var countdownRunnable: Runnable? = null
    private var countdownRemaining = 0

    private var thermalWatchTick = 0
    private var meterTicks = 0

    private var pulseAnimator: ObjectAnimator? = null
    private var ringPulseAnimator: ObjectAnimator? = null

    private val tempDir: File by lazy { File(cacheDir, "wowcam") }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (phase != Phase.RECORDING) return
            val elapsed = SystemClock.elapsedRealtime() - recordStartElapsed
            binding.timerText.text = formatDuration(elapsed)
            thermalWatchTick = (thermalWatchTick + 1) % THERMAL_WATCH_TICKS
            meterTicks = (meterTicks + 1) % METER_UPDATE_TICKS
            if (meterTicks == 0) {
                val level = audioRecorder?.lastLevelDb ?: Float.NEGATIVE_INFINITY
                binding.audioStatus.text = if (level == Float.NEGATIVE_INFINITY) {
                    getString(R.string.audio_off)
                } else {
                    getString(R.string.db_live, level)
                }
            }
            if (thermalWatchTick == 0 && effectiveFps >= 60 && isThermalThrottled()) {
                // A live rebind would cut the encoder mid-clip. Force CFR 30 FPS on the next
                // bind instead, and tell the user why the label changed.
                selectedFps = DEFAULT_FPS
                effectiveFps = DEFAULT_FPS
                rebindNeeded = true
                updateFpsLabel()
                Log.w(TAG, "Thermal headroom exhausted; switching to locked 30 FPS")
                toast(R.string.thermal_fallback)
            }
            mainHandler.postDelayed(this, 250L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasCameraPermission()) {
            startCameraIfNeeded()
        } else {
            showPermissionState()
        }
        updateAudioAvailabilityUi()
    }

    // Picks a folder on the SD card once; the URI is persisted so later recordings
    // can be written straight into it.
    private val storagePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            sdTreeUri = uri
            selectedStorage = StorageMode.SD_CARD
            prefs.edit {
                putString(KEY_STORAGE, selectedStorage.name)
                putString(KEY_SD_URI, uri.toString())
            }
            toast(R.string.storage_sd_ok)
        } else {
            // User cancelled; revert to gallery until they pick a folder.
            if (selectedStorage == StorageMode.SD_CARD) {
                selectedStorage = StorageMode.GALLERY
                prefs.edit { putString(KEY_STORAGE, selectedStorage.name) }
            }
            toast(R.string.storage_sd_reset)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Very low memory devices (e.g. 1–2 GB RAM) cannot keep a 1080p preview + encoder +
        // GPU color pipeline healthy; default them to 720p HD and skip the live filter
        // pipeline while NORMAL is selected.
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            isLowRamDevice = info.totalMem > 0 && info.totalMem < 2_500_000_000L
        }

        loadPreferences()
        applyWindowInsets()
        applyInitialState()

        permissionAskedBefore = savedInstanceState?.getBoolean(KEY_PERMISSION_ASKED)
            ?: prefs.getBoolean(KEY_PERMISSION_ASKED, false)

        setupUi()
        cleanTempDir()

        if (hasCameraPermission()) {
            startCameraIfNeeded()
        } else if (!permissionAskedBefore) {
            requestPermissions()
        } else {
            showPermissionState()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_PERMISSION_ASKED, permissionAskedBefore)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            when {
                cameraProvider == null -> startCameraIfNeeded()
                // The camera was unbound in onStop (battery/thermal). Rebind on resume.
                !cameraReady && phase != Phase.PROCESSING && phase != Phase.STOPPING ->
                    bindPreferredFps()
            }
        }
        updateAudioAvailabilityUi()
    }

    override fun onStop() {
        cancelCountdown()
        runCatching { cameraControl?.enableTorch(false) }
        torchOn = false
        if (phase == Phase.RECORDING || phase == Phase.STARTING) {
            stopRecording()
        }
        // Release the camera almost immediately once we are out of view. Keeping a bound
        // session behind the task drains GPU/encoder resources and causes thermal
        // throttling on budget chips, so unbind unless a finalize is pending; STOPPING /
        // PROCESSING still need the codec alive for the mux step.
        if (phase != Phase.STOPPING && phase != Phase.PROCESSING) {
            unbindCamera()
        }
        super.onStop()
    }

    override fun onDestroy() {
        cancelCountdown()
        stopTimer()
        stopPulse()

        if (phase == Phase.RECORDING || phase == Phase.STARTING || phase == Phase.STOPPING) {
            runCatching { recording?.stop() }
            audioRecorder?.cancel()
            audioRecorder = null
            destination?.abort()
            destination = null
        }
        recording = null

        runCatching { cameraControl?.enableTorch(false) }
        torchOn = false
        runCatching { cameraProvider?.unbindAll() }
        detachCamera()
        audioExecutor.shutdown()
        ioScope.cancel()
        controlExecutor.shutdown()

        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------------------------

    private fun applyWindowInsets() {
        // The preview stays edge-to-edge; only the first and last control rows are
        // pushed inside the system bars. flashButton anchors the whole top row and
        // audioStatus terminates the bottom packed chain, so two margin updates are
        // enough to keep the entire control layer clear of cutouts and gesture bars.
        val topBase = dp(12)
        val bottomBase = dp(12)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            updateTopMargin(binding.flashButton, topBase + bars.top)
            updateBottomMargin(binding.audioStatus, bottomBase + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun updateTopMargin(view: View, margin: Int) {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        if (params.topMargin != margin) {
            params.topMargin = margin
            view.layoutParams = params
        }
    }

    private fun updateBottomMargin(view: View, margin: Int) {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin
            view.layoutParams = params
        }
    }

    private fun loadPreferences() {
        selectedFilter = prefs.getString(KEY_FILTER, null)?.let { name ->
            runCatching { FilterPreset.valueOf(name) }.getOrNull()
        } ?: FilterPreset.NORMAL
        timerMode = prefs.getString(KEY_TIMER, null)?.let { name ->
            runCatching { TimerMode.valueOf(name) }.getOrNull()
        } ?: TimerMode.OFF
        lensFacing = prefs.getInt(KEY_LENS, CameraSelector.LENS_FACING_FRONT)
        if (lensFacing != CameraSelector.LENS_FACING_FRONT &&
            lensFacing != CameraSelector.LENS_FACING_BACK
        ) {
            lensFacing = CameraSelector.LENS_FACING_BACK
        }
        selectedQuality = prefs.getString(KEY_QUALITY, null)?.let { name ->
            runCatching { VideoQuality.valueOf(name) }.getOrNull()
        } ?: if (isLowRamDevice) VideoQuality.HD else VideoQuality.FHD
        selectedStorage = prefs.getString(KEY_STORAGE, null)?.let { name ->
            runCatching { StorageMode.valueOf(name) }.getOrNull()
        } ?: StorageMode.GALLERY
        sdTreeUri = prefs.getString(KEY_SD_URI, null)?.let { uriString ->
            runCatching { Uri.parse(uriString) }.getOrNull()
        }
        if (selectedStorage == StorageMode.SD_CARD && sdTreeUri == null) {
            selectedStorage = StorageMode.GALLERY
        }
        cameraSelector = selectorFor(lensFacing)
    }

    private fun applyInitialState() {
        updateFpsLabel()
        updateQualityLabel()
        updateFilterLabel()
        updateTimerLabel()
        updateZoomDisplay(1f)
        binding.timerText.setText(R.string.timer_zero)
        binding.countdownOverlay.visibility = View.GONE
        renderPhase()
        updateAudioAvailabilityUi()
    }

    private fun setupUi() {
        binding.recordButton.setOnClickListener {
            when (phase) {
                Phase.COUNTDOWN -> cancelCountdown()
                Phase.RECORDING -> stopRecording()
                Phase.IDLE -> when {
                    !hasCameraPermission() || !hasStoragePermission() -> requestPermissions()
                    !cameraReady -> startCameraIfNeeded()
                    timerMode == TimerMode.OFF -> startRecording()
                    else -> beginCountdown(timerMode.seconds)
                }
                else -> Unit
            }
        }

        binding.fpsToggle.setOnClickListener {
            if (phase != Phase.IDLE || !cameraReady) return@setOnClickListener
            if (selectedFps == DEFAULT_FPS) {
                if (!supports60Fps) {
                    toast(R.string.fps_60_unavailable)
                    return@setOnClickListener
                }
                if (isThermalThrottled()) {
                    toast(R.string.thermal_fallback)
                    return@setOnClickListener
                }
                selectedFps = 60
            } else {
                selectedFps = DEFAULT_FPS
            }
            updateFpsLabel()
            bindPreferredFps()
        }

        binding.qualityText.setOnClickListener {
            if (phase == Phase.IDLE && cameraReady) showSetupDialog()
        }
        binding.filterChip.setOnClickListener { cycleFilter() }
        binding.timerChip.setOnClickListener { cycleTimer() }
        binding.flipButton.setOnClickListener { flipCamera() }
        binding.flashButton.setOnClickListener { toggleTorch() }
        binding.countdownOverlay.setOnClickListener {
            if (phase == Phase.COUNTDOWN) cancelCountdown()
        }

        setupZoom()

        binding.statusText.setOnClickListener {
            if (phase == Phase.IDLE && savedUri != null) shareLastVideo()
        }

        binding.audioStatus.setOnClickListener {
            if (phase == Phase.IDLE && !hasMicPermission()) showSettingsDialog()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupZoom() {
        binding.zoomSlider.max = ZOOM_SLIDER_MAX
        binding.zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val control = cameraControl ?: return
                val span = zoomLinearMax - zoomLinearMin
                control.setLinearZoom(zoomLinearMin + (progress.toFloat() / ZOOM_SLIDER_MAX) * span)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                sliderTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                sliderTracking = false
            }
        })

        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val control = cameraControl ?: return false
                    val target = (currentZoomRatio * detector.scaleFactor)
                        .coerceIn(zoomMinRatio, zoomMaxRatio)
                    currentZoomRatio = target
                    control.setZoomRatio(target)
                    return true
                }
            }
        )

        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            false
        }
    }

    private fun cycleFilter() {
        val presets = FilterPreset.entries
        selectedFilter = presets[(selectedFilter.ordinal + 1) % presets.size]
        prefs.edit { putString(KEY_FILTER, selectedFilter.name) }
        updateFilterLabel()
        applySelectedFilter()

        if (cameraReady && !effectActive && selectedFilter != FilterPreset.NORMAL) {
            toast(R.string.filters_unavailable)
        }
    }

    private fun applySelectedFilter() {
        colorEffect?.processor?.setTransform(ColorFilters.forPreset(selectedFilter))
    }

    private fun cycleTimer() {
        if (phase != Phase.IDLE) return
        timerMode = when (timerMode) {
            TimerMode.OFF -> TimerMode.THREE
            TimerMode.THREE -> TimerMode.TEN
            TimerMode.TEN -> TimerMode.OFF
        }
        prefs.edit { putString(KEY_TIMER, timerMode.name) }
        updateTimerLabel()
    }

    private fun updateFilterLabel() {
        val name = getString(
            when (selectedFilter) {
                FilterPreset.NORMAL -> R.string.filter_normal
                FilterPreset.NATURAL -> R.string.filter_natural
                FilterPreset.VIVID -> R.string.filter_vivid
                FilterPreset.MONO -> R.string.filter_mono
                FilterPreset.WARM -> R.string.filter_warm
            }
        )
        binding.filterChip.text = getString(R.string.filter_chip_format, name)
        binding.filterChip.setBackgroundResource(
            if (selectedFilter == FilterPreset.NORMAL) {
                R.drawable.bg_chip
            } else {
                R.drawable.bg_chip_active
            }
        )
    }

    private fun updateTimerLabel() {
        val name = getString(
            when (timerMode) {
                TimerMode.OFF -> R.string.timer_off
                TimerMode.THREE -> R.string.timer_3s
                TimerMode.TEN -> R.string.timer_10s
            }
        )
        binding.timerChip.text = getString(R.string.timer_chip_format, name)
        binding.timerChip.setBackgroundResource(
            if (timerMode == TimerMode.OFF) {
                R.drawable.bg_chip
            } else {
                R.drawable.bg_chip_active
            }
        )
    }

    private fun updateZoomDisplay(ratio: Float) {
        binding.zoomLabel.text = String.format(Locale.US, "%.1fx", ratio)
    }

    private fun renderPhase() {
        val idle = phase == Phase.IDLE
        val countdown = phase == Phase.COUNTDOWN
        val active = phase == Phase.RECORDING || phase == Phase.STOPPING
        val armed = active || countdown

        binding.hintText.setText(
            when (phase) {
                Phase.IDLE -> if (hasCameraPermission()) R.string.hint_record else R.string.hint_tap_to_grant
                Phase.COUNTDOWN -> R.string.hint_countdown
                Phase.STARTING -> R.string.hint_preparing
                Phase.RECORDING -> R.string.hint_stop
                Phase.STOPPING, Phase.PROCESSING -> R.string.hint_processing
            }
        )

        binding.recordButton.isEnabled = idle || active || countdown
        binding.recordButton.contentDescription = getString(
            when {
                countdown -> R.string.cd_cancel_countdown
                active -> R.string.cd_stop
                else -> R.string.cd_record
            }
        )

        binding.fpsToggle.isEnabled = idle && cameraReady
        binding.fpsToggle.alpha = if (binding.fpsToggle.isEnabled) 1f else 0.45f

        binding.timerChip.isEnabled = idle && cameraReady
        binding.timerChip.alpha = if (binding.timerChip.isEnabled) 1f else 0.45f

        binding.filterChip.isEnabled = cameraReady && phase != Phase.PROCESSING
        binding.filterChip.alpha = if (binding.filterChip.isEnabled) 1f else 0.45f

        val zoomEnabled = cameraReady && phase != Phase.PROCESSING && phase != Phase.STARTING
        binding.zoomSlider.isEnabled = zoomEnabled
        binding.zoomSlider.alpha = if (zoomEnabled) 1f else 0.45f
        binding.zoomLabel.alpha = if (zoomEnabled) 1f else 0.45f

        val busy = phase == Phase.STARTING || phase == Phase.STOPPING ||
            phase == Phase.PROCESSING
        val flashEnabled = cameraReady && !busy &&
            lensFacing == CameraSelector.LENS_FACING_BACK && hasFlashUnit
        binding.flashButton.isEnabled = flashEnabled
        binding.flashButton.alpha = if (flashEnabled) 1f else 0.45f
        binding.flashButton.setBackgroundResource(
            if (torchOn) R.drawable.bg_chip_active else R.drawable.bg_chip
        )
        binding.flashButton.setImageResource(
            if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
        binding.flashButton.contentDescription = getString(
            if (torchOn) R.string.cd_flash_off else R.string.cd_flash_on
        )

        val flipEnabled = cameraReady && phase == Phase.IDLE
        binding.flipButton.isEnabled = flipEnabled
        binding.flipButton.alpha = if (flipEnabled) 1f else 0.45f

        binding.recordRing.setBackgroundResource(
            if (armed) R.drawable.bg_record_ring_active else R.drawable.bg_record_ring
        )
        binding.recordInner.setBackgroundResource(
            if (armed) R.drawable.bg_record_stop else R.drawable.bg_record_inner
        )

        val innerSize = dp(if (armed) 30 else 60)
        val params = binding.recordInner.layoutParams
        if (params.width != innerSize || params.height != innerSize) {
            params.width = innerSize
            params.height = innerSize
            binding.recordInner.layoutParams = params
        }
    }

    private fun updateFpsLabel() {
        binding.fpsToggle.text = getString(R.string.fps_format, selectedFps)
    }

    private fun updateQualityLabel() {
        val mbps = selectedQuality.bitsPerSecond / 1_000_000
        val text = getString(R.string.quality_format, selectedQuality.label, mbps)
        binding.qualityText.text = text
        if (savedUri == null && phase == Phase.IDLE) {
            binding.statusText.text = text
            binding.statusText.isClickable = false
        }
    }

    private fun updateAudioAvailabilityUi() {
        if (phase != Phase.IDLE) return
        binding.audioStatus.text = getString(
            if (hasMicPermission()) R.string.audio_idle else R.string.audio_grant
        )
        binding.audioStatus.isClickable = !hasMicPermission()
    }

    private fun showPermissionState() {
        cameraReady = false
        phase = Phase.IDLE
        binding.statusText.setText(R.string.permission_denied)
        binding.statusText.isClickable = false
        renderPhase()
    }

    private fun isActivityGone(): Boolean = isFinishing || isDestroyed

    private fun toast(resId: Int) {
        if (!isActivityGone()) {
            Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------------------------

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /** Public Movies/WowCam writes need WRITE_EXTERNAL_STORAGE only on API 24-28. */
    private fun hasStoragePermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

    private fun requestedPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return permissions.toTypedArray()
    }

    private fun requestPermissions() {
        if (hasCameraPermission() && hasStoragePermission()) {
            startCameraIfNeeded()
            return
        }

        val cameraRationale = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        val micRationale = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (!cameraRationale && !micRationale && permissionAskedBefore) {
            showSettingsDialog()
            return
        }

        permissionAskedBefore = true
        prefs.edit { putBoolean(KEY_PERMISSION_ASKED, true) }
        permissionLauncher.launch(requestedPermissions())
    }

    private fun showCapabilityHint() {
        if (isActivityGone() || isFinishing) return
        val message = getString(
            R.string.hint_capability_msg,
            if (supportsUhd) "✓ 4K UHD available" else "not advertised — app falls back to 1080P",
            if (supports60Fps) "✓ 60 FPS available" else "not available — smooth 30 FPS CFR",
            if (supportsEis) "✓ EIS enabled" else "not supported on this phone",
            if (supportsOis) "✓ OIS enabled" else "not supported on this phone"
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.hint_capability_title)
            .setMessage(message)
            .setPositiveButton(R.string.hint_capability_ok, null)
            .show()
    }

    private fun showSettingsDialog() {
        if (isActivityGone() || isFinishing) return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_message)
            .setPositiveButton(R.string.permission_open_settings) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
                runCatching { startActivity(intent) }
            }
            .setNegativeButton(R.string.permission_cancel, null)
            .show()
    }

    private fun showSetupDialog() {
        if (isActivityGone() || isFinishing) return

        val qualityLabels = arrayOf(
            getString(R.string.quality_menu_1080),
            getString(R.string.quality_menu_4k),
            getString(R.string.quality_menu_720)
        )
        val qualityValues = arrayOf(VideoQuality.FHD, VideoQuality.UHD, VideoQuality.HD)
        val storageLabels = arrayOf(
            getString(R.string.storage_menu_reels),
            getString(R.string.storage_menu_long)
        )
        val storageValues = arrayOf(StorageMode.GALLERY, StorageMode.SD_CARD)
        val items: Array<CharSequence> = arrayOf<CharSequence>(
            qualityLabels[0],
            qualityLabels[1],
            qualityLabels[2],
            getString(R.string.setup_separator),
            storageLabels[0],
            storageLabels[1]
        )
        val separatorIndex = 3
        val checked = when {
            qualityValues.contains(selectedQuality) -> qualityValues.indexOf(selectedQuality)
            selectedStorage == StorageMode.SD_CARD -> 5
            else -> 4
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.setup_dialog_title)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                when (which) {
                    in 0..2 -> {
                        val picked = qualityValues[which]
                        if (picked != selectedQuality) {
                            selectedQuality = picked
                            prefs.edit { putString(KEY_QUALITY, picked.name) }
                            updateQualityLabel()
                            bindPreferredFps()
                        }
                        dialog.dismiss()
                    }
                    separatorIndex -> dialog.dismiss() // inert divider
                    in 4..5 -> {
                        val picked = storageValues[which - 4]
                        dialog.dismiss()
                        if (picked == selectedStorage) return@setSingleChoiceItems
                        if (picked == StorageMode.SD_CARD) {
                            if (sdTreeUri != null) {
                                selectedStorage = picked
                                prefs.edit { putString(KEY_STORAGE, picked.name) }
                                toast(R.string.storage_sd_ok)
                            } else {
                                // Needs one-time folder grant.
                                selectedStorage = StorageMode.SD_CARD
                                toast(R.string.storage_sd_required)
                                storagePicker.launch(null)
                            }
                        } else {
                            selectedStorage = StorageMode.GALLERY
                            prefs.edit { putString(KEY_STORAGE, selectedStorage.name) }
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------------------------

    private fun startCameraIfNeeded() {
        if (cameraProvider != null) {
            if (!cameraReady) bindPreferredFps()
            return
        }
        if (cameraProviderRequested) return
        cameraProviderRequested = true
        if (phase == Phase.IDLE) binding.hintText.setText(R.string.hint_camera_starting)

        val future = ProcessCameraProvider.getInstance(this)
        // Provider creation is heavy (it opens the camera service and enumerates
        // capabilities), so it runs on the control executor. bindToLifecycle itself is
        // @MainThread in CameraX and is dispatched back to the main thread below.
        future.addListener({
            val provider = try {
                future.get()
            } catch (t: Throwable) {
                Log.e(TAG, "Camera provider unavailable", t)
                mainHandler.post {
                    if (isActivityGone()) return@post
                    cameraProviderRequested = false
                    cameraReady = false
                    binding.statusText.setText(R.string.camera_unavailable)
                    binding.hintText.setText(R.string.hint_tap_to_grant)
                }
                return@addListener
            }

            mainHandler.post {
                if (isActivityGone()) return@post
                try {
                    cameraProvider = provider
                    if (!provider.hasCamera(selectorFor(lensFacing))) {
                        lensFacing = when {
                            provider.hasCamera(selectorFor(CameraSelector.LENS_FACING_BACK)) ->
                                CameraSelector.LENS_FACING_BACK
                            provider.hasCamera(selectorFor(CameraSelector.LENS_FACING_FRONT)) ->
                                CameraSelector.LENS_FACING_FRONT
                            else -> throw IllegalStateException("No camera available")
                        }
                    }
                    cameraSelector = selectorFor(lensFacing)
                    refreshCameraCapabilities()
                    bindPreferredFps()
                } catch (t: Throwable) {
                    Log.e(TAG, "Camera provider unavailable", t)
                    cameraProviderRequested = false
                    cameraReady = false
                    binding.statusText.setText(R.string.camera_unavailable)
                    binding.hintText.setText(R.string.hint_tap_to_grant)
                }
            }
        }, controlExecutor)
    }

    /**
     * CameraX 1.3.1 does not expose `ProcessCameraProvider.getCameraInfo(selector)`;
     * the supported path is to filter [ProcessCameraProvider.getAvailableCameraInfos]
     * with the same selector. The probe runs before binding, so capabilities for all
     * lenses are known without opening a capture session.
     */
    private fun cameraInfoFor(selector: CameraSelector): CameraInfo? {
        val provider = cameraProvider ?: return null
        return selector.filter(provider.availableCameraInfos).firstOrNull()
    }

    @ExperimentalCamera2Interop
    private fun refreshCameraCapabilities() {
        val info = cameraInfoFor(cameraSelector)
        supports60Fps = info?.supportedFrameRateRanges
            ?.any { range -> range.contains(60) } == true
        hasFlashUnit = info?.hasFlashUnit() == true

        // EIS/OIS/4K come from Camera2 characteristics. Cameras that advertise mode 1
        // in CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES / LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
        // can be driven through Camera2Interop.
        val camera2Info = info?.let { camInfo ->
            runCatching { Camera2CameraInfo.from(camInfo) }.getOrNull()
        }

        fun stabilizationKey(key: CameraCharacteristics.Key<IntArray>): Boolean =
            camera2Info?.let { cam2 ->
                runCatching { cam2.getCameraCharacteristic(key) }.getOrNull()
            }?.any { it == 1 } == true

        supportsEis = stabilizationKey(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        )
        supportsOis = stabilizationKey(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
        )
        // 4K probe: does any H.264 encoder claim 3840x2160? CameraX's own fallback ladder
        // already covers the case where this lies; this just keeps the hint honest.
        supportsUhd = runCatching {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { codec ->
                codec.isEncoder && "video/avc" in codec.supportedTypes &&
                    runCatching {
                        codec.getCapabilitiesForType("video/avc")
                            ?.videoCapabilities
                            ?.isSizeSupported(3840, 2160) == true
                    }.getOrDefault(false)
            }
        }.getOrDefault(false)
        Log.i(
            TAG,
            "Capabilities: 60fps=$supports60Fps uhd=$supportsUhd flash=$hasFlashUnit eis=$supportsEis ois=$supportsOis lens=$lensFacing"
        )
        if (lensFacing != CameraSelector.LENS_FACING_BACK || !hasFlashUnit) {
            torchOn = false
        }
    }

    /**
     * True when the platform reports the device is thermally constrained (or the
     * headroom forecast is nearly exhausted). Requires API 29+ for the thermal status;
     * the headroom forecast needs API 30+. Below that we conservatively return false
     * and rely on the camera capability check alone.
     */
    private fun isThermalThrottled(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val pm = getSystemService(PowerManager::class.java) ?: return false
            if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_LIGHT) return true
            if (Build.VERSION.SDK_INT >= 30) {
                val headroom = pm.getThermalHeadroom(0)
                if (!headroom.isNaN() && headroom < 0.9f) return true
            }
            false
        } catch (t: Throwable) {
            Log.w(TAG, "Thermal state unavailable", t)
            false
        }
    }

    private fun selectorFor(facing: Int): CameraSelector =
        CameraSelector.Builder().requireLensFacing(facing).build()

    private fun flipCamera() {
        if (switchingCamera || !cameraReady || phase != Phase.IDLE) return
        switchingCamera = true

        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        cameraSelector = selectorFor(lensFacing)
        prefs.edit { putInt(KEY_LENS, lensFacing) }

        // The torch belongs to the old lens; drop it before CameraX unbinds.
        torchOn = false
        runCatching { cameraControl?.enableTorch(false) }

        binding.hintText.setText(R.string.hint_switching)
        binding.zoomSlider.progress = 0
        updateZoomDisplay(1f)

        refreshCameraCapabilities()
        if (selectedFps == 60 && !supports60Fps) {
            selectedFps = DEFAULT_FPS
            updateFpsLabel()
        }

        bindPreferredFps()
        switchingCamera = false
    }

    private fun toggleTorch() {
        if (lensFacing != CameraSelector.LENS_FACING_BACK || !hasFlashUnit) {
            toast(R.string.flash_unavailable)
            return
        }
        val control = cameraControl ?: return
        val next = !torchOn
        torchOn = next
        renderPhase()

        val future = control.enableTorch(next)
        future.addListener({
            val ok = runCatching { future.get(); true }.getOrDefault(false)
            if (ok || !next) return@addListener
            mainHandler.post {
                if (isActivityGone()) return@post
                if (torchOn == next) {
                    torchOn = false
                    renderPhase()
                    toast(R.string.flash_unavailable)
                }
            }
        }, mainExecutor)
    }

    private fun bindPreferredFps() {
        if (cameraProvider == null) return

        cameraReady = false
        binding.hintText.setText(R.string.hint_camera_starting)
        binding.recordButton.isEnabled = false

        // CFR selection. Both inputs (60 FPS capability and thermal headroom) are
        // checked here, so the next bind is guaranteed to render at a sustainable rate:
        //   • 60 requested but camera cannot lock 60 FHD → drop to 30.
        //   • 60 requested but the device is hot / headroom exhausted → drop to 30.
        // User preference remains untouched by the capability path; the thermal path
        // rewrites it so the label and every future bind stay honest.
        val thermalFallback = selectedFps == 60 && isThermalThrottled()
        if (thermalFallback) {
            selectedFps = DEFAULT_FPS
            updateFpsLabel()
        }
        val desired = selectedFps
        // 4K → try UHD first, then 1080P, then 720P. 1080P → 1080P then 720P. 720P stays 720P.
        // Every rung of the ladder also carries the 60→30 FPS CFR fallback.
        val qualityLadder = when (selectedQuality) {
            VideoQuality.UHD -> listOf(Quality.UHD, Quality.FHD, Quality.HD)
            VideoQuality.FHD -> listOf(Quality.FHD, Quality.HD)
            VideoQuality.HD -> listOf(Quality.HD)
        }
        val fpsLadder = ArrayList<Int>().apply {
            add(desired)
            if (desired != DEFAULT_FPS) add(DEFAULT_FPS)
            add(0) // let the device choose its preferred FPS
        }
        val attempts = ArrayList<BindAttempt>(qualityLadder.size * fpsLadder.size * 2).apply {
            for (quality in qualityLadder) {
                for (fps in fpsLadder) {
                    add(BindAttempt(fps, quality, lockAe = true))
                    add(BindAttempt(fps, quality, lockAe = false))
                }
            }
        }

        // Try the GPU color effect first. If the device rejects CameraX effects,
        // fall back to a normal camera bind so recording still works.
        val passes = when {
            effectDisabled -> listOf(false)
            isLowRamDevice && selectedFilter == FilterPreset.NORMAL -> listOf(false)
            else -> listOf(true, false)
        }

        var boundFps = -1
        var boundWithEffect = false
        var boundQuality = Quality.FHD
        var lastError: Throwable? = null

        bindLoop@ for (useEffect in passes) {
            for (attempt in attempts) {
                if (bindCamera(attempt, useEffect)) {
                    boundFps = if (attempt.targetFps > 0) attempt.targetFps else DEFAULT_FPS
                    boundWithEffect = useEffect
                    boundQuality = attempt.quality
                    break@bindLoop
                } else {
                    lastError = bindFailure
                }
            }
        }

        if (boundFps < 0) {
            Log.e(TAG, "Camera binding failed", lastError)
            cameraReady = false
            phase = Phase.IDLE
            binding.statusText.setText(R.string.camera_start_failed)
            renderPhase()
            binding.hintText.setText(R.string.hint_tap_to_grant)
            toast(R.string.camera_start_failed)
            return
        }

        effectiveFps = boundFps
        cameraReady = true
        rebindNeeded = false

        if (thermalFallback) {
            toast(R.string.thermal_fallback)
        } else if (desired == 60 && boundFps != 60) {
            selectedFps = DEFAULT_FPS
            updateFpsLabel()
            toast(R.string.fps_fallback)
        }

        if (boundQuality != selectedQuality.cameraQuality) {
            val downgradedTo = VideoQuality.entries.firstOrNull { it.cameraQuality == boundQuality }
            if (downgradedTo != null) {
                // Persist the rung that actually worked so the next bind doesn't retry UHD.
                selectedQuality = downgradedTo
                prefs.edit { putString(KEY_QUALITY, downgradedTo.name) }
                updateQualityLabel()
                if (downgradedTo != VideoQuality.UHD) {
                    toast(R.string.quality_4k_fallback)
                }
            }
        }

        if (!boundWithEffect && selectedFilter != FilterPreset.NORMAL) {
            toast(R.string.filters_unavailable)
        }

        hasFlashUnit = runCatching { boundCamera?.cameraInfo?.hasFlashUnit() == true }
            .getOrDefault(false)
        if (lensFacing == CameraSelector.LENS_FACING_BACK && hasFlashUnit && torchOn) {
            // A rebind (FPS change, effect fallback, flip back) resets the torch.
            runCatching { cameraControl?.enableTorch(true) }
        } else if (lensFacing != CameraSelector.LENS_FACING_BACK || !hasFlashUnit) {
            torchOn = false
        }

        phase = Phase.IDLE
        renderPhase()
        updateQualityLabel()
        updateAudioAvailabilityUi()
        if (!prefs.getBoolean(KEY_HINT_SHOWN, false)) {
            prefs.edit { putBoolean(KEY_HINT_SHOWN, true) }
            mainHandler.post {
                if (!isActivityGone()) showCapabilityHint()
            }
        }
    }

    private var bindFailure: Throwable? = null

    @ExperimentalCamera2Interop
    private fun bindCamera(attempt: BindAttempt, useEffect: Boolean): Boolean {
        val provider = cameraProvider ?: return false
        bindFailure = null
        var newEffect: ColorMatrixEffect? = null
        return try {
            val rotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0

            val previewBuilder = Preview.Builder()
                .setTargetRotation(rotation)
            if (supportsEis) {
                runCatching {
                    Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
                }
            }
            val preview = previewBuilder.build()
            preview.setSurfaceProvider(binding.previewView.surfaceProvider)

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        attempt.quality,
                        FallbackStrategy.higherQualityOrLowerThan(attempt.quality)
                    )
                )
                .setTargetVideoEncodingBitRate(selectedQuality.bitsPerSecond)
                .build()

            val videoBuilder = VideoCapture.Builder(recorder)
                .setTargetRotation(rotation)

            val videoExtender = Camera2Interop.Extender(videoBuilder)
            if (supportsEis) {
                runCatching {
                    videoExtender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
                }
            }
            if (supportsOis) {
                runCatching {
                    videoExtender.setCaptureRequestOption(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                }
            }

            if (attempt.targetFps > 0) {
                videoBuilder.setTargetFrameRate(
                    Range(attempt.targetFps, attempt.targetFps)
                )
                if (attempt.lockAe) {
                    videoExtender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(attempt.targetFps, attempt.targetFps)
                    )
                }
            }

            val capture = videoBuilder.build()

            val groupBuilder = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(capture)

            if (useEffect) {
                val processor = ColorMatrixSurfaceProcessor { throwable ->
                    onEffectError(throwable)
                }
                processor.setTransform(ColorFilters.forPreset(selectedFilter))
                val effect = ColorMatrixEffect(processor) { throwable ->
                    onEffectError(throwable)
                }
                newEffect = effect
                groupBuilder.addEffect(effect)
            }

            provider.unbindAll()
            detachCamera()

            val camera = provider.bindToLifecycle(this, cameraSelector, groupBuilder.build())

            boundCamera = camera
            cameraControl = camera.cameraControl
            colorEffect = newEffect
            effectActive = useEffect
            videoCapture = capture
            observeZoom(camera)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Bind attempt failed: $attempt effect=$useEffect", t)
            bindFailure = t
            newEffect?.release()
            runCatching { provider.unbindAll() }
            false
        }
    }

    private fun observeZoom(camera: Camera) {
        val observer = Observer<ZoomState> { state -> onZoomState(state) }
        zoomObserver = observer
        camera.cameraInfo.zoomState.observe(this, observer)
    }

    private fun onZoomState(state: ZoomState) {
        // CameraX's linearZoom is logarithmic. Anchor it so the slider always spans
        // exactly 1x to 5x, or the camera's narrower range if 5x is unavailable.
        val rawMin = state.minZoomRatio
        val rawMax = state.maxZoomRatio
        val low = maxOf(1f, rawMin).coerceAtMost(rawMax)
        val high = minOf(MAX_ZOOM_RATIO, rawMax).coerceAtLeast(low)
        zoomMinRatio = low
        zoomMaxRatio = high

        val logSpan = ln(rawMax) - ln(rawMin)
        if (logSpan > 0.0001f) {
            zoomLinearMin = ((ln(low) - ln(rawMin)) / logSpan).coerceIn(0f, 1f)
            zoomLinearMax = ((ln(high) - ln(rawMin)) / logSpan).coerceIn(0f, 1f)
        } else {
            zoomLinearMin = 0f
            zoomLinearMax = 1f
        }

        currentZoomRatio = state.zoomRatio

        if (!sliderTracking) {
            val span = zoomLinearMax - zoomLinearMin
            val fraction = if (span > 0.0001f) {
                (state.linearZoom - zoomLinearMin) / span
            } else {
                0f
            }
            val progress = (fraction * ZOOM_SLIDER_MAX).roundToInt()
                .coerceIn(0, ZOOM_SLIDER_MAX)
            if (binding.zoomSlider.progress != progress) {
                binding.zoomSlider.progress = progress
            }
        }
        updateZoomDisplay(state.zoomRatio)
    }

    private fun unbindCamera() {
        runCatching { cameraProvider?.unbindAll() }
        // closes EGL surfaces and quits the GL thread; must happen with the camera unbound
        detachCamera()
        cameraReady = false
        rebindNeeded = false
    }

    private fun detachCamera() {
        zoomObserver?.let { observer ->
            runCatching { boundCamera?.cameraInfo?.zoomState?.removeObserver(observer) }
        }
        zoomObserver = null
        boundCamera = null
        cameraControl = null
        sliderTracking = false

        val effect = colorEffect
        colorEffect = null
        effectActive = false
        effect?.release()
    }

    private fun onEffectError(throwable: Throwable) {
        Log.e(TAG, "Live color effect failed", throwable)
        mainHandler.post {
            if (isActivityGone()) return@post
            // CameraX and the processor can both report the same failure.
            if (effectDisabled) return@post
            effectDisabled = true
            effectActive = false
            rebindNeeded = true
            toast(R.string.filters_unavailable)
            if (phase == Phase.IDLE && cameraProvider != null) {
                bindPreferredFps()
            }
        }
    }

    /**
     * Picks the right destination for the current save mode. If the SD card option was
     * chosen but its folder/URI is missing or unwritable, falls back to Gallery instead
     * of crashing or writing into an inaccessible volume.
     */
    private fun createDestination(displayName: String): VideoDestination {
        if (selectedStorage == StorageMode.SD_CARD) {
            val treeUri = sdTreeUri
            if (treeUri != null) {
                try {
                    return VideoDestination.forSaf(this, treeUri, displayName, tempDir)
                } catch (t: Throwable) {
                    Log.w(TAG, "SD card destination unavailable; using gallery", t)
                    toast(R.string.storage_sd_unavailable)
                }
            } else {
                toast(R.string.storage_sd_required)
            }
            selectedStorage = StorageMode.GALLERY
            prefs.edit { putString(KEY_STORAGE, selectedStorage.name) }
        }
        return VideoDestination.create(this, displayName, tempDir)
    }

    // ------------------------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------------------------

    private fun setPhase(newPhase: Phase) {
        phase = newPhase
        renderPhase()
    }

    private fun startRecording() {
        if (phase != Phase.IDLE || !cameraReady) return
        if (rebindNeeded) {
            // The GPU pipeline failed (usually mid-clip). Rebuild the camera before
            // recording again instead of handing a dead VideoCapture to the encoder.
            bindPreferredFps()
            return
        }
        val capture = videoCapture ?: return

        setPhase(Phase.STARTING)
        stopPulse()
        binding.timerText.setText(R.string.timer_zero)
        savedUri = null
        binding.statusText.text = getString(
            R.string.quality_format,
            selectedQuality.label,
            selectedQuality.bitsPerSecond / 1_000_000
        )
        binding.statusText.isClickable = false
        binding.audioStatus.setText(R.string.audio_idle)

        videoFinalized = false
        audioStopCompleted = false
        audioResult = null
        muxStarted = false
        tooShort = false
        recordStartElapsed = 0L
        videoStartNs = System.nanoTime()

        val output: VideoDestination
        try {
            output = createDestination(buildDisplayName(effectiveFps))
        } catch (t: Throwable) {
            Log.e(TAG, "Could not create output destination", t)
            destination = null
            setPhase(Phase.IDLE)
            binding.statusText.setText(R.string.storage_unavailable)
            toast(R.string.storage_unavailable)
            return
        }
        destination = output

        val videoFile = File(tempDir, "wowcam_video_${System.nanoTime()}.mp4")
        tempVideoFile = videoFile

        try {
            val options = FileOutputOptions.Builder(videoFile).build()
            val pending = capture.output.prepareRecording(this, options)
            // Video only: audio is captured by StudioAudioRecorder so DSP effects can attach.
            recording = pending.start(mainExecutor) { event -> onVideoEvent(event) }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start CameraX recording", t)
            output.abort()
            destination = null
            tempVideoFile?.delete()
            tempVideoFile = null
            setPhase(Phase.IDLE)
            binding.statusText.setText(R.string.record_start_failed)
            toast(R.string.record_start_failed)
        }
    }

    private fun onVideoEvent(event: VideoRecordEvent) {
        if (isActivityGone()) return
        when (event) {
            is VideoRecordEvent.Start -> onVideoStarted()
            is VideoRecordEvent.Finalize -> onVideoFinalized(event)
            else -> Unit
        }
    }

    private fun onVideoStarted() {
        if (phase != Phase.STARTING) return

        videoStartNs = System.nanoTime()
        recordStartElapsed = SystemClock.elapsedRealtime()
        setPhase(Phase.RECORDING)
        startTimer()
        startPulse()
        startAudioCapture()
    }

    private fun startAudioCapture() {
        if (!hasMicPermission() || audioResult != null || audioRecorder != null) {
            binding.audioStatus.setText(R.string.audio_off)
            return
        }

        // Clear any stale Bluetooth/headset route before opening the mic; otherwise a
        // neckband connected some time ago can leave capture routed at nothing.
        StudioAudioRecorder.resetAudioRouting(this)
        val recorder = StudioAudioRecorder()
        audioRecorder = recorder
        val audioFile = File(tempDir, "wowcam_audio_${System.nanoTime()}.m4a")

        audioExecutor.execute {
            try {
                recorder.start(this@MainActivity, audioFile)
                mainHandler.post {
                    if (isActivityGone()) return@post
                    if (audioRecorder !== recorder) {
                        recorder.cancel()
                        return@post
                    }
                    binding.audioStatus.text = getString(
                        R.string.audio_ready_format,
                        recorder.effectsLabel
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Studio audio unavailable", t)
                audioFile.delete()
                mainHandler.post {
                    if (audioRecorder === recorder) audioRecorder = null
                    if (!isActivityGone()) {
                        binding.audioStatus.setText(R.string.audio_off)
                        toast(R.string.audio_failed)
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        if (phase != Phase.RECORDING && phase != Phase.STARTING) return

        val elapsed = if (recordStartElapsed > 0L) {
            SystemClock.elapsedRealtime() - recordStartElapsed
        } else {
            0L
        }
        tooShort = elapsed < MIN_RECORDING_MS

        setPhase(Phase.STOPPING)
        stopTimer()
        stopPulse()
        if (elapsed > 0L) binding.timerText.text = formatDuration(elapsed)

        runCatching { recording?.stop() }
        stopAudioCapture()
    }

    private fun stopAudioCapture() {
        val recorder = audioRecorder
        if (recorder == null) {
            audioStopCompleted = true
            maybeFinishMux()
            return
        }
        audioRecorder = null

        audioExecutor.execute {
            val result = try {
                recorder.stopAndAwait()
            } catch (t: Throwable) {
                Log.w(TAG, "Audio stop failed", t)
                null
            }
            mainHandler.post {
                audioResult = result
                audioStopCompleted = true
                maybeFinishMux()
            }
        }
    }

    private fun onVideoFinalized(event: VideoRecordEvent.Finalize) {
        recording = null
        videoFinalized = true

        val recordedMs = event.recordingStats.recordedDurationNanos / 1_000_000L
        if (recordedMs in 1 until MIN_RECORDING_MS) tooShort = true
        if (recordedMs > 0L && phase != Phase.IDLE) {
            binding.timerText.text = formatDuration(recordedMs)
        }

        if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
            Log.e(TAG, "CameraX finalize error ${event.error}", event.cause)
            val recorder = audioRecorder
            audioRecorder = null
            recorder?.cancel()
            audioStopCompleted = true
            destination?.abort()
            destination = null
            tempVideoFile?.delete()
            tempVideoFile = null
            audioResult?.file?.delete()
            audioResult = null
            setPhase(Phase.IDLE)
            binding.statusText.setText(R.string.recording_failed)
            toast(R.string.recording_failed)
            return
        }

        if (phase == Phase.RECORDING || phase == Phase.STARTING) {
            // The recording stopped on its own (for example, storage ran out).
            setPhase(Phase.STOPPING)
            stopTimer()
            stopPulse()
            stopAudioCapture()
        }

        maybeFinishMux()
    }

    private fun maybeFinishMux() {
        if (muxStarted) return
        if (!videoFinalized || !audioStopCompleted) return
        if (phase == Phase.IDLE) return

        muxStarted = true
        finishMux()
    }

    private fun finishMux() {
        val output = destination
        val videoFile = tempVideoFile

        if (tooShort || output == null || videoFile == null || !videoFile.exists()) {
            output?.abort()
            destination = null
            videoFile?.delete()
            tempVideoFile = null
            audioResult?.file?.delete()
            audioResult = null
            setPhase(Phase.IDLE)
            binding.statusText.setText(R.string.recording_too_short)
            toast(R.string.recording_too_short)
            updateAudioAvailabilityUi()
            return
        }

        val audio = audioResult
        val offsetUs = if (audio != null && videoStartNs > 0L) {
            (audio.startTimeNs - videoStartNs) / 1_000L
        } else {
            0L
        }

        // Free the camera while the mux runs. The preview + GL pipeline compete with the
        // encoder/muxer for budget chips; dropping it here removes the post-record freeze.
        // The camera is rebound automatically once the save completes.
        runCatching { cameraProvider?.unbindAll() }
        detachCamera()
        cameraReady = false
        videoCapture = null

        setPhase(Phase.PROCESSING)

        // Entire mux (MediaExtractor -> MediaMuxer copy, including final muxer flush and
        // commit/rewrite) runs on Dispatchers.IO so the UI thread never waits for disk I/O.
        ioScope.launch {
            try {
                val result = Mp4Muxer.mux(videoFile, audio?.file, offsetUs, output)
                val uri = output.commit()

                mainHandler.post {
                    cleanupTempFiles(audio?.file)
                    destination = null
                    audioResult = null

                    savedUri = uri
                    setPhase(Phase.IDLE)
                    binding.statusText.text = getString(
                        if (uri != null) R.string.saved_share else R.string.saved_no_share
                    )
                    binding.statusText.isClickable = uri != null
                    binding.audioStatus.setText(
                        if (result.hasAudio) {
                            getString(
                                R.string.audio_ready_format,
                                audio?.effectsLabel ?: getString(R.string.audio_effects_none)
                            )
                        } else {
                            getString(R.string.audio_off)
                        }
                    )
                    // Rebind the camera now that the GPU/encoder is idle again, so the
                    // next record starts instantly with a live preview.
                    if (hasCameraPermission() && cameraProvider != null) {
                        bindPreferredFps()
                    }
                    toast(R.string.saved_toast)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Mux/save failed", t)
                output.abort()
                mainHandler.post {
                    cleanupTempFiles(audio?.file)
                    destination = null
                    audioResult = null

                    setPhase(Phase.IDLE)
                    binding.statusText.setText(R.string.save_failed)
                    binding.statusText.isClickable = false
                    if (hasCameraPermission() && cameraProvider != null) {
                        bindPreferredFps()
                    }
                    toast(R.string.save_failed_toast)
                }
            }
        }
    }

    private fun cleanupTempFiles(audioFile: File?) {
        tempVideoFile?.delete()
        tempVideoFile = null
        audioFile?.delete()
    }

    // ------------------------------------------------------------------------------------
    // Timer / animation / sharing
    // ------------------------------------------------------------------------------------

    private fun startTimer() {
        mainHandler.removeCallbacks(timerRunnable)
        binding.timerText.setText(R.string.timer_zero)
        mainHandler.post(timerRunnable)
    }

    private fun stopTimer() {
        mainHandler.removeCallbacks(timerRunnable)
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(binding.recordingDot, View.ALPHA, 0.35f, 1f).apply {
            duration = 650L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        ringPulseAnimator?.cancel()
        binding.recordRing.scaleX = 1f
        binding.recordRing.scaleY = 1f
        ringPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.recordRing,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f)
        ).apply {
            duration = 650L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.recordingDot.alpha = 0.35f

        ringPulseAnimator?.cancel()
        ringPulseAnimator = null
        binding.recordRing.scaleX = 1f
        binding.recordRing.scaleY = 1f
    }

    // ------------------------------------------------------------------------------------
    // Countdown timer
    // ------------------------------------------------------------------------------------

    private fun beginCountdown(seconds: Int) {
        if (phase != Phase.IDLE || !cameraReady || seconds <= 0) return
        countdownRemaining = seconds
        setPhase(Phase.COUNTDOWN)
        showCountdownNumber(seconds)
        binding.countdownOverlay.visibility = View.VISIBLE
        scheduleCountdownTick()
    }

    private fun scheduleCountdownTick() {
        val runnable = Runnable {
            countdownRunnable = null
            if (phase != Phase.COUNTDOWN) return@Runnable
            countdownRemaining -= 1
            if (countdownRemaining <= 0) {
                cancelCountdown()
                startRecording()
            } else {
                showCountdownNumber(countdownRemaining)
                scheduleCountdownTick()
            }
        }
        countdownRunnable = runnable
        mainHandler.postDelayed(runnable, 1_000L)
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        countdownRemaining = 0

        binding.countdownText.animate().cancel()
        binding.countdownOverlay.visibility = View.GONE

        if (phase == Phase.COUNTDOWN) {
            setPhase(Phase.IDLE)
        }
    }

    private fun showCountdownNumber(value: Int) {
        binding.countdownText.text = String.format(Locale.US, "%d", value)
        binding.countdownText.animate().cancel()
        binding.countdownText.scaleX = 1.35f
        binding.countdownText.scaleY = 1.35f
        binding.countdownText.alpha = 0.15f
        binding.countdownText.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(260L)
            .start()
    }

    private fun shareLastVideo() {
        val uri = savedUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_chooser)))
        } catch (t: ActivityNotFoundException) {
            Log.w(TAG, "No share target", t)
            toast(R.string.share_failed)
        } catch (t: Throwable) {
            Log.w(TAG, "Share failed", t)
            toast(R.string.share_failed)
        }
    }

    private fun buildDisplayName(fps: Int): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return "WOWCAM_${stamp}_${fps}FPS.mp4"
    }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun cleanTempDir() {
        tempDir.mkdirs()
        runCatching {
            tempDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("wowcam_")) file.delete()
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class BindAttempt(
        val targetFps: Int,
        val quality: Quality,
        val lockAe: Boolean
    )

    private enum class Phase {
        IDLE, COUNTDOWN, STARTING, RECORDING, STOPPING, PROCESSING
    }

    private enum class TimerMode(val seconds: Int) {
        OFF(0), THREE(3), TEN(10)
    }

    private enum class VideoQuality(
        val bitsPerSecond: Int,
        val label: String,
        val cameraQuality: Quality
    ) {
        HD(20_000_000, "720P", Quality.HD),
        FHD(60_000_000, "1080P", Quality.FHD),
        UHD(80_000_000, "4K", Quality.UHD)
    }

    private enum class StorageMode {
        GALLERY, SD_CARD
    }

    private companion object {
        const val TAG = "WowCamMain"
        const val DEFAULT_FPS = 30
        const val MIN_RECORDING_MS = 800L
        const val ZOOM_SLIDER_MAX = 1_000
        const val MAX_ZOOM_RATIO = 5f
        const val THERMAL_WATCH_TICKS = 16 // 16 × 250 ms ≈ every 4 s during recording
        const val METER_UPDATE_TICKS = 2 // 2 × 250 ms = 500 ms mic-meter updates
        const val KEY_PERMISSION_ASKED = "permission_asked"
        const val KEY_FILTER = "filter_preset"
        const val KEY_TIMER = "timer_mode"
        const val KEY_LENS = "lens_facing"
        const val KEY_QUALITY = "video_quality"
        const val KEY_STORAGE = "storage_mode"
        const val KEY_SD_URI = "sd_tree_uri"
        const val KEY_HINT_SHOWN = "capability_hint_shown"
        const val PREFS_NAME = "wowcam_prefs"
    }
}
