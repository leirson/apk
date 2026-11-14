package com.musicplayer.php

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
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
import android.view.animation.LinearInterpolator
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

data class MobileThemePalette(
    val id: String,
    val label: String,
    val bgHex: String,
    val surfaceHex: String,
    val cardHex: String,
    val miniPlayerHex: String,
    val borderHex: String,
    val primaryHex: String,
    val accentHex: String,
    val darkTintHex: String
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

    // Themes matching mobile.php (#theme-modal)
    private val themes = listOf(
        MobileThemePalette("default", "Celeste", "#070b13", "#0b1322", "#0a111e", "#0e1726", "#1e293b", "#0ea5e9", "#38bdf8", "#082f49"),
        MobileThemePalette("emerald", "Esmeralda", "#040d0a", "#061812", "#071c15", "#09221a", "#064e3b", "#10b981", "#34d399", "#052e16"),
        MobileThemePalette("rose", "Rosa", "#0f0508", "#19080e", "#1f0a12", "#260c16", "#881337", "#f43f5e", "#fb7185", "#4c0519"),
        MobileThemePalette("amber", "Âmbar", "#0f0a04", "#191106", "#1f1508", "#271a0a", "#78350f", "#f59e0b", "#fbbf24", "#451a03"),
        MobileThemePalette("violet", "Violeta", "#090514", "#110924", "#150b2e", "#1b0e3a", "#4c1d95", "#8b5cf6", "#a78bfa", "#2e1065"),
        MobileThemePalette("crimson", "Rubi", "#0f0505", "#1a0808", "#220a0a", "#2a0d0d", "#7f1d1d", "#ef4444", "#f87171", "#450a0a")
    )
    private var activeTheme: MobileThemePalette = themes[0]

    // API, Sync & Auth State
    private var serverBaseUrl: String = ""
    private var currentUsername: String = ""
    private var currentRole: String = "admin"
    private var isServerReachable: Boolean = false
    private var isSyncEnabled: Boolean = true
    private var syncOnlyPlayed: Boolean = true
    private var isAutoOfflineDownloadEnabled: Boolean = false
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

    // Navigation & Filtering State (Matches mobile.php)
    private var currentTab = "albums" // "albums" | "artists" | "playlists" | "radios" | "podcasts" | "settings"
    private var searchQuery = ""
    private var isSearchExpanded = false
    private var selectedAlbumLetter = "ALL"
    private var selectedArtistLetter = "ALL"
    private var randomTenAlbumsCache: List<AlbumGroup>? = null
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
    private var vinylAnimator: ObjectAnimator? = null

    // Root & Header Views (Matching mobile.php)
    private lateinit var rootContainer: LinearLayout
    private lateinit var headerBar: LinearLayout
    private lateinit var brandDiscBadge: TextView
    private lateinit var headerGreetingTxt: TextView
    private lateinit var headerSyncBadge: TextView
    private lateinit var headerSearchBtn: TextView
    private lateinit var headerThemeBtn: TextView
    private lateinit var searchBarContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var contentScroll: ScrollView
    private lateinit var contentContainer: LinearLayout

    // Floating Mini Player Views (Matching mobile.php #mini-player)
    private lateinit var miniPlayerCard: LinearLayout
    private lateinit var miniCoverImg: ImageView
    private lateinit var miniTitleTxt: TextView
    private lateinit var miniArtistTxt: TextView
    private lateinit var miniThinProgressBar: ProgressBar
    private lateinit var miniPlayPauseBtn: TextView

    // Top/Bottom Navigation Tab Views
    private lateinit var bottomNavContainer: LinearLayout
    private val tabButtons = mutableMapOf<String, LinearLayout>()
    private val tabIcons = mutableMapOf<String, TextView>()
    private val tabLabels = mutableMapOf<String, TextView>()

    companion object {
        private const val PREFS_NAME = "PHPlayerNativeApiPrefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_ROLE = "user_role"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_SYNC_ONLY_PLAYED = "sync_only_played"
        private const val KEY_AUTO_OFFLINE_DL = "auto_offline_download"
        private const val KEY_FORCE_OFFLINE = "force_offline_mode"
        private const val KEY_MAX_OFFLINE_MB = "max_offline_storage_mb"
        private const val KEY_THEME_ID = "mobile_theme_id"
        private const val DEFAULT_SERVER_URL = "http://leirson.ddns.me"
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            try {
                val mp = mediaPlayer
                if (mp != null && isPlayingState) {
                    val pos = mp.currentPosition / 1000
                    val dur = (mp.duration.takeIf { it > 0 } ?: 0) / 1000
                    miniThinProgressBar.max = if (dur > 0) dur else 100
                    miniThinProgressBar.progress = pos
                }
            } catch (_: Exception) {}
            mainHandler.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        serverBaseUrl = normalizeServerUrl(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)
        currentUsername = prefs.getString(KEY_USERNAME, "admin") ?: "admin"
        currentRole = prefs.getString(KEY_ROLE, "admin") ?: "admin"
        isSyncEnabled = prefs.getBoolean(KEY_SYNC_ENABLED, true)
        syncOnlyPlayed = prefs.getBoolean(KEY_SYNC_ONLY_PLAYED, true)
        isAutoOfflineDownloadEnabled = prefs.getBoolean(KEY_AUTO_OFFLINE_DL, !syncOnlyPlayed)
        forceOfflineMode = prefs.getBoolean(KEY_FORCE_OFFLINE, false)
        maxOfflineStorageMB = prefs.getLong(KEY_MAX_OFFLINE_MB, 2048L)

        val savedThemeId = prefs.getString(KEY_THEME_ID, "default") ?: "default"
        activeTheme = themes.firstOrNull { it.id == savedThemeId } ?: themes[0]

        // Load locally persisted catalog immediately so offline works on cold boot
        loadOfflineCatalogFromDisk()

        buildMobilePhpStyleLayout()
        setContentView(rootContainer)

        mainHandler.post(progressRunnable)

        if (!isSyncEnabled || forceOfflineMode || !isNetworkAvailable()) {
            isServerReachable = false
            updateHeaderStatusUI()
            renderCurrentTab()
        } else {
            syncAllFromApi(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(progressRunnable)
        vinylAnimator?.cancel()
        releasePlayer()
    }

    // =========================================================================
    // SYNC ENABLE / DISABLE & "ONLY PLAYED" MODE CONTROLLER
    // =========================================================================
    private fun setSyncEnabledState(enabled: Boolean, showFeedback: Boolean = true) {
        isSyncEnabled = enabled
        prefs.edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()

        if (!enabled) {
            downloadedSyncProgressText = "Sincronização desativada pelo usuário"
            updateHeaderStatusUI()
            renderCurrentTab()
            if (showFeedback) {
                Toast.makeText(this, "Sincronização (Sync) desativada. Usando apenas dados locais.", Toast.LENGTH_SHORT).show()
            }
        } else {
            downloadedSyncProgressText = if (syncOnlyPlayed) {
                "Modo ativo: Sincronizar somente o que for tocado"
            } else {
                "Modo ativo: Sincronizar e baixar biblioteca completa"
            }
            updateHeaderStatusUI()
            if (showFeedback) {
                val modeMsg = if (syncOnlyPlayed) "Sincronizando catálogo (salvando músicas somente ao tocar)..." else "Sincronizando catálogo e baixando músicas..."
                Toast.makeText(this, modeMsg, Toast.LENGTH_SHORT).show()
            }
            if (!forceOfflineMode) {
                syncAllFromApi(false)
            } else {
                renderCurrentTab()
            }
        }
    }

    private fun setSyncOnlyPlayedMode(onlyPlayed: Boolean, showFeedback: Boolean = true) {
        syncOnlyPlayed = onlyPlayed
        isAutoOfflineDownloadEnabled = !onlyPlayed
        prefs.edit()
            .putBoolean(KEY_SYNC_ONLY_PLAYED, onlyPlayed)
            .putBoolean(KEY_AUTO_OFFLINE_DL, !onlyPlayed)
            .apply()

        if (onlyPlayed) {
            downloadedSyncProgressText = "🎧 Modo ativo: Salvando no celular somente o que for tocado (${getOfflineTracksCount()} salvas)"
            if (showFeedback) {
                Toast.makeText(this, "Ativado: Sincronizar somente o que for tocado!", Toast.LENGTH_SHORT).show()
            }
        } else {
            downloadedSyncProgressText = "📥 Modo ativo: Download automático da biblioteca"
            if (showFeedback) {
                Toast.makeText(this, "Ativado: Download automático de todas as músicas!", Toast.LENGTH_SHORT).show()
            }
            if (isSyncEnabled && !forceOfflineMode) {
                startAutoOfflineDownloadWorker(false)
            }
        }
        updateHeaderStatusUI()
        renderCurrentTab()
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
        return forceOfflineMode || !isSyncEnabled || !isServerReachable || !isNetworkAvailable()
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
     * Automatically downloads library songs to local phone storage in the background
     * up to the user's selected storage limit (maxOfflineStorageMB), respecting isSyncEnabled.
     */
    private fun startAutoOfflineDownloadWorker(showToastOnStart: Boolean = false) {
        if (!isSyncEnabled) {
            if (showToastOnStart) {
                Toast.makeText(this, "Ative a Sincronização (Sync) para baixar músicas do servidor.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (forceOfflineMode || !isServerReachable) return
        if (isBulkDownloading.getAndSet(true)) {
            if (showToastOnStart) {
                Toast.makeText(this, "Download offline já está em andamento em segundo plano.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (showToastOnStart) {
            Toast.makeText(this, "Baixando músicas para armazenamento local offline...", Toast.LENGTH_SHORT).show()
        }

        downloadExecutor.execute {
            try {
                val orderedTracks = allTracks.sortedByDescending { favoriteIds.contains(it.id) }
                val limitBytes = if (maxOfflineStorageMB <= 0L) Long.MAX_VALUE else maxOfflineStorageMB * 1024L * 1024L

                var downloadedNow = 0
                val missingTracks = orderedTracks.filter { !isTrackStoredOffline(it.id) && !it.id.startsWith("radio-") }

                for ((idx, track) in missingTracks.withIndex()) {
                    if (!isSyncEnabled || syncOnlyPlayed || forceOfflineMode) break
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
                    downloadedSyncProgressText = if (!isSyncEnabled) {
                        "Sincronização pausada ($totalOffline músicas salvas)"
                    } else if (downloadedNow > 0) {
                        "✓ $totalOffline músicas salvas no celular (${formatBytes(getUsedOfflineAudioBytes())})"
                    } else {
                        "✓ Biblioteca offline pronta ($totalOffline músicas salvas)"
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
                setRequestProperty("User-Agent", "PHPlayerAndroidAPI/1.4.1")
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
                            if (!isSyncEnabled) {
                                tempFile.delete()
                                return false
                            }
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

    private fun enforceStorageQuotaIfNeeded(reserveBytes: Long = 0L) {
        if (maxOfflineStorageMB <= 0L) return
        val maxBytes = (maxOfflineStorageMB * 1024L * 1024L - reserveBytes).coerceAtLeast(10L * 1024L * 1024L)
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
    // MOBILE.PHP REPLICA UI CONSTRUCTION
    // =========================================================================
    private fun buildMobilePhpStyleLayout() {
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(activeTheme.bgHex))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. TOP APP BAR (Matches mobile.php lines 1138-1174)
        headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.parseColor(activeTheme.bgHex))
        }

        brandDiscBadge = TextView(this).apply {
            text = "💿"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = roundedBox(activeTheme.darkTintHex, activeTheme.primaryHex, 12)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val appTitle = TextView(this).apply {
            text = "PHPlayer Mobile"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            maxLines = 1
        }

        headerGreetingTxt = TextView(this).apply {
            text = "OLÁ, ${currentUsername.uppercase()}"
            setTextColor(Color.parseColor(activeTheme.accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        titleCol.addView(appTitle)
        titleCol.addView(headerGreetingTxt)

        // Quick Sync Mode Toggle Pill in Header (cycles: Somente Tocado -> Sync Tudo -> Sync Off)
        headerSyncBadge = TextView(this).apply {
            text = "🎧 SÓ TOCADO"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) }
            setOnClickListener {
                when {
                    !isSyncEnabled -> {
                        syncOnlyPlayed = true
                        isAutoOfflineDownloadEnabled = false
                        prefs.edit()
                            .putBoolean(KEY_SYNC_ONLY_PLAYED, true)
                            .putBoolean(KEY_AUTO_OFFLINE_DL, false)
                            .apply()
                        setSyncEnabledState(true, true)
                    }
                    syncOnlyPlayed -> {
                        setSyncOnlyPlayedMode(false, true)
                    }
                    else -> {
                        setSyncEnabledState(false, true)
                    }
                }
            }
        }

        // Theme Picker Button (Matches mobile.php palette button)
        headerThemeBtn = TextView(this).apply {
            text = "🎨"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = roundedBox("#0f172a", "#1e293b", 12)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(6) }
            setOnClickListener { showThemeSelectorDialog() }
        }

        // Search Toggle Button (Matches mobile.php #mobile-search-toggle)
        headerSearchBtn = TextView(this).apply {
            text = "🔍"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = roundedBox("#0f172a", "#1e293b", 12)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener {
                isSearchExpanded = !isSearchExpanded
                searchBarContainer.visibility = if (isSearchExpanded) View.VISIBLE else View.GONE
                if (!isSearchExpanded && searchQuery.isNotEmpty()) {
                    searchInput.setText("")
                }
            }
        }

        headerBar.addView(brandDiscBadge)
        headerBar.addView(titleCol)
        headerBar.addView(headerSyncBadge)
        headerBar.addView(headerThemeBtn)
        headerBar.addView(headerSearchBtn)
        rootContainer.addView(headerBar)

        // Subtle 1dp bottom border under header like mobile.php border-slate-800/70
        val headerDivider = View(this).apply {
            setBackgroundColor(Color.parseColor(activeTheme.borderHex))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }
        rootContainer.addView(headerDivider)

        // 2. COLLAPSIBLE SEARCH BAR (Matches mobile.php #mobile-search-bar)
        searchBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
            visibility = View.GONE
        }

        searchInput = EditText(this).apply {
            hint = "🔍 Buscar música, artista ou álbum..."
            setHintTextColor(Color.parseColor("#64748b"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isSingleLine = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBox("#0f172a", activeTheme.borderHex, 12)
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

        val clearSearchBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setOnClickListener {
                searchInput.setText("")
            }
        }

        searchBarContainer.addView(searchInput)
        searchBarContainer.addView(clearSearchBtn)
        rootContainer.addView(searchBarContainer)

        // 3. SCROLLABLE MAIN CONTENT AREA
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
            setPadding(dp(14), dp(12), dp(14), dp(28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        contentScroll.addView(contentContainer)
        rootContainer.addView(contentScroll)

        // 4. FLOATING MINI-PLAYER (Matches mobile.php #mini-player lines 1423-1450)
        miniPlayerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBox(activeTheme.miniPlayerHex, activeTheme.borderHex, 16)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(10), dp(4), dp(10), dp(6))
            }
            setOnClickListener { showExpandedPlayerDialog() }
        }

        miniCoverImg = createRoundedImageView(dp(42), 10).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10)
        }

        val miniInfoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
        }

        miniTitleTxt = TextView(this).apply {
            text = "Selecione uma música"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        miniArtistTxt = TextView(this).apply {
            text = "Artista"
            setTextColor(Color.parseColor(activeTheme.accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        // Thin 2dp progress bar directly below artist name (exact match to mobile.php #mini-progress)
        miniThinProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
                topMargin = dp(5)
            }
        }

        miniInfoCol.addView(miniTitleTxt)
        miniInfoCol.addView(miniArtistTxt)
        miniInfoCol.addView(miniThinProgressBar)

        val prevBtn = createIconBtn("⏮") { playPrevious() }
        miniPlayPauseBtn = TextView(this).apply {
            text = "▶"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = roundedBox(activeTheme.primaryHex, activeTheme.accentHex, 20)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
            setOnClickListener { togglePlayPause() }
        }
        val nextBtn = createIconBtn("⏭") { playNext() }

        miniPlayerCard.addView(miniCoverImg)
        miniPlayerCard.addView(miniInfoCol)
        miniPlayerCard.addView(prevBtn)
        miniPlayerCard.addView(miniPlayPauseBtn)
        miniPlayerCard.addView(nextBtn)
        rootContainer.addView(miniPlayerCard)

        // 5. BOTTOM NAVIGATION BAR (Matches mobile.php #bottom-nav with 6 tabs: Álbuns, Artistas, Playlists, Rádios, Podcast, Config)
        val navDivider = View(this).apply {
            setBackgroundColor(Color.parseColor("#1e293b"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }
        rootContainer.addView(navDivider)

        bottomNavContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor(activeTheme.bgHex))
            setPadding(dp(4), dp(6), dp(4), dp(8))
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
                setPadding(dp(2), dp(4), dp(2), dp(4))
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
            bottomNavContainer.addView(tabBox)
        }

        rootContainer.addView(bottomNavContainer)
        applyActiveThemeColors()
        updateHeaderStatusUI()
    }

    private fun applyActiveThemeColors() {
        rootContainer.setBackgroundColor(Color.parseColor(activeTheme.bgHex))
        headerBar.setBackgroundColor(Color.parseColor(activeTheme.bgHex))
        bottomNavContainer.setBackgroundColor(Color.parseColor(activeTheme.bgHex))
        brandDiscBadge.background = roundedBox(activeTheme.darkTintHex, activeTheme.primaryHex, 12)
        headerGreetingTxt.setTextColor(Color.parseColor(activeTheme.accentHex))
        miniPlayerCard.background = roundedBox(activeTheme.miniPlayerHex, activeTheme.borderHex, 16)
        miniArtistTxt.setTextColor(Color.parseColor(activeTheme.accentHex))
        miniPlayPauseBtn.background = roundedBox(activeTheme.primaryHex, activeTheme.accentHex, 20)
        updateTabStyles()
    }

    private fun switchTab(tabId: String) {
        currentTab = tabId
        updateTabStyles()
        renderCurrentTab()
    }

    private fun updateTabStyles() {
        for ((id, box) in tabButtons) {
            val lbl = tabLabels[id]
            val icn = tabIcons[id]
            if (id == currentTab) {
                box.background = roundedBox(activeTheme.surfaceHex, activeTheme.primaryHex, 10)
                lbl?.setTextColor(Color.parseColor(activeTheme.accentHex))
                icn?.alpha = 1.0f
            } else {
                box.setBackgroundColor(Color.TRANSPARENT)
                lbl?.setTextColor(Color.parseColor("#64748b"))
                icn?.alpha = 0.7f
            }
        }
    }

    private fun updateHeaderStatusUI() {
        val offlineCount = getOfflineTracksCount()
        headerGreetingTxt.text = if (isOfflineModeActive()) {
            "OLÁ, ${currentUsername.uppercase()} • $offlineCount OFFLINE"
        } else {
            "OLÁ, ${currentUsername.uppercase()} • ONLINE"
        }

        if (!isSyncEnabled) {
            headerSyncBadge.text = "⏸ SYNC OFF"
            headerSyncBadge.setTextColor(Color.parseColor("#fbbf24"))
            headerSyncBadge.background = roundedBox("#451a03", "#f59e0b", 12)
        } else if (isOfflineModeActive()) {
            headerSyncBadge.text = "📴 OFFLINE"
            headerSyncBadge.setTextColor(Color.parseColor("#fb7185"))
            headerSyncBadge.background = roundedBox("#4c0519", "#f43f5e", 12)
        } else if (syncOnlyPlayed) {
            headerSyncBadge.text = "🎧 SÓ TOCADO"
            headerSyncBadge.setTextColor(Color.parseColor("#38bdf8"))
            headerSyncBadge.background = roundedBox("#082f49", "#0ea5e9", 12)
        } else {
            headerSyncBadge.text = "🔄 SYNC TUDO"
            headerSyncBadge.setTextColor(Color.parseColor("#34d399"))
            headerSyncBadge.background = roundedBox("#052e16", "#10b981", 12)
        }
    }

    // =========================================================================
    // API SYNC WITH AUTOMATIC OFFLINE CACHING
    // =========================================================================
    private fun syncAllFromApi(triggerToast: Boolean = false) {
        if (!isSyncEnabled) {
            if (triggerToast) {
                AlertDialog.Builder(this)
                    .setTitle("Sincronização Desativada")
                    .setMessage("O Sync está desativado nas configurações. Deseja habilitar a sincronização agora?")
                    .setNegativeButton("Manter Desativado", null)
                    .setPositiveButton("Habilitar e Sincronizar") { _, _ ->
                        setSyncEnabledState(true, true)
                    }
                    .show()
            }
            return
        }

        headerGreetingTxt.text = "SINCRONIZANDO BIBLIOTECA..."

        ioExecutor.execute {
            try {
                val tracksRaw = httpGet(getApiEndpoint("tracks"))
                val parsedTracks = parseTracksJson(tracksRaw).toMutableList()

                // Fetch podcasts from ?route=podcasts
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
                    randomTenAlbumsCache = null
                    rebuildPodcastShowsFromTracks()

                    isServerReachable = true
                    updateHeaderStatusUI()
                    renderCurrentTab()

                    if (triggerToast) {
                        val msg = if (syncOnlyPlayed) {
                            "Catálogo sincronizado! As músicas serão salvas à medida que forem tocadas."
                        } else {
                            "Sincronização concluída! Baixando músicas em segundo plano..."
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    if (isSyncEnabled && !syncOnlyPlayed && isAutoOfflineDownloadEnabled) {
                        startAutoOfflineDownloadWorker(false)
                    }
                }
            } catch (_: Exception) {
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
            setRequestProperty("User-Agent", "PHPlayerAndroidAPI/1.4.1")
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
            setRequestProperty("User-Agent", "PHPlayerAndroidAPI/1.4.1")
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
    // TAB RENDERING (MOBILE.PHP VISUAL PARITY)
    // =========================================================================
    private fun renderCurrentTab() {
        contentContainer.removeAllViews()

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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(activeTheme.surfaceHex, activeTheme.borderHex, 14)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val statusReason = if (!isSyncEnabled) "⏸ SYNC DESATIVADO (MODO LOCAL)" else "📴 MODO OFFLINE ATIVO"
        titleCol.addView(TextView(this).apply {
            text = statusReason
            setTextColor(Color.parseColor("#fbbf24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(this).apply {
            text = "${offlineTracks.size} músicas no celular prontas para tocar"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        })

        val actionBtn = createModernButton("▶ Tocar", activeTheme.primaryHex, "#ffffff", 10) {
            if (offlineTracks.isNotEmpty()) {
                startPlaybackQueue(offlineTracks.shuffled(), 0)
            } else {
                Toast.makeText(this, "Nenhuma música salva offline ainda.", Toast.LENGTH_SHORT).show()
            }
        }

        banner.addView(titleCol)
        banner.addView(actionBtn)
        return banner
    }

    /**
     * 3-Column Summary Stats Grid from mobile.php lines 1200-1213
     */
    private fun buildMobilePhpStatsRow(musicCount: Int, albumsCount: Int, artistsCount: Int): View {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        fun statCard(valueStr: String, labelStr: String, valueColorHex: String, weightMarginRight: Int): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = roundedBox(activeTheme.surfaceHex, activeTheme.borderHex, 16)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = weightMarginRight
                }
                addView(TextView(this@MainActivity).apply {
                    text = valueStr
                    setTextColor(Color.parseColor(valueColorHex))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = labelStr
                    setTextColor(Color.parseColor("#64748b"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                })
            }
        }

        grid.addView(statCard(musicCount.toString(), "MÚSICAS", "#ffffff", dp(8)))
        grid.addView(statCard(albumsCount.toString(), "ÁLBUNS", activeTheme.accentHex, dp(8)))
        grid.addView(statCard(artistsCount.toString(), "ARTISTAS", "#34d399", 0))
        return grid
    }

    /**
     * Horizontal A-Z Filter Bar matching mobile.php (#mobile-albums-az-bar / #mobile-artists-az-bar)
     */
    private fun buildAzFilterBar(selectedLetter: String, onSelect: (String) -> Unit): View {
        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val options = mutableListOf("RANDOM_10" to "🎲 10 Álbuns", "ALL" to "TODOS", "#" to "#")
        for (c in 'A'..'Z') options.add(c.toString() to c.toString())

        for ((key, label) in options) {
            if (key == "RANDOM_10" && currentTab != "albums") continue
            val isSel = selectedLetter == key
            val pill = TextView(this).apply {
                text = label
                setTextColor(if (isSel) Color.WHITE else Color.parseColor("#94a3b8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(11), dp(6), dp(11), dp(6))
                background = roundedBox(
                    if (isSel) activeTheme.primaryHex else activeTheme.surfaceHex,
                    if (isSel) activeTheme.accentHex else activeTheme.borderHex,
                    10
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(6) }
                setOnClickListener {
                    if (key == "RANDOM_10") {
                        randomTenAlbumsCache = null
                    }
                    onSelect(key)
                }
            }
            row.addView(pill)
        }
        hScroll.addView(row)
        return hScroll
    }

    // -------------------------------------------------------------------------
    // 1. ALBUMS TAB (mobile.php 3-column stats + 50 Aleatórias + 2-Column Bento Grid)
    // -------------------------------------------------------------------------
    private fun renderAlbumsTab() {
        val musicTracks = getVisibleMusicTracks()
        val allAlbumsMap = linkedMapOf<String, MutableList<TrackItem>>()
        for (t in musicTracks) {
            allAlbumsMap.getOrPut("${t.artist}___${t.album}") { mutableListOf() }.add(t)
        }
        val allAlbumsList = allAlbumsMap.values.map { list ->
            val first = list.first()
            AlbumGroup(
                name = first.album,
                artist = first.artist,
                coverUrl = list.firstOrNull { it.coverUrl.isNotEmpty() }?.coverUrl ?: "",
                year = first.albumYear,
                type = first.albumType,
                tracks = list.sortedBy { if (it.trackNumber > 0) it.trackNumber else 999 }
            )
        }
        val totalArtistsCount = musicTracks.map { it.artist.lowercase() }.toSet().size

        // 1. 3-Column Summary Stats Row (MÚSICAS | ÁLBUNS | ARTISTAS)
        contentContainer.addView(buildMobilePhpStatsRow(musicTracks.size, allAlbumsList.size, totalArtistsCount))

        // 2. Section Header + "50 Aleatórias" Purple-Indigo Pill Button (Matches mobile.php lines 1217-1226)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(10))
        }
        val sectionTitle = TextView(this).apply {
            text = if (selectedAlbumLetter == "RANDOM_10") "10 ÁLBUNS ALEATÓRIOS" else "ÁLBUNS EM DESTAQUE"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btn50Random = TextView(this).apply {
            text = "🔀 50 Aleatórias"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#9333ea"), Color.parseColor("#4f46e5"))
            ).apply { cornerRadius = dp(12).toFloat() }
            setOnClickListener {
                playRandom50Tracks()
            }
        }
        headerRow.addView(sectionTitle)
        headerRow.addView(btn50Random)
        contentContainer.addView(headerRow)

        // 3. A-Z + "10 Álbuns" Filter Bar
        contentContainer.addView(buildAzFilterBar(selectedAlbumLetter) { letter ->
            selectedAlbumLetter = letter
            renderCurrentTab()
        })

        // Filter albums by searchQuery and selectedAlbumLetter
        var displayedAlbums = if (searchQuery.isNotEmpty()) {
            allAlbumsList.filter {
                it.name.contains(searchQuery, true) || it.artist.contains(searchQuery, true)
            }
        } else {
            allAlbumsList
        }

        displayedAlbums = when (selectedAlbumLetter) {
            "RANDOM_10" -> {
                if (randomTenAlbumsCache == null) {
                    randomTenAlbumsCache = displayedAlbums.shuffled().take(10)
                }
                randomTenAlbumsCache ?: emptyList()
            }
            "ALL" -> displayedAlbums.sortedBy { it.name.lowercase() }
            "#" -> displayedAlbums.filter {
                val firstChar = it.name.trim().firstOrNull()?.uppercaseChar() ?: '#'
                firstChar !in 'A'..'Z'
            }
            else -> displayedAlbums.filter {
                it.name.trim().startsWith(selectedAlbumLetter, ignoreCase = true)
            }
        }

        if (displayedAlbums.isEmpty()) {
            val msg = if (isOfflineModeActive()) {
                "Nenhum álbum offline encontrado.\nAtive o Sync ou conecte-se na aba ⚙ Config para sincronizar."
            } else {
                "Nenhum álbum encontrado para este filtro."
            }
            contentContainer.addView(emptyLabel(msg))
            return
        }

        // 4. 2-Column Bento Grid (Matches mobile.php #albums-grid-container grid-cols-2 gap-3.5)
        val screenWidth = resources.displayMetrics.widthPixels
        val cardWidth = (screenWidth - dp(14 * 2) - dp(10)) / 2
        val coverSize = cardWidth - dp(20)

        for (pair in displayedAlbums.chunked(2)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

            val leftCard = createAlbumBentoCard(pair[0], coverSize).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(5)
                }
            }
            row.addView(leftCard)

            if (pair.size > 1) {
                val rightCard = createAlbumBentoCard(pair[1], coverSize).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(5)
                    }
                }
                row.addView(rightCard)
            } else {
                val spacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(5) }
                }
                row.addView(spacer)
            }

            contentContainer.addView(row)
        }
    }

    private fun createAlbumBentoCard(alb: AlbumGroup, coverSizePx: Int): View {
        val offlineCount = alb.tracks.count { isTrackStoredOffline(it.id) }
        val allOffline = offlineCount == alb.tracks.size && alb.tracks.isNotEmpty()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(12))
            background = roundedBox(activeTheme.surfaceHex, activeTheme.borderHex, 16)
            setOnClickListener {
                selectedAlbumFilter = alb
                renderCurrentTab()
            }
        }

        val coverFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, coverSizePx).apply {
                bottomMargin = dp(8)
            }
        }

        val img = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBox("#0f172a", activeTheme.borderHex, 12)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        loadImageAsync(alb.coverUrl, img)
        coverFrame.addView(img)

        // Floating Play Button on Bottom-Right of Cover (Matches mobile.php album card)
        val playBadge = TextView(this).apply {
            text = "▶"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = roundedBox(activeTheme.primaryHex, activeTheme.accentHex, 18)
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            setOnClickListener {
                startPlaybackQueue(alb.tracks, 0)
            }
        }
        coverFrame.addView(playBadge)

        if (allOffline) {
            val offPill = TextView(this).apply {
                text = "✓"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#052e16"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                background = roundedBox("#10b981", "#34d399", 10)
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                }
            }
            coverFrame.addView(offPill)
        }

        val titleTxt = TextView(this).apply {
            text = alb.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val subTxt = TextView(this).apply {
            text = alb.artist
            setTextColor(Color.parseColor(activeTheme.accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val metaTxt = TextView(this).apply {
            val yr = if (alb.year.isNotEmpty()) "${alb.year} • " else ""
            text = "$yr${alb.tracks.size} faixas"
            setTextColor(Color.parseColor("#64748b"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            maxLines = 1
        }

        card.addView(coverFrame)
        card.addView(titleTxt)
        card.addView(subTxt)
        card.addView(metaTxt)
        return card
    }

    private fun renderAlbumDetailView(alb: AlbumGroup) {
        val backBtn = createModernButton("← Voltar aos Álbuns", activeTheme.surfaceHex, activeTheme.accentHex, 12) {
            selectedAlbumFilter = null
            renderCurrentTab()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)
        }
        contentContainer.addView(backBtn)

        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBox(activeTheme.surfaceHex, activeTheme.borderHex, 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val cover = createRoundedImageView(dp(84), 14).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
        }
        loadImageAsync(alb.coverUrl, cover)

        val metaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        metaCol.addView(TextView(this).apply {
            text = alb.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        })
        metaCol.addView(TextView(this).apply {
            text = alb.artist
            setTextColor(Color.parseColor(activeTheme.accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                selectedAlbumFilter = null
                selectedArtistFilter = alb.artist
                renderCurrentTab()
            }
        })
        metaCol.addView(TextView(this).apply {
            val offCnt = alb.tracks.count { isTrackStoredOffline(it.id) }
            text = "${alb.tracks.size} músicas • $offCnt offline"
            setTextColor(Color.parseColor("#64748b"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        })

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actionsRow.addView(createModernButton("▶ Tocar Álbum", activeTheme.primaryHex, "#ffffff", 12) {
            startPlaybackQueue(alb.tracks, 0)
        })
        actionsRow.addView(createModernButton("🔀 Aleatório", "#1e293b", activeTheme.accentHex, 12) {
            startPlaybackQueue(alb.tracks.shuffled(), 0)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        })

        metaCol.addView(actionsRow)
        headerCard.addView(cover)
        headerCard.addView(metaCol)
        contentContainer.addView(headerCard)

        alb.tracks.forEachIndexed { idx, track ->
            contentContainer.addView(createTrackRow(track, alb.tracks, idx))
        }
    }

    // -------------------------------------------------------------------------
    // 2. ARTISTS TAB (mobile.php 2-Column Bento Grid + A-Z Filter)
    // -------------------------------------------------------------------------
    private fun renderArtistsTab() {
        val musicTracks = getVisibleMusicTracks()
        val grouped = linkedMapOf<String, MutableList<TrackItem>>()
        for (t in musicTracks) {
            if (searchQuery.isNotEmpty() && !t.artist.contains(searchQuery, true)) continue
            grouped.getOrPut(t.artist) { mutableListOf() }.add(t)
        }

        var artists = grouped.entries.map { (name, list) ->
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

        artists = when (selectedArtistLetter) {
            "ALL" -> artists
            "#" -> artists.filter {
                val c = it.name.trim().firstOrNull()?.uppercaseChar() ?: '#'
                c !in 'A'..'Z'
            }
            else -> artists.filter { it.name.trim().startsWith(selectedArtistLetter, ignoreCase = true) }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(10))
        }
        val titleTxt = TextView(this).apply {
            text = "ARTISTAS (${artists.size})"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val randomArtistBtn = createModernButton("🔀 Artista Aleatório", activeTheme.primaryHex, "#ffffff", 10) {
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

        contentContainer.addView(buildAzFilterBar(selectedArtistLetter) { letter ->
            selectedArtistLetter = letter
            renderCurrentTab()
        })

        if (artists.isEmpty()) {
            contentContainer.addView(emptyLabel("Nenhum artista encontrado."))
            return
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val cardWidth = (screenWidth - dp(14 * 2) - dp(10)) / 2
        val coverSize = cardWidth - dp(20)

        for (pair in artists.chunked(2)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

            val leftCard = createArtistBentoCard(pair[0], coverSize).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(5)
                }
            }
            row.addView(leftCard)

            if (pair.size > 1) {
                val rightCard = createArtistBentoCard(pair[1], coverSize).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(5)
                    }
                }
                row.addView(rightCard)
            } else {
                val spacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(5) }
                }
                row.addView(spacer)
            }

            contentContainer.addView(row)
        }
    }

    private fun createArtistBentoCard(art: ArtistGroup, coverSizePx: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(12))
            background = roundedBox(activeTheme.surfaceHex, activeTheme.borderHex, 16)
            setOnClickListener {
                selectedArtistFilter = art.name
                renderCurrentTab()
            }
        }

        val coverFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, coverSizePx).apply {
                bottomMargin = dp(8)
            }
        }

        val img = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBox("#0f172a", activeTheme.borderHex, 12)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        loadImageAsync(art.photoUrl, img)
        coverFrame.addView(img)

        val playBadge = TextView(this).apply {
            text = "▶"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = roundedBox(activeTheme.primaryHex, activeTheme.accentHex, 18)
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            setOnClickListener {
                startPlaybackQueue(art.tracks, 0)
            }
        }
        coverFrame.addView(playBadge)

        val nameTxt = TextView(this).apply {
            text = art.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val subTxt = TextView(this).apply {
            text = "${art.albumsCount} álbuns • ${art.tracks.size} músicas"
            setTextColor(Color.parseColor(activeTheme.accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        card.addView(coverFrame)
        card.addView(nameTxt)
        card.addView(subTxt)
        return card
    }

    private fun renderArtistDetailView(artistName: String) {
        val artistTracks = getVisibleMusicTracks().filter { it.artist.equals(artistName, true) }
        val backBtn = createModernButton("← Todos os Artistas", activeTheme.surfaceHex, activeTheme.accentHex, 12) {
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
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBox(activeTheme.surfaceHex, activeTheme.borderHex, 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val avatar = createRoundedImageView(dp(78), 39).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
        }
        loadImageAsync(photoUrl, avatar)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = artistName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            val offCount = artistTracks.count { isTrackStoredOffline(it.id) }
            text = "${artistTracks.size} músicas • $offCount salvas offline"
            setTextColor(Color.parseColor(activeTheme.accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        btnRow.addView(createModernButton("▶ Tocar Tudo", activeTheme.primaryHex, "#ffffff", 12) {
            startPlaybackQueue(artistTracks, 0)
        })
        btnRow.addView(createModernButton("🔀 Shuffle", "#1e293b", activeTheme.accentHex, 12) {
            startPlaybackQueue(artistTracks.shuffled(), 0)
        }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8) })

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

        val screenWidth = resources.displayMetrics.widthPixels
        val cardWidth = (screenWidth - dp(14 * 2) - dp(10)) / 2
        val coverSize = cardWidth - dp(20)

        for (pair in albumsByName.chunked(2)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            row.addView(createAlbumBentoCard(pair[0], coverSize).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) }
            })
            if (pair.size > 1) {
                row.addView(createAlbumBentoCard(pair[1], coverSize).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) }
                })
            } else {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(5) }
                })
            }
            contentContainer.addView(row)
        }
    }

    // -------------------------------------------------------------------------
    // 3. PLAYLISTS TAB
    // -------------------------------------------------------------------------
    private fun renderPlaylistsTab() {
        val visibleTracks = getVisibleMusicTracks()
        val offlineTracks = allTracks.filter { !isPodcastTrack(it) && isTrackStoredOffline(it.id) }

        val offlineSmartCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBox("#052e16", "#10b981", 16)
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
            text = "📥 Músicas Salvas Offline"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        })
        offCol.addView(TextView(this).apply {
            text = "${offlineTracks.size} músicas no celular • ${formatBytes(getUsedOfflineAudioBytes())}"
            setTextColor(Color.parseColor("#6ee7b7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        })
        offlineSmartCard.addView(offCol)
        offlineSmartCard.addView(createModernButton("▶ Tocar", "#10b981", "#052e16", 10) {
            if (offlineTracks.isNotEmpty()) startPlaybackQueue(offlineTracks, 0)
        })
        offlineSmartCard.addView(createModernButton("🔀", "#064e3b", "#6ee7b7", 10) {
            if (offlineTracks.isNotEmpty()) startPlaybackQueue(offlineTracks.shuffled(), 0)
        }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6) })
        contentContainer.addView(offlineSmartCard)

        // Favorites Card (Matches mobile.php Favoritas section)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        })
        favCol.addView(TextView(this).apply {
            text = "${favTracks.size} músicas marcadas com coração"
            setTextColor(Color.parseColor("#fda4af"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        })
        favSmartCard.addView(favCol)
        favSmartCard.addView(createModernButton("▶ Tocar", "#e11d48", "#ffffff", 10) {
            if (favTracks.isNotEmpty()) startPlaybackQueue(favTracks, 0)
        })
        contentContainer.addView(favSmartCard)

        contentContainer.addView(TextView(this).apply {
            text = "MINHAS PLAYLISTS (${playlists.size})"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        })

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
                background = roundedBox(activeTheme.surfaceHex, activeTheme.borderHex, 16)
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
            })
            col.addView(TextView(this).apply {
                val offCnt = plTracks.count { isTrackStoredOffline(it.id) }
                text = "${plTracks.size} músicas ($offCnt offline)"
                setTextColor(Color.parseColor("#94a3b8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            })

            card.addView(col)
            card.addView(createModernButton("▶ Tocar", activeTheme.primaryHex, "#ffffff", 10) {
                if (plTracks.isNotEmpty()) startPlaybackQueue(plTracks, 0)
            })
            card.addView(createModernButton("🔀", "#1e293b", activeTheme.accentHex, 10) {
                if (plTracks.isNotEmpty()) startPlaybackQueue(plTracks.shuffled(), 0)
            }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6) })
            contentContainer.addView(card)
        }
    }

    // -------------------------------------------------------------------------
    // 4. RADIOS TAB
    // -------------------------------------------------------------------------
    private fun renderRadiosTab() {
        contentContainer.addView(TextView(this).apply {
            text = "📻 ESTAÇÕES DE RÁDIO (${radioStations.size})"
            setTextColor(Color.parseColor(activeTheme.accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(10))
        })

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
                background = roundedBox(activeTheme.surfaceHex, activeTheme.borderHex, 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                setOnClickListener {
                    if (!isNetworkAvailable() || forceOfflineMode) {
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val playBtn = createModernButton("▶ Ao Vivo", activeTheme.primaryHex, "#ffffff", 10) {
                card.performClick()
            }
            card.addView(nameTxt)
            card.addView(playBtn)
            contentContainer.addView(card)
        }
    }

    // -------------------------------------------------------------------------
    // 5. PODCASTS TAB (mobile.php 2-Column Bento Grid)
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

        contentContainer.addView(TextView(this).apply {
            text = "🎙 MEUS PODCASTS (${shows.size})"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(10))
        })

        if (shows.isEmpty()) {
            contentContainer.addView(emptyLabel("Nenhum programa de podcast disponível."))
            return
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val cardWidth = (screenWidth - dp(14 * 2) - dp(10)) / 2
        val coverSize = cardWidth - dp(20)

        for (pair in shows.chunked(2)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            row.addView(createPodcastBentoCard(pair[0], coverSize).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) }
            })
            if (pair.size > 1) {
                row.addView(createPodcastBentoCard(pair[1], coverSize).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) }
                })
            } else {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(5) }
                })
            }
            contentContainer.addView(row)
        }
    }

    private fun createPodcastBentoCard(show: PodcastShow, coverSizePx: Int): View {
        val offEps = show.episodes.count { isTrackStoredOffline(it.id) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(12))
            background = roundedBox(activeTheme.surfaceHex, activeTheme.borderHex, 16)
            setOnClickListener {
                selectedPodcastFilter = show
                renderCurrentTab()
            }
        }

        val coverFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, coverSizePx).apply {
                bottomMargin = dp(8)
            }
        }
        val img = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBox("#0f172a", activeTheme.borderHex, 12)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        loadImageAsync(show.coverUrl, img)
        coverFrame.addView(img)

        val playBadge = TextView(this).apply {
            text = "▶"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = roundedBox(activeTheme.primaryHex, activeTheme.accentHex, 18)
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            setOnClickListener {
                startPlaybackQueue(show.episodes, 0)
            }
        }
        coverFrame.addView(playBadge)

        card.addView(coverFrame)
        card.addView(TextView(this).apply {
            text = show.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(TextView(this).apply {
            text = "${show.episodes.size} episódios ($offEps offline)"
            setTextColor(Color.parseColor(activeTheme.accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
        })
        return card
    }

    private fun renderPodcastDetailView(show: PodcastShow) {
        val backBtn = createModernButton("← Voltar aos Podcasts", activeTheme.surfaceHex, activeTheme.accentHex, 12) {
            selectedPodcastFilter = null
            renderCurrentTab()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)
        }
        contentContainer.addView(backBtn)

        contentContainer.addView(TextView(this).apply {
            text = "🎙 ${show.name} (${show.episodes.size} episódios)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        })

        show.episodes.forEachIndexed { idx, ep ->
            contentContainer.addView(createTrackRow(ep, show.episodes, idx))
        }
    }

    // -------------------------------------------------------------------------
    // 6. SETTINGS TAB (Enable/Disable Sync, API/Login, Offline Storage Quota, Themes)
    // -------------------------------------------------------------------------
    private fun renderSettingsTab() {
        contentContainer.addView(TextView(this).apply {
            text = "⚙ CONFIGURAÇÕES DO PHPLAYER MOBILE"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(12))
        })

        // CARD 1: HABILITAR / DESABILITAR SINCRONIZAÇÃO (SYNC) + API / LOGIN
        val syncControlCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBox(
                activeTheme.surfaceHex,
                if (isSyncEnabled) "#10b981" else "#f59e0b",
                18
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val syncTopHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val syncTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        syncTitleCol.addView(TextView(this).apply {
            text = "🔄 Sincronização com o Servidor (Sync)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        })
        syncTitleCol.addView(TextView(this).apply {
            text = when {
                !isSyncEnabled -> "DESATIVADO • Operando somente com arquivos já salvos no celular"
                syncOnlyPlayed -> "ATIVO (SOMENTE O QUE FOR TOCADO) • Salva offline cada música ao reproduzir"
                else -> "ATIVO (BIBLIOTECA COMPLETA) • Baixa todas as músicas automaticamente"
            }
            setTextColor(
                when {
                    !isSyncEnabled -> Color.parseColor("#fbbf24")
                    syncOnlyPlayed -> Color.parseColor(activeTheme.accentHex)
                    else -> Color.parseColor("#34d399")
                }
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        val masterSyncSwitch = Switch(this).apply {
            isChecked = isSyncEnabled
            setOnCheckedChangeListener { _, checked ->
                setSyncEnabledState(checked, true)
            }
        }

        syncTopHeader.addView(syncTitleCol)
        syncTopHeader.addView(masterSyncSwitch)
        syncControlCard.addView(syncTopHeader)

        // Quick 3-Mode Selector Row (Somente o que for tocado | Baixar Tudo | Desativar Sync)
        val syncModesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
        }

        val btnPlayedOnlyMode = createModernButton(
            if (isSyncEnabled && syncOnlyPlayed) "✓ 🎧 Só o Tocado" else "🎧 Só o Tocado",
            if (isSyncEnabled && syncOnlyPlayed) activeTheme.primaryHex else "#0f172a",
            "#ffffff",
            10
        ) {
            if (!isSyncEnabled) {
                isSyncEnabled = true
                prefs.edit().putBoolean(KEY_SYNC_ENABLED, true).apply()
            }
            setSyncOnlyPlayedMode(true, true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
            gravity = Gravity.CENTER
        }

        val btnAllAutoMode = createModernButton(
            if (isSyncEnabled && !syncOnlyPlayed) "✓ 📥 Baixar Tudo" else "📥 Baixar Tudo",
            if (isSyncEnabled && !syncOnlyPlayed) "#10b981" else "#0f172a",
            if (isSyncEnabled && !syncOnlyPlayed) "#052e16" else "#cbd5e1",
            10
        ) {
            if (!isSyncEnabled) {
                isSyncEnabled = true
                prefs.edit().putBoolean(KEY_SYNC_ENABLED, true).apply()
            }
            setSyncOnlyPlayedMode(false, true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
            gravity = Gravity.CENTER
        }

        val btnDisableSyncMode = createModernButton(
            if (!isSyncEnabled) "✓ ⏸ Sync Off" else "⏸ Sync Off",
            if (!isSyncEnabled) "#f59e0b" else "#0f172a",
            if (!isSyncEnabled) "#451a03" else "#cbd5e1",
            10
        ) {
            setSyncEnabledState(false, true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }

        syncModesRow.addView(btnPlayedOnlyMode)
        syncModesRow.addView(btnAllAutoMode)
        syncModesRow.addView(btnDisableSyncMode)
        syncControlCard.addView(syncModesRow)

        // Switch 1: Sincronizar somente o que for tocado
        val onlyPlayedSwitch = Switch(this).apply {
            text = "🎧 Sincronizar somente o que for tocado (Salvar no celular ao reproduzir)"
            setTextColor(Color.WHITE)
            textSize = 11f
            isChecked = syncOnlyPlayed
            isEnabled = isSyncEnabled
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked ->
                setSyncOnlyPlayedMode(checked, true)
            }
        }
        syncControlCard.addView(onlyPlayedSwitch)

        // Switch 2: Baixar todas as músicas automaticamente
        val autoDlSwitch = Switch(this).apply {
            text = "📥 Baixar toda a biblioteca automaticamente em segundo plano"
            setTextColor(Color.parseColor("#cbd5e1"))
            textSize = 11f
            isChecked = !syncOnlyPlayed && isAutoOfflineDownloadEnabled
            isEnabled = isSyncEnabled
            setPadding(0, dp(4), 0, dp(10))
            setOnCheckedChangeListener { _, checked ->
                setSyncOnlyPlayedMode(!checked, true)
            }
        }
        syncControlCard.addView(autoDlSwitch)

        syncControlCard.addView(TextView(this).apply {
            text = "Servidor API: $serverBaseUrl • Usuário: $currentUsername ($currentRole)"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, dp(12))
        })

        val apiBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnApiLogin = createModernButton("🔑 API / Login", activeTheme.primaryHex, "#ffffff", 12) {
            showApiConfigDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
            gravity = Gravity.CENTER
        }

        val btnSyncNow = createModernButton(
            if (isSyncEnabled) "🔄 Sincronizar Agora" else "⏸ Sync Desativado",
            if (isSyncEnabled) "#10b981" else "#334155",
            if (isSyncEnabled) "#052e16" else "#94a3b8",
            12
        ) {
            if (!isSyncEnabled) {
                syncAllFromApi(true)
            } else {
                forceOfflineMode = false
                prefs.edit().putBoolean(KEY_FORCE_OFFLINE, false).apply()
                syncAllFromApi(true)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }

        apiBtnRow.addView(btnApiLogin)
        apiBtnRow.addView(btnSyncNow)
        syncControlCard.addView(apiBtnRow)
        contentContainer.addView(syncControlCard)

        // CARD 2: OFFLINE STORAGE QUOTA & LOCAL DOWNLOADS
        val storageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBox(activeTheme.surfaceHex, activeTheme.borderHex, 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        storageCard.addView(TextView(this).apply {
            text = "💾 Espaço do Celular para Músicas Offline"
            setTextColor(Color.parseColor(activeTheme.accentHex))
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
                "Espaço livre no celular: ${formatBytes(freePhoneBytes)}"
            setTextColor(Color.parseColor("#e2e8f0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(8), 0, dp(10))
        })

        if (downloadedSyncProgressText.isNotEmpty()) {
            storageCard.addView(TextView(this).apply {
                text = downloadedSyncProgressText
                setTextColor(Color.parseColor(activeTheme.accentHex))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(0, 0, 0, dp(10))
            })
        }

        val progressPercent = if (maxOfflineStorageMB <= 0L) {
            if (totalMusicCount > 0) ((offlineCount * 100) / totalMusicCount).coerceIn(0, 100) else 0
        } else {
            val maxB = maxOfflineStorageMB * 1024L * 1024L
            ((usedBytes * 100L) / maxB).toInt().coerceIn(0, 100)
        }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = progressPercent
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply {
                bottomMargin = dp(12)
            }
        }
        storageCard.addView(progressBar)

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
                bottomMargin = dp(6)
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
                if (isSelected) activeTheme.primaryHex else "#0f172a",
                if (isSelected) "#ffffff" else "#cbd5e1",
                10
            ) {
                maxOfflineStorageMB = mbVal
                prefs.edit().putLong(KEY_MAX_OFFLINE_MB, mbVal).apply()
                enforceStorageQuotaIfNeeded()
                renderCurrentTab()
                if (isSyncEnabled && !syncOnlyPlayed) startAutoOfflineDownloadWorker(true)
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

        val downloadAllNowBtn = createModernButton(
            if (syncOnlyPlayed) "🎧 Modo: Salvar Só ao Tocar" else "⬇ Baixar Biblioteca Offline",
            if (syncOnlyPlayed) "#0f172a" else activeTheme.primaryHex,
            if (syncOnlyPlayed) activeTheme.accentHex else "#ffffff",
            12
        ) {
            if (syncOnlyPlayed) {
                AlertDialog.Builder(this)
                    .setTitle("Modo 'Somente o que for tocado' ativo")
                    .setMessage("No modo atual, o aplicativo salva no celular apenas as músicas que você reproduz.\n\nDeseja manter assim ou baixar toda a biblioteca agora?")
                    .setNegativeButton("Manter Só o Tocado", null)
                    .setPositiveButton("Baixar Tudo Agora") { _, _ ->
                        setSyncOnlyPlayedMode(false, true)
                    }
                    .show()
            } else {
                startAutoOfflineDownloadWorker(true)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
            gravity = Gravity.CENTER
        }

        val clearCacheBtn = createModernButton("🗑 Limpar", "#7f1d1d", "#fecaca", 12) {
            AlertDialog.Builder(this)
                .setTitle("Limpar músicas offline?")
                .setMessage("Isso removerá os arquivos MP3 salvos no celular.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpar") { _, _ ->
                    getAudioCacheDir().listFiles()?.forEach { it.delete() }
                    downloadedSyncProgressText = ""
                    updateHeaderStatusUI()
                    renderCurrentTab()
                }
                .show()
        }

        storageActionsRow.addView(downloadAllNowBtn)
        storageActionsRow.addView(clearCacheBtn)
        storageCard.addView(storageActionsRow)
        contentContainer.addView(storageCard)

        // CARD 3: TEMA DE CORES (Matches mobile.php #theme-modal) + MODO OFFLINE + EQUALIZADOR
        val extraCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBox(activeTheme.surfaceHex, activeTheme.borderHex, 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        extraCard.addView(TextView(this).apply {
            text = "🎨 Tema Visual (Igual ao mobile.php)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })

        val themeRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        val themeRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }

        themes.forEachIndexed { idx, t ->
            val isSel = activeTheme.id == t.id
            val btn = createModernButton(
                if (isSel) "● ${t.label}" else t.label,
                if (isSel) t.primaryHex else "#0f172a",
                "#ffffff",
                10
            ) {
                activeTheme = t
                prefs.edit().putString(KEY_THEME_ID, t.id).apply()
                applyActiveThemeColors()
                renderCurrentTab()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx % 3 != 2) rightMargin = dp(6)
                }
                gravity = Gravity.CENTER
            }
            if (idx < 3) themeRow1.addView(btn) else themeRow2.addView(btn)
        }
        extraCard.addView(themeRow1)
        extraCard.addView(themeRow2)

        val offlineSwitch = Switch(this).apply {
            text = "Modo Somente Offline (Exibir apenas músicas salvas no celular)"
            setTextColor(Color.WHITE)
            textSize = 11f
            isChecked = forceOfflineMode
            setPadding(0, dp(4), 0, dp(12))
            setOnCheckedChangeListener { _, isChecked ->
                forceOfflineMode = isChecked
                prefs.edit().putBoolean(KEY_FORCE_OFFLINE, isChecked).apply()
                updateHeaderStatusUI()
                renderCurrentTab()
            }
        }
        extraCard.addView(offlineSwitch)

        val eqBtn = createModernButton("🎚 Abrir Equalizador de Áudio", "#0f172a", activeTheme.accentHex, 12) {
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
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(
                if (isCurrent) activeTheme.darkTintHex else activeTheme.surfaceHex,
                if (isCurrent) activeTheme.accentHex else activeTheme.borderHex,
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

        val numTxt = TextView(this).apply {
            text = if (track.trackNumber > 0) "${track.trackNumber}." else "•"
            setTextColor(Color.parseColor("#64748b"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(10), 0)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        col.addView(TextView(this).apply {
            text = track.title
            setTextColor(if (isCurrent) Color.parseColor(activeTheme.accentHex) else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(TextView(this).apply {
            val badge = if (isOffline) " • ✓ Offline" else ""
            text = "${track.artist} • ${track.album}$badge"
            setTextColor(if (isOffline) Color.parseColor("#34d399") else Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        val favBtn = TextView(this).apply {
            text = if (isFav) "♥" else "♡"
            setTextColor(if (isFav) Color.parseColor("#fb7185") else Color.parseColor("#64748b"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(dp(10), dp(4), dp(6), dp(4))
            setOnClickListener {
                toggleFavoriteOnApi(track.id)
            }
        }

        row.addView(numTxt)
        row.addView(col)
        row.addView(favBtn)
        return row
    }

    // =========================================================================
    // AUDIO PLAYBACK ENGINE
    // =========================================================================
    private fun playRandom50Tracks() {
        val pool = getVisibleMusicTracks()
        if (pool.isEmpty()) {
            Toast.makeText(this, "Nenhuma música disponível no momento.", Toast.LENGTH_SHORT).show()
            return
        }
        isShuffleEnabled = true
        val chosen50 = pool.shuffled().take(50)
        Toast.makeText(this, "Tocando ${chosen50.size} músicas aleatórias!", Toast.LENGTH_SHORT).show()
        startPlaybackQueue(chosen50, 0)
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
                    // Touch lastModified so frequently played tracks are kept longest in quota
                    try { cachedFile.setLastModified(System.currentTimeMillis()) } catch (_: Exception) {}
                    cachedFile.absolutePath
                } else {
                    if (isOfflineModeActive() && !track.id.startsWith("radio-")) {
                        mainHandler.post {
                            Toast.makeText(this, "Esta faixa ainda não foi tocada/salva para uso offline.", Toast.LENGTH_SHORT).show()
                        }
                        return@execute
                    }
                    if (!track.id.startsWith("radio-") && isSyncEnabled) {
                        cacheSingleTrackOnPlayAsync(track, streamUrl, cachedFile)
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

    /**
     * Saves the currently played track (and its cover art) into local phone storage
     * while respecting the user's storage quota (evicting oldest tracks if needed).
     */
    private fun cacheSingleTrackOnPlayAsync(track: TrackItem, streamUrl: String, targetFile: File) {
        if (!isSyncEnabled) return
        ioExecutor.execute {
            try {
                // Ensure room for the newly played track within user's selected storage limit
                enforceStorageQuotaIfNeeded(15L * 1024L * 1024L)
                if (track.coverUrl.isNotEmpty()) {
                    cacheImageToDiskSync(track.coverUrl)
                }
                val limitBytes = if (maxOfflineStorageMB <= 0L) Long.MAX_VALUE else maxOfflineStorageMB * 1024L * 1024L
                val remaining = (limitBytes - getUsedOfflineAudioBytes()).coerceAtLeast(20L * 1024L * 1024L)
                if (downloadFileSync(streamUrl, targetFile, remaining)) {
                    mainHandler.post {
                        val totalOff = getOfflineTracksCount()
                        downloadedSyncProgressText = "✓ Salva ao tocar: ${track.title} ($totalOff no celular)"
                        val currentPlaying = playQueue.getOrNull(currentQueueIndex)
                        if (currentPlaying?.id == track.id) {
                            miniArtistTxt.text = "${track.artist} • ${track.album} • ✓ Offline"
                        }
                        updateHeaderStatusUI()
                        renderCurrentTab()
                    }
                }
            } catch (_: Exception) {}
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
        if (trackId.startsWith("radio-") || isOfflineModeActive() || !isSyncEnabled) return
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
        if (isOfflineModeActive() || !isSyncEnabled) return

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
    // DIALOGS: FULL-SCREEN ROTATING VINYL PLAYER (Matches mobile.php #full-player)
    // =========================================================================
    private fun createVinylDiscDrawable(): BitmapDrawable {
        val size = 500
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#0b0f17")
        }
        canvas.drawCircle(cx, cy, size / 2f - 4f, fillPaint)

        // Concentric vinyl grooves like mobile.php
        val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(38, 255, 255, 255)
        }
        for (r in listOf(238f, 214f, 190f, 166f, 145f)) {
            canvas.drawCircle(cx, cy, r, groovePaint)
        }
        return BitmapDrawable(resources, bmp)
    }

    private fun showExpandedPlayerDialog() {
        val track = playQueue.getOrNull(currentQueueIndex) ?: return
        vinylAnimator?.cancel()

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(activeTheme.bgHex))
            isFillViewport = true
        }

        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        // Top Bar: Collapse button | TOCANDO AGORA | Letra button (Matches mobile.php lines 1455-1467)
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }

        var alertDialogRef: AlertDialog? = null

        val collapseBtn = createModernButton("⌄ Fechar", "#0f172a", "#94a3b8", 12) {
            vinylAnimator?.cancel()
            alertDialogRef?.dismiss()
        }

        val playingNowCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        playingNowCol.addView(TextView(this).apply {
            text = "TOCANDO AGORA"
            setTextColor(Color.parseColor(activeTheme.accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        })
        playingNowCol.addView(TextView(this).apply {
            text = "Fila de Reprodução (${currentQueueIndex + 1}/${playQueue.size})"
            setTextColor(Color.parseColor("#64748b"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
        })

        val lyricsBtn = createModernButton("📄 Letra", activeTheme.darkTintHex, activeTheme.accentHex, 12) {
            showLyricsDialog(track)
        }

        topHeader.addView(collapseBtn)
        topHeader.addView(playingNowCol)
        topHeader.addView(lyricsBtn)
        dialogLayout.addView(topHeader)

        // ROTATING VINYL RECORD (Matches mobile.php #vinyl-disc lines 1471-1488)
        val vinylSize = dp(224)
        val centerCoverSize = dp(116)
        val pinholeSize = dp(16)

        val vinylFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(vinylSize, vinylSize).apply {
                bottomMargin = dp(20)
            }
            background = createVinylDiscDrawable()
        }

        val centerCover = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(centerCoverSize, centerCoverSize).apply {
                gravity = Gravity.CENTER
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBox("#1e293b", activeTheme.primaryHex, 60)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        loadImageAsync(track.coverUrl, centerCover)

        val centerPinhole = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(pinholeSize, pinholeSize).apply {
                gravity = Gravity.CENTER
            }
            background = roundedBox(activeTheme.bgHex, "#334155", 10)
        }

        vinylFrame.addView(centerCover)
        vinylFrame.addView(centerPinhole)
        dialogLayout.addView(vinylFrame)

        vinylAnimator = ObjectAnimator.ofFloat(vinylFrame, "rotation", 0f, 360f).apply {
            duration = 18000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            if (isPlayingState) start()
        }

        val titleTxt = TextView(this).apply {
            text = track.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val artistTxt = TextView(this).apply {
            val offTag = if (isTrackStoredOffline(track.id)) " • ✓ Offline" else ""
            text = "${track.artist} — ${track.album}$offTag"
            setTextColor(Color.parseColor(activeTheme.accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        }

        dialogLayout.addView(titleTxt)
        dialogLayout.addView(artistTxt)

        // Seek Bar + Time Labels (Matches mobile.php lines 1497-1511)
        val mp = mediaPlayer
        val curPos = (mp?.currentPosition ?: 0) / 1000
        val totalDur = ((mp?.duration?.takeIf { it > 0 } ?: (track.duration * 1000)) / 1000).coerceAtLeast(1)

        val seekBar = SeekBar(this).apply {
            max = totalDur
            progress = curPos
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) mediaPlayer?.seekTo(p * 1000)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        dialogLayout.addView(seekBar)

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        }
        timeRow.addView(TextView(this).apply {
            text = formatSecs(curPos)
            setTextColor(Color.parseColor("#64748b"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        timeRow.addView(TextView(this).apply {
            text = formatSecs(totalDur)
            setTextColor(Color.parseColor("#64748b"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
        })
        dialogLayout.addView(timeRow)

        // Main Playback Controls (Matches mobile.php lines 1514-1536)
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(18))
        }

        val shufBtn = createModernButton(if (isShuffleEnabled) "🔀 ON" else "🔀", "#0f172a", if (isShuffleEnabled) activeTheme.accentHex else "#94a3b8", 12) {
            isShuffleEnabled = !isShuffleEnabled
            if (isShuffleEnabled && playQueue.size > 1) {
                playQueue.shuffle()
                currentQueueIndex = 0
            }
            Toast.makeText(this, if (isShuffleEnabled) "Modo Aleatório Ativado" else "Modo Aleatório Desativado", Toast.LENGTH_SHORT).show()
        }

        val prevBtn = createModernButton("⏮", "#0f172a", "#ffffff", 14) {
            vinylAnimator?.cancel()
            alertDialogRef?.dismiss()
            playPrevious()
        }.apply { (layoutParams as LinearLayout.LayoutParams).setMargins(dp(10), 0, dp(10), 0) }

        val playPauseCircle = TextView(this).apply {
            text = if (isPlayingState) "⏸" else "▶"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = roundedBox(activeTheme.primaryHex, activeTheme.accentHex, 32)
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(62))
            setOnClickListener {
                togglePlayPause()
                text = if (isPlayingState) "⏸" else "▶"
                if (isPlayingState) vinylAnimator?.start() else vinylAnimator?.pause()
            }
        }

        val nextBtn = createModernButton("⏭", "#0f172a", "#ffffff", 14) {
            vinylAnimator?.cancel()
            alertDialogRef?.dismiss()
            playNext()
        }.apply { (layoutParams as LinearLayout.LayoutParams).setMargins(dp(10), 0, dp(10), 0) }

        val repLabel = when (repeatMode) {
            1 -> "🔁 ALL"
            2 -> "🔂 ONE"
            else -> "🔁"
        }
        val repBtn = createModernButton(repLabel, "#0f172a", if (repeatMode > 0) activeTheme.accentHex else "#94a3b8", 12) {
            repeatMode = (repeatMode + 1) % 3
            Toast.makeText(this, "Repetição: ${if (repeatMode == 0) "Desligada" else if (repeatMode == 1) "Todas" else "Uma"}", Toast.LENGTH_SHORT).show()
        }

        controlsRow.addView(shufBtn)
        controlsRow.addView(prevBtn)
        controlsRow.addView(playPauseCircle)
        controlsRow.addView(nextBtn)
        controlsRow.addView(repBtn)
        dialogLayout.addView(controlsRow)

        // Queue Section Header (Matches mobile.php lines 1554-1575)
        val queueHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        queueHeader.addView(TextView(this).apply {
            text = "FILA DE REPRODUÇÃO (${playQueue.size})"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        queueHeader.addView(createModernButton("🎚 Equalizador", "#0f172a", activeTheme.accentHex, 10) {
            showEqualizerDialog()
        })
        dialogLayout.addView(queueHeader)

        // Show up to 25 upcoming tracks in the queue
        val queueSlice = playQueue.take(25)
        queueSlice.forEachIndexed { idx, qTrack ->
            val qRow = createTrackRow(qTrack, playQueue, idx)
            qRow.setOnClickListener {
                vinylAnimator?.cancel()
                alertDialogRef?.dismiss()
                startPlaybackQueue(playQueue, idx)
            }
            dialogLayout.addView(qRow)
        }

        rootScroll.addView(dialogLayout)

        alertDialogRef = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
            .setView(rootScroll)
            .setOnDismissListener { vinylAnimator?.cancel() }
            .create()

        alertDialogRef.show()
    }

    private fun showThemeSelectorDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(activeTheme.surfaceHex))
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        box.addView(TextView(this).apply {
            text = "🎨 ESCOLHER TEMA DE CORES"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        })

        var dlg: AlertDialog? = null
        for (t in themes) {
            val isSel = activeTheme.id == t.id
            val btn = createModernButton(
                if (isSel) "✓ ${t.label}" else t.label,
                t.surfaceHex,
                t.accentHex,
                12
            ) {
                activeTheme = t
                prefs.edit().putString(KEY_THEME_ID, t.id).apply()
                applyActiveThemeColors()
                renderCurrentTab()
                dlg?.dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            box.addView(btn)
        }

        dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(box)
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showLyricsDialog(track: TrackItem) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(activeTheme.bgHex))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val header = TextView(this).apply {
            text = "${track.title} • ${track.artist}"
            setTextColor(Color.parseColor(activeTheme.accentHex))
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
            setBackgroundColor(Color.parseColor(activeTheme.bgHex))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        container.addView(TextView(this).apply {
            text = "🎚 EQUALIZADOR DE ÁUDIO"
            setTextColor(Color.parseColor(activeTheme.accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        })

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
            setBackgroundColor(Color.parseColor(activeTheme.bgHex))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        box.addView(TextView(this).apply {
            text = "🔑 CONFIGURAR SERVIDOR API & LOGIN"
            setTextColor(Color.parseColor(activeTheme.accentHex))
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
            background = roundedBox("#0f172a", "#334155", 10)
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
            background = roundedBox("#0f172a", "#334155", 10)
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
            background = roundedBox("#0f172a", "#334155", 10)
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
                isSyncEnabled = true
                forceOfflineMode = false
                prefs.edit()
                    .putString(KEY_SERVER_URL, serverBaseUrl)
                    .putString(KEY_USERNAME, currentUsername)
                    .putBoolean(KEY_SYNC_ENABLED, true)
                    .putBoolean(KEY_FORCE_OFFLINE, false)
                    .apply()

                updateHeaderStatusUI()

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
        if (url.isEmpty() || !isSyncEnabled) return
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
                if (bmp == null && !isOfflineModeActive() && isSyncEnabled) {
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
            background = roundedBox("#0f172a", activeTheme.borderHex, radiusDp)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }

    private fun createModernButton(label: String, bgHex: String, fgHex: String, radiusDp: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(fgHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(11), dp(7), dp(11), dp(7))
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
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
