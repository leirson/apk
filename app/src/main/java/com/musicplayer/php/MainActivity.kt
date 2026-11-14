package com.musicplayer.php

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

data class TrackItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val duration: Int,
    val coverUrl: String,
    val albumYear: String,
    val albumType: String,
    val trackNumber: Int,
    val fileName: String
)

data class AlbumGroup(
    val name: String,
    val artist: String,
    val coverUrl: String,
    val year: String,
    val type: String,
    val tracks: List<TrackItem>
)

data class ArtistGroup(
    val name: String,
    val photoUrl: String,
    val albumsCount: Int,
    val tracks: List<TrackItem>
)

data class PlaylistGroup(
    val id: String,
    val name: String,
    val trackIds: List<String>
)

data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String
)

data class PodcastShow(
    val name: String,
    val author: String,
    val coverUrl: String,
    val episodes: List<TrackItem>
)

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val ioExecutor = Executors.newFixedThreadPool(4)
    private val downloadExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isBulkDownloading = AtomicBoolean(false)

    // Memory image cache + disk image cache
    private val bitmapCache = object : LruCache<String, Bitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // API & Auth State
    private var serverBaseUrl: String = ""
    private var currentUsername: String = ""
    private var currentRole: String = "admin"
    private var isServerReachable: Boolean = false
    private var forceOfflineMode: Boolean = false
    private var maxOfflineStorageMB: Long = 2048L // Default 2 GB
    private var downloadedSyncProgressText: String = ""

    // Library Data
    private var allTracks = mutableListOf<TrackItem>()
    private var artistPhotosMap = mutableMapOf<String, String>()
    private var favoriteIds = mutableSetOf<String>()
    private var playlists = mutableListOf<PlaylistGroup>()
    private var radioStations = mutableListOf<RadioStation>()
    private var podcastShows = mutableListOf<PodcastShow>()

    // Navigation & Filtering State
    private var currentTab = "albums" // "albums" | "artists" | "playlists" | "radios" | "podcasts" | "settings"
    private var searchQuery = ""
    private var selectedArtistFilter: String? = null
    private var selectedAlbumFilter: AlbumGroup? = null
    private var selectedPodcastFilter: PodcastShow? = null

    // Audio Player State
    private var mediaPlayer: MediaPlayer? = null
    private var audioEqualizer: Equalizer? = null
    private var playQueue = mutableListOf<TrackItem>()
    private var currentQueueIndex = -1
    private var isPlayingState = false
    private var isShuffleEnabled = false
    private var repeatMode = 0 // 0 = off, 1 = all, 2 = one

    // Views
    private lateinit var rootContainer: LinearLayout
    private lateinit var topStatusPill: TextView
    private lateinit var topSubStatusTxt: TextView
    private lateinit var searchBarContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var contentScroll: ScrollView
    private lateinit var contentContainer: LinearLayout

    // Floating Mini Player Views
    private lateinit var miniPlayerCard: LinearLayout
    private lateinit var miniCoverImg: ImageView
    private lateinit var miniTitleTxt: TextView
    private lateinit var miniArtistTxt: TextView
    private lateinit var miniPlayPauseBtn: TextView
    private lateinit var miniSeekBar: SeekBar
    private lateinit var miniTimeTxt: TextView

    // Bottom Navigation Tab Views
    private val tabButtons = mutableMapOf<String, LinearLayout>()
    private val tabIcons = mutableMapOf<String, TextView>()
    private val tabLabels = mutableMapOf<String, TextView>()

    companion object {
        private const val PREFS_NAME = "PHPlayerNativeApiPrefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_ROLE = "user_role"
        private const val KEY_FORCE_OFFLINE = "force_offline_mode"
        private const val KEY_MAX_OFFLINE_MB = "max_offline_storage_mb"
        private const val DEFAULT_SERVER_URL = "http://leirson.ddns.me"
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            try {
                val mp = mediaPlayer
                if (mp != null && isPlayingState) {
                    val pos = mp.currentPosition / 1000
                    val dur = (mp.duration.takeIf { it > 0 } ?: 0) / 1000
                    miniSeekBar.max = if (dur > 0) dur else 100
                    miniSeekBar.progress = pos
                    miniTimeTxt.text = "${formatSecs(pos)} / ${formatSecs(dur)}"
                }
            } catch (_: Exception) {}
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        serverBaseUrl = normalizeServerUrl(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)
        currentUsername = prefs.getString(KEY_USERNAME, "admin") ?: "admin"
        currentRole = prefs.getString(KEY_ROLE, "admin") ?: "admin"
        forceOfflineMode = prefs.getBoolean(KEY_FORCE_OFFLINE, false)
        maxOfflineStorageMB = prefs.getLong(KEY_MAX_OFFLINE_MB, 2048L)

        // Load locally persisted catalog immediately so offline works on cold boot
        loadOfflineCatalogFromDisk()

        buildModernLayout()
        setContentView(rootContainer)

        mainHandler.post(progressRunnable)
        if (forceOfflineMode || !isNetworkAvailable()) {
            isServerReachable = false
            updateHeaderStatusUI()
            renderCurrentTab()
        } else {
            syncAllFromApi()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(progressRunnable)
        releasePlayer()
    }

    // =========================================================================
    // OFFLINE STORAGE & LOCAL PERSISTENCE ENGINE
    // =========================================================================
    private fun getAudioCacheDir(): File = File(filesDir, "audio_cache").apply { mkdirs() }
    private fun getImageCacheDir(): File = File(filesDir, "image_cache").apply { mkdirs() }
    private fun getMetaCacheDir(): File = File(filesDir, "meta_cache").apply { mkdirs() }

    private fun getTrackOfflineFile(trackId: String): File {
        val safeId = trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(getAudioCacheDir(), "track_$safeId.mp3")
    }

    private fun isTrackStoredOffline(trackId: String): Boolean {
        if (trackId.startsWith("radio-")) return false
        val f = getTrackOfflineFile(trackId)
        return f.exists() && f.length() > 1024L
    }

    private fun isOfflineModeActive(): Boolean {
        return forceOfflineMode || !isServerReachable || !isNetworkAvailable()
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun getUsedOfflineAudioBytes(): Long {
        val files = getAudioCacheDir().listFiles() ?: return 0L
        var total = 0L
        for (f in files) {
            if (f.isFile) total += f.length()
        }
        return total
    }

    private fun getOfflineTracksCount(): Int {
        val files = getAudioCacheDir().listFiles() ?: return 0
        return files.count { it.isFile && it.name.endsWith(".mp3") && it.length() > 1024L }
    }

    private fun getDeviceFreeBytes(): Long {
        return try {
            val stat = StatFs(filesDir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format("%.2f GB", mb / 1024.0)
        } else {
            String.format("%.0f MB", mb)
        }
    }

    private fun saveOfflineCatalogToDisk(tracksRaw: String, photosRaw: String, favRaw: String, plRaw: String, radRaw: String) {
        try {
            val dir = getMetaCacheDir()
            if (tracksRaw.isNotEmpty()) File(dir, "tracks.json").writeText(tracksRaw, Charsets.UTF_8)
            if (photosRaw.isNotEmpty()) File(dir, "photos.json").writeText(photosRaw, Charsets.UTF_8)
            if (favRaw.isNotEmpty()) File(dir, "favorites.json").writeText(favRaw, Charsets.UTF_8)
            if (plRaw.isNotEmpty()) File(dir, "playlists.json").writeText(plRaw, Charsets.UTF_8)
            if (radRaw.isNotEmpty()) File(dir, "radios.json").writeText(radRaw, Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun loadOfflineCatalogFromDisk() {
        try {
            val dir = getMetaCacheDir()
            val tracksFile = File(dir, "tracks.json")
            if (tracksFile.exists()) {
                val parsed = parseTracksJson(tracksFile.readText(Charsets.UTF_8))
                allTracks.clear()
                allTracks.addAll(parsed)
                rebuildPodcastShowsFromTracks()
            }
            val photosFile = File(dir, "photos.json")
            if (photosFile.exists()) {
                val pObj = JSONObject(extractJsonPayload(photosFile.readText(Charsets.UTF_8)))
                val photosNode = pObj.optJSONObject("photos")
                if (photosNode != null) {
                    artistPhotosMap.clear()
                    val keys = photosNode.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = photosNode.optString(k, "")
                        if (v.isNotEmpty()) artistPhotosMap[k] = resolveMediaUrl(v)
                    }
                }
            }
            val favFile = File(dir, "favorites.json")
            if (favFile.exists()) {
                val favArr = JSONArray(extractJsonPayload(favFile.readText(Charsets.UTF_8)))
                favoriteIds.clear()
                for (i in 0 until favArr.length()) {
                    favoriteIds.add(favArr.optString(i))
                }
            }
            val plFile = File(dir, "playlists.json")
            if (plFile.exists()) {
                val plArr = JSONArray(extractJsonPayload(plFile.readText(Charsets.UTF_8)))
                playlists.clear()
                for (i in 0 until plArr.length()) {
                    val o = plArr.optJSONObject(i) ?: continue
                    val tIds = mutableListOf<String>()
                    val tArr = o.optJSONArray("trackIds")
                    if (tArr != null) {
                        for (j in 0 until tArr.length()) tIds.add(tArr.optString(j))
                    }
                    playlists.add(PlaylistGroup(o.optString("id"), o.optString("name", "Playlist"), tIds))
                }
            }
            val radFile = File(dir, "radios.json")
            if (radFile.exists()) {
                val radArr = JSONArray(extractJsonPayload(radFile.readText(Charsets.UTF_8)))
                radioStations.clear()
                for (i in 0 until radArr.length()) {
                    val o = radArr.optJSONObject(i) ?: continue
                    radioStations.add(RadioStation(o.optString("id", "$i"), o.optString("name", "Rádio"), o.optString("url", "")))
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Automatically downloads all library songs to local phone storage in the background
     * up to the user's selected storage limit (maxOfflineStorageMB).
     */
    private fun startAutoOfflineDownloadWorker(showToastOnStart: Boolean = false) {
        if (forceOfflineMode || !isServerReachable) return
        if (isBulkDownloading.getAndSet(true)) {
            if (showToastOnStart) {
                Toast.makeText(this, "Download offline já está em andamento em segundo plano.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (showToastOnStart) {
            Toast.makeText(this, "Sincronizando músicas para armazenamento local offline...", Toast.LENGTH_SHORT).show()
        }

        downloadExecutor.execute {
            try {
                // Prioritize favorite tracks first, then regular music tracks, then podcasts
                val orderedTracks = allTracks.sortedByDescending { favoriteIds.contains(it.id) }
                val limitBytes = if (maxOfflineStorageMB <= 0L) Long.MAX_VALUE else maxOfflineStorageMB * 1024L * 1024L

                var downloadedNow = 0
                val missingTracks = orderedTracks.filter { !isTrackStoredOffline(it.id) && !it.id.startsWith("radio-") }

                for ((idx, track) in missingTracks.withIndex()) {
                    if (forceOfflineMode) break
                    val currentUsed = getUsedOfflineAudioBytes()
                    if (currentUsed >= limitBytes) {
                        mainHandler.post {
                            downloadedSyncProgressText = "Limite de espaço atingido (${formatBytes(currentUsed)} / ${if (maxOfflineStorageMB <= 0L) "Ilimitado" else "${maxOfflineStorageMB} MB"})"
                            if (currentTab == "settings") renderCurrentTab()
                        }
                        break
                    }

                    mainHandler.post {
                        downloadedSyncProgressText = "Salvando offline (${idx + 1}/${missingTracks.size}): ${track.title}"
                        updateHeaderStatusUI()
                        if (currentTab == "settings") renderCurrentTab()
                    }

                    // Also cache cover image on disk
                    if (track.coverUrl.isNotEmpty()) {
                        cacheImageToDiskSync(track.coverUrl)
                    }

                    val targetFile = getTrackOfflineFile(track.id)
                    val streamUrl = getApiEndpoint("stream", "id=" + URLEncoder.encode(track.id, "UTF-8"))
                    val success = downloadFileSync(streamUrl, targetFile, limitBytes - currentUsed)
                    if (success) {
                        downloadedNow++
                    }
                }

                mainHandler.post {
                    val totalOffline = getOfflineTracksCount()
                    downloadedSyncProgressText = if (downloadedNow > 0) {
                        "✓ $totalOffline músicas salvas no celular (${formatBytes(getUsedOfflineAudioBytes())})"
                    } else {
                        "✓ Biblioteca offline atualizada ($totalOffline músicas salvas)"
                    }
                    updateHeaderStatusUI()
                    renderCurrentTab()
                }
            } catch (_: Exception) {
            } finally {
                isBulkDownloading.set(false)
            }
        }
    }

    private fun downloadFileSync(urlStr: String, targetFile: File, maxAllowedBytes: Long): Boolean {
        if (targetFile.exists() && targetFile.length() > 1024L) return true
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 25000
                setRequestProperty("User-Agent", "PHPlayerAndroidAPI/1.4")
            }
            if (conn.responseCode in 200..299) {
                val contentLength = conn.contentLengthLong
                if (contentLength > 0 && contentLength > maxAllowedBytes) {
                    conn.disconnect()
                    return false
                }
                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        var copied = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            copied += read
                            if (copied > maxAllowedBytes) {
                                tempFile.delete()
                                return false
                            }
                            output.write(buf, 0, read)
                        }
                    }
                }
                if (tempFile.length() > 1024L) {
                    tempFile.renameTo(targetFile)
                    true
                } else {
                    tempFile.delete()
                    false
                }
            } else {
                false
            }
        } catch (_: Exception) {
            tempFile.delete()
            false
        }
    }

    private fun enforceStorageQuotaIfNeeded() {
        if (maxOfflineStorageMB <= 0L) return
        val maxBytes = maxOfflineStorageMB * 1024L * 1024L
        val files = getAudioCacheDir().listFiles()?.filter { it.isFile && it.name.endsWith(".mp3") }?.sortedBy { it.lastModified() } ?: return
        var used = files.sumOf { it.length() }
        for (f in files) {
            if (used <= maxBytes) break
            val len = f.length()
            if (f.delete()) {
                used -= len
            }
        }
    }

    // =========================================================================
    // URL & TRACK HELPERS
    // =========================================================================
    private fun normalizeServerUrl(raw: String): String {
        var clean = raw.trim().removeSuffix("/")
        if (clean.isEmpty()) clean = DEFAULT_SERVER_URL
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        for (suffix in listOf("/api.php", "/index.php", "/mobile.php")) {
            if (clean.endsWith(suffix)) clean = clean.removeSuffix(suffix)
        }
        return clean.removeSuffix("/")
    }

    private fun getApiEndpoint(route: String, queryParams: String = ""): String {
        val extra = if (queryParams.isNotEmpty()) "&$queryParams" else ""
        return "$serverBaseUrl/api.php?route=$route$extra"
    }

    private fun resolveMediaUrl(rawUrl: String): String {
        if (rawUrl.isEmpty()) return ""
        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) return rawUrl
        return "$serverBaseUrl/${rawUrl.removePrefix("/")}"
    }

    private fun isPodcastTrack(track: TrackItem): Boolean {
        val g = track.genre.lowercase().trim()
        val f = track.fileName.lowercase().replace("\\", "/")
        val a = track.artist.lowercase().trim()
        return g == "podcast" || g == "podcasts" || f.startsWith("podcast/") || f.contains("/podcast/") || a == "podcast"
    }

    /**
     * Returns visible music tracks. When Offline Mode is active, returns ONLY tracks stored locally on the phone!
     */
    private fun getVisibleMusicTracks(): List<TrackItem> {
        val musicOnly = allTracks.filter { !isPodcastTrack(it) }
        return if (isOfflineModeActive()) {
            musicOnly.filter { isTrackStoredOffline(it.id) }
        } else {
            musicOnly
        }
    }

    private fun getVisiblePodcastTracks(): List<TrackItem> {
        val podOnly = allTracks.filter { isPodcastTrack(it) }
        return if (isOfflineModeActive()) {
            podOnly.filter { isTrackStoredOffline(it.id) }
        } else {
            podOnly
        }
    }

    // =========================================================================
    // MODERN UI CONSTRUCTION (Obsidian + Emerald/Sky Glass Theme)
    // =========================================================================
    private fun buildModernLayout() {
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050811"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Sleek Modern Header (No cluttered buttons — API/Login & Sync live in Settings tab)
        val headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#0f172a"), Color.parseColor("#050811"))
            )
        }

        val brandBadge = TextView(this).apply {
            text = "♫"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#0ea5e9"), Color.parseColor("#6366f1"))
            ).apply { cornerRadius = dp(12).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val appTitle = TextView(this).apply {
            text = "PHPlayer"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }

        topSubStatusTxt = TextView(this).apply {
            text = "Carregando biblioteca..."
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        titleCol.addView(appTitle)
        titleCol.addView(topSubStatusTxt)

        topStatusPill = TextView(this).apply {
            text = "ONLINE"
            setTextColor(Color.parseColor("#10b981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = roundedBox("#052e16", "#10b981", 20)
            setOnClickListener {
                switchTab("settings")
            }
        }

        headerBar.addView(brandBadge)
        headerBar.addView(titleCol)
        headerBar.addView(topStatusPill)
        rootContainer.addView(headerBar)

        // 2. Modern Search Bar + Quick Shuffle Pill
        searchBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(10))
        }

        searchInput = EditText(this).apply {
            hint = "🔍 Pesquisar álbuns, artistas, podcasts..."
            setHintTextColor(Color.parseColor("#64748b"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isSingleLine = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBox("#0d1324", "#1e293b", 14)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    renderCurrentTab()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        val quickShuffleBtn = createModernButton("🔀 Tocar Aleatório", "#4f46e5", "#ffffff", 14) {
            playRandomLibraryQueue()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        }

        searchBarContainer.addView(searchInput)
        searchBarContainer.addView(quickShuffleBtn)
        rootContainer.addView(searchBarContainer)

        // 3. Scrollable Main Content Container
        contentScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        contentScroll.addView(contentContainer)
        rootContainer.addView(contentScroll)

        // 4. Floating Glass Mini-Player Card
        miniPlayerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox("#11192e", "#253252", 18)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), dp(4), dp(12), dp(8))
            }
            setOnClickListener { showExpandedPlayerDialog() }
        }

        val miniTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        miniCoverImg = createRoundedImageView(dp(46), 10).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12)
        }

        val miniInfoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        miniTitleTxt = TextView(this).apply {
            text = "Selecione uma música"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        miniArtistTxt = TextView(this).apply {
            text = "PHPlayer"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        miniInfoCol.addView(miniTitleTxt)
        miniInfoCol.addView(miniArtistTxt)

        val prevBtn = createIconBtn("⏮") { playPrevious() }
        miniPlayPauseBtn = TextView(this).apply {
            text = "▶"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = roundedBox("#0ea5e9", "#38bdf8", 20)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
            setOnClickListener { togglePlayPause() }
        }
        val nextBtn = createIconBtn("⏭") { playNext() }

        miniTopRow.addView(miniCoverImg)
        miniTopRow.addView(miniInfoCol)
        miniTopRow.addView(prevBtn)
        miniTopRow.addView(miniPlayPauseBtn)
        miniTopRow.addView(nextBtn)

        val miniSeekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }

        miniSeekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) mediaPlayer?.seekTo(progress * 1000)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        miniTimeTxt = TextView(this).apply {
            text = "0:00 / 0:00"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }

        miniSeekRow.addView(miniSeekBar)
        miniSeekRow.addView(miniTimeTxt)

        miniPlayerCard.addView(miniTopRow)
        miniPlayerCard.addView(miniSeekRow)
        rootContainer.addView(miniPlayerCard)

        // 5. Modern Bottom Navigation Bar (Removed Músicas & Web, Added Podcasts & Config)
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#090d1a"))
            setPadding(dp(6), dp(8), dp(6), dp(10))
        }

        val tabs = listOf(
            Triple("albums", "💿", "Álbuns"),
            Triple("artists", "🎤", "Artistas"),
            Triple("playlists", "📂", "Playlists"),
            Triple("radios", "📻", "Rádios"),
            Triple("podcasts", "🎙", "Podcast"),
            Triple("settings", "⚙", "Config")
        )

        for ((tabId, iconStr, labelStr) in tabs) {
            val tabBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(6), dp(4), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    selectedArtistFilter = null
                    selectedAlbumFilter = null
                    selectedPodcastFilter = null
                    switchTab(tabId)
                }
            }
            val iconView = TextView(this).apply {
                text = iconStr
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            val lblView = TextView(this).apply {
                text = labelStr
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
            }
            tabBox.addView(iconView)
            tabBox.addView(lblView)
            tabButtons[tabId] = tabBox
            tabIcons[tabId] = iconView
            tabLabels[tabId] = lblView
            bottomNav.addView(tabBox)
        }

        rootContainer.addView(bottomNav)
        updateTabStyles()
        updateHeaderStatusUI()
    }

    private fun switchTab(tabId: String) {
        currentTab = tabId
        searchBarContainer.visibility = if (tabId == "settings") View.GONE else View.VISIBLE
        updateTabStyles()
        renderCurrentTab()
    }

    private fun updateTabStyles() {
        for ((id, box) in tabButtons) {
            val lbl = tabLabels[id]
            if (id == currentTab) {
                box.background = roundedBox("#152238", "#0ea5e9", 12)
                lbl?.setTextColor(Color.parseColor("#38bdf8"))
            } else {
                box.setBackgroundColor(Color.TRANSPARENT)
                lbl?.setTextColor(Color.parseColor("#64748b"))
            }
        }
    }

    private fun updateHeaderStatusUI() {
        val offlineCount = getOfflineTracksCount()
        if (isOfflineModeActive()) {
            topStatusPill.text = "📴 OFFLINE ($offlineCount)"
            topStatusPill.setTextColor(Color.parseColor("#fbbf24"))
            topStatusPill.background = roundedBox("#451a03", "#f59e0b", 20)
            topSubStatusTxt.text = "Modo Offline • Exibindo $offlineCount músicas salvas no celular"
            topSubStatusTxt.setTextColor(Color.parseColor("#fbbf24"))
        } else {
            topStatusPill.text = "● ONLINE"
            topStatusPill.setTextColor(Color.parseColor("#34d399"))
            topStatusPill.background = roundedBox("#052e16", "#10b981", 20)
            val totalMusic = allTracks.count { !isPodcastTrack(it) }
            val extra = if (downloadedSyncProgressText.isNotEmpty()) " • $downloadedSyncProgressText" else " • $offlineCount salvas offline"
            topSubStatusTxt.text = "$currentUsername • $totalMusic músicas$extra"
            topSubStatusTxt.setTextColor(Color.parseColor("#94a3b8"))
        }
    }

    // =========================================================================
    // API SYNC WITH AUTOMATIC OFFLINE CACHING
    // =========================================================================
    private fun syncAllFromApi(triggerToast: Boolean = false) {
        topSubStatusTxt.text = "Sincronizando catálogo com $serverBaseUrl..."
        topSubStatusTxt.setTextColor(Color.parseColor("#38bdf8"))

        ioExecutor.execute {
            try {
                val tracksRaw = httpGet(getApiEndpoint("tracks"))
                val parsedTracks = parseTracksJson(tracksRaw).toMutableList()

                // Also fetch podcasts from ?route=podcasts so episodes from RSS feeds are included
                try {
                    val podRaw = httpGet(getApiEndpoint("podcasts"))
                    val podArr = JSONArray(extractJsonPayload(podRaw))
                    val existingIds = parsedTracks.map { it.id }.toMutableSet()
                    for (i in 0 until podArr.length()) {
                        val podObj = podArr.optJSONObject(i) ?: continue
                        val podName = podObj.optString("name", "Podcast")
                        val podArtist = podObj.optString("artist", podName)
                        val podCover = resolveMediaUrl(podObj.optString("coverUrl", ""))
                        val eps = podObj.optJSONArray("episodes") ?: continue
                        for (j in 0 until eps.length()) {
                            val ep = eps.optJSONObject(j) ?: continue
                            val epId = ep.optString("id", "")
                            if (epId.isNotEmpty() && !existingIds.contains(epId)) {
                                existingIds.add(epId)
                                parsedTracks.add(
                                    TrackItem(
                                        id = epId,
                                        title = ep.optString("title", "Episódio"),
                                        artist = podArtist,
                                        album = podName,
                                        genre = "Podcast",
                                        duration = ep.optInt("duration", 0),
                                        coverUrl = resolveMediaUrl(ep.optString("coverUrl", ep.optString("cover_url", podCover))),
                                        albumYear = "",
                                        albumType = "podcast",
                                        trackNumber = j + 1,
                                        fileName = ep.optString("fileName", ep.optString("file_name", ""))
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}

                var photosRaw = ""
                val photosMap = mutableMapOf<String, String>()
                try {
                    photosRaw = httpGet(getApiEndpoint("artist_photos"))
                    val pObj = JSONObject(extractJsonPayload(photosRaw))
                    val photosNode = pObj.optJSONObject("photos")
                    if (photosNode != null) {
                        val keys = photosNode.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = photosNode.optString(k, "")
                            if (v.isNotEmpty()) photosMap[k] = resolveMediaUrl(v)
                        }
                    }
                } catch (_: Exception) {}

                var favRaw = ""
                val favSet = mutableSetOf<String>()
                try {
                    favRaw = httpGet(getApiEndpoint("favorites", "username=" + URLEncoder.encode(currentUsername, "UTF-8")))
                    val favArr = JSONArray(extractJsonPayload(favRaw))
                    for (i in 0 until favArr.length()) favSet.add(favArr.optString(i))
                } catch (_: Exception) {}

                var plRaw = ""
                val plList = mutableListOf<PlaylistGroup>()
                try {
                    plRaw = httpGet(getApiEndpoint("playlists", "username=" + URLEncoder.encode(currentUsername, "UTF-8")))
                    val plArr = JSONArray(extractJsonPayload(plRaw))
                    for (i in 0 until plArr.length()) {
                        val o = plArr.optJSONObject(i) ?: continue
                        val tIds = mutableListOf<String>()
                        val tArr = o.optJSONArray("trackIds")
                        if (tArr != null) {
                            for (j in 0 until tArr.length()) tIds.add(tArr.optString(j))
                        }
                        plList.add(PlaylistGroup(o.optString("id"), o.optString("name", "Playlist"), tIds))
                    }
                } catch (_: Exception) {}

                var radRaw = ""
                val radList = mutableListOf<RadioStation>()
                try {
                    radRaw = httpGet(getApiEndpoint("radios"))
                    val radArr = JSONArray(extractJsonPayload(radRaw))
                    for (i in 0 until radArr.length()) {
                        val o = radArr.optJSONObject(i) ?: continue
                        radList.add(RadioStation(o.optString("id", "$i"), o.optString("name", "Rádio"), o.optString("url", "")))
                    }
                } catch (_: Exception) {}

                // Save catalog JSONs locally on phone so offline mode works anytime
                saveOfflineCatalogToDisk(tracksRaw, photosRaw, favRaw, plRaw, radRaw)

                mainHandler.post {
                    allTracks.clear()
                    allTracks.addAll(parsedTracks)
                    artistPhotosMap.clear()
                    artistPhotosMap.putAll(photosMap)
                    favoriteIds.clear()
                    favoriteIds.addAll(favSet)
                    playlists.clear()
                    playlists.addAll(plList)
                    radioStations.clear()
                    radioStations.addAll(radList)
                    rebuildPodcastShowsFromTracks()

                    isServerReachable = true
                    updateHeaderStatusUI()
                    renderCurrentTab()

                    if (triggerToast) {
                        Toast.makeText(this, "Sincronização concluída! Salvando músicas no armazenamento local...", Toast.LENGTH_SHORT).show()
                    }
                    // Automatically download all tracks locally within the user's storage limit
                    startAutoOfflineDownloadWorker(false)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    isServerReachable = false
                    loadOfflineCatalogFromDisk()
                    updateHeaderStatusUI()
                    renderCurrentTab()
                    if (triggerToast) {
                        Toast.makeText(this, "Sem conexão com o servidor. Exibindo músicas salvas offline.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun rebuildPodcastShowsFromTracks() {
        val podTracks = allTracks.filter { isPodcastTrack(it) }
        val grouped = podTracks.groupBy { it.album.ifEmpty { "Podcast" } }
        podcastShows.clear()
        for ((showName, eps) in grouped) {
            val first = eps.first()
            val cover = eps.firstOrNull { it.coverUrl.isNotEmpty() }?.coverUrl ?: ""
            podcastShows.add(
                PodcastShow(
                    name = showName,
                    author = first.artist.ifEmpty { showName },
                    coverUrl = cover,
                    episodes = eps
                )
            )
        }
    }

    private fun parseTracksJson(raw: String): List<TrackItem> {
        val clean = extractJsonPayload(raw)
        val arr = JSONArray(clean)
        val list = mutableListOf<TrackItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rawCover = o.optString("coverUrl", o.optString("cover_url", ""))
            list.add(
                TrackItem(
                    id = o.optString("id"),
                    title = o.optString("title", "Sem Título"),
                    artist = o.optString("artist", "Artista Desconhecido").trim().ifEmpty { "Artista Desconhecido" },
                    album = o.optString("album", "Single").trim().ifEmpty { "Single" },
                    genre = o.optString("genre", "Geral"),
                    duration = o.optInt("duration", 0),
                    coverUrl = resolveMediaUrl(rawCover),
                    albumYear = o.optString("album_year", o.optString("year", "")),
                    albumType = o.optString("album_type", "album").lowercase(),
                    trackNumber = o.optInt("track_number", 0),
                    fileName = o.optString("file_name", o.optString("fileName", ""))
                )
            )
        }
        return list
    }

    private fun extractJsonPayload(raw: String): String {
        val trimmed = raw.trim()
        val firstBracket = trimmed.indexOf('[')
        val firstBrace = trimmed.indexOf('{')
        val start = when {
            firstBracket != -1 && (firstBrace == -1 || firstBracket < firstBrace) -> firstBracket
            firstBrace != -1 -> firstBrace
            else -> 0
        }
        return trimmed.substring(start)
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Username", currentUsername)
            setRequestProperty("User-Agent", "PHPlayerAndroidAPI/1.4")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val reader = BufferedReader(InputStreamReader(stream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) sb.append(line)
        reader.close()
        if (code !in 200..299) throw Exception("HTTP $code")
        return sb.toString()
    }

    private fun httpPostJson(urlStr: String, payload: JSONObject): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 10000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Username", currentUsername)
            setRequestProperty("User-Agent", "PHPlayerAndroidAPI/1.4")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
            writer.flush()
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val reader = BufferedReader(InputStreamReader(stream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) sb.append(line)
        reader.close()
        if (code !in 200..299) throw Exception("HTTP $code")
        return sb.toString()
    }

    // =========================================================================
    // TAB RENDERING
    // =========================================================================
    private fun renderCurrentTab() {
        contentContainer.removeAllViews()

        // Show Offline Mode Banner at the top of content tabs when offline
        if (isOfflineModeActive() && currentTab != "settings") {
            contentContainer.addView(buildOfflineBannerCard())
        }

        if (selectedAlbumFilter != null) {
            renderAlbumDetailView(selectedAlbumFilter!!)
            return
        }
        if (selectedArtistFilter != null) {
            renderArtistDetailView(selectedArtistFilter!!)
            return
        }
        if (selectedPodcastFilter != null) {
            renderPodcastDetailView(selectedPodcastFilter!!)
            return
        }

        when (currentTab) {
            "albums" -> renderAlbumsTab()
            "artists" -> renderArtistsTab()
            "playlists" -> renderPlaylistsTab()
            "radios" -> renderRadiosTab()
            "podcasts" -> renderPodcastsTab()
            "settings" -> renderSettingsTab()
        }
    }

    private fun buildOfflineBannerCard(): View {
        val offlineTracks = allTracks.filter { !isPodcastTrack(it) && isTrackStoredOffline(it.id) }
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBox("#1c1917", "#f59e0b", 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = "📴 MODO OFFLINE ATIVO"
            setTextColor(Color.parseColor("#fbbf24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val sub = TextView(this).apply {
            text = "${offlineTracks.size} músicas armazenadas no celular prontas para tocar sem internet."
            setTextColor(Color.parseColor("#e7e5e4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        titleCol.addView(title)
        titleCol.addView(sub)

        val playOfflineBtn = createModernButton("▶ Tocar Offline", "#f59e0b", "#090d16", 12) {
            if (offlineTracks.isNotEmpty()) {
                startPlaybackQueue(offlineTracks.shuffled(), 0)
            } else {
                Toast.makeText(this, "Nenhuma música baixada ainda. Conecte-se e sincronize na aba Config.", Toast.LENGTH_SHORT).show()
            }
        }

        topRow.addView(titleCol)
        topRow.addView(playOfflineBtn)
        banner.addView(topRow)
        return banner
    }

    // -------------------------------------------------------------------------
    // 1. ALBUMS TAB
    // -------------------------------------------------------------------------
    private fun renderAlbumsTab() {
        val musicTracks = getVisibleMusicTracks()
        val filtered = if (searchQuery.isEmpty()) musicTracks else musicTracks.filter {
            it.title.contains(searchQuery, true) ||
                it.artist.contains(searchQuery, true) ||
                it.album.contains(searchQuery, true)
        }

        val albumsMap = linkedMapOf<String, MutableList<TrackItem>>()
        for (t in filtered) {
            val key = "${t.artist}___${t.album}"
            albumsMap.getOrPut(key) { mutableListOf() }.add(t)
        }

        val albums = albumsMap.values.map { list ->
            val first = list.first()
            val cover = list.firstOrNull { it.coverUrl.isNotEmpty() }?.coverUrl ?: ""
            AlbumGroup(
                name = first.album,
                artist = first.artist,
                coverUrl = cover,
                year = first.albumYear,
                type = first.albumType,
                tracks = list.sortedBy { if (it.trackNumber > 0) it.trackNumber else 999 }
            )
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(12))
        }
        val countTxt = TextView(this).apply {
            text = if (isOfflineModeActive()) "ÁLBUNS OFFLINE (${albums.size})" else "ÁLBUNS (${albums.size})"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val randAlbumBtn = createModernButton("🎲 Álbum Aleatório", "#1e293b", "#38bdf8", 12) {
            if (albums.isNotEmpty()) {
                val chosen = albums[Random.nextInt(albums.size)]
                startPlaybackQueue(chosen.tracks, 0)
            }
        }
        headerRow.addView(countTxt)
        headerRow.addView(randAlbumBtn)
        contentContainer.addView(headerRow)

        if (albums.isEmpty()) {
            val msg = if (isOfflineModeActive()) {
                "Nenhuma música offline encontrada no celular.\nVá até a aba ⚙ Config e toque em Sincronizar quando estiver conectado."
            } else {
                "Nenhum álbum encontrado."
            }
            contentContainer.addView(emptyLabel(msg))
            return
        }

        for (alb in albums) {
            contentContainer.addView(createAlbumRowCard(alb))
        }
    }

    private fun createAlbumRowCard(alb: AlbumGroup): View {
        val offlineCount = alb.tracks.count { isTrackStoredOffline(it.id) }
        val allOffline = offlineCount == alb.tracks.size && alb.tracks.isNotEmpty()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBox("#0d1324", "#1e293b", 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setOnClickListener {
                selectedAlbumFilter = alb
                renderCurrentTab()
            }
        }

        val img = createRoundedImageView(dp(60), 12).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
        }
        loadImageAsync(alb.coverUrl, img)

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = alb.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val sub = TextView(this).apply {
            val yr = if (alb.year.isNotEmpty()) " • ${alb.year}" else ""
            val offTag = if (allOffline) " • ✓ Offline" else if (offlineCount > 0) " • $offlineCount/${alb.tracks.size} Offline" else ""
            text = "${alb.artist}$yr • ${alb.tracks.size} faixas$offTag"
            setTextColor(if (allOffline) Color.parseColor("#34d399") else Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        infoCol.addView(title)
        infoCol.addView(sub)

        val playBtn = createModernButton("▶", "#0ea5e9", "#ffffff", 12) {
            startPlaybackQueue(alb.tracks, 0)
        }
        val shuffleBtn = createModernButton("🔀", "#1e293b", "#38bdf8", 12) {
            startPlaybackQueue(alb.tracks.shuffled(), 0)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6)
        }

        card.addView(img)
        card.addView(infoCol)
        card.addView(playBtn)
        card.addView(shuffleBtn)
        return card
    }

    private fun renderAlbumDetailView(alb: AlbumGroup) {
        val backBtn = createModernButton("← Voltar aos Álbuns", "#1e293b", "#38bdf8", 12) {
            selectedAlbumFilter = null
            renderCurrentTab()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)
        }
        contentContainer.addView(backBtn)

        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBox("#0d1324", "#1e293b", 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val cover = createRoundedImageView(dp(86), 14).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(16)
        }
        loadImageAsync(alb.coverUrl, cover)

        val metaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val albTitle = TextView(this).apply {
            text = alb.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val albArtist = TextView(this).apply {
            text = alb.artist
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener {
                selectedAlbumFilter = null
                selectedArtistFilter = alb.artist
                renderCurrentTab()
            }
        }
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val playAll = createModernButton("▶ Reproduzir", "#0ea5e9", "#ffffff", 12) {
            startPlaybackQueue(alb.tracks, 0)
        }
        val shufAll = createModernButton("🔀 Aleatório", "#1e293b", "#38bdf8", 12) {
            startPlaybackQueue(alb.tracks.shuffled(), 0)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        }
        actionsRow.addView(playAll)
        actionsRow.addView(shufAll)

        metaCol.addView(albTitle)
        metaCol.addView(albArtist)
        metaCol.addView(actionsRow)

        headerCard.addView(cover)
        headerCard.addView(metaCol)
        contentContainer.addView(headerCard)

        alb.tracks.forEachIndexed { idx, track ->
            contentContainer.addView(createTrackRow(track, alb.tracks, idx))
        }
    }

    // -------------------------------------------------------------------------
    // 2. ARTISTS TAB
    // -------------------------------------------------------------------------
    private fun renderArtistsTab() {
        val musicTracks = getVisibleMusicTracks()
        val grouped = linkedMapOf<String, MutableList<TrackItem>>()
        for (t in musicTracks) {
            if (searchQuery.isNotEmpty() && !t.artist.contains(searchQuery, true)) continue
            grouped.getOrPut(t.artist) { mutableListOf() }.add(t)
        }

        val artists = grouped.entries.map { (name, list) ->
            val customPhoto = artistPhotosMap[name]
            val fallbackCover = list.firstOrNull { it.coverUrl.isNotEmpty() }?.coverUrl ?: ""
            val albumsSet = list.map { it.album }.toSet()
            ArtistGroup(
                name = name,
                photoUrl = if (!customPhoto.isNullOrEmpty()) customPhoto else fallbackCover,
                albumsCount = albumsSet.size,
                tracks = list
            )
        }.sortedBy { it.name.lowercase() }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(12))
        }
        val titleTxt = TextView(this).apply {
            text = if (isOfflineModeActive()) "ARTISTAS OFFLINE (${artists.size})" else "ARTISTAS (${artists.size})"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val randomArtistBtn = createModernButton("🔀 Artista Aleatório", "#4f46e5", "#ffffff", 12) {
            if (artists.isNotEmpty()) {
                val currentArt = playQueue.getOrNull(currentQueueIndex)?.artist
                val pool = if (artists.size > 1 && currentArt != null) {
                    artists.filter { it.name != currentArt }.ifEmpty { artists }
                } else artists
                val chosen = pool[Random.nextInt(pool.size)]
                startPlaybackQueue(chosen.tracks.shuffled(), 0)
                Toast.makeText(this, "Tocando: ${chosen.name}", Toast.LENGTH_SHORT).show()
            }
        }
        topRow.addView(titleTxt)
        topRow.addView(randomArtistBtn)
        contentContainer.addView(topRow)

        if (artists.isEmpty()) {
            contentContainer.addView(emptyLabel("Nenhum artista encontrado."))
            return
        }

        for (art in artists) {
            val offlineCount = art.tracks.count { isTrackStoredOffline(it.id) }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundedBox("#0d1324", "#1e293b", 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                setOnClickListener {
                    selectedArtistFilter = art.name
                    renderCurrentTab()
                }
            }

            val img = createRoundedImageView(dp(56), 28).apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
            }
            loadImageAsync(art.photoUrl, img)

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameView = TextView(this).apply {
                text = art.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val subView = TextView(this).apply {
                val offInfo = if (offlineCount > 0) " • ✓ $offlineCount offline" else ""
                text = "${art.albumsCount} álbuns • ${art.tracks.size} músicas$offInfo"
                setTextColor(Color.parseColor("#94a3b8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            infoCol.addView(nameView)
            infoCol.addView(subView)

            val playBtn = createModernButton("▶", "#0ea5e9", "#ffffff", 12) {
                startPlaybackQueue(art.tracks, 0)
            }
            val shufBtn = createModernButton("🔀", "#1e293b", "#38bdf8", 12) {
                startPlaybackQueue(art.tracks.shuffled(), 0)
            }.apply {
                (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6)
            }

            card.addView(img)
            card.addView(infoCol)
            card.addView(playBtn)
            card.addView(shufBtn)
            contentContainer.addView(card)
        }
    }

    private fun renderArtistDetailView(artistName: String) {
        val artistTracks = getVisibleMusicTracks().filter { it.artist.equals(artistName, true) }
        val backBtn = createModernButton("← Todos os Artistas", "#1e293b", "#38bdf8", 12) {
            selectedArtistFilter = null
            renderCurrentTab()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)
        }
        contentContainer.addView(backBtn)

        val photoUrl = artistPhotosMap[artistName]
            ?: artistTracks.firstOrNull { it.coverUrl.isNotEmpty() }?.coverUrl
            ?: ""

        val bannerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBox("#0d1324", "#1e293b", 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val avatar = createRoundedImageView(dp(80), 40).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(16)
        }
        loadImageAsync(photoUrl, avatar)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(this).apply {
            text = artistName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val sub = TextView(this).apply {
            val offCount = artistTracks.count { isTrackStoredOffline(it.id) }
            text = "${artistTracks.size} músicas • $offCount salvas offline"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        btnRow.addView(createModernButton("▶ Tocar Tudo", "#0ea5e9", "#ffffff", 12) {
            startPlaybackQueue(artistTracks, 0)
        })
        btnRow.addView(createModernButton("🔀 Shuffle", "#1e293b", "#38bdf8", 12) {
            startPlaybackQueue(artistTracks.shuffled(), 0)
        }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8) })

        col.addView(title)
        col.addView(sub)
        col.addView(btnRow)
        bannerCard.addView(avatar)
        bannerCard.addView(col)
        contentContainer.addView(bannerCard)

        val albumsByName = artistTracks.groupBy { it.album }.map { (albName, list) ->
            val first = list.first()
            AlbumGroup(
                name = albName,
                artist = artistName,
                coverUrl = list.firstOrNull { it.coverUrl.isNotEmpty() }?.coverUrl ?: "",
                year = first.albumYear,
                type = first.albumType,
                tracks = list
            )
        }

        val typeLabels = listOf(
            "album" to "ÁLBUNS DE ESTÚDIO",
            "ep" to "EPs",
            "single" to "SINGLES",
            "live" to "ÁLBUNS AO VIVO",
            "compilation" to "COMPILAÇÕES"
        )

        for ((typeKey, label) in typeLabels) {
            val matching = albumsByName.filter { (it.type.ifEmpty { "album" }) == typeKey }
            if (matching.isNotEmpty()) {
                val secHeader = TextView(this).apply {
                    text = "$label (${matching.size})"
                    setTextColor(Color.parseColor("#38bdf8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(10), 0, dp(6))
                }
                contentContainer.addView(secHeader)
                for (alb in matching) {
                    contentContainer.addView(createAlbumRowCard(alb))
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 3. PLAYLISTS TAB (Includes Automatic Offline & Favorites Playlists)
    // -------------------------------------------------------------------------
    private fun renderPlaylistsTab() {
        val visibleTracks = getVisibleMusicTracks()

        // Built-in Smart Card 1: All Offline Stored Tracks
        val offlineTracks = allTracks.filter { !isPodcastTrack(it) && isTrackStoredOffline(it.id) }
        val offlineSmartCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBox("#062e22", "#10b981", 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val offCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        offCol.addView(TextView(this).apply {
            text = "📥 Músicas Salvas no Celular (Offline)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        })
        offCol.addView(TextView(this).apply {
            text = "${offlineTracks.size} músicas disponíveis sem internet • ${formatBytes(getUsedOfflineAudioBytes())}"
            setTextColor(Color.parseColor("#6ee7b7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        val playOffBtn = createModernButton("▶ Tocar", "#10b981", "#052e16", 12) {
            if (offlineTracks.isNotEmpty()) startPlaybackQueue(offlineTracks, 0)
        }
        val shufOffBtn = createModernButton("🔀", "#064e3b", "#6ee7b7", 12) {
            if (offlineTracks.isNotEmpty()) startPlaybackQueue(offlineTracks.shuffled(), 0)
        }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6) }
        offlineSmartCard.addView(offCol)
        offlineSmartCard.addView(playOffBtn)
        offlineSmartCard.addView(shufOffBtn)
        contentContainer.addView(offlineSmartCard)

        // Built-in Smart Card 2: Favorites
        val favTracks = visibleTracks.filter { favoriteIds.contains(it.id) }
        val favSmartCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBox("#2a0a18", "#f43f5e", 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val favCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        favCol.addView(TextView(this).apply {
            text = "♥ Minhas Favoritas"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        })
        favCol.addView(TextView(this).apply {
            text = "${favTracks.size} músicas favoritas disponíveis"
            setTextColor(Color.parseColor("#fda4af"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        favSmartCard.addView(favCol)
        favSmartCard.addView(createModernButton("▶ Tocar", "#e11d48", "#ffffff", 12) {
            if (favTracks.isNotEmpty()) startPlaybackQueue(favTracks, 0)
        })
        contentContainer.addView(favSmartCard)

        val header = TextView(this).apply {
            text = "PLAYLISTS DO SERVIDOR (${playlists.size})"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }
        contentContainer.addView(header)

        if (playlists.isEmpty()) {
            contentContainer.addView(emptyLabel("Nenhuma playlist personalizada encontrada."))
            return
        }

        for (pl in playlists) {
            val plTracks = visibleTracks.filter { pl.trackIds.contains(it.id) }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = roundedBox("#0d1324", "#1e293b", 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = "📂 ${pl.name}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            })
            col.addView(TextView(this).apply {
                val offCnt = plTracks.count { isTrackStoredOffline(it.id) }
                text = "${plTracks.size} músicas ($offCnt salvas offline)"
                setTextColor(Color.parseColor("#94a3b8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            })

            val playBtn = createModernButton("▶ Tocar", "#0ea5e9", "#ffffff", 12) {
                if (plTracks.isNotEmpty()) startPlaybackQueue(plTracks, 0)
            }
            val shufBtn = createModernButton("🔀", "#1e293b", "#38bdf8", 12) {
                if (plTracks.isNotEmpty()) startPlaybackQueue(plTracks.shuffled(), 0)
            }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6) }

            card.addView(col)
            card.addView(playBtn)
            card.addView(shufBtn)
            contentContainer.addView(card)
        }
    }

    // -------------------------------------------------------------------------
    // 4. RADIOS TAB
    // -------------------------------------------------------------------------
    private fun renderRadiosTab() {
        val radHeader = TextView(this).apply {
            text = "📻 ESTAÇÕES DE RÁDIO ON-LINE (${radioStations.size})"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(10))
        }
        contentContainer.addView(radHeader)

        if (radioStations.isEmpty()) {
            contentContainer.addView(emptyLabel("Nenhuma estação de rádio cadastrada."))
            return
        }

        for (rad in radioStations) {
            if (searchQuery.isNotEmpty() && !rad.name.contains(searchQuery, true)) continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = roundedBox("#0d1324", "#1e293b", 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                setOnClickListener {
                    if (isOfflineModeActive()) {
                        Toast.makeText(this@MainActivity, "Rádios ao vivo exigem conexão com a internet.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val radioTrack = TrackItem(
                        id = "radio-${rad.id}",
                        title = rad.name,
                        artist = "Rádio Ao Vivo",
                        album = "Streaming",
                        genre = "Radio",
                        duration = 0,
                        coverUrl = "",
                        albumYear = "",
                        albumType = "radio",
                        trackNumber = 0,
                        fileName = rad.streamUrl
                    )
                    startPlaybackQueue(listOf(radioTrack), 0)
                }
            }
            val nameTxt = TextView(this).apply {
                text = "📻 ${rad.name}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val playBtn = createModernButton("▶ Sintonizar", "#10b981", "#052e16", 12) {
                card.performClick()
            }
            card.addView(nameTxt)
            card.addView(playBtn)
            contentContainer.addView(card)
        }
    }

    // -------------------------------------------------------------------------
    // 5. DEDICATED PODCASTS TAB
    // -------------------------------------------------------------------------
    private fun renderPodcastsTab() {
        val visiblePodTracks = getVisiblePodcastTracks()
        val showsMap = visiblePodTracks.groupBy { it.album.ifEmpty { "Podcast" } }
        val shows = showsMap.entries.map { (name, eps) ->
            val first = eps.first()
            PodcastShow(
                name = name,
                author = first.artist.ifEmpty { name },
                coverUrl = eps.firstOrNull { it.coverUrl.isNotEmpty() }?.coverUrl ?: "",
                episodes = eps
            )
        }.filter {
            searchQuery.isEmpty() || it.name.contains(searchQuery, true) || it.author.contains(searchQuery, true)
        }

        val header = TextView(this).apply {
            text = if (isOfflineModeActive()) "🎙 PODCASTS OFFLINE (${shows.size} programas)" else "🎙 PROGRAMAS DE PODCAST (${shows.size})"
            setTextColor(Color.parseColor("#c084fc"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(12))
        }
        contentContainer.addView(header)

        if (shows.isEmpty()) {
            contentContainer.addView(emptyLabel("Nenhum episódio de podcast disponível."))
            return
        }

        for (show in shows) {
            val offEps = show.episodes.count { isTrackStoredOffline(it.id) }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundedBox("#0d1324", "#2e1065", 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                setOnClickListener {
                    selectedPodcastFilter = show
                    renderCurrentTab()
                }
            }

            val cover = createRoundedImageView(dp(60), 12).apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
            }
            loadImageAsync(show.coverUrl, cover)

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(this).apply {
                text = show.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            infoCol.addView(TextView(this).apply {
                text = "${show.episodes.size} episódios • $offEps offline"
                setTextColor(Color.parseColor("#c084fc"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            })

            val playBtn = createModernButton("▶ Ouvir", "#9333ea", "#ffffff", 12) {
                startPlaybackQueue(show.episodes, 0)
            }

            card.addView(cover)
            card.addView(infoCol)
            card.addView(playBtn)
            contentContainer.addView(card)
        }
    }

    private fun renderPodcastDetailView(show: PodcastShow) {
        val backBtn = createModernButton("← Voltar aos Podcasts", "#1e293b", "#c084fc", 12) {
            selectedPodcastFilter = null
            renderCurrentTab()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)
        }
        contentContainer.addView(backBtn)

        val title = TextView(this).apply {
            text = "🎙 ${show.name} (${show.episodes.size} episódios)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }
        contentContainer.addView(title)

        show.episodes.forEachIndexed { idx, ep ->
            contentContainer.addView(createTrackRow(ep, show.episodes, idx))
        }
    }

    // -------------------------------------------------------------------------
    // 6. DEDICATED SETTINGS TAB (API/Login, Sync, Offline Storage Quota)
    // -------------------------------------------------------------------------
    private fun renderSettingsTab() {
        val pageTitle = TextView(this).apply {
            text = "⚙ CONFIGURAÇÕES & ARMAZENAMENTO OFFLINE"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(14))
        }
        contentContainer.addView(pageTitle)

        // CARD 1: API / LOGIN & SYNC
        val apiCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBox("#0d1324", "#1e293b", 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        apiCard.addView(TextView(this).apply {
            text = "🔗 Conexão API & Sincronização"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        })

        apiCard.addView(TextView(this).apply {
            text = "Servidor: $serverBaseUrl\nUsuário autenticado: $currentUsername ($currentRole)"
            setTextColor(Color.parseColor("#cbd5e1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(12))
        })

        val apiBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnApiLogin = createModernButton("🔑 API / Login", "#0284c7", "#ffffff", 12) {
            showApiConfigDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
            gravity = Gravity.CENTER
        }

        val btnSyncNow = createModernButton("🔄 Sync Biblioteca", "#10b981", "#052e16", 12) {
            forceOfflineMode = false
            prefs.edit().putBoolean(KEY_FORCE_OFFLINE, false).apply()
            syncAllFromApi(true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }

        apiBtnRow.addView(btnApiLogin)
        apiBtnRow.addView(btnSyncNow)
        apiCard.addView(apiBtnRow)
        contentContainer.addView(apiCard)

        // CARD 2: OFFLINE STORAGE QUOTA & LOCAL DOWNLOADS
        val storageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBox("#0d1324", "#10b981", 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        storageCard.addView(TextView(this).apply {
            text = "💾 Espaço para Músicas Offline no Celular"
            setTextColor(Color.parseColor("#34d399"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        })

        val usedBytes = getUsedOfflineAudioBytes()
        val freePhoneBytes = getDeviceFreeBytes()
        val offlineCount = getOfflineTracksCount()
        val totalMusicCount = allTracks.count { !isPodcastTrack(it) }
        val limitText = if (maxOfflineStorageMB <= 0L) "Ilimitado" else formatBytes(maxOfflineStorageMB * 1024L * 1024L)

        storageCard.addView(TextView(this).apply {
            text = "Usado pelo App: ${formatBytes(usedBytes)} de $limitText\n" +
                "Músicas salvas localmente: $offlineCount de $totalMusicCount faixas\n" +
                "Espaço livre disponível no celular: ${formatBytes(freePhoneBytes)}"
            setTextColor(Color.parseColor("#e2e8f0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, dp(10))
        })

        if (downloadedSyncProgressText.isNotEmpty()) {
            storageCard.addView(TextView(this).apply {
                text = downloadedSyncProgressText
                setTextColor(Color.parseColor("#38bdf8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 0, 0, dp(10))
            })
        }

        // Visual progress bar of storage used
        val progressPercent = if (maxOfflineStorageMB <= 0L) {
            if (totalMusicCount > 0) ((offlineCount * 100) / totalMusicCount).coerceIn(0, 100) else 0
        } else {
            val maxB = maxOfflineStorageMB * 1024L * 1024L
            ((usedBytes * 100L) / maxB).toInt().coerceIn(0, 100)
        }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = progressPercent
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply {
                bottomMargin = dp(14)
            }
        }
        storageCard.addView(progressBar)

        storageCard.addView(TextView(this).apply {
            text = "Escolha quanto espaço do celular usar para armazenar as músicas offline:"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, dp(8))
        })

        val quotaOptions = listOf(
            512L to "512 MB",
            1024L to "1 GB",
            2048L to "2 GB",
            5120L to "5 GB",
            10240L to "10 GB",
            0L to "Ilimitado"
        )

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }

        quotaOptions.forEachIndexed { index, (mbVal, label) ->
            val isSelected = maxOfflineStorageMB == mbVal
            val btn = createModernButton(
                if (isSelected) "✓ $label" else label,
                if (isSelected) "#10b981" else "#162038",
                if (isSelected) "#052e16" else "#cbd5e1",
                10
            ) {
                maxOfflineStorageMB = mbVal
                prefs.edit().putLong(KEY_MAX_OFFLINE_MB, mbVal).apply()
                enforceStorageQuotaIfNeeded()
                renderCurrentTab()
                startAutoOfflineDownloadWorker(true)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index % 3 != 2) rightMargin = dp(6)
                }
                gravity = Gravity.CENTER
            }
            if (index < 3) row1.addView(btn) else row2.addView(btn)
        }

        storageCard.addView(row1)
        storageCard.addView(row2)

        val storageActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val downloadAllNowBtn = createModernButton("⬇ Baixar Todas as Músicas", "#0ea5e9", "#ffffff", 12) {
            startAutoOfflineDownloadWorker(true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
            gravity = Gravity.CENTER
        }

        val clearCacheBtn = createModernButton("🗑 Limpar Offline", "#7f1d1d", "#fecaca", 12) {
            AlertDialog.Builder(this)
                .setTitle("Limpar músicas offline?")
                .setMessage("Isso removerá os arquivos MP3 salvos no celular. Você poderá baixá-los novamente ao sincronizar.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpar") { _, _ ->
                    getAudioCacheDir().listFiles()?.forEach { it.delete() }
                    downloadedSyncProgressText = ""
                    updateHeaderStatusUI()
                    renderCurrentTab()
                }
                .show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        storageActionsRow.addView(downloadAllNowBtn)
        storageActionsRow.addView(clearCacheBtn)
        storageCard.addView(storageActionsRow)
        contentContainer.addView(storageCard)

        // CARD 3: OFFLINE MODE SWITCH & EQUALIZER
        val extraCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBox("#0d1324", "#1e293b", 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val offlineSwitch = Switch(this).apply {
            text = "Modo Somente Offline (Exibir e tocar apenas músicas salvas no celular)"
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = forceOfflineMode
            setPadding(0, 0, 0, dp(12))
            setOnCheckedChangeListener { _, isChecked ->
                forceOfflineMode = isChecked
                prefs.edit().putBoolean(KEY_FORCE_OFFLINE, isChecked).apply()
                updateHeaderStatusUI()
                renderCurrentTab()
            }
        }
        extraCard.addView(offlineSwitch)

        val eqBtn = createModernButton("🎚 Abrir Equalizador de Áudio Nativo", "#1e293b", "#38bdf8", 12) {
            showEqualizerDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        }
        extraCard.addView(eqBtn)
        contentContainer.addView(extraCard)
    }

    private fun createTrackRow(track: TrackItem, queueContext: List<TrackItem>, indexInQueue: Int): View {
        val isCurrent = playQueue.getOrNull(currentQueueIndex)?.id == track.id
        val isFav = favoriteIds.contains(track.id)
        val isOffline = isTrackStoredOffline(track.id)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = roundedBox(
                if (isCurrent) "#132347" else "#0d1324",
                if (isCurrent) "#38bdf8" else "#1e293b",
                14
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener {
                startPlaybackQueue(queueContext, indexInQueue)
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = track.title
            setTextColor(if (isCurrent) Color.parseColor("#38bdf8") else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(this).apply {
            val badge = if (isOffline) " • ✓ Offline" else ""
            text = "${track.artist} • ${track.album}$badge"
            setTextColor(if (isOffline) Color.parseColor("#34d399") else Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        col.addView(title)
        col.addView(sub)

        val favBtn = TextView(this).apply {
            text = if (isFav) "♥" else "♡"
            setTextColor(if (isFav) Color.parseColor("#fb7185") else Color.parseColor("#64748b"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                toggleFavoriteOnApi(track.id)
            }
        }

        row.addView(col)
        row.addView(favBtn)
        return row
    }

    // =========================================================================
    // AUDIO PLAYBACK ENGINE (Plays from local phone storage first, streams & saves if not cached)
    // =========================================================================
    private fun playRandomLibraryQueue() {
        val pool = getVisibleMusicTracks()
        if (pool.isEmpty()) {
            Toast.makeText(this, "Nenhuma música disponível no momento.", Toast.LENGTH_SHORT).show()
            return
        }
        isShuffleEnabled = true
        startPlaybackQueue(pool.shuffled().take(80), 0)
    }

    private fun startPlaybackQueue(tracks: List<TrackItem>, startIndex: Int) {
        if (tracks.isEmpty()) return
        playQueue.clear()
        playQueue.addAll(tracks)
        currentQueueIndex = startIndex.coerceIn(0, playQueue.size - 1)
        playCurrentTrack()
    }

    private fun playCurrentTrack() {
        val track = playQueue.getOrNull(currentQueueIndex) ?: return
        miniPlayerCard.visibility = View.VISIBLE
        miniTitleTxt.text = track.title
        val offBadge = if (isTrackStoredOffline(track.id)) " • ✓ Offline" else ""
        miniArtistTxt.text = "${track.artist} • ${track.album}$offBadge"
        miniPlayPauseBtn.text = "⏳"
        loadImageAsync(track.coverUrl, miniCoverImg)
        renderCurrentTab()

        val streamUrl = if (track.id.startsWith("radio-")) {
            getApiEndpoint("proxy_radio", "url=" + URLEncoder.encode(track.fileName, "UTF-8"))
        } else {
            getApiEndpoint("stream", "id=" + URLEncoder.encode(track.id, "UTF-8"))
        }

        ioExecutor.execute {
            try {
                val cachedFile = getTrackOfflineFile(track.id)
                val playSource = if (!track.id.startsWith("radio-") && cachedFile.exists() && cachedFile.length() > 1024L) {
                    cachedFile.absolutePath
                } else {
                    if (isOfflineModeActive() && !track.id.startsWith("radio-")) {
                        mainHandler.post {
                            Toast.makeText(this, "Esta faixa ainda não foi salva offline.", Toast.LENGTH_SHORT).show()
                        }
                        return@execute
                    }
                    if (!track.id.startsWith("radio-")) {
                        cacheSingleTrackAsync(streamUrl, cachedFile)
                    }
                    streamUrl
                }

                mainHandler.post {
                    try {
                        releasePlayer()
                        mediaPlayer = MediaPlayer().apply {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .build()
                            )
                            setDataSource(playSource)
                            setOnPreparedListener { mp ->
                                mp.start()
                                isPlayingState = true
                                miniPlayPauseBtn.text = "⏸"
                                setupEqualizerForSession(mp.audioSessionId)
                                registerPlayOnServer(track.id)
                            }
                            setOnCompletionListener {
                                if (repeatMode == 2) playCurrentTrack() else playNext()
                            }
                            setOnErrorListener { _, _, _ ->
                                isPlayingState = false
                                miniPlayPauseBtn.text = "▶"
                                true
                            }
                            prepareAsync()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "Erro ao reproduzir: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun cacheSingleTrackAsync(streamUrl: String, targetFile: File) {
        ioExecutor.execute {
            val limitBytes = if (maxOfflineStorageMB <= 0L) Long.MAX_VALUE else maxOfflineStorageMB * 1024L * 1024L
            val remaining = (limitBytes - getUsedOfflineAudioBytes()).coerceAtLeast(15L * 1024L * 1024L)
            if (downloadFileSync(streamUrl, targetFile, remaining)) {
                mainHandler.post {
                    updateHeaderStatusUI()
                }
            }
        }
    }

    private fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            isPlayingState = false
            miniPlayPauseBtn.text = "▶"
        } else {
            mp.start()
            isPlayingState = true
            miniPlayPauseBtn.text = "⏸"
        }
    }

    private fun playNext() {
        if (playQueue.isEmpty()) return
        if (currentQueueIndex + 1 < playQueue.size) {
            currentQueueIndex++
            playCurrentTrack()
        } else if (repeatMode == 1) {
            currentQueueIndex = 0
            playCurrentTrack()
        }
    }

    private fun playPrevious() {
        if (playQueue.isEmpty()) return
        if (currentQueueIndex - 1 >= 0) {
            currentQueueIndex--
            playCurrentTrack()
        } else {
            mediaPlayer?.seekTo(0)
        }
    }

    private fun releasePlayer() {
        try {
            audioEqualizer?.release()
            audioEqualizer = null
        } catch (_: Exception) {}
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        isPlayingState = false
    }

    private fun setupEqualizerForSession(audioSessionId: Int) {
        try {
            audioEqualizer?.release()
            audioEqualizer = Equalizer(0, audioSessionId).apply { enabled = true }
        } catch (_: Exception) {}
    }

    private fun registerPlayOnServer(trackId: String) {
        if (trackId.startsWith("radio-") || isOfflineModeActive()) return
        ioExecutor.execute {
            try {
                httpPostJson(
                    getApiEndpoint("register_play"),
                    JSONObject().put("id", trackId).put("username", currentUsername)
                )
            } catch (_: Exception) {}
        }
    }

    private fun toggleFavoriteOnApi(trackId: String) {
        if (favoriteIds.contains(trackId)) favoriteIds.remove(trackId) else favoriteIds.add(trackId)
        renderCurrentTab()
        if (isOfflineModeActive()) return

        ioExecutor.execute {
            try {
                val res = httpPostJson(
                    getApiEndpoint("favorites_toggle"),
                    JSONObject().put("username", currentUsername).put("trackId", trackId)
                )
                val arr = JSONArray(extractJsonPayload(res))
                val updated = mutableSetOf<String>()
                for (i in 0 until arr.length()) updated.add(arr.optString(i))
                mainHandler.post {
                    favoriteIds.clear()
                    favoriteIds.addAll(updated)
                    renderCurrentTab()
                }
            } catch (_: Exception) {}
        }
    }

    // =========================================================================
    // DIALOGS: EXPANDED PLAYER, LYRICS, EQUALIZER & API/LOGIN CONFIG
    // =========================================================================
    private fun showExpandedPlayerDialog() {
        val track = playQueue.getOrNull(currentQueueIndex) ?: return
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#080d1a"))
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }

        val cover = createRoundedImageView(dp(220), 20).apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(18)
        }
        loadImageAsync(track.coverUrl, cover)

        val title = TextView(this).apply {
            text = track.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val artist = TextView(this).apply {
            val offTag = if (isTrackStoredOffline(track.id)) " • ✓ Salva Offline" else ""
            text = "${track.artist} — ${track.album}$offTag"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(18))
        }

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        }

        val prev = createModernButton("⏮ Anterior", "#1e293b", "#ffffff", 14) { playPrevious() }
        val playPause = createModernButton(if (isPlayingState) "⏸ Pausar" else "▶ Tocar", "#0ea5e9", "#ffffff", 14) {
            togglePlayPause()
        }.apply { (layoutParams as LinearLayout.LayoutParams).setMargins(dp(10), 0, dp(10), 0) }
        val next = createModernButton("⏭ Próxima", "#1e293b", "#ffffff", 14) { playNext() }

        controlsRow.addView(prev)
        controlsRow.addView(playPause)
        controlsRow.addView(next)

        val extrasRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val lyricsBtn = createModernButton("📄 Letra", "#1e293b", "#38bdf8", 12) {
            showLyricsDialog(track)
        }
        val eqBtn = createModernButton("🎚 Equalizador", "#1e293b", "#38bdf8", 12) {
            showEqualizerDialog()
        }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8) }

        extrasRow.addView(lyricsBtn)
        extrasRow.addView(eqBtn)

        dialogLayout.addView(cover)
        dialogLayout.addView(title)
        dialogLayout.addView(artist)
        dialogLayout.addView(controlsRow)
        dialogLayout.addView(extrasRow)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(dialogLayout)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun showLyricsDialog(track: TrackItem) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080d1a"))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val header = TextView(this).apply {
            text = "${track.title} • ${track.artist}"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }
        val bodyTxt = TextView(this).apply {
            text = "Buscando letra na API..."
            setTextColor(Color.parseColor("#e2e8f0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val scroll = ScrollView(this).apply {
            addView(bodyTxt)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360))
        }
        box.addView(header)
        box.addView(scroll)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(box)
            .setPositiveButton("Fechar", null)
            .show()

        ioExecutor.execute {
            try {
                val url = getApiEndpoint(
                    "lyrics",
                    "artist=" + URLEncoder.encode(track.artist, "UTF-8") +
                        "&title=" + URLEncoder.encode(track.title, "UTF-8")
                )
                val res = JSONObject(extractJsonPayload(httpGet(url)))
                val lyrics = res.optString("lyrics", "").replace(Regex("\\[\\d{2}:\\d{2}(?:\\.\\d{2,3})?\\]"), "").trim()
                mainHandler.post {
                    bodyTxt.text = if (lyrics.isNotEmpty()) lyrics else "Letra não encontrada para esta faixa."
                }
            } catch (_: Exception) {
                mainHandler.post {
                    bodyTxt.text = "Não foi possível carregar a letra no momento."
                }
            }
        }
    }

    private fun showEqualizerDialog() {
        val eq = audioEqualizer
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080d1a"))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val title = TextView(this).apply {
            text = "🎚 EQUALIZADOR DE ÁUDIO"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }
        container.addView(title)

        if (eq == null) {
            container.addView(TextView(this).apply {
                text = "Inicie a reprodução de uma música para ativar o Equalizador de hardware do Android."
                setTextColor(Color.parseColor("#94a3b8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        } else {
            val bands = eq.numberOfBands.toInt()
            val minLevel = eq.bandLevelRange[0].toInt()
            val maxLevel = eq.bandLevelRange[1].toInt()

            for (b in 0 until bands) {
                val bandIdx = b.toShort()
                val freqHz = eq.getCenterFreq(bandIdx) / 1000
                val label = TextView(this).apply {
                    text = if (freqHz >= 1000) "${freqHz / 1000} kHz" else "$freqHz Hz"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                }
                val seek = SeekBar(this).apply {
                    max = maxLevel - minLevel
                    progress = eq.getBandLevel(bandIdx).toInt() - minLevel
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                            if (fromUser) eq.setBandLevel(bandIdx, (minLevel + p).toShort())
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                }
                container.addView(label)
                container.addView(seek)
            }
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(container)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun showApiConfigDialog() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#080d1a"))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        box.addView(TextView(this).apply {
            text = "🔑 CONFIGURAR SERVIDOR API & LOGIN"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        })

        box.addView(TextView(this).apply {
            text = "URL do Servidor PHP (ex: http://leirson.ddns.me/music):"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        val urlInput = EditText(this).apply {
            setText(serverBaseUrl)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox("#111827", "#334155", 10)
            isSingleLine = true
        }
        box.addView(urlInput)

        box.addView(TextView(this).apply {
            text = "Usuário:"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, 0)
        })
        val userInput = EditText(this).apply {
            setText(currentUsername)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox("#111827", "#334155", 10)
            isSingleLine = true
        }
        box.addView(userInput)

        box.addView(TextView(this).apply {
            text = "Senha:"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, 0)
        })
        val passInput = EditText(this).apply {
            hint = "Digite a senha para autenticar..."
            setHintTextColor(Color.parseColor("#64748b"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox("#111827", "#334155", 10)
            isSingleLine = true
        }
        box.addView(passInput)

        scroll.addView(box)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar e Sincronizar") { _, _ ->
                val newUrl = normalizeServerUrl(urlInput.text.toString())
                val newUser = userInput.text.toString().trim().ifEmpty { "admin" }
                val rawPass = passInput.text.toString().trim()

                serverBaseUrl = newUrl
                currentUsername = newUser
                forceOfflineMode = false
                prefs.edit()
                    .putString(KEY_SERVER_URL, serverBaseUrl)
                    .putString(KEY_USERNAME, currentUsername)
                    .putBoolean(KEY_FORCE_OFFLINE, false)
                    .apply()

                if (rawPass.isNotEmpty()) {
                    ioExecutor.execute {
                        try {
                            val res = httpPostJson(
                                getApiEndpoint("login"),
                                JSONObject().put("username", newUser).put("password", rawPass)
                            )
                            val obj = JSONObject(extractJsonPayload(res))
                            if (obj.optBoolean("success", false)) {
                                currentRole = obj.optString("role", "ouvinte")
                                prefs.edit().putString(KEY_ROLE, currentRole).apply()
                                mainHandler.post {
                                    Toast.makeText(this, "Autenticado como $newUser!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            mainHandler.post { syncAllFromApi(true) }
                        }
                    }
                } else {
                    syncAllFromApi(true)
                }
            }
            .show()
    }

    // =========================================================================
    // IMAGE LOADING (MEMORY + LOCAL DISK CACHE FOR OFFLINE COVERS)
    // =========================================================================
    private fun hashKeyForDisk(url: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            url.hashCode().toString()
        }
    }

    private fun cacheImageToDiskSync(url: String) {
        if (url.isEmpty()) return
        val diskFile = File(getImageCacheDir(), "img_${hashKeyForDisk(url)}.bin")
        if (diskFile.exists() && diskFile.length() > 128L) return
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 7000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { input ->
                    FileOutputStream(diskFile).use { output -> input.copyTo(output) }
                }
            }
        } catch (_: Exception) {}
    }

    private fun loadImageAsync(url: String, target: ImageView) {
        target.setImageBitmap(null)
        if (url.isEmpty()) return
        val memBmp = bitmapCache.get(url)
        if (memBmp != null) {
            target.setImageBitmap(memBmp)
            return
        }
        target.tag = url
        ioExecutor.execute {
            try {
                val diskFile = File(getImageCacheDir(), "img_${hashKeyForDisk(url)}.bin")
                var bmp: Bitmap? = null
                if (diskFile.exists() && diskFile.length() > 128L) {
                    bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                }
                if (bmp == null && !isOfflineModeActive()) {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 7000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    if (conn.responseCode in 200..299) {
                        val bytes = conn.inputStream.readBytes()
                        if (bytes.isNotEmpty()) {
                            FileOutputStream(diskFile).use { it.write(bytes) }
                            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    }
                }
                if (bmp != null) {
                    bitmapCache.put(url, bmp)
                    mainHandler.post {
                        if (target.tag == url) target.setImageBitmap(bmp)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // =========================================================================
    // UI WIDGET HELPERS
    // =========================================================================
    private fun createRoundedImageView(sizePx: Int, radiusDp: Int): ImageView {
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBox("#162038", "#253252", radiusDp)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }

    private fun createModernButton(label: String, bgHex: String, fgHex: String, radiusDp: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(fgHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBox(bgHex, bgHex, radiusDp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun createIconBtn(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun emptyLabel(msg: String): TextView {
        return TextView(this).apply {
            text = msg
            setTextColor(Color.parseColor("#64748b"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(40), dp(20), dp(40))
        }
    }

    private fun roundedBox(bgHex: String, borderHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.parseColor(borderHex))
        }
    }

    private fun formatSecs(secs: Int): String {
        val m = secs / 60
        val s = secs % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onBackPressed() {
        when {
            selectedAlbumFilter != null -> {
                selectedAlbumFilter = null
                renderCurrentTab()
            }
            selectedArtistFilter != null -> {
                selectedArtistFilter = null
                renderCurrentTab()
            }
            selectedPodcastFilter != null -> {
                selectedPodcastFilter = null
                renderCurrentTab()
            }
            else -> super.onBackPressed()
        }
    }
}
