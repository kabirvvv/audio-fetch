package com.audiofetch

import android.util.Log
import android.Manifest
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.audiofetch.databinding.ActivityMainBinding
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Player
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var player: MediaController? = null
    private var androidVisualizer: Visualizer? = null
    private var equalizer: Equalizer? = null

    // State
    private val tracks = mutableListOf<Track>()
    private var currentIndex = -1
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var currentTheme = VibeThemes.all[0]
    private var sleepTimerHandler: Handler? = null
    private var sleepTimerRunnable: Runnable? = null

    // UI update handler
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRunnable = object : Runnable {
        override fun run() {
            updateProgressUI()
            uiHandler.postDelayed(this, 250)
        }
    }

    // Adapter
    private lateinit var trackAdapter: TrackAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkForUpdate()

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        val savedThemeId = getSharedPreferences("vibe", MODE_PRIVATE)
            .getString("theme", "emerald") ?: "emerald"
        currentTheme = VibeThemes.byId(savedThemeId)

        setupUI()
        applyTheme(currentTheme, animate = false)
        connectPlayer()

        // Request READ_MEDIA_AUDIO permission — scan only after it's granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            scanAndLoadTracks()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO),
                1002
            )
        }
    }

    // ─────────────────────────────────────────────
    // TRACK SCANNING
    // ─────────────────────────────────────────────

    private fun scanAndLoadTracks() {
        lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) { MusicScanner.scanDownloads(this@MainActivity) }
            if (scanned.isNotEmpty()) {
                tracks.addAll(scanned)
                trackAdapter.updateTracks(tracks)
                if (currentIndex < 0) setTrackInfo(tracks[0], -1)
            }
        }
    }

    // ─────────────────────────────────────────────
    // UI SETUP
    // ─────────────────────────────────────────────

    private fun setupUI() {
        trackAdapter = TrackAdapter(mutableListOf(), ::loadTrack)
        binding.playlistRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = trackAdapter
        }

        binding.fetchBtn.setOnClickListener { startDownload() }
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { startDownload(); true } else false
        }

        binding.playPauseBtn.setOnClickListener { togglePlayPause() }
        binding.nextBtn.setOnClickListener { playNext() }
        binding.prevBtn.setOnClickListener { playPrev() }
        binding.repeatBtn.setOnClickListener { cycleRepeat() }

        binding.seekBar.onSeek = { fraction ->
            player?.let { p ->
                val pos = (fraction * p.duration).toLong()
                p.seekTo(pos)
            }
        }

        binding.playlistBtn.setOnClickListener { showPlaylist() }
        binding.closePlaylistBtn.setOnClickListener { hidePlaylist() }
        binding.clearQueueBtn.setOnClickListener { clearQueue() }

        binding.menuBtn.setOnClickListener { showSettings() }
        binding.closeSettingsBtn.setOnClickListener { hideSettings() }

        binding.scrim.setOnClickListener {
            hidePlaylist()
            hideSettings()
        }

        setupEQ()
        setupTimerChips()
        setupThemeGrid()
    }

    private fun setupEQ() {
        fun dBFromProgress(p: Int) = (p - 12).toFloat()
        fun label(v: Float) = if (v >= 0) "+${v.toInt()}dB" else "${v.toInt()}dB"

        fun applyEQ() {
            val eq = equalizer ?: return
            if (!eq.enabled) eq.enabled = true
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val minMB = range[0]; val maxMB = range[1]

            fun progressToMillibels(p: Int): Short {
                val fraction = p / 24f
                return (minMB + (fraction * (maxMB - minMB))).toInt().toShort()
            }

            if (numBands >= 1) eq.setBandLevel(0, progressToMillibels(binding.eqLow.progress))
            if (numBands >= 3) eq.setBandLevel((numBands / 2).toShort(), progressToMillibels(binding.eqMid.progress))
            if (numBands >= 2) eq.setBandLevel((numBands - 1).toShort(), progressToMillibels(binding.eqHigh.progress))
        }

        binding.eqLow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                binding.eqLowVal.text = label(dBFromProgress(p)); applyEQ()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        binding.eqMid.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                binding.eqMidVal.text = label(dBFromProgress(p)); applyEQ()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        binding.eqHigh.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                binding.eqHighVal.text = label(dBFromProgress(p)); applyEQ()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val presets = listOf("Flat", "Bass", "Pop", "Chill")
        val presetValues = mapOf(
            "Flat"  to Triple(12, 12, 12),
            "Bass"  to Triple(20, 14, 10),
            "Pop"   to Triple(14, 16, 18),
            "Chill" to Triple(16, 10, 14)
        )
        presets.forEach { name ->
            val chip = buildChip(name, isActive = name == "Flat")
            chip.setOnClickListener {
                val (l, m, h) = presetValues[name] ?: Triple(12, 12, 12)
                binding.eqLow.progress = l
                binding.eqMid.progress = m
                binding.eqHigh.progress = h
                updateChipSelection(binding.eqPresets, chip)
                applyEQ()
            }
            binding.eqPresets.addView(chip)
        }
    }

    private fun setupTimerChips() {
        val options = listOf("Off" to 0, "30m" to 30, "1h" to 60, "2h" to 120, "3h" to 180)
        options.forEach { (label, mins) ->
            val chip = buildChip(label, isActive = mins == 0)
            chip.setOnClickListener {
                setSleepTimer(mins)
                updateChipSelection(binding.timerChips, chip)
            }
            binding.timerChips.addView(chip)
        }
    }

    private fun setupThemeGrid() {
        VibeThemes.all.forEach { theme ->
            val dot = View(this).apply {
                val size = (48 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(6, 6, 6, 6)
                }
                val gd = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(theme.bgColor, theme.accentColor)
                )
                gd.cornerRadius = size / 2f
                background = gd
                if (theme.id == currentTheme.id) {
                    alpha = 1f; scaleX = 1.15f; scaleY = 1.15f
                } else {
                    alpha = 0.6f
                }
                setOnClickListener {
                    applyTheme(theme, animate = true)
                    for (i in 0 until binding.themeGrid.childCount) {
                        val v = binding.themeGrid.getChildAt(i)
                        v.alpha = 0.6f; v.scaleX = 1f; v.scaleY = 1f
                    }
                    alpha = 1f; scaleX = 1.15f; scaleY = 1.15f
                }
            }
            binding.themeGrid.addView(dot)
        }
    }

    private fun buildChip(label: String, isActive: Boolean): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11f
            setPadding(
                (12 * resources.displayMetrics.density).toInt(),
                (6 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (6 * resources.displayMetrics.density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, (8 * resources.displayMetrics.density).toInt(), 0) }
            setTextColor(if (isActive) currentTheme.accentColor else Color.argb(128, 255, 255, 255))
            val gd = GradientDrawable()
            gd.cornerRadius = 24f * resources.displayMetrics.density
            gd.setStroke(1, if (isActive) currentTheme.accentColor else Color.argb(40, 255, 255, 255))
            gd.setColor(if (isActive) Color.argb(30, Color.red(currentTheme.accentColor),
                Color.green(currentTheme.accentColor), Color.blue(currentTheme.accentColor))
            else Color.TRANSPARENT)
            background = gd
            isClickable = true; isFocusable = true
        }
    }

    private fun updateChipSelection(container: LinearLayout, selected: TextView) {
        for (i in 0 until container.childCount) {
            val chip = container.getChildAt(i) as? TextView ?: continue
            val isSelected = chip == selected
            chip.setTextColor(if (isSelected) currentTheme.accentColor else Color.argb(128, 255, 255, 255))
            val gd = chip.background as? GradientDrawable ?: continue
            gd.setStroke(1, if (isSelected) currentTheme.accentColor else Color.argb(40, 255, 255, 255))
            gd.setColor(if (isSelected) Color.argb(30, Color.red(currentTheme.accentColor),
                Color.green(currentTheme.accentColor), Color.blue(currentTheme.accentColor))
            else Color.TRANSPARENT)
        }
    }

    // ─────────────────────────────────────────────
    // THEMING
    // ─────────────────────────────────────────────

    private fun applyTheme(theme: VibeTheme, animate: Boolean) {
        currentTheme = theme
        getSharedPreferences("vibe", MODE_PRIVATE).edit().putString("theme", theme.id).apply()

        if (animate) {
            val from = (binding.bgVibe.background as? GradientDrawable)?.colors?.getOrNull(0)
                ?: theme.bgColor
            val colorAnim = ValueAnimator.ofArgb(from, theme.bgColor).apply {
                duration = 800
                addUpdateListener { anim ->
                    val color = anim.animatedValue as Int
                    val vibe = blendColors(color, theme.vibeColor, 0.5f)
                    val gd = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(color, vibe, color)
                    )
                    binding.bgVibe.background = gd
                    binding.rootLayout.setBackgroundColor(color)
                }
            }
            colorAnim.start()
        } else {
            val gd = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(theme.bgColor, theme.vibeColor, theme.bgColor)
            )
            binding.bgVibe.background = gd
            binding.rootLayout.setBackgroundColor(theme.bgColor)
        }

        binding.fetchBtn.setColorFilter(theme.accentColor)
        binding.artGlow.setBackgroundColor(theme.accentColor)
        binding.visualizer.setAccentColor(theme.accentColor)
        binding.seekBar.setAccentColor(theme.accentColor)
        binding.progressBar.indeterminateTintList =
            android.content.res.ColorStateList.valueOf(theme.accentColor)

        val playBg = GradientDrawable()
        playBg.shape = GradientDrawable.OVAL
        playBg.setColor(Color.WHITE)
        binding.playPauseBtn.background = playBg

        listOf(binding.eqLow, binding.eqMid, binding.eqHigh).forEach { sb ->
            sb.progressTintList = android.content.res.ColorStateList.valueOf(theme.accentColor)
            sb.thumbTintList = android.content.res.ColorStateList.valueOf(theme.accentColor)
        }
        listOf(binding.eqLowVal, binding.eqMidVal, binding.eqHighVal).forEach {
            it.setTextColor(theme.accentColor)
        }

        trackAdapter.accentColor = theme.accentColor
        trackAdapter.notifyDataSetChanged()
    }

    private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = (Color.red(c1) * inv + Color.red(c2) * ratio).toInt()
        val g = (Color.green(c1) * inv + Color.green(c2) * ratio).toInt()
        val b = (Color.blue(c1) * inv + Color.blue(c2) * ratio).toInt()
        return Color.rgb(r, g, b)
    }

    // ─────────────────────────────────────────────
    // PLAYER CONNECTION
    // ─────────────────────────────────────────────

    private fun connectPlayer() {
        val sessionToken = SessionToken(this, ComponentName(this, PlayerService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            player = controllerFuture?.get()
            setupPlayerListeners()
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListeners() {
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
                if (isPlaying) {
                    uiHandler.post(uiRunnable)
                    animateAlbumArt(true)
                    startVisualizer()
                } else {
                    uiHandler.removeCallbacks(uiRunnable)
                    animateAlbumArt(false)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = player?.currentMediaItemIndex ?: return
                if (idx >= 0 && idx < tracks.size) {
                    currentIndex = idx
                    setTrackInfo(tracks[idx], idx)
                    trackAdapter.setNowPlaying(idx)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (repeatMode == Player.REPEAT_MODE_ONE) {
                        player?.seekTo(0)
                        player?.play()
                    }
                }
            }
        })
    }

    // ─────────────────────────────────────────────
    // VISUALIZER & EQ
    // ─────────────────────────────────────────────

    private fun startVisualizer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            return
        }

        val audioSessionId = PlayerService.audioSessionId
        Log.d("VIBE", "audioSessionId = ${PlayerService.audioSessionId}")
        if (audioSessionId == 0) {
            uiHandler.postDelayed({ startVisualizer() }, 500)
            return
        }

        try {
            androidVisualizer?.release()
            androidVisualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, sr: Int) {}
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, sr: Int) {
                        runOnUiThread {
                            binding.visualizer.updateFft(fft)
                            updateGlow(fft)
                        }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
            initEqualizer(audioSessionId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startVisualizer()
        }
        if (requestCode == 1002 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            scanAndLoadTracks()
        }
    }

    private fun stopVisualizer() {
        androidVisualizer?.enabled = false
        androidVisualizer?.release()
        androidVisualizer = null
        equalizer?.release()
        equalizer = null
        binding.visualizer.clear()
    }

    private fun initEqualizer(audioSessionId: Int) {
        try {
            equalizer?.release()
            equalizer = Equalizer(0, audioSessionId).apply { enabled = true }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateGlow(fft: ByteArray) {
        if (fft.isEmpty()) return
        val energy = fft.take(20).sumOf { (it.toInt() and 0xFF) } / 20f / 255f
        val targetAlpha = (0.3f + energy * 0.7f).coerceIn(0f, 1f)
        binding.artGlow.animate()
            .alpha(t
