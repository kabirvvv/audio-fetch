package com.audiofetch

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
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

        // Check for updates
        checkForUpdate()

        // Init Python / Chaquopy
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // Load saved theme
        val savedThemeId = getSharedPreferences("vibe", MODE_PRIVATE)
            .getString("theme", "emerald") ?: "emerald"
        currentTheme = VibeThemes.byId(savedThemeId)

        setupUI()
        applyTheme(currentTheme, animate = false)
        connectPlayer()

        // Scan existing downloads
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
        // RecyclerView
        trackAdapter = TrackAdapter(mutableListOf(), ::loadTrack)
        binding.playlistRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = trackAdapter
        }

        // Download / search button
        binding.fetchBtn.setOnClickListener { startDownload() }
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { startDownload(); true } else false
        }

        // Playback controls
        binding.playPauseBtn.setOnClickListener { togglePlayPause() }
        binding.nextBtn.setOnClickListener { playNext() }
        binding.prevBtn.setOnClickListener { playPrev() }
        binding.repeatBtn.setOnClickListener { cycleRepeat() }

        // Seek bar
        binding.seekBar.onSeek = { fraction ->
            player?.let { p ->
                val pos = (fraction * p.duration).toLong()
                p.seekTo(pos)
            }
        }

        // Playlist sheet
        binding.playlistBtn.setOnClickListener { showPlaylist() }
        binding.closePlaylistBtn.setOnClickListener { hidePlaylist() }
        binding.clearQueueBtn.setOnClickListener { clearQueue() }

        // Settings panel
        binding.menuBtn.setOnClickListener { showSettings() }
        binding.closeSettingsBtn.setOnClickListener { hideSettings() }

        // Scrim dismisses everything
        binding.scrim.setOnClickListener {
            hidePlaylist()
            hideSettings()
        }

        // EQ sliders (each SeekBar goes 0-24, centre 12 = 0dB)
        setupEQ()

        // Sleep timer chips
        setupTimerChips()

        // Theme grid
        setupThemeGrid()
    }

    private fun setupEQ() {
        fun dBFromProgress(p: Int) = (p - 12).toFloat()
        fun label(v: Float) = if (v >= 0) "+${v.toInt()}dB" else "${v.toInt()}dB"

        binding.eqLow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                val dB = dBFromProgress(p)
                binding.eqLowVal.text = label(dB)
                // Future: wire to AudioEffect if needed
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        binding.eqMid.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                binding.eqMidVal.text = label(dBFromProgress(p))
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        binding.eqHigh.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                binding.eqHighVal.text = label(dBFromProgress(p))
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // EQ preset chips
        val presets = listOf("Flat", "Bass", "Pop", "Chill")
        val presetValues = mapOf(
            "Flat" to Triple(12, 12, 12),
            "Bass" to Triple(20, 14, 10),
            "Pop" to Triple(14, 16, 18),
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
                    alpha = 1f
                    scaleX = 1.15f; scaleY = 1.15f
                } else {
                    alpha = 0.6f
                }
                setOnClickListener {
                    applyTheme(theme, animate = true)
                    // Reset all dots
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
            // Animate background color
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

        // Update accent-colored elements
        binding.fetchBtn.setColorFilter(theme.accentColor)
        binding.artGlow.setBackgroundColor(theme.accentColor)
        binding.visualizer.setAccentColor(theme.accentColor)
        binding.seekBar.setAccentColor(theme.accentColor)
        binding.progressBar.indeterminateTintList =
            android.content.res.ColorStateList.valueOf(theme.accentColor)

        // Play button background
        val playBg = GradientDrawable()
        playBg.shape = GradientDrawable.OVAL
        playBg.setColor(Color.WHITE)
        binding.playPauseBtn.background = playBg

        // SeekBars in settings
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
    // VISUALIZER
    // ─────────────────────────────────────────────

   private fun startVisualizer() {
    val exoPlayer = (player as? androidx.media3.exoplayer.ExoPlayer) ?: return
    val audioSessionId = exoPlayer.audioSessionId
    if (audioSessionId == 0) return

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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopVisualizer() {
        androidVisualizer?.enabled = false
        androidVisualizer?.release()
        androidVisualizer = null
        binding.visualizer.clear()
    }

    private fun updateGlow(fft: ByteArray) {
        if (fft.isEmpty()) return
        val energy = fft.take(20).sumOf { (it.toInt() and 0xFF) } / 20f / 255f
        val targetAlpha = (0.3f + energy * 0.7f).coerceIn(0f, 1f)
        binding.artGlow.animate()
            .alpha(targetAlpha)
            .scaleX(1.1f + energy * 0.15f)
            .scaleY(1.1f + energy * 0.15f)
            .setDuration(80)
            .start()
    }

    // ─────────────────────────────────────────────
    // PLAYBACK CONTROLS
    // ─────────────────────────────────────────────

    private fun loadTrack(index: Int) {
        if (index < 0 || index >= tracks.size) return
        currentIndex = index
        val track = tracks[index]
        setTrackInfo(track, index)
        trackAdapter.setNowPlaying(index)

        player?.let { p ->
            p.clearMediaItems()
            tracks.forEach { t ->
                p.addMediaItem(MediaItem.fromUri(t.uri))
            }
            p.seekTo(index, 0)
            p.prepare()
            p.play()
        }
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (tracks.isEmpty()) return
        if (p.isPlaying) {
            p.pause()
            stopVisualizer()
        } else {
            if (currentIndex < 0 && tracks.isNotEmpty()) loadTrack(0)
            else p.play()
        }
    }

    private fun playNext() {
        val next = currentIndex + 1
        if (next < tracks.size) loadTrack(next)
        else if (repeatMode == Player.REPEAT_MODE_ALL) loadTrack(0)
    }

    private fun playPrev() {
        val prev = currentIndex - 1
        if (prev >= 0) loadTrack(prev)
        else if (repeatMode == Player.REPEAT_MODE_ALL) loadTrack(tracks.size - 1)
    }

    private fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player?.repeatMode = repeatMode
        binding.repeatBtn.alpha = if (repeatMode == Player.REPEAT_MODE_OFF) 0.4f else 1f
        binding.repeatBtn.setColorFilter(
            if (repeatMode != Player.REPEAT_MODE_OFF) currentTheme.accentColor
            else Color.argb(153, 255, 255, 255)
        )
    }

    private fun clearQueue() {
        tracks.clear()
        currentIndex = -1
        player?.clearMediaItems()
        player?.stop()
        stopVisualizer()
        trackAdapter.updateTracks(emptyList())
        binding.trackTitle.text = "Vibe Check"
        binding.trackArtist.text = "Queue cleared"
        updatePlayPauseIcon(false)
        animateAlbumArt(false)
    }

    // ─────────────────────────────────────────────
    // UI UPDATES
    // ─────────────────────────────────────────────

    private fun setTrackInfo(track: Track, index: Int) {
        binding.trackTitle.text = track.title
        binding.trackArtist.text = track.artist.ifEmpty { "AudioFetch" }
    }

    private fun updatePlayPauseIcon(playing: Boolean) {
        binding.playIcon.visibility = if (playing) View.GONE else View.VISIBLE
        binding.pauseIcon.visibility = if (playing) View.VISIBLE else View.GONE
    }

    private fun updateProgressUI() {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: return
        val pos = p.currentPosition
        val fraction = pos.toFloat() / duration.toFloat()

        binding.seekBar.progress = fraction
        binding.currentTime.text = formatTime(pos)
        binding.totalTime.text = formatTime(duration)
    }

    private fun animateAlbumArt(playing: Boolean) {
        binding.albumArt.animate()
            .scaleX(if (playing) 1.04f else 1f)
            .scaleY(if (playing) 1.04f else 1f)
            .setDuration(400)
            .setInterpolator(DecelerateInterpolator())
            .start()

        if (!playing) {
            binding.artGlow.animate().alpha(0f).setDuration(600).start()
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        val sec = s % 60
        return "$m:${sec.toString().padStart(2, '0')}"
    }

    // ─────────────────────────────────────────────
    // PANEL ANIMATIONS
    // ─────────────────────────────────────────────

    private fun showPlaylist() {
        trackAdapter.updateTracks(tracks)
        binding.scrim.visibility = View.VISIBLE
        binding.playlistSheet.visibility = View.VISIBLE
        binding.scrim.animate().alpha(0.6f).setDuration(300).start()
        binding.playlistSheet.animate()
            .translationY(0f).setDuration(400)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun hidePlaylist() {
        binding.scrim.animate().alpha(0f).setDuration(200).withEndAction {
            binding.scrim.visibility = View.GONE
        }.start()
        binding.playlistSheet.animate()
            .translationY(binding.playlistSheet.height.toFloat() + 100f)
            .setDuration(300).withEndAction {
                binding.playlistSheet.visibility = View.GONE
            }.start()
    }

    private fun showSettings() {
        binding.scrim.visibility = View.VISIBLE
        binding.settingsPanel.visibility = View.VISIBLE
        binding.scrim.animate().alpha(0.6f).setDuration(300).start()
        binding.settingsPanel.animate()
            .translationX(0f).setDuration(400)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun hideSettings() {
        binding.scrim.animate().alpha(0f).setDuration(200).withEndAction {
            binding.scrim.visibility = View.GONE
        }.start()
        binding.settingsPanel.animate()
            .translationX(binding.settingsPanel.width.toFloat())
            .setDuration(300).withEndAction {
                binding.settingsPanel.visibility = View.GONE
            }.start()
    }

    // ─────────────────────────────────────────────
    // SLEEP TIMER
    // ─────────────────────────────────────────────

    private fun setSleepTimer(minutes: Int) {
        sleepTimerRunnable?.let { sleepTimerHandler?.removeCallbacks(it) }
        if (minutes <= 0) return
        sleepTimerHandler = Handler(Looper.getMainLooper())
        sleepTimerRunnable = Runnable {
            player?.pause()
            stopVisualizer()
        }
        sleepTimerHandler?.postDelayed(sleepTimerRunnable!!, minutes * 60 * 1000L)
        setStatus("Sleep timer: ${minutes}m", StatusType.NEUTRAL)
    }

    // ─────────────────────────────────────────────
    // DOWNLOAD (preserved from original)
    // ─────────────────────────────────────────────

    private fun startDownload() {
        val input = binding.urlInput.text?.toString()?.trim() ?: ""
        if (input.isEmpty()) {
            setStatus("no url or search term provided.", StatusType.ERROR)
            return
        }

        // Hide keyboard
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.urlInput.windowToken, 0)

        setStatus("fetching… this may take a moment.", StatusType.NEUTRAL)
        binding.fetchBtn.isEnabled = false
        binding.progressBar.isVisible = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runDownload(input) }

            binding.fetchBtn.isEnabled = true
            binding.progressBar.isVisible = false

            if (result.startsWith("ERROR:")) {
                setStatus(result, StatusType.ERROR)
            } else {
                setStatus("done ✓ adding to queue…", StatusType.SUCCESS)
                val uri = saveToDownloads(result)
                if (uri != null) {
                    // Resolve title from filename
                    val file = File(result)
                    val title = file.nameWithoutExtension
                        .replace('_', ' ').replace('-', ' ').trim()
                    val newTrack = Track(uri = uri, title = title)

                    // Add to top of queue (most recent download plays next)
                    tracks.add(0, newTrack)
                    currentIndex = if (currentIndex >= 0) currentIndex + 1 else -1
                    trackAdapter.updateTracks(tracks)

                    // Auto-play if nothing is playing
                    if (player?.isPlaying == false) {
                        loadTrack(0)
                    }

                    binding.urlInput.text?.clear()
                    setStatus("added: $title", StatusType.SUCCESS)
                }
            }
        }
    }

    private fun runDownload(url: String): String {
        return try {
            val py = Python.getInstance()
            val module = py.getModule("main")
            val tmpDir = cacheDir.absolutePath
            module.callAttr("download_audio", url, tmpDir).toString()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    /**
     * Copies downloaded tmp file to public Downloads.
     * Returns the MediaStore URI so the player can immediately reference it.
     */
    private fun saveToDownloads(srcPath: String): Uri? {
        val src = File(srcPath)
        if (!src.exists()) return null

        val mimeType = when (src.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "webm" -> "audio/webm"
            else -> "audio/*"
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().copyTo(out) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            src.delete()
            uri
        } else {
            val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            destDir.mkdirs()
            val dest = File(destDir, src.name)
            src.copyTo(dest, overwrite = true)
            src.delete()
            Uri.fromFile(dest)
        }
    }

    // ─────────────────────────────────────────────
    // UPDATE CHECK (preserved)
    // ─────────────────────────────────────────────

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) {
                UpdateChecker.checkForUpdate(BuildConfig.VERSION_CODE)
            }
            if (update != null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update available — ${update.versionName}")
                    .setMessage(update.changelog)
                    .setPositiveButton("Update Now") { _, _ ->
                        UpdateChecker.downloadAndInstall(this@MainActivity, update)
                    }
                    .setNeutralButton("View on GitHub") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    // ─────────────────────────────────────────────
    // STATUS
    // ─────────────────────────────────────────────

    private enum class StatusType { NEUTRAL, ERROR, SUCCESS }

    private fun setStatus(msg: String, type: StatusType) {
        binding.statusText.text = msg
        binding.statusText.setTextColor(
            getColor(when (type) {
                StatusType.ERROR -> R.color.status_error
                StatusType.SUCCESS -> R.color.status_success
                StatusType.NEUTRAL -> R.color.status_muted
            })
        )
    }

    // ─────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (player?.isPlaying == true) uiHandler.post(uiRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(uiRunnable)
        stopVisualizer()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        sleepTimerRunnable?.let { sleepTimerHandler?.removeCallbacks(it) }
    }
}
