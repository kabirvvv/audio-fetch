// ── Add this near the top of MainActivity with the other adapter declarations ──
private lateinit var recentlyPlayedAdapter: HomeCardAdapter

// ── Call this inside setupUI(), after the other adapter initialisations ──
private fun setupHomeAdapters() {
    recentlyPlayedAdapter = HomeCardAdapter(emptyList()) { card -> onHomeCardClick(card) }
    binding.recentlyPlayedRecycler.apply {
        layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
        adapter = recentlyPlayedAdapter
    }
}

// ── Replace loadHomeData() entirely ──
private fun loadHomeData() {
    // Greeting
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    binding.homeGreeting.text = when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else      -> "Good evening"
    }

    // Layer 1: local data — instant, no shimmer needed
    val history = LibraryManager.getHistory().takeLast(10).reversed()
    if (history.isNotEmpty()) {
        val cards = history.map { track ->
            HomeCard(
                videoId   = track.videoId ?: "",
                title     = track.title,
                artist    = track.artist,
                thumbnail = track.thumbnailUrl,
                type      = HomeCardType.TRACK,
            )
        }
        recentlyPlayedAdapter.update(cards)
        binding.recentlyPlayedSection.isVisible = true
    } else {
        binding.recentlyPlayedSection.isVisible = false
    }

    // Layer 2: online shelves — show shimmer, load async
    binding.homeShelvesContainer.removeAllViews()
    binding.homeShimmerContainer.isVisible = true

    lifecycleScope.launch {
        val shelves = withContext(Dispatchers.IO) { fetchOnlineShelves() }
        binding.homeShimmerContainer.isVisible = false
        if (shelves.isNotEmpty()) {
            renderShelves(shelves)
            homeDataCache = shelves
            homeCacheTime = System.currentTimeMillis()
        }
    }
}

// ── Replace renderShelves() entirely ──
private fun renderShelves(shelves: List<HomeShelf>) {
    homeDataCache = shelves
    binding.homeShelvesContainer.removeAllViews()

    shelves.forEach { shelf ->
        if (shelf.items.isEmpty()) return@forEach

        // Section header
        val header = TextView(this).apply {
            text = shelf.title
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            val px = (20 * resources.displayMetrics.density).toInt()
            val pb = (10 * resources.displayMetrics.density).toInt()
            setPadding(px, 0, px, pb)
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
        }

        // Horizontal RecyclerView
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = HomeCardAdapter(shelf.items) { card -> onHomeCardClick(card) }
            overScrollMode = View.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = false
            val ph = (12 * resources.displayMetrics.density).toInt()
            val pb = (20 * resources.displayMetrics.density).toInt()
            setPadding(ph, 0, ph, pb)
            clipToPadding = false
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        binding.homeShelvesContainer.addView(header)
        binding.homeShelvesContainer.addView(rv)
    }
}

// ── Add this — fetches online shelves from Python (stub returns empty for now) ──
private suspend fun fetchOnlineShelves(): List<HomeShelf> {
    return try {
        val result = withContext(Dispatchers.IO) {
            Python.getInstance().getModule("main")
                .callAttr("get_home").toString()
        }
        if (result.startsWith("ERROR")) return emptyList()
        val arr = JSONArray(result)
        (0 until arr.length()).map { i ->
            val obj   = arr.getJSONObject(i)
            val items = obj.getJSONArray("items")
            HomeShelf(
                title = obj.getString("title"),
                items = (0 until items.length()).map { j ->
                    val card = items.getJSONObject(j)
                    HomeCard(
                        videoId    = card.optString("videoId"),
                        playlistId = card.optString("playlistId").ifEmpty { null },
                        title      = card.optString("title", "Unknown"),
                        artist     = card.optString("artist", ""),
                        thumbnail  = card.optString("thumbnail", ""),
                        type       = when (card.optString("type")) {
                            "album"    -> HomeCardType.ALBUM
                            "playlist" -> HomeCardType.PLAYLIST
                            else       -> HomeCardType.TRACK
                        }
                    )
                }
            )
        }
    } catch (_: Exception) { emptyList() }
}

// ── Add this — handles taps on any home card ──
private fun onHomeCardClick(card: HomeCard) {
    when (card.type) {
        HomeCardType.TRACK -> {
            if (card.videoId.isEmpty()) return
            streamFromHomeCard(card)
        }
        HomeCardType.ALBUM, HomeCardType.PLAYLIST -> {
            // stub — future: show track list sheet
            setStatus("Coming soon: ${card.title}", StatusType.NEUTRAL)
        }
        HomeCardType.MOOD -> { /* handled by mood chip adapter */ }
    }
}

// ── Add this — streams a track card from the home screen ──
private fun streamFromHomeCard(card: HomeCard) {
    setStatus("loading…", StatusType.NEUTRAL)
    binding.progressBar.isVisible = true

    lifecycleScope.launch {
        val streamJson = withContext(Dispatchers.IO) {
            try {
                Python.getInstance().getModule("main")
                    .callAttr("get_stream_url_by_id", card.videoId).toString()
            } catch (e: Exception) { "ERROR: ${e.message}" }
        }
        binding.progressBar.isVisible = false

        if (streamJson.startsWith("ERROR")) {
            setStatus(streamJson, StatusType.ERROR)
            return@launch
        }
        try {
            playStreamJson(JSONObject(streamJson), card.thumbnail)
        } catch (e: Exception) {
            setStatus("ERROR: ${e.message}", StatusType.ERROR)
        }
    }
}
