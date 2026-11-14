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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.util.concurrent.Executors
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

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val ioExecutor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Image memory cache
    private val bitmapCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // API & Auth State
    private var serverBaseUrl: String = ""
    private var currentUsername: String = ""
    private var currentRole: String = "ouvinte"

    // Library Data from PHP API
    private var allTracks = mutableListOf<TrackItem>()
    private var artistPhotosMap = mutableMapOf<String, String>()
    private var favoriteIds = mutableSetOf<String>()
    private var playlists = mutableListOf<PlaylistGroup>()
    private var radioStations = mutableListOf<RadioStation>()

    // UI Filter / Navigation State
    private var currentTab = "albums" // "albums" | "artists" | "tracks" | "playlists" | "radios" | "webview"
    private var searchQuery = ""
    private var selectedArtistFilter: String? = null
    private var selectedAlbumFilter: AlbumGroup? = null
    private var showOnlyFavorites = false

    // Audio Player State
    private var mediaPlayer: MediaPlayer? = null
    private var audioEqualizer: Equalizer? = null
    private var playQueue = mutableListOf<TrackItem>()
    private var currentQueueIndex = -1
    private var isPlayingState = false
    private var isShuffleEnabled = false
    private var repeatMode = 0 // 0 = off, 1 = all, 2 = one

    // Root Views
    private lateinit var rootContainer: LinearLayout
    private lateinit var topHeaderStatus: TextView
    private lateinit var searchInput: EditText
    private lateinit var contentScroll: ScrollView
    private lateinit var contentContainer: LinearLayout
    private lateinit var webViewContainer: FrameLayout
    private var webView: WebView? = null

    // Mini Player Views
    private lateinit var miniPlayerBar: LinearLayout
    private lateinit var miniCoverImg: ImageView
    private lateinit var miniTitleTxt: TextView
    private lateinit var miniArtistTxt: TextView
    private lateinit var miniPlayPauseBtn: TextView
    private lateinit var miniSeekBar: SeekBar
    private lateinit var miniTimeTxt: TextView

    // Tab Buttons
    private val tabButtons = mutableMapOf<String, TextView>()

    companion object {
        private const val PREFS_NAME = "PHPlayerNativeApiPrefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_ROLE = "user_role"
        private const val KEY_CACHE_AUDIO = "cache_audio_offline"
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

        buildMainLayout()
        setContentView(rootContainer)

        mainHandler.post(progressRunnable)
        syncAllFromApi()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(progressRunnable)
        releasePlayer()
    }

    private fun normalizeServerUrl(raw: String): String {
        var clean = raw.trim().removeSuffix("/")
        if (clean.isEmpty()) clean = DEFAULT_SERVER_URL
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        if (clean.endsWith("/api.php")) {
            clean = clean.removeSuffix("/api.php")
        }
        if (clean.endsWith("/index.php")) {
            clean = clean.removeSuffix("/index.php")
        }
        if (clean.endsWith("/mobile.php")) {
            clean = clean.removeSuffix("/mobile.php")
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
        val g = track.genre.lowercase()
        val f = track.fileName.lowercase()
        val a = track.artist.lowercase()
        return g == "podcast" || f.contains("podcast/") || f.contains("/podcast/") || a == "podcast"
    }

    // =========================================================================
    // UI CONSTRUCTION (100% Programmatic Material Dark Slate UI)
    // =========================================================================
    private fun buildMainLayout() {
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#070b13"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Top App Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0b111e"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val appTitle = TextView(this).apply {
            text = "🎵 PHPLAYER ANDROID API"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }

        topHeaderStatus = TextView(this).apply {
            text = "Conectando a $serverBaseUrl..."
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        titleCol.addView(appTitle)
        titleCol.addView(topHeaderStatus)
        topBar.addView(titleCol)

        val refreshBtn = createPillButton("↻ Sync", "#1e293b", "#38bdf8") {
            syncAllFromApi()
        }
        val settingsBtn = createPillButton("⚙ API / Login", "#0284c7", "#ffffff") {
            showApiConfigDialog()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6)
        }

        topBar.addView(refreshBtn)
        topBar.addView(settingsBtn)
        rootContainer.addView(topBar)

        // 2. Search Bar + Quick Shuffle Bar
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#090e1a"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        searchInput = EditText(this).apply {
            hint = "Buscar música, artista ou álbum..."
            setHintTextColor(Color.parseColor("#64748b"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isSingleLine = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBox("#111827", "#1e293b", 10)
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

        val shuffleAllBtn = createPillButton("🔀 Aleatório", "#4f46e5", "#ffffff") {
            playRandomLibraryQueue()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        }

        searchRow.addView(searchInput)
        searchRow.addView(shuffleAllBtn)
        rootContainer.addView(searchRow)

        // 3. Main Content Area (Scrollable Native Views + Optional WebView)
        val middleFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        contentScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        contentScroll.addView(contentContainer)
        middleFrame.addView(contentScroll)

        webViewContainer = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        middleFrame.addView(webViewContainer)
        rootContainer.addView(middleFrame)

        // 4. Persistent Mini Player Bar
        miniPlayerBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0f172a"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            setOnClickListener {
                showExpandedPlayerDialog()
            }
        }

        val miniTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        miniCoverImg = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                rightMargin = dp(10)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1e293b"))
        }

        val miniInfoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        miniTitleTxt = TextView(this).apply {
            text = "Nenhuma música tocando"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        miniArtistTxt = TextView(this).apply {
            text = "Selecione uma faixa"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        miniInfoCol.addView(miniTitleTxt)
        miniInfoCol.addView(miniArtistTxt)

        val prevBtn = createIconBtn("⏮") { playPrevious() }
        miniPlayPauseBtn = createIconBtn("▶") { togglePlayPause() }
        val nextBtn = createIconBtn("⏭") { playNext() }
        val eqBtn = createIconBtn("🎚") { showEqualizerDialog() }

        miniTopRow.addView(miniCoverImg)
        miniTopRow.addView(miniInfoCol)
        miniTopRow.addView(prevBtn)
        miniTopRow.addView(miniPlayPauseBtn)
        miniTopRow.addView(nextBtn)
        miniTopRow.addView(eqBtn)

        val miniSeekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }

        miniSeekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        mediaPlayer?.seekTo(progress * 1000)
                    }
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

        miniPlayerBar.addView(miniTopRow)
        miniPlayerBar.addView(miniSeekRow)
        rootContainer.addView(miniPlayerBar)

        // 5. Bottom Navigation Bar
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0b111e"))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        val tabs = listOf(
            "albums" to "💿 Álbuns",
            "artists" to "🎤 Artistas",
            "tracks" to "🎵 Músicas",
            "playlists" to "📂 Playlists",
            "radios" to "📻 Rádios",
            "webview" to "🌐 Web"
        )

        for ((tabId, label) in tabs) {
            val btn = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(8), dp(4), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    selectedArtistFilter = null
                    selectedAlbumFilter = null
                    switchTab(tabId)
                }
            }
            tabButtons[tabId] = btn
            bottomNav.addView(btn)
        }

        rootContainer.addView(bottomNav)
        updateTabStyles()
    }

    private fun switchTab(tabId: String) {
        currentTab = tabId
        updateTabStyles()
        if (tabId == "webview") {
            contentScroll.visibility = View.GONE
            webViewContainer.visibility = View.VISIBLE
            ensureWebViewLoaded()
        } else {
            webViewContainer.visibility = View.GONE
            contentScroll.visibility = View.VISIBLE
            renderCurrentTab()
        }
    }

    private fun updateTabStyles() {
        for ((id, btn) in tabButtons) {
            if (id == currentTab) {
                btn.setTextColor(Color.parseColor("#38bdf8"))
                btn.background = roundedBox("#1e293b", "#0284c7", 8)
            } else {
                btn.setTextColor(Color.parseColor("#94a3b8"))
                btn.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private fun ensureWebViewLoaded() {
        if (webView == null) {
            webView = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                        handler?.proceed()
                    }
                }
                webChromeClient = WebChromeClient()
            }
            webViewContainer.addView(webView)
        }
        webView?.loadUrl("$serverBaseUrl/mobile.php")
    }

    // =========================================================================
    // API COMMUNICATION WITH PHP SERVER (api.php)
    // =========================================================================
    private fun syncAllFromApi() {
        topHeaderStatus.text = "Sincronizando com $serverBaseUrl/api.php..."
        topHeaderStatus.setTextColor(Color.parseColor("#fbbf24"))

        ioExecutor.execute {
            try {
                // 1. Fetch tracks
                val tracksRaw = httpGet(getApiEndpoint("tracks"))
                val parsedTracks = parseTracksJson(tracksRaw)

                // 2. Fetch custom artist photos / logos
                val photosMap = mutableMapOf<String, String>()
                try {
                    val photosRaw = httpGet(getApiEndpoint("artist_photos"))
                    val pObj = JSONObject(photosRaw)
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

                // 3. Fetch user favorites
                val favSet = mutableSetOf<String>()
                try {
                    val favRaw = httpGet(getApiEndpoint("favorites", "username=" + URLEncoder.encode(currentUsername, "UTF-8")))
                    val favArr = JSONArray(favRaw)
                    for (i in 0 until favArr.length()) {
                        favSet.add(favArr.optString(i))
                    }
                } catch (_: Exception) {}

                // 4. Fetch playlists
                val plList = mutableListOf<PlaylistGroup>()
                try {
                    val plRaw = httpGet(getApiEndpoint("playlists", "username=" + URLEncoder.encode(currentUsername, "UTF-8")))
                    val plArr = JSONArray(plRaw)
                    for (i in 0 until plArr.length()) {
                        val o = plArr.optJSONObject(i) ?: continue
                        val tIds = mutableListOf<String>()
                        val tArr = o.optJSONArray("trackIds")
                        if (tArr != null) {
                            for (j in 0 until tArr.length()) {
                                tIds.add(tArr.optString(j))
                            }
                        }
                        plList.add(
                            PlaylistGroup(
                                id = o.optString("id"),
                                name = o.optString("name", "Playlist"),
                                trackIds = tIds
                            )
                        )
                    }
                } catch (_: Exception) {}

                // 5. Fetch radio stations
                val radList = mutableListOf<RadioStation>()
                try {
                    val radRaw = httpGet(getApiEndpoint("radios"))
                    val radArr = JSONArray(radRaw)
                    for (i in 0 until radArr.length()) {
                        val o = radArr.optJSONObject(i) ?: continue
                        radList.add(
                            RadioStation(
                                id = o.optString("id", "$i"),
                                name = o.optString("name", "Rádio"),
                                streamUrl = o.optString("url", "")
                            )
                        )
                    }
                } catch (_: Exception) {}

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

                    val musicCount = allTracks.count { !isPodcastTrack(it) }
                    topHeaderStatus.text = "Conectado ($currentUsername) • $musicCount músicas na API"
                    topHeaderStatus.setTextColor(Color.parseColor("#34d399"))
                    renderCurrentTab()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    topHeaderStatus.text = "Erro na API: toque em ⚙ API / Login para configurar URL"
                    topHeaderStatus.setTextColor(Color.parseColor("#f87171"))
                    renderErrorState(e.message ?: "Falha de conexão com $serverBaseUrl/api.php")
                }
            }
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
                    genre = o.optString("genre", " Geral"),
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
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Username", currentUsername)
            setRequestProperty("User-Agent", "PHPlayerAndroidAPI/1.3")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val reader = BufferedReader(InputStreamReader(stream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line)
        }
        reader.close()
        if (code !in 200..299) {
            throw Exception("HTTP $code: $sb")
        }
        return sb.toString()
    }

    private fun httpPostJson(urlStr: String, payload: JSONObject): String {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 10000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Username", currentUsername)
            setRequestProperty("User-Agent", "PHPlayerAndroidAPI/1.3")
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
        while (reader.readLine().also { line = it } != null) {
            sb.append(line)
        }
        reader.close()
        if (code !in 200..299) {
            throw Exception("HTTP $code: $sb")
        }
        return sb.toString()
    }

    // =========================================================================
    // TAB RENDERING
    // =========================================================================
    private fun renderCurrentTab() {
        contentContainer.removeAllViews()

        if (selectedAlbumFilter != null) {
            renderAlbumDetailView(selectedAlbumFilter!!)
            return
        }
        if (selectedArtistFilter != null) {
            renderArtistDetailView(selectedArtistFilter!!)
            return
        }

        when (currentTab) {
            "albums" -> renderAlbumsTab()
            "artists" -> renderArtistsTab()
            "tracks" -> renderTracksTab()
            "playlists" -> renderPlaylistsTab()
            "radios" -> renderRadiosAndPodcastsTab()
        }
    }

    private fun renderErrorState(errorMsg: String) {
        contentContainer.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBox("#111827", "#dc2626", 14)
        }
        val title = TextView(this).apply {
            text = "Não foi possível conectar à API do Servidor PHP"
            setTextColor(Color.parseColor("#f87171"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val desc = TextView(this).apply {
            text = "URL atual: $serverBaseUrl/api.php\nDetalhe: $errorMsg\n\nVerifique se o endereço do servidor PHP está correto e acessível na sua rede ou internet."
            setTextColor(Color.parseColor("#cbd5e1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, dp(14))
        }
        val cfgBtn = createPillButton("⚙ Configurar URL do Servidor PHP", "#0284c7", "#ffffff") {
            showApiConfigDialog()
        }
        card.addView(title)
        card.addView(desc)
        card.addView(cfgBtn)
        contentContainer.addView(card)
    }

    private fun renderAlbumsTab() {
        val musicTracks = allTracks.filter { !isPodcastTrack(it) }
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

        // Top action bar
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val countTxt = TextView(this).apply {
            text = "ÁLBUNS NA BIBLIOTECA (${albums.size})"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val randAlbumBtn = createPillButton("🎲 Álbum Aleatório", "#1e293b", "#38bdf8") {
            if (albums.isNotEmpty()) {
                val chosen = albums[Random.nextInt(albums.size)]
                startPlaybackQueue(chosen.tracks, 0)
            }
        }
        headerRow.addView(countTxt)
        headerRow.addView(randAlbumBtn)
        contentContainer.addView(headerRow)

        if (albums.isEmpty()) {
            contentContainer.addView(emptyLabel("Nenhum álbum encontrado."))
            return
        }

        for (alb in albums) {
            contentContainer.addView(createAlbumRowCard(alb))
        }
    }

    private fun createAlbumRowCard(alb: AlbumGroup): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBox("#0f172a", "#1e293b", 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener {
                selectedAlbumFilter = alb
                renderCurrentTab()
            }
        }

        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1e293b"))
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
            text = "${alb.artist}$yr • ${alb.tracks.size} faixas"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        infoCol.addView(title)
        infoCol.addView(sub)

        val playBtn = createPillButton("▶", "#0284c7", "#ffffff") {
            startPlaybackQueue(alb.tracks, 0)
        }
        val shuffleBtn = createPillButton("🔀", "#1e293b", "#38bdf8") {
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
        val backBtn = createPillButton("← Voltar", "#1e293b", "#38bdf8") {
            selectedAlbumFilter = null
            renderCurrentTab()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(10)
        }
        contentContainer.addView(backBtn)

        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBox("#111827", "#1e293b", 14)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val cover = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(80)).apply { rightMargin = dp(14) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1e293b"))
        }
        loadImageAsync(alb.coverUrl, cover)

        val metaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val albTitle = TextView(this).apply {
            text = alb.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
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
            setPadding(0, dp(8), 0, 0)
        }
        val playAll = createPillButton("▶ Tocar Álbum", "#0284c7", "#ffffff") {
            startPlaybackQueue(alb.tracks, 0)
        }
        val shufAll = createPillButton("🔀 Aleatório", "#1e293b", "#38bdf8") {
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

    private fun renderArtistsTab() {
        val musicTracks = allTracks.filter { !isPodcastTrack(it) }
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
            setPadding(0, 0, 0, dp(10))
        }
        val titleTxt = TextView(this).apply {
            text = "ARTISTAS (${artists.size})"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val randomArtistBtn = createPillButton("🔀 Artista Aleatório", "#4f46e5", "#ffffff") {
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
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = roundedBox("#0f172a", "#1e293b", 12)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    selectedArtistFilter = art.name
                    renderCurrentTab()
                }
            }

            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(12) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#1e293b"))
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
                text = "${art.albumsCount} álbuns • ${art.tracks.size} músicas"
                setTextColor(Color.parseColor("#94a3b8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            infoCol.addView(nameView)
            infoCol.addView(subView)

            val playBtn = createPillButton("▶", "#0284c7", "#ffffff") {
                startPlaybackQueue(art.tracks, 0)
            }
            val shufBtn = createPillButton("🔀", "#1e293b", "#38bdf8") {
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
        val artistTracks = allTracks.filter { !isPodcastTrack(it) && it.artist.equals(artistName, true) }
        val backBtn = createPillButton("← Todos os Artistas", "#1e293b", "#38bdf8") {
            selectedArtistFilter = null
            renderCurrentTab()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(10)
        }
        contentContainer.addView(backBtn)

        val photoUrl = artistPhotosMap[artistName]
            ?: artistTracks.firstOrNull { it.coverUrl.isNotEmpty() }?.coverUrl
            ?: ""

        val bannerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBox("#111827", "#1e293b", 14)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val avatar = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply { rightMargin = dp(14) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1e293b"))
        }
        loadImageAsync(photoUrl, avatar)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(this).apply {
            text = artistName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val sub = TextView(this).apply {
            text = "${artistTracks.size} músicas disponíveis"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        btnRow.addView(createPillButton("▶ Tocar Tudo", "#0284c7", "#ffffff") {
            startPlaybackQueue(artistTracks, 0)
        })
        btnRow.addView(createPillButton("🔀 Shuffle", "#1e293b", "#38bdf8") {
            startPlaybackQueue(artistTracks.shuffled(), 0)
        }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8) })

        col.addView(title)
        col.addView(sub)
        col.addView(btnRow)
        bannerCard.addView(avatar)
        bannerCard.addView(col)
        contentContainer.addView(bannerCard)

        // Group artist albums by type
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
            val matching = albumsByName.filter {
                val norm = it.type.ifEmpty { "album" }
                norm == typeKey
            }
            if (matching.isNotEmpty()) {
                val secHeader = TextView(this).apply {
                    text = "$label (${matching.size})"
                    setTextColor(Color.parseColor("#38bdf8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(8), 0, dp(6))
                }
                contentContainer.addView(secHeader)
                for (alb in matching) {
                    contentContainer.addView(createAlbumRowCard(alb))
                }
            }
        }
    }

    private fun renderTracksTab() {
        val base = allTracks.filter { !isPodcastTrack(it) }
        val favFiltered = if (showOnlyFavorites) base.filter { favoriteIds.contains(it.id) } else base
        val tracks = if (searchQuery.isEmpty()) favFiltered else favFiltered.filter {
            it.title.contains(searchQuery, true) ||
                it.artist.contains(searchQuery, true) ||
                it.album.contains(searchQuery, true)
        }

        val filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val allBtn = createPillButton(
            "Todas (${base.size})",
            if (!showOnlyFavorites) "#0284c7" else "#1e293b",
            "#ffffff"
        ) {
            showOnlyFavorites = false
            renderCurrentTab()
        }
        val favBtn = createPillButton(
            "♥ Favoritas (${favoriteIds.size})",
            if (showOnlyFavorites) "#e11d48" else "#1e293b",
            "#ffffff"
        ) {
            showOnlyFavorites = true
            renderCurrentTab()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        }

        filterBar.addView(allBtn)
        filterBar.addView(favBtn)
        contentContainer.addView(filterBar)

        if (tracks.isEmpty()) {
            contentContainer.addView(emptyLabel("Nenhuma música encontrada."))
            return
        }

        val displayList = tracks.take(150)
        displayList.forEachIndexed { idx, track ->
            contentContainer.addView(createTrackRow(track, tracks, idx))
        }
    }

    private fun createTrackRow(track: TrackItem, queueContext: List<TrackItem>, indexInQueue: Int): View {
        val isCurrent = playQueue.getOrNull(currentQueueIndex)?.id == track.id
        val isFav = favoriteIds.contains(track.id)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBox(
                if (isCurrent) "#172554" else "#0f172a",
                if (isCurrent) "#38bdf8" else "#1e293b",
                10
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
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
            text = "${track.artist} • ${track.album}"
            setTextColor(Color.parseColor("#94a3b8"))
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

    private fun renderPlaylistsTab() {
        val header = TextView(this).apply {
            text = "PLAYLISTS DO SERVIDOR (${playlists.size})"
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }
        contentContainer.addView(header)

        if (playlists.isEmpty()) {
            contentContainer.addView(emptyLabel("Nenhuma playlist cadastrada no servidor."))
            return
        }

        for (pl in playlists) {
            val plTracks = allTracks.filter { pl.trackIds.contains(it.id) }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundedBox("#0f172a", "#1e293b", 12)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameTxt = TextView(this).apply {
                text = "📂 ${pl.name}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val countTxt = TextView(this).apply {
                text = "${plTracks.size} músicas"
                setTextColor(Color.parseColor("#94a3b8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            col.addView(nameTxt)
            col.addView(countTxt)

            val playBtn = createPillButton("▶ Tocar", "#0284c7", "#ffffff") {
                if (plTracks.isNotEmpty()) startPlaybackQueue(plTracks, 0)
            }
            val shufBtn = createPillButton("🔀", "#1e293b", "#38bdf8") {
                if (plTracks.isNotEmpty()) startPlaybackQueue(plTracks.shuffled(), 0)
            }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6) }

            card.addView(col)
            card.addView(playBtn)
            card.addView(shufBtn)
            contentContainer.addView(card)
        }
    }

    private fun renderRadiosAndPodcastsTab() {
        val radHeader = TextView(this).apply {
            text = "📻 RÁDIOS ON-LINE (${radioStations.size})"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        contentContainer.addView(radHeader)

        if (radioStations.isEmpty()) {
            contentContainer.addView(emptyLabel("Nenhuma rádio cadastrada."))
        } else {
            for (rad in radioStations) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = roundedBox("#0f172a", "#1e293b", 12)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    setOnClickListener {
                        val radioTrack = TrackItem(
                            id = "radio-${rad.id}",
                            title = rad.name,
                            artist = "Rádio On-line",
                            album = "Ao Vivo",
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
                val playBtn = createPillButton("▶ Ouvir", "#10b981", "#052e16") {
                    card.performClick()
                }
                card.addView(nameTxt)
                card.addView(playBtn)
                contentContainer.addView(card)
            }
        }

        val podcastTracks = allTracks.filter { isPodcastTrack(it) }
        val podHeader = TextView(this).apply {
            text = "🎙 PODCASTS (${podcastTracks.size} episódios)"
            setTextColor(Color.parseColor("#a855f7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(8))
        }
        contentContainer.addView(podHeader)

        if (podcastTracks.isEmpty()) {
            contentContainer.addView(emptyLabel("Nenhum episódio de podcast encontrado."))
        } else {
            podcastTracks.take(60).forEachIndexed { idx, ep ->
                contentContainer.addView(createTrackRow(ep, podcastTracks, idx))
            }
        }
    }

    // =========================================================================
    // AUDIO PLAYBACK ENGINE (Streams from api.php?route=stream&id=...)
    // =========================================================================
    private fun playRandomLibraryQueue() {
        val pool = allTracks.filter { !isPodcastTrack(it) }
        if (pool.isEmpty()) {
            Toast.makeText(this, "Biblioteca vazia ou ainda sincronizando.", Toast.LENGTH_SHORT).show()
            return
        }
        isShuffleEnabled = true
        startPlaybackQueue(pool.shuffled().take(60), 0)
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
        miniPlayerBar.visibility = View.VISIBLE
        miniTitleTxt.text = track.title
        miniArtistTxt.text = "${track.artist} • ${track.album}"
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
                // Check local offline audio cache first
                val cacheDir = File(filesDir, "audio_cache").apply { mkdirs() }
                val cachedFile = File(cacheDir, "track_${track.id}.mp3")
                val playSource = if (!track.id.startsWith("radio-") && cachedFile.exists() && cachedFile.length() > 1024) {
                    cachedFile.absolutePath
                } else {
                    if (!track.id.startsWith("radio-") && prefs.getBoolean(KEY_CACHE_AUDIO, true)) {
                        cacheTrackInBackground(streamUrl, cachedFile)
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
                                if (repeatMode == 2) {
                                    playCurrentTrack()
                                } else {
                                    playNext()
                                }
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

    private fun cacheTrackInBackground(streamUrl: String, targetFile: File) {
        ioExecutor.execute {
            try {
                if (targetFile.exists()) return@execute
                val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
                val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 15000
                }
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tempFile.length() > 1024) {
                        tempFile.renameTo(targetFile)
                    } else {
                        tempFile.delete()
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
            audioEqualizer = Equalizer(0, audioSessionId).apply {
                enabled = true
            }
        } catch (_: Exception) {}
    }

    private fun registerPlayOnServer(trackId: String) {
        if (trackId.startsWith("radio-")) return
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
        ioExecutor.execute {
            try {
                val res = httpPostJson(
                    getApiEndpoint("favorites_toggle"),
                    JSONObject().put("username", currentUsername).put("trackId", trackId)
                )
                val arr = JSONArray(extractJsonPayload(res))
                val updated = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    updated.add(arr.optString(i))
                }
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
            setBackgroundColor(Color.parseColor("#0b0f19"))
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val cover = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(200)).apply { bottomMargin = dp(16) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1e293b"))
        }
        loadImageAsync(track.coverUrl, cover)

        val title = TextView(this).apply {
            text = track.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val artist = TextView(this).apply {
            text = "${track.artist} — ${track.album}"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        }

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }

        val prev = createPillButton("⏮ Anterior", "#1e293b", "#ffffff") { playPrevious() }
        val playPause = createPillButton(if (isPlayingState) "⏸ Pausar" else "▶ Tocar", "#0284c7", "#ffffff") {
            togglePlayPause()
        }.apply { (layoutParams as LinearLayout.LayoutParams).setMargins(dp(10), 0, dp(10), 0) }
        val next = createPillButton("⏭ Próxima", "#1e293b", "#ffffff") { playNext() }

        controlsRow.addView(prev)
        controlsRow.addView(playPause)
        controlsRow.addView(next)

        val extrasRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val lyricsBtn = createPillButton("📄 Letra da Música", "#1e293b", "#38bdf8") {
            showLyricsDialog(track)
        }
        val eqBtn = createPillButton("🎚 Equalizador", "#1e293b", "#38bdf8") {
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
            setBackgroundColor(Color.parseColor("#0b0f19"))
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
            setBackgroundColor(Color.parseColor("#0b0f19"))
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
            val info = TextView(this).apply {
                text = "Inicie a reprodução de uma música para ativar o Equalizador de hardware do Android."
                setTextColor(Color.parseColor("#94a3b8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            container.addView(info)
        } else {
            val bands = eq.numberOfBands.toInt()
            val minLevel = eq.bandLevelRange[0].toInt()
            val maxLevel = eq.bandLevelRange[1].toInt()

            for (b in 0 until bands) {
                val bandIdx = b.toShort()
                val freqHz = eq.getCenterFreq(bandIdx) / 1000
                val label = TextView(this).apply {
                    text = "${if (freqHz >= 1000) "${freqHz / 1000} kHz" else "$freqHz Hz"}"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                }
                val seek = SeekBar(this).apply {
                    max = maxLevel - minLevel
                    progress = eq.getBandLevel(bandIdx).toInt() - minLevel
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                            if (fromUser) {
                                eq.setBandLevel(bandIdx, (minLevel + p).toShort())
                            }
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
            setBackgroundColor(Color.parseColor("#0b0f19"))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = TextView(this).apply {
            text = "⚙ CONEXÃO COM A API DO SERVIDOR PHP"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }
        box.addView(header)

        val urlLbl = TextView(this).apply {
            text = "URL do Servidor PHP (ex: http://leirson.ddns.me/music):"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val urlInput = EditText(this).apply {
            setText(serverBaseUrl)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox("#111827", "#334155", 8)
            isSingleLine = true
        }
        box.addView(urlLbl)
        box.addView(urlInput)

        val userLbl = TextView(this).apply {
            text = "Usuário:"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, 0)
        }
        val userInput = EditText(this).apply {
            setText(currentUsername)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox("#111827", "#334155", 8)
            isSingleLine = true
        }
        box.addView(userLbl)
        box.addView(userInput)

        val passLbl = TextView(this).apply {
            text = "Senha (opcional para autenticar via api.php?route=login):"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, 0)
        }
        val passInput = EditText(this).apply {
            hint = "Digite a senha para validar login..."
            setHintTextColor(Color.parseColor("#64748b"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox("#111827", "#334155", 8)
            isSingleLine = true
        }
        box.addView(passLbl)
        box.addView(passInput)

        val cacheSwitch = Switch(this).apply {
            text = "Cachear músicas reproduzidas para ouvir Offline"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean(KEY_CACHE_AUDIO, true)
            setPadding(0, dp(14), 0, dp(14))
        }
        box.addView(cacheSwitch)

        scroll.addView(box)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar e Conectar") { _, _ ->
                val newUrl = normalizeServerUrl(urlInput.text.toString())
                val newUser = userInput.text.toString().trim().ifEmpty { "admin" }
                val rawPass = passInput.text.toString().trim()

                serverBaseUrl = newUrl
                currentUsername = newUser
                prefs.edit()
                    .putString(KEY_SERVER_URL, serverBaseUrl)
                    .putString(KEY_USERNAME, currentUsername)
                    .putBoolean(KEY_CACHE_AUDIO, cacheSwitch.isChecked)
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
                                    Toast.makeText(this, "Autenticado como $newUser ($currentRole)!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            mainHandler.post {
                                Toast.makeText(this, "Aviso de login: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            mainHandler.post { syncAllFromApi() }
                        }
                    }
                } else {
                    syncAllFromApi()
                }
            }
            .create()

        dialog.show()
    }

    // =========================================================================
    // HELPERS
    // =========================================================================
    private fun loadImageAsync(url: String, target: ImageView) {
        target.setImageBitmap(null)
        if (url.isEmpty()) return
        val cached = bitmapCache.get(url)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        target.tag = url
        ioExecutor.execute {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 7000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                if (conn.responseCode in 200..299) {
                    val bmp = BitmapFactory.decodeStream(conn.inputStream)
                    if (bmp != null) {
                        bitmapCache.put(url, bmp)
                        mainHandler.post {
                            if (target.tag == url) {
                                target.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun createPillButton(label: String, bgHex: String, fgHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(fgHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = roundedBox(bgHex, bgHex, 8)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
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
            currentTab == "webview" && webView?.canGoBack() == true -> {
                webView?.goBack()
            }
            else -> super.onBackPressed()
        }
    }
}
