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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.audiofetch.databinding.ActivityMainBinding
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var player: MediaController? = null
    private var androidVisualizer: Visualizer? = null
    private var equalizer: Equalizer? = null

    private val tracks = mutableListOf<Track>()
    private var currentIndex = -1
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var currentTheme = VibeThemes.all[0]
    private var sleepTimerHandler: Handler? = null
    private var sleepTimerRunnable: Runnable? = null

    // Holds metadata of the currently streaming track so download button can use it
    private var currentStreamWebpageUrl: String? = null
    private var currentStreamTitle: String? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRunnable = object : Runnable {
        override fun run() {
            updateProgressUI()
            uiHandler.postDelayed(this, 250)
        }
    }

    private lateinit var trackAdapter: TrackAdapter
    private lateinit var libraryAdapter: LibraryAdapter
    private lateinit var playlistsAdapter: PlaylistsAdapter
    private lateinit var searchResultsAdapter: SearchResultsAdapter

    private var openPlaylist: Playlist? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LibraryManager.init(this)
        checkForUpdate()

        if (!Python.isStarted()) Python.start(AndroidPlatform(this))

        val savedThemeId = getSharedPreferences("vibe", MODE_PRIVATE)
            .getString("theme", "emerald") ?: "emerald"
        currentTheme = VibeThemes.byId(savedThemeId)

        setupUI()
        applyTheme(currentTheme, animate = false)
        connectPlayer()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            scanLocalTracks()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_MEDIA_AUDIO), 1002
            )
        }
    }

    // ─────────────────────────────────────────────
    // LOCAL SCAN
    // ─────────────────────────────────────────────

    private fun scanLocalTracks() {
        lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) { MusicScanner.scanDownloads(this@MainActivity) }
            LibraryManager.setLocal(this@MainActivity, scanned)
            refreshLibraryTab()
        }
    }

    // ─────────────────────────────────────────────
    // UI SETUP
    // ─────────────────────────────────────────────

    private fun setupUI() {
        // ── Queue ─────────────────────────────────────────────────────────────
        trackAdapter = TrackAdapter(mutableListOf(), ::loadTrack)
        trackAdapter.onTrackMoved = { from, to -> onQueueReordered(from, to) }
        val dragCallback = DragDropCallback(trackAdapter)
        val itemTouchHelper = ItemTouchHelper(dragCallback)
        trackAdapter.itemTouchHelper = itemTouchHelper
        binding.playlistRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = trackAdapter
        }
        itemTouchHelper.attachToRecyclerView(binding.playlistRecycler)

        // ── Library ───────────────────────────────────────────────────────────
        libraryAdapter = LibraryAdapter(emptyList(), ::playFromLibrary, ::onLibraryTrackLongPress)
        binding.libraryRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = libraryAdapter
        }

        binding.playlistDetailRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = libraryAdapter
        }

        playlistsAdapter = PlaylistsAdapter(emptyList(), ::openPlaylist, ::onPlaylistLongPress)
        binding.playlistsRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = playlistsAdapter
        }

        // ── Search results ────────────────────────────────────────────────────
        searchResultsAdapter = SearchResultsAdapter(emptyList()) { result ->
            hideSearchSheet()
            streamFromSearchResult(result)
        }
        binding.searchResultsRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = searchResultsAdapter
        }
        binding.closeSearchSheetBtn.setOnClickListener { hideSearchSheet() }

        // ── Library tabs ──────────────────────────────────────────────────────
        binding.libTabDownloads.setOnClickListener { selectLibraryTab(0) }
        binding.libTabLocal.setOnClickListener     { selectLibraryTab(1) }
        binding.libTabHistory.setOnClickListener   { selectLibraryTab(2) }
        binding.newPlaylistBtn.setOnClickListener  { promptCreatePlaylist() }
        binding.backFromPlaylistBtn.setOnClickListener { closeOpenPlaylist() }

        // ── Bottom nav ────────────────────────────────────────────────────────
        binding.navPlayer.setOnClickListener  { showPlayerTab() }
        binding.navLibrary.setOnClickListener { showLibraryTab() }

        // ── Player controls ───────────────────────────────────────────────────
        binding.fetchBtn.setOnClickListener { startStream() }
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { startStream(); true } else false
        }

        // Download button — downloads whatever is currently streaming
        binding.downloadBtn.setOnClickListener { downloadCurrentStream() }
        binding.downloadBtn.isVisible = false

        binding.playPauseBtn.setOnClickListener { togglePlayPause() }
        binding.nextBtn.setOnClickListener { playNext() }
        binding.prevBtn.setOnClickListener { playPrev() }
        binding.repeatBtn.setOnClickListener { cycleRepeat() }

        binding.seekBar.onSeek = { fraction ->
            player?.let { p -> p.seekTo((fraction * p.duration).toLong()) }
        }

        binding.playlistBtn.setOnClickListener { showQueueSheet() }
        binding.closePlaylistBtn.setOnClickListener { hideQueueSheet() }
        binding.clearQueueBtn.setOnClickListener { clearQueue() }

        binding.menuBtn.setOnClickListener { showSettings() }
        binding.closeSettingsBtn.setOnClickListener { hideSettings() }
        binding.libraryCloseBtn.setOnClickListener { showPlayerTab() }

        binding.scrim.setOnClickListener {
            hideQueueSheet()
            hideSettings()
            hideSearchSheet()
        }

        setupEQ()
        setupTimerChips()
        setupThemeGrid()
        selectLibraryTab(0)
    }

    // ─────────────────────────────────────────────
    // BOTTOM NAV
    // ─────────────────────────────────────────────

    private fun showPlayerTab() {
        binding.playerContainer.visibility = View.VISIBLE
        binding.libraryContainer.visibility = View.GONE
        binding.navPlayer.alpha = 1f
        binding.navLibrary.alpha = 0.4f
    }

    private fun showLibraryTab() {
        binding.playerContainer.visibility = View.GONE
        binding.libraryContainer.visibility = View.VISIBLE
        binding.navPlayer.alpha = 0.4f
        binding.navLibrary.alpha = 1f
        refreshLibraryTab()
        refreshPlaylists()
    }

    // ─────────────────────────────────────────────
    // LIBRARY TABS
    // ─────────────────────────────────────────────

    private var currentLibTab = 0

    private fun selectLibraryTab(tab: Int) {
        currentLibTab = tab
        val accent = currentTheme.accentColor
        val dim = 0x40FFFFFF.toInt()
        binding.libTabDownloads.setTextColor(if (tab == 0) accent else dim)
        binding.libTabLocal.setTextColor(if (tab == 1) accent else dim)
        binding.libTabHistory.setTextColor(if (tab == 2) accent else dim)
        refreshLibraryTab()
    }

    private fun refreshLibraryTab() {
        val list = when (currentLibTab) {
            0 -> LibraryManager.getDownloads()
            1 -> LibraryManager.getLocal()
            else -> LibraryManager.getHistory()
        }
        libraryAdapter.update(list)
    }

    private fun refreshPlaylists() {
        playlistsAdapter.update(LibraryManager.getPlaylists())
    }

    // ─────────────────────────────────────────────
    // PLAYLIST OPEN / CLOSE
    // ─────────────────────────────────────────────

    private fun openPlaylist(playlist: Playlist) {
        openPlaylist = playlist
        binding.playlistDetailTitle.text = playlist.name
        binding.libraryNormalSection.visibility = View.GONE
        binding.playlistDetailSection.visibility = View.VISIBLE
        libraryAdapter.update(LibraryManager.getPlaylistTracks(playlist))
    }

    private fun closeOpenPlaylist() {
        openPlaylist = null
        binding.playlistDetailSection.visibility = View.GONE
        binding.libraryNormalSection.visibility = View.VISIBLE
        refreshLibraryTab()
        refreshPlaylists()
    }

    // ─────────────────────────────────────────────
    // LIBRARY INTERACTIONS
    // ─────────────────────────────────────────────

    private fun playFromLibrary(track: Track) {
        LibraryManager.addToHistory(this, track)
        if (tracks.none { it.uri == track.uri }) {
            tracks.add(track)
            trackAdapter.updateTracks(tracks)
        }
        val idx = tracks.indexOfFirst { it.uri == track.uri }
        loadTrack(idx)
        showPlayerTab()
    }

    private fun onLibraryTrackLongPress(track: Track, _anchor: View) {
        val options = mutableListOf("Add to Queue", "Add to Playlist")
        if (currentLibTab == 0) options.add("Remove from Downloads")
        if (currentLibTab == 1) options.add("Remove from Local")
        if (openPlaylist != null) options.add("Remove from Playlist")

        AlertDialog.Builder(this)
            .setTitle(track.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Add to Queue" -> addToQueue(track)
                    "Add to Playlist" -> promptAddToPlaylist(track)
                    "Remove from Downloads" -> {
                        LibraryManager.removeDownload(this, track)
                        refreshLibraryTab()
                    }
                    "Remove from Local" -> {
                        val updated = LibraryManager.getLocal().filter { it.uri != track.uri }
                        LibraryManager.setLocal(this, updated)
                        refreshLibraryTab()
                    }
                    "Remove from Playlist" -> {
                        openPlaylist?.let { pl ->
                            LibraryManager.removeTrackFromPlaylist(this, pl.id, track.uri.toString())
                            openPlaylist(pl)
                        }
                    }
                }
            }.show()
    }

    private fun addToQueue(track: Track) {
        if (tracks.none { it.uri == track.uri }) {
            tracks.add(track)
            trackAdapter.updateTracks(tracks)
        }
        setStatus("Added to queue: ${track.title}", StatusType.SUCCESS)
    }

    private fun promptAddToPlaylist(track: Track) {
        val playlists = LibraryManager.getPlaylists()
        if (playlists.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("No playlists yet. Create one first.")
                .setPositiveButton("Create") { _, _ -> promptCreatePlaylist() }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val names = playlists.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add to playlist")
            .setItems(names) { _, which ->
                LibraryManager.addTrackToPlaylist(this, playlists[which].id, track)
                setStatus("Added to ${playlists[which].name}", StatusType.SUCCESS)
            }.show()
    }

    // ─────────────────────────────────────────────
    // PLAYLIST MANAGEMENT
    // ─────────────────────────────────────────────

    private fun promptCreatePlaylist() {
        val input = EditText(this).apply {
            hint = "Playlist name"
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    LibraryManager.createPlaylist(this, name)
                    refreshPlaylists()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun onPlaylistLongPress(playlist: Playlist, _anchor: View) {
        val options = arrayOf("Rename", "Delete")
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this).apply {
                            setText(playlist.name)
                            setPadding(48, 32, 48, 16)
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Rename Playlist")
                            .setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                val name = input.text.toString().trim()
                                if (name.isNotEmpty()) {
                                    LibraryManager.renamePlaylist(this, playlist.id, name)
                                    refreshPlaylists()
                                }
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                    1 -> {
                        AlertDialog.Builder(this)
                            .setMessage("Delete \"${playlist.name}\"?")
                            .setPositiveButton("Delete") { _, _ ->
                                LibraryManager.deletePlaylist(this, playlist.id)
                                refreshPlaylists()
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                }
            }.show()
    }

    // ─────────────────────────────────────────────
    // QUEUE REORDER
    // ─────────────────────────────────────────────

    private fun onQueueReordered(from: Int, to: Int) {
        currentIndex = when {
            from == currentIndex -> to
            from < currentIndex && to >= currentIndex -> currentIndex - 1
            from > currentIndex && to <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        player?.moveMediaItem(from, to)
    }

    // ─────────────────────────────────────────────
    // QUEUE SHEET
    // ─────────────────────────────────────────────

    private fun showQueueSheet() {
        trackAdapter.updateTracks(tracks)
        binding.scrim.visibility = View.VISIBLE
        binding.playlistSheet.visibility = View.VISIBLE
        binding.scrim.animate().alpha(0.6f).setDuration(300).start()
        binding.playlistSheet.animate()
            .translationY(0f).setDuration(400)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun hideQueueSheet() {
        binding.scrim.animate().alpha(0f).setDuration(200).withEndAction {
            binding.scrim.visibility = View.GONE
        }.start()
        binding.playlistSheet.animate()
            .translationY(binding.playlistSheet.height.toFloat() + 100f)
            .setDuration(300).withEndAction {
                binding.playlistSheet.visibility = View.GONE
            }.start()
    }

    // ─────────────────────────────────────────────
    // SEARCH SHEET
    // ─────────────────────────────────────────────

    private fun showSearchSheet(searching: Boolean = false) {
        binding.searchSheetProgress.isVisible = searching
        binding.scrim.visibility = View.VISIBLE
        binding.searchSheet.visibility = View.VISIBLE
        binding.scrim.animate().alpha(0.6f).setDuration(300).start()
        binding.searchSheet.animate()
            .translationY(0f).setDuration(400)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun hideSearchSheet() {
        binding.scrim.animate().alpha(0f).setDuration(200).withEndAction {
            binding.scrim.visibility = View.GONE
        }.start()
        binding.searchSheet.animate()
            .translationY(binding.searchSheet.height.toFloat() + 100f)
            .setDuration(300).withEndAction {
                binding.searchSheet.visibility = View.GONE
            }.start()
    }

    // ─────────────────────────────────────────────
    // EQ / TIMER / THEME
    // ─────────────────────────────────────────────

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
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { binding.eqLowVal.text = label(dBFromProgress(p)); applyEQ() }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        binding.eqMid.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { binding.eqMidVal.text = label(dBFromProgress(p)); applyEQ() }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        binding.eqHigh.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { binding.eqHighVal.text = label(dBFromProgress(p)); applyEQ() }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val presets = listOf("Flat", "Bass", "Pop", "Chill")
        val presetValues = mapOf("Flat" to Triple(12,12,12), "Bass" to Triple(20,14,10), "Pop" to Triple(14,16,18), "Chill" to Triple(16,10,14))
        presets.forEach { name ->
            val chip = buildChip(name, isActive = name == "Flat")
            chip.setOnClickListener {
                val (l, m, h) = presetValues[name] ?: Triple(12,12,12)
                binding.eqLow.progress = l; binding.eqMid.progress = m; binding.eqHigh.progress = h
                updateChipSelection(binding.eqPresets, chip); applyEQ()
            }
            binding.eqPresets.addView(chip)
        }
    }

    private fun setupTimerChips() {
        val options = listOf("Off" to 0, "30m" to 30, "1h" to 60, "2h" to 120, "3h" to 180)
        options.forEach { (label, mins) ->
            val chip = buildChip(label, isActive = mins == 0)
            chip.setOnClickListener { setSleepTimer(mins); updateChipSelection(binding.timerChips, chip) }
            binding.timerChips.addView(chip)
        }
    }

    private fun setupThemeGrid() {
        VibeThemes.all.forEach { theme ->
            val dot = View(this).apply {
                val size = (48 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(6,6,6,6) }
                val gd = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(theme.bgColor, theme.accentColor))
                gd.cornerRadius = size / 2f
                background = gd
                if (theme.id == currentTheme.id) { alpha = 1f; scaleX = 1.15f; scaleY = 1.15f } else alpha = 0.6f
                setOnClickListener {
                    applyTheme(theme, animate = true)
                    for (i in 0 until binding.themeGrid.childCount) {
                        val v = binding.themeGrid.getChildAt(i); v.alpha = 0.6f; v.scaleX = 1f; v.scaleY = 1f
                    }
                    alpha = 1f; scaleX = 1.15f; scaleY = 1.15f
                }
            }
            binding.themeGrid.addView(dot)
        }
    }

    private fun buildChip(label: String, isActive: Boolean): TextView {
        return TextView(this).apply {
            text = label; textSize = 11f
            setPadding((12*resources.displayMetrics.density).toInt(),(6*resources.displayMetrics.density).toInt(),(12*resources.displayMetrics.density).toInt(),(6*resources.displayMetrics.density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,(8*resources.displayMetrics.density).toInt(),0) }
            setTextColor(if (isActive) currentTheme.accentColor else Color.argb(128,255,255,255))
            val gd = GradientDrawable()
            gd.cornerRadius = 24f * resources.displayMetrics.density
            gd.setStroke(1, if (isActive) currentTheme.accentColor else Color.argb(40,255,255,255))
            gd.setColor(if (isActive) Color.argb(30, Color.red(currentTheme.accentColor), Color.green(currentTheme.accentColor), Color.blue(currentTheme.accentColor)) else Color.TRANSPARENT)
            background = gd; isClickable = true; isFocusable = true
        }
    }

    private fun updateChipSelection(container: LinearLayout, selected: TextView) {
        for (i in 0 until container.childCount) {
            val chip = container.getChildAt(i) as? TextView ?: continue
            val isSelected = chip == selected
            chip.setTextColor(if (isSelected) currentTheme.accentColor else Color.argb(128,255,255,255))
            val gd = chip.background as? GradientDrawable ?: continue
            gd.setStroke(1, if (isSelected) currentTheme.accentColor else Color.argb(40,255,255,255))
            gd.setColor(if (isSelected) Color.argb(30, Color.red(currentTheme.accentColor), Color.green(currentTheme.accentColor), Color.blue(currentTheme.accentColor)) else Color.TRANSPARENT)
        }
    }

    // ─────────────────────────────────────────────
    // THEMING
    // ─────────────────────────────────────────────

    private fun applyTheme(theme: VibeTheme, animate: Boolean) {
        currentTheme = theme
        getSharedPreferences("vibe", MODE_PRIVATE).edit().putString("theme", theme.id).apply()
        if (animate) {
            val from = (binding.bgVibe.background as? GradientDrawable)?.colors?.getOrNull(0) ?: theme.bgColor
            ValueAnimator.ofArgb(from, theme.bgColor).apply {
                duration = 800
                addUpdateListener { anim ->
                    val color = anim.animatedValue as Int
                    val vibe = blendColors(color, theme.vibeColor, 0.5f)
                    binding.bgVibe.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color, vibe, color))
                    binding.rootLayout.setBackgroundColor(color)
                }
            }.start()
        } else {
            binding.bgVibe.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(theme.bgColor, theme.vibeColor, theme.bgColor))
            binding.rootLayout.setBackgroundColor(theme.bgColor)
        }
        binding.fetchBtn.setColorFilter(theme.accentColor)
        binding.downloadBtn.setColorFilter(theme.accentColor)
        binding.artGlow.setBackgroundColor(theme.accentColor)
        binding.visualizer.setAccentColor(theme.accentColor)
        binding.seekBar.setAccentColor(theme.accentColor)
        binding.progressBar.indeterminateTintList = android.content.res.ColorStateList.valueOf(theme.accentColor)
        val playBg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        binding.playPauseBtn.background = playBg
        listOf(binding.eqLow, binding.eqMid, binding.eqHigh).forEach {
            it.progressTintList = android.content.res.ColorStateList.valueOf(theme.accentColor)
            it.thumbTintList = android.content.res.ColorStateList.valueOf(theme.accentColor)
        }
        listOf(binding.eqLowVal, binding.eqMidVal, binding.eqHighVal).forEach { it.setTextColor(theme.accentColor) }
        trackAdapter.accentColor = theme.accentColor
        libraryAdapter.accentColor = theme.accentColor
        trackAdapter.notifyDataSetChanged()
        libraryAdapter.notifyDataSetChanged()
        selectLibraryTab(currentLibTab)
    }

    private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return Color.rgb(
            (Color.red(c1)*inv + Color.red(c2)*ratio).toInt(),
            (Color.green(c1)*inv + Color.green(c2)*ratio).toInt(),
            (Color.blue(c1)*inv + Color.blue(c2)*ratio).toInt()
        )
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
                if (isPlaying) { uiHandler.post(uiRunnable); animateAlbumArt(true); startVisualizer() }
                else { uiHandler.removeCallbacks(uiRunnable); animateAlbumArt(false) }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = player?.currentMediaItemIndex ?: return
                if (idx >= 0 && idx < tracks.size) {
                    currentIndex = idx
                    setTrackInfo(tracks[idx])
                    trackAdapter.setNowPlaying(idx)
                    LibraryManager.addToHistory(this@MainActivity, tracks[idx])
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && repeatMode == Player.REPEAT_MODE_ONE) {
                    player?.seekTo(0); player?.play()
                }
            }
        })
    }

    // ─────────────────────────────────────────────
    // VISUALIZER & EQ
    // ─────────────────────────────────────────────

    private fun startVisualizer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            return
        }
        val audioSessionId = PlayerService.audioSessionId
        Log.d("VIBE", "audioSessionId = $audioSessionId")
        if (audioSessionId == 0) { uiHandler.postDelayed({ startVisualizer() }, 500); return }
        try {
            androidVisualizer?.release()
            androidVisualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, w: ByteArray, sr: Int) {}
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, sr: Int) {
                        runOnUiThread { binding.visualizer.updateFft(fft); updateGlow(fft) }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
            initEqualizer(audioSessionId)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVisualizer()
        if (requestCode == 1002 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanLocalTracks()
    }

    private fun stopVisualizer() {
        androidVisualizer?.enabled = false; androidVisualizer?.release(); androidVisualizer = null
        equalizer?.release(); equalizer = null
        binding.visualizer.clear()
    }

    private fun initEqualizer(audioSessionId: Int) {
        try { equalizer?.release(); equalizer = Equalizer(0, audioSessionId).apply { enabled = true } }
        catch (e: Exception) { e.printStackTrace() }
    }

    private fun updateGlow(fft: ByteArray) {
        if (fft.isEmpty()) return
        val energy = fft.take(20).sumOf { (it.toInt() and 0xFF) } / 20f / 255f
        val targetAlpha = (0.3f + energy * 0.7f).coerceIn(0f, 1f)
        binding.artGlow.animate().alpha(targetAlpha).scaleX(1.1f + energy * 0.15f).scaleY(1.1f + energy * 0.15f).setDuration(80).start()
    }

    // ─────────────────────────────────────────────
    // PLAYBACK CONTROLS
    // ─────────────────────────────────────────────

    private fun loadTrack(index: Int) {
        if (index < 0 || index >= tracks.size) return
        currentIndex = index
        val track = tracks[index]
        setTrackInfo(track)
        trackAdapter.setNowPlaying(index)
        currentStreamWebpageUrl = null
        currentStreamTitle = null
        binding.downloadBtn.isVisible = false
        player?.let { p ->
            p.clearMediaItems()
            tracks.forEach { t -> p.addMediaItem(MediaItem.fromUri(t.uri)) }
            p.seekTo(index, 0); p.prepare(); p.play()
        }
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (tracks.isEmpty()) return
        if (p.isPlaying) { p.pause(); stopVisualizer() }
        else { if (currentIndex < 0 && tracks.isNotEmpty()) loadTrack(0) else p.play() }
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
        binding.repeatBtn.setColorFilter(if (repeatMode != Player.REPEAT_MODE_OFF) currentTheme.accentColor else Color.argb(153,255,255,255))
    }

    private fun clearQueue() {
        tracks.clear(); currentIndex = -1
        player?.clearMediaItems(); player?.stop()
        stopVisualizer()
        trackAdapter.updateTracks(emptyList())
        binding.trackTitle.text = "Vibe Check"
        binding.trackArtist.text = "Queue cleared"
        currentStreamWebpageUrl = null
        currentStreamTitle = null
        binding.downloadBtn.isVisible = false
        updatePlayPauseIcon(false); animateAlbumArt(false)
    }

    // ─────────────────────────────────────────────
    // UI UPDATES
    // ─────────────────────────────────────────────

    private fun setTrackInfo(track: Track) {
        binding.trackTitle.text = track.title
        binding.trackArtist.text = track.artist.ifEmpty { "AudioFetch" }
        loadAlbumArt(track.uri)
    }

   private fun loadAlbumArt(uri: Uri) {
    lifecycleScope.launch(Dispatchers.IO) {
        val bitmap = tryLoadAlbumArt(uri)
        withContext(Dispatchers.Main) {
            if (bitmap != null) {
                binding.albumArt.setImageBitmap(bitmap)
                androidx.palette.graphics.Palette.from(bitmap).generate { palette ->
                    val swatch = palette?.dominantSwatch ?: palette?.vibrantSwatch ?: palette?.mutedSwatch
                    if (swatch != null) binding.artGlow.setBackgroundColor(swatch.rgb)
                }
            } else {
                binding.albumArt.setImageResource(0)
                binding.albumArt.background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_album_art_default)
                binding.artGlow.setBackgroundColor(currentTheme.accentColor)
            }
        }
    }
}

private fun tryLoadAlbumArt(uri: Uri): android.graphics.Bitmap? {
    // Step 1: loadThumbnail (API 29+, fast, works for MediaStore-indexed files)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            return contentResolver.loadThumbnail(uri, android.util.Size(512, 512), null)
        } catch (_: Exception) {}
    }

    // Step 2: MediaMetadataRetriever on the URI directly (reads embedded tags)
    try {
        android.media.MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(this, uri)
            val raw = mmr.embeddedPicture
            if (raw != null) return android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
        }
    } catch (_: Exception) {}

    // Step 3: If URI is a file:// URI, try MMR with the file path directly
    // (some content URIs fail MMR but the underlying file path works)
    try {
        val path = uri.path
        if (path != null && path.isNotEmpty()) {
            android.media.MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(path)
                val raw = mmr.embeddedPicture
                if (raw != null) return android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
            }
        }
    } catch (_: Exception) {}

    return null
}

    private fun updatePlayPauseIcon(playing: Boolean) {
        binding.playIcon.visibility = if (playing) View.GONE else View.VISIBLE
        binding.pauseIcon.visibility = if (playing) View.VISIBLE else View.GONE
    }

    private fun updateProgressUI() {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: return
        val pos = p.currentPosition
        binding.seekBar.progress = pos.toFloat() / duration.toFloat()
        binding.currentTime.text = formatTime(pos)
        binding.totalTime.text = formatTime(duration)
    }

    private fun animateAlbumArt(playing: Boolean) {
        binding.albumArt.animate().scaleX(if (playing) 1.04f else 1f).scaleY(if (playing) 1.04f else 1f).setDuration(400).setInterpolator(DecelerateInterpolator()).start()
        if (!playing) binding.artGlow.animate().alpha(0f).setDuration(600).start()
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; val m = s / 60; val sec = s % 60
        return "$m:${sec.toString().padStart(2,'0')}"
    }

    // ─────────────────────────────────────────────
    // SETTINGS PANEL
    // ─────────────────────────────────────────────

    private fun showSettings() {
        binding.scrim.visibility = View.VISIBLE
        binding.settingsPanel.visibility = View.VISIBLE
        binding.scrim.animate().alpha(0.6f).setDuration(300).start()
        binding.settingsPanel.animate().translationX(0f).setDuration(400).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun hideSettings() {
        binding.scrim.animate().alpha(0f).setDuration(200).withEndAction { binding.scrim.visibility = View.GONE }.start()
        binding.settingsPanel.animate().translationX(binding.settingsPanel.width.toFloat()).setDuration(300).withEndAction { binding.settingsPanel.visibility = View.GONE }.start()
    }

    // ─────────────────────────────────────────────
    // SLEEP TIMER
    // ─────────────────────────────────────────────

    private fun setSleepTimer(minutes: Int) {
        sleepTimerRunnable?.let { sleepTimerHandler?.removeCallbacks(it) }
        if (minutes <= 0) return
        sleepTimerHandler = Handler(Looper.getMainLooper())
        sleepTimerRunnable = Runnable { player?.pause(); stopVisualizer() }
        sleepTimerHandler?.postDelayed(sleepTimerRunnable!!, minutes * 60 * 1000L)
        setStatus("Sleep timer: ${minutes}m", StatusType.NEUTRAL)
    }

    // ─────────────────────────────────────────────
    // STREAM — main entry point
    // ─────────────────────────────────────────────

    private fun startStream() {
        val input = binding.urlInput.text?.toString()?.trim() ?: ""
        if (input.isEmpty()) { setStatus("enter a song name or url.", StatusType.ERROR); return }

        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.urlInput.windowToken, 0)

        // Direct URLs bypass the picker and stream immediately
        val isUrl = input.startsWith("http://") || input.startsWith("https://")
        if (isUrl) {
            streamDirectUrl(input)
            return
        }

        // Text query → show search picker
        setStatus("searching…", StatusType.NEUTRAL)
        binding.fetchBtn.isEnabled = false
        binding.progressBar.isVisible = true
        showSearchSheet(searching = true)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Python.getInstance().getModule("main")
                        .callAttr("search_tracks", input, 15).toString()
                } catch (e: Exception) { "ERROR: ${e.message}" }
            }
            binding.fetchBtn.isEnabled = true
            binding.progressBar.isVisible = false
            binding.searchSheetProgress.isVisible = false

            if (result.startsWith("ERROR")) {
                setStatus(result, StatusType.ERROR)
                hideSearchSheet()
                return@launch
            }

            try {
                val arr = JSONArray(result)
                val list = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    SearchResult(
                        videoId         = obj.optString("videoId"),
                        title           = obj.optString("title", "Unknown"),
                        artist          = obj.optString("artist", ""),
                        duration        = obj.optString("duration", ""),
                        durationSeconds = obj.optInt("durationSeconds", 0),
                        thumbnail       = obj.optString("thumbnail", ""),
                        webpageUrl      = obj.optString("webpage_url", ""),
                    )
                }
                binding.searchSheetTitle.text = "Results for \"$input\""
                searchResultsAdapter.update(list)
                binding.urlInput.text?.clear()
                setStatus("${list.size} results", StatusType.NEUTRAL)
            } catch (e: Exception) {
                setStatus("ERROR: ${e.message}", StatusType.ERROR)
                hideSearchSheet()
            }
        }
    }

    // Called when a search result row is tapped
   private fun streamFromSearchResult(result: SearchResult) {
    // Show optimistic UI immediately using metadata we already have
    binding.trackTitle.text  = result.title
    binding.trackArtist.text = result.artist.ifEmpty { "AudioFetch" }

    // Load thumbnail immediately from search result (we already have it)
    if (result.thumbnail.isNotEmpty()) {
        try {
            com.bumptech.glide.Glide.with(this)
                .load(result.thumbnail)
                .placeholder(R.drawable.bg_album_art_default)
                .error(R.drawable.bg_album_art_default)
                .centerCrop()
                .into(binding.albumArt)
        } catch (_: Exception) {
            binding.albumArt.setImageResource(0)
            binding.albumArt.background = ContextCompat.getDrawable(this, R.drawable.bg_album_art_default)
        }
    }

    setStatus("loading…", StatusType.NEUTRAL)
    binding.fetchBtn.isEnabled = false
    binding.progressBar.isVisible = true

    lifecycleScope.launch {
        // Use videoId directly — faster than resolving through webpage_url
        val streamJson = withContext(Dispatchers.IO) {
            try {
                Python.getInstance().getModule("main")
                    .callAttr("get_stream_url_by_id", result.videoId).toString()
            } catch (e: Exception) { "ERROR: ${e.message}" }
        }
        binding.fetchBtn.isEnabled = true
        binding.progressBar.isVisible = false

        if (streamJson.startsWith("ERROR")) {
            setStatus(streamJson, StatusType.ERROR)
            return@launch
        }
        try {
            // Pass the thumbnail we already loaded so playStreamJson doesn't re-fetch
            playStreamJson(JSONObject(streamJson), result.thumbnail)
        } catch (e: Exception) {
            setStatus("ERROR: ${e.message}", StatusType.ERROR)
        }
    }
}
    // Direct URL path — skips the picker
    private fun streamDirectUrl(url: String) {
        setStatus("finding…", StatusType.NEUTRAL)
        binding.fetchBtn.isEnabled = false
        binding.progressBar.isVisible = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Python.getInstance().getModule("main")
                        .callAttr("get_stream_url", url).toString()
                } catch (e: Exception) { "ERROR: ${e.message}" }
            }
            binding.fetchBtn.isEnabled = true
            binding.progressBar.isVisible = false

            if (result.startsWith("ERROR")) {
                setStatus(result, StatusType.ERROR)
                return@launch
            }
            try {
                playStreamJson(JSONObject(result), "")
                binding.urlInput.text?.clear()
            } catch (e: Exception) {
                setStatus("ERROR: ${e.message}", StatusType.ERROR)
            }
        }
    }

    // Shared: takes a get_stream_url JSON object and starts playback
    private fun playStreamJson(json: JSONObject, fallbackThumbnail: String) {
        val streamUrl  = json.getString("url")
        val title      = json.getString("title")
        val artist     = json.optString("artist", "")
        val webpageUrl = json.optString("webpage_url", "").ifEmpty { streamUrl }
        val thumbnail  = json.optString("thumbnail", "").ifEmpty { fallbackThumbnail }

        currentStreamWebpageUrl = webpageUrl
        currentStreamTitle = title

        val streamTrack = Track(uri = Uri.parse(streamUrl), title = title, artist = artist)
        if (tracks.none { it.uri == streamTrack.uri }) {
            tracks.add(streamTrack)
            trackAdapter.updateTracks(tracks)
        }
        currentIndex = tracks.indexOfFirst { it.uri == streamTrack.uri }
        trackAdapter.setNowPlaying(currentIndex)

        binding.trackTitle.text  = title
        binding.trackArtist.text = artist.ifEmpty { "AudioFetch" }

        // Load stream thumbnail via Glide
        if (thumbnail.isNotEmpty()) {
            try {
                com.bumptech.glide.Glide.with(this)
                    .load(thumbnail)
                    .placeholder(R.drawable.bg_album_art_default)
                    .error(R.drawable.bg_album_art_default)
                    .centerCrop()
                    .into(binding.albumArt)
            } catch (_: Exception) {
                binding.albumArt.setImageResource(0)
                binding.albumArt.background = ContextCompat.getDrawable(this, R.drawable.bg_album_art_default)
            }
        } else {
            binding.albumArt.setImageResource(0)
            binding.albumArt.background = ContextCompat.getDrawable(this, R.drawable.bg_album_art_default)
        }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(streamUrl))
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setArtist(artist).build()
            ).build()

        player?.let { p ->
            p.clearMediaItems()
            tracks.forEachIndexed { i, t ->
                if (i == currentIndex) p.addMediaItem(mediaItem)
                else p.addMediaItem(MediaItem.fromUri(t.uri))
            }
            p.seekTo(currentIndex, 0); p.prepare(); p.play()
        }

        binding.downloadBtn.isVisible = true
        setStatus("streaming: $title", StatusType.SUCCESS)
    }

    // ─────────────────────────────────────────────
    // DOWNLOAD CURRENT STREAM
    // ─────────────────────────────────────────────

    private fun downloadCurrentStream() {
        val url = currentStreamWebpageUrl ?: return
        val title = currentStreamTitle ?: "track"

        binding.downloadBtn.isEnabled = false
        setStatus("downloading…", StatusType.NEUTRAL)
        binding.progressBar.isVisible = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Python.getInstance().getModule("main")
                        .callAttr("download_audio", url, cacheDir.absolutePath).toString()
                } catch (e: Exception) { "ERROR: ${e.message}" }
            }
            binding.progressBar.isVisible = false
            binding.downloadBtn.isEnabled = true

            if (result.startsWith("ERROR")) {
                setStatus(result, StatusType.ERROR)
                return@launch
            }

            val uri = saveToDownloads(result)
            if (uri != null) {
                val file = File(result)
                val savedTitle = file.nameWithoutExtension.replace('_',' ').replace('-',' ').trim()
                val newTrack = Track(uri = uri, title = savedTitle)
                LibraryManager.addDownload(this@MainActivity, newTrack)
                binding.downloadBtn.isVisible = false
                currentStreamWebpageUrl = null
                setStatus("saved: $savedTitle ✓", StatusType.SUCCESS)
            } else {
                setStatus("ERROR: failed to save file.", StatusType.ERROR)
            }
        }
    }

    private fun saveToDownloads(srcPath: String): Uri? {
        val src = File(srcPath)
        if (!src.exists()) return null
        val mimeType = when (src.extension.lowercase()) {
            "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "opus" -> "audio/opus"; "webm" -> "audio/webm"; else -> "audio/*"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { src.inputStream().copyTo(it) }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            src.delete(); uri
        } else {
            val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            destDir.mkdirs()
            val dest = File(destDir, src.name)
            src.copyTo(dest, overwrite = true); src.delete()
            Uri.fromFile(dest)
        }
    }

    // ─────────────────────────────────────────────
    // UPDATE CHECK
    // ─────────────────────────────────────────────

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate(BuildConfig.VERSION_CODE) }
            if (update != null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update available — ${update.versionName}")
                    .setMessage(update.changelog)
                    .setPositiveButton("Update Now") { _, _ -> UpdateChecker.downloadAndInstall(this@MainActivity, update) }
                    .setNeutralButton("View on GitHub") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl))) }
                    .setNegativeButton("Later", null).show()
            }
        }
    }

    // ─────────────────────────────────────────────
    // STATUS
    // ─────────────────────────────────────────────

    private enum class StatusType { NEUTRAL, ERROR, SUCCESS }

    private fun setStatus(msg: String, type: StatusType) {
        binding.statusText.text = msg
        binding.statusText.setTextColor(getColor(when (type) {
            StatusType.ERROR -> R.color.status_error
            StatusType.SUCCESS -> R.color.status_success
            StatusType.NEUTRAL -> R.color.status_muted
        }))
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
