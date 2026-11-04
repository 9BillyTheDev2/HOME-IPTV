package fr.homeiptv.v2

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF12110F)
private val Panel = Color(0xFF211D19)
private val Cream = Color(0xFFF2EBDD)
private val Muted = Color(0xFFB7ADA2)
private val Rust = Color(0xFFC95132)
private val Cyan = Color(0xFFD2AD68)

private val artworkCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

@Suppress("DEPRECATION")
internal fun Activity.applyImmersiveMode() {
    window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
}

private data class M3uVariantGroup(val key: String, val entries: List<PlaylistEntry>)
private data class XtreamVariantGroup(val key: String, val items: List<XtreamItem>)
private data class VariantChoice(val title: String, val details: String, val onSelect: () -> Unit)

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableStateOf(0)
    private lateinit var sourceStore: SourceStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isTelevisionDevice()) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else if (resources.configuration.smallestScreenWidthDp < 600) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        applyImmersiveMode()
        sourceStore = SourceStore(this)
        setContent { HomeApp(sourceStore, resumeTick.value) }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        if (::sourceStore.isInitialized) resumeTick.value++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }
}

private fun android.content.Context.isTelevisionDevice(): Boolean {
    val features = packageManager
    val mode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
    return features.hasSystemFeature("android.software.leanback") ||
        features.hasSystemFeature("android.hardware.type.television") ||
        mode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
}

@Composable
private fun isCompactLayout(): Boolean {
    val context = LocalContext.current
    return !context.isTelevisionDevice() && LocalConfiguration.current.screenWidthDp < 600
}

@Composable
private fun HomeApp(store: SourceStore, resumeTick: Int) {
    var section by remember { mutableStateOf("Accueil") }
    var profiles by remember { mutableStateOf(store.all()) }
    var active by remember { mutableStateOf(store.active()) }
    var adding by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<Any>>(emptyList()) }
    var xtreamCategories by remember { mutableStateOf<List<XtreamCategory>>(emptyList()) }
    var categoryOpen by remember { mutableStateOf(false) }
    var selectedSeries by remember { mutableStateOf<XtreamItem?>(null) }
    var seriesDetails by remember { mutableStateOf<XtreamSeriesDetails?>(null) }
    var selectedSeason by remember { mutableStateOf<String?>(null) }
    var selectedFilm by remember { mutableStateOf<XtreamItem?>(null) }
    var filmDetails by remember { mutableStateOf<XtreamMediaDetails?>(null) }
    var m3uFilm by remember { mutableStateOf<PlaylistEntry?>(null) }
    var recentContent by remember { mutableStateOf(store.recents()) }
    var favoriteContent by remember { mutableStateOf(store.favorites()) }
    var watchHistory by remember { mutableStateOf(store.history()) }
    val inProgressHistory = remember(watchHistory) { watchHistory.filter { it.kind != "live" && it.positionMs > 0L } }
    var historyKindFilter by remember { mutableStateOf<String?>(null) }
    var pendingFavoriteSeries by remember { mutableStateOf<FavoriteContent?>(null) }
    var searchResults by remember { mutableStateOf<List<XtreamItem>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as Activity
    val playerSettingsStore = remember { PlayerSettingsStore(activity) }
    var playerSettings by remember { mutableStateOf(playerSettingsStore.get()) }
    val selectedTab = when (section) { "Films" -> "vod"; "Séries" -> "series"; "TV en direct" -> "live"; else -> "library" }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var installingUpdate by remember { mutableStateOf(false) }

    LaunchedEffect(resumeTick) {
        recentContent = store.recents()
        favoriteContent = store.favorites()
        watchHistory = store.history()
    }

    LaunchedEffect(Unit) {
        checkingUpdate = true
        val result = runCatching { UpdateManager.findUpdate() }
        availableUpdate = result.getOrNull()
        updateStatusText = when {
            result.isFailure -> "Vérification GitHub impossible. Réessaie depuis les paramètres."
            availableUpdate != null -> "Version " + availableUpdate!!.versionName + " disponible."
            else -> "Aucune mise à jour publiée sur GitHub."
        }
        checkingUpdate = false
    }

    LaunchedEffect(active?.id, selectedTab) {
        if (selectedTab == "library") return@LaunchedEffect
        records = emptyList()
        selectedGroup = null
        categoryOpen = false
        xtreamCategories = emptyList()
        selectedSeries = null
        seriesDetails = null
        selectedSeason = null
        selectedFilm = null
        filmDetails = null
        m3uFilm = null
        searchResults = emptyList()
        message = ""
        val profile = active ?: return@LaunchedEffect
        loading = true
        try {
            if (profile.kind == "M3U") {
                requireHttpEndpoint(profile.endpoint)
                val parsed = withContext(Dispatchers.IO) {
                    val connection = URL(profile.endpoint).openConnection() as HttpURLConnection
                    connection.connectTimeout = 12000
                    connection.readTimeout = 20000
                    try {
                        if (connection.responseCode !in 200..299) error("Erreur HTTP ${connection.responseCode}")
                        if ((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L) > M3uParser.MAX_PLAYLIST_CHARACTERS) {
                            error(M3uParser.playlistTooLargeMessage)
                        }
                        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            M3uParser.parse(reader, includeEntry = { m3uSection(it) == selectedTab })
                        }
                    } finally { connection.disconnect() }
                }
                records = parsed
                if (parsed.isEmpty()) message = "Aucun contenu dans cette section de la playlist."
            } else {
                val client = XtreamClient(profile.endpoint, profile.user, profile.password)
                val categories = client.categories(if (selectedTab == "live") "live" else selectedTab)
                xtreamCategories = categories
                val firstCategory = categories.firstOrNull()
                if (firstCategory == null) {
                    message = "Aucune catégorie fournie pour cette section."
                } else {
                    selectedGroup = firstCategory.name
                    records = client.items(selectedTab, firstCategory.ids)
                    if (records.isEmpty()) message = "Cette catégorie ne contient aucun élément."
                }
                val pending = pendingFavoriteSeries
                if (selectedTab == "series" && pending != null && pending.sourceKey == sourceStorageKey(profile)) {
                    val item = XtreamItem(pending.url.removePrefix("series:"), pending.title, logo = pending.image)
                    selectedSeries = item
                    seriesDetails = client.seriesDetails(item)
                    selectedSeason = seriesDetails?.seasons?.firstOrNull()?.number
                    pendingFavoriteSeries = null
                }
            }
        } catch (e: Exception) { message = e.message ?: "Impossible de charger cette source." }
        loading = false
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Rust, onPrimary = Cream, onBackground = Cream, onSurface = Cream, secondary = Cyan)) {
        Column(Modifier.fillMaxSize().background(Ink)) {
            HeaderNavigation(
                section,
                onNavigate = { section = it }
            )
            SectionHeader(section)
            Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                "Paramètres" -> SettingsScreen(profiles, active, onAdd = { adding = true }, onSelect = {
                    store.setActive(it.id); active = it
                }, onDelete = {
                    store.delete(it.id); profiles = store.all(); active = store.active(); recentContent = store.recents()
                }, onVpn = {
                    runCatching { activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                        .onFailure { runCatching { activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }
                }, onCheckUpdate = {
                    scope.launch {
                        checkingUpdate = true
                        updateStatusText = null
                        val result = runCatching { UpdateManager.findUpdate() }
                        availableUpdate = result.getOrNull()
                        updateStatusText = when {
                            result.isFailure -> "Échec de la vérification : " + (result.exceptionOrNull()?.message ?: "erreur inconnue")
                            availableUpdate != null -> "Version " + availableUpdate!!.versionName + " disponible."
                            else -> "Aucune mise à jour publiée sur GitHub."
                        }
                        if (result.isFailure || availableUpdate == null) updateMessage = updateStatusText
                        checkingUpdate = false
                    }
                }, updateStatus = when {
                    checkingUpdate -> "Recherche de mise à jour sur GitHub…"
                    else -> updateStatusText
                }, playerSettings = playerSettings, onPlayerSettingsChanged = { updated ->
                    playerSettingsStore.save(updated)
                    playerSettings = updated
                })
                "Accueil" -> HomeDashboard(
                    inProgressHistory,
                    active == null,
                    favoriteCount = favoriteContent.size,
                    historyCount = inProgressHistory.size,
                    onOpenFavorites = { section = "Favoris" },
                    onOpenHistory = { kind -> historyKindFilter = kind; section = "Historique" },
                    onResume = { recent ->
                        activity.play(recent.url, recent.kind, recent.title, recent.subtitle, recent.image, recent.category,
                            recent.positionMs, recent.sourceKey)
                    }
                )
                "Favoris" -> SavedLibraryScreen(
                    title = "Mes favoris",
                    subtitle = "Conservés sur cet appareil et retrouvés après les mises à jour.",
                    entries = favoriteContent.map { RecentPlayback(it.kind, it.title, it.subtitle, it.image, it.url, 0L, it.category, it.sourceKey) },
                    favoriteCounts = mapOf(
                        "live" to favoriteContent.count { it.kind == "live" },
                        "vod" to favoriteContent.count { it.kind == "vod" },
                        "series" to favoriteContent.count { it.kind == "series" }
                    ),
                    emptyMessage = "Aucun favori pour le moment. Ajoute un cœur sur une chaîne, un film ou une série.",
                    onPlay = { entry -> activity.play(entry.url, entry.kind, entry.title, entry.subtitle, entry.image, entry.category, 0L, entry.sourceKey) },
                    onRemove = { entry ->
                        store.removeFavorite(favoriteContent.firstOrNull {
                            it.url == entry.url && it.title == entry.title && it.sourceKey == entry.sourceKey
                        }?.key.orEmpty())
                        favoriteContent = store.favorites()
                    },
                    onOpenSeries = { entry ->
                        val favorite = favoriteContent.firstOrNull {
                            it.url == entry.url && it.title == entry.title && it.sourceKey == entry.sourceKey
                        }
                        if (favorite != null) {
                            val profile = profiles.firstOrNull { sourceStorageKey(it) == favorite.sourceKey }
                            if (profile != null) {
                                if (active?.id != profile.id) { store.setActive(profile.id); active = profile }
                                pendingFavoriteSeries = favorite
                                section = "Séries"
                            }
                        }
                    }
                )
                "Historique" -> SavedLibraryScreen(
                    title = "Historique",
                    subtitle = "Films et séries dont la lecture peut être reprise.",
                    entries = inProgressHistory,
                    initialHistoryKind = historyKindFilter,
                    emptyMessage = "Aucune lecture en cours. Les films et séries apparaîtront ici après quelques instants de lecture.",
                    onPlay = { entry -> activity.play(entry.url, entry.kind, entry.title, entry.subtitle, entry.image,
                        entry.category, if (entry.kind == "live") 0L else entry.positionMs, entry.sourceKey) },
                    onRemove = { entry ->
                        store.removeHistoryItem(entry)
                        watchHistory = store.history()
                    },
                    onOpenSeries = { }
                )
                else -> CatalogScreen(
                    section = section,
                    sourceKind = active?.kind ?: "",
                    sourceKey = active?.let(::sourceStorageKey).orEmpty(),
                    favorites = favoriteContent,
                    records = records,
                    categories = xtreamCategories,
                    selectedGroup = selectedGroup,
                    loading = loading,
                    searchLoading = searchLoading,
                    searchResults = searchResults,
                    message = message,
                    filmItem = selectedFilm,
                    filmDetails = filmDetails,
                    m3uFilm = m3uFilm,
                    seriesItem = selectedSeries,
                    seriesDetails = seriesDetails,
                    selectedSeason = selectedSeason,
                    onSelectSeason = { selectedSeason = it },
                    onBackDetails = {
                        selectedFilm = null; filmDetails = null; m3uFilm = null
                        selectedSeries = null; seriesDetails = null; selectedSeason = null
                    },
                    onPlayFilm = { item ->
                        val p = active
                        if (p != null) activity.play(XtreamClient(p.endpoint, p.user, p.password).streamUrl("vod", item), "vod", item.name, item.year ?: "", item.logo, selectedGroup.orEmpty(), sourceKey = sourceStorageKey(p))
                    },
                    onPlayM3uFilm = { entry -> activity.play(entry.url, "vod", entry.name, entry.group, entry.logo, entry.group, sourceKey = active?.let(::sourceStorageKey).orEmpty()) },
                    onToggleFavorite = { favorite ->
                        store.toggleFavorite(favorite)
                        favoriteContent = store.favorites()
                    },
                    favoriteXtreamUrl = { kind, item ->
                        active?.let { profile -> XtreamClient(profile.endpoint, profile.user, profile.password).streamUrl(kind, item, playerSettings.liveStreamFormat) }.orEmpty()
                    },
                    onSearch = { query ->
                        searchJob?.cancel()
                        val profile = active
                        if (profile == null || profile.kind != "Xtream" || query.length < 2) {
                            searchResults = emptyList()
                            searchLoading = false
                        } else {
                            searchJob = scope.launch {
                                searchLoading = true
                                message = ""
                                val result = runCatching {
                                    XtreamClient(profile.endpoint, profile.user, profile.password).searchItems(selectedTab, query)
                                }
                                searchResults = result.getOrDefault(emptyList())
                                if (result.isFailure) message = result.exceptionOrNull()?.message ?: "La recherche a échoué."
                                searchLoading = false
                            }
                        }
                    },
                    onSelectGroup = { name ->
                        selectedGroup = name.ifBlank { null }
                        selectedSeries = null
                        seriesDetails = null
                        selectedSeason = null
                        selectedFilm = null
                        filmDetails = null
                        m3uFilm = null
                        if (active?.kind == "Xtream") {
                            val category = xtreamCategories.firstOrNull { it.name == name }
                            if (category != null) scope.launch {
                                loading = true; message = ""
                                try {
                                    val profile = active ?: return@launch
                                    records = XtreamClient(profile.endpoint, profile.user, profile.password).items(selectedTab, category.ids)
                                    if (records.isEmpty()) message = "Cette catégorie ne contient aucun élément."
                                } catch (e: Exception) { message = e.message ?: "Erreur de chargement." }
                                loading = false
                            }
                        }
                    },
                    onPlayM3u = { entry ->
                        if (selectedTab == "vod") {
                            m3uFilm = entry; selectedFilm = null; filmDetails = null
                        } else activity.play(entry.url, selectedTab, entry.name, entry.group, entry.logo, entry.group,
                            sourceKey = active?.let(::sourceStorageKey).orEmpty())
                    },
                    onPlayXtream = { item ->
                        val kind = selectedTab
                        val p = active
                        if (p != null) {
                            val client = XtreamClient(p.endpoint, p.user, p.password)
                            val fallback = if (kind == "live") client.streamUrl(
                                kind, item,
                                if (playerSettings.liveStreamFormat == LiveStreamFormat.MPEG_TS) LiveStreamFormat.HLS else LiveStreamFormat.MPEG_TS
                            ) else null
                            activity.play(
                            client.streamUrl(kind, item, playerSettings.liveStreamFormat),
                            kind, item.name, item.year ?: "", item.logo,
                            xtreamCategories.firstOrNull { item.categoryId in it.ids }?.name.orEmpty(),
                            sourceKey = sourceStorageKey(p), fallbackUrl = fallback
                            )
                        }
                    },
                    onFilm = { item ->
                        val p = active ?: return@CatalogScreen
                        selectedFilm = item; m3uFilm = null; filmDetails = null
                        scope.launch {
                            loading = true; message = ""
                            try { filmDetails = XtreamClient(p.endpoint, p.user, p.password).vodDetails(item) }
                            catch (e: Exception) { message = e.message ?: "Les détails du film ne sont pas disponibles." }
                            loading = false
                        }
                    },
                    onSeries = { item ->
                        val p = active ?: return@CatalogScreen
                        selectedSeries = item; selectedSeason = null; seriesDetails = null
                        scope.launch {
                            loading = true; message = ""
                            try {
                                seriesDetails = XtreamClient(p.endpoint, p.user, p.password).seriesDetails(item)
                                selectedSeason = seriesDetails?.seasons?.firstOrNull()?.number
                            } catch (e: Exception) { message = e.message ?: "Impossible de charger les saisons et épisodes." }
                            loading = false
                        }
                    },
                    onPlayEpisode = { episode ->
                        val p = active
                        val series = selectedSeries
                        if (p != null && series != null) activity.play(
                            XtreamClient(p.endpoint, p.user, p.password).streamUrl("series", episode.item),
                            "series", series.name, "S${selectedSeason ?: "?"}E${episode.number} · ${episode.item.name}", episode.thumbnail ?: series.logo,
                            xtreamCategories.firstOrNull { series.categoryId in it.ids }?.name.orEmpty(),
                            sourceKey = sourceStorageKey(p)
                        )
                    }
                )
            }
            }
        }
        availableUpdate?.let { update ->
            AlertDialog(
                onDismissRequest = { if (!installingUpdate) availableUpdate = null },
                title = { Text("Mise à jour disponible") },
                text = { Text("HOME IPTV ${update.versionName} est disponible. Après le téléchargement, Android te demandera de confirmer l’installation.") },
                confirmButton = {
                    TextButton(enabled = !installingUpdate, onClick = {
                        scope.launch {
                            installingUpdate = true
                            val result = UpdateManager.downloadAndOpenInstaller(activity, update)
                            installingUpdate = false
                            availableUpdate = null
                            if (result != null) updateMessage = result
                        }
                    }) { Text(if (installingUpdate) "Préparation…" else "Télécharger") }
                },
                dismissButton = {
                    TextButton(enabled = !installingUpdate, onClick = { availableUpdate = null }) { Text("Plus tard") }
                }
            )
        }
        updateMessage?.let { info ->
            AlertDialog(
                onDismissRequest = { updateMessage = null },
                title = { Text("Mise à jour HOME IPTV") },
                text = { Text(info) },
                confirmButton = { TextButton(onClick = { updateMessage = null }) { Text("OK") } }
            )
        }
        if (adding) AddSourceDialog(onDismiss = { adding = false }, onSave = { name, kind, endpoint, user, pass ->
            scope.launch {
                loading = true; message = "Test de la source…"
                try {
                    val profile = SourceProfile(UUID.randomUUID().toString(), name, kind, endpoint, user, pass)
                    if (kind == "M3U") {
                        requireHttpEndpoint(endpoint)
                        val entries = withContext(Dispatchers.IO) {
                            val c = URL(endpoint).openConnection() as HttpURLConnection
                            c.connectTimeout = 12000; c.readTimeout = 20000
                            try {
                                if (c.responseCode !in 200..299) error("La playlist répond HTTP ${c.responseCode}")
                                c.inputStream.bufferedReader(Charsets.UTF_8).use { M3uParser.parse(it, stopAfterEntries = 1) }
                            } finally { c.disconnect() }
                        }
                        if (entries.isEmpty()) error("Le lien répond, mais aucune entrée M3U valide n’a été trouvée.")
                    } else {
                        val client = XtreamClient(endpoint, user, pass)
                        if (!client.accountIsValid()) error("Le fournisseur a refusé les identifiants Xtream.")
                    }
                    store.save(profile); profiles = store.all(); active = store.active(); adding = false; section = "TV en direct"; message = "Source testée et ajoutée."
                } catch (e: Exception) { message = e.message ?: "Échec du test de la source." }
                loading = false
            }
        })
        if (loading || message.isNotBlank()) AlertDialog(onDismissRequest = { if (!loading) message = "" }, title = { Text(if (loading) "Connexion" else "HOME IPTV") }, text = { Text(if (loading && message.isBlank()) "Chargement…" else message) }, confirmButton = { if (!loading) TextButton(onClick = { message = "" }) { Text("OK") } })
    }
}

private fun Activity.play(
    url: String, kind: String, title: String, subtitle: String = "", image: String? = null,
    category: String = "", positionMs: Long = 0L, sourceKey: String = "", fallbackUrl: String? = null
) {
    startActivity(Intent(this, PlaybackActivity::class.java)
        .putExtra(PlaybackActivity.EXTRA_URL, url)
        .putExtra(PlaybackActivity.EXTRA_KIND, kind)
        .putExtra(PlaybackActivity.EXTRA_TITLE, title)
        .putExtra(PlaybackActivity.EXTRA_SUBTITLE, subtitle)
        .putExtra(PlaybackActivity.EXTRA_IMAGE, image)
        .putExtra(PlaybackActivity.EXTRA_CATEGORY, category)
        .putExtra(PlaybackActivity.EXTRA_POSITION, positionMs)
        .putExtra(PlaybackActivity.EXTRA_SOURCE_KEY, sourceKey)
        .putExtra(PlaybackActivity.EXTRA_FALLBACK_URL, fallbackUrl)
        .putExtra(PlaybackActivity.EXTRA_DECODER, PlayerSettingsStore(this).get().decoderPreference.name))
}

private fun sourceStorageKey(profile: SourceProfile): String {
    val host = android.net.Uri.parse(profile.endpoint.trim()).host?.lowercase().orEmpty()
    return "${profile.kind.lowercase()}|${host.ifBlank { profile.endpoint.trim().lowercase() }}"
}

private fun stableFavoriteKey(kind: String, title: String, category: String, sourceKey: String): String =
    listOf(sourceKey, kind.lowercase(), normalizeSearchText(title), normalizeSearchText(category)).joinToString("|")

private fun requireHttpEndpoint(value: String) {
    val uri = android.net.Uri.parse(value.trim())
    require((uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) && !uri.host.isNullOrBlank()) {
        "Entre une adresse HTTP ou HTTPS complète avec un nom de serveur valide."
    }
}

private fun m3uSection(entry: PlaylistEntry): String {
    val group = Normalizer.normalize(entry.group.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    val url = entry.url.lowercase()
    return when {
        listOf("serie", "series", "saison", "episode").any(group::contains) -> "series"
        listOf("film", "movie", "vod", "cinema").any(group::contains) ||
            listOf(".mkv", ".mp4", ".avi", ".mov", ".webm").any(url.substringBefore('?')::endsWith) -> "vod"
        else -> "live"
    }
}

@Composable
private fun Hero() {
    Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(18.dp))) {
        Image(painterResource(R.drawable.home_frontier), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color(0xAA101419)))
        Column(Modifier.align(Alignment.CenterStart).padding(26.dp)) {
            Text("WESTERN · RÉTROFUTURISTE", color = Cyan, fontSize = 11.sp, letterSpacing = 2.sp)
            Text("Le grand écran\nà ta façon.", color = Cream, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 37.sp)
            Text("TV en direct, films et séries depuis tes services.", color = Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun PlayerSettingRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = Rust, unselectedColor = Muted))
        Text(title, color = Cream, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun SettingsScreen(
    profiles: List<SourceProfile>,
    active: SourceProfile?,
    onAdd: () -> Unit,
    onSelect: (SourceProfile) -> Unit,
    onDelete: (SourceProfile) -> Unit,
    onVpn: () -> Unit,
    onCheckUpdate: () -> Unit,
    updateStatus: String?,
    playerSettings: PlayerSettings,
    onPlayerSettingsChanged: (PlayerSettings) -> Unit
) {
    val compact = isCompactLayout()
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 14.dp else 26.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsSection("Lecteur intégré", "Media3 · lecteur vidéo et audio HOME IPTV") {
            Text("Format des chaînes Xtream en direct", color = Cream, fontWeight = FontWeight.SemiBold)
            PlayerSettingRow(
                title = "MPEG-TS",
                description = "Flux direct au format .ts",
                selected = playerSettings.liveStreamFormat == LiveStreamFormat.MPEG_TS,
                onClick = { onPlayerSettingsChanged(playerSettings.copy(liveStreamFormat = LiveStreamFormat.MPEG_TS)) }
            )
            PlayerSettingRow(
                title = "HLS",
                description = "Playlist adaptative au format .m3u8",
                selected = playerSettings.liveStreamFormat == LiveStreamFormat.HLS,
                onClick = { onPlayerSettingsChanged(playerSettings.copy(liveStreamFormat = LiveStreamFormat.HLS)) }
            )
            Text(
                "Ce réglage concerne Xtream. Les liens M3U conservent leur format d’origine.",
                color = Muted, fontSize = 12.sp
            )
            HorizontalDivider(color = Color(0xFF4A4036))
            Text("Décodeur vidéo prioritaire", color = Cream, fontWeight = FontWeight.SemiBold)
            PlayerSettingRow(
                title = "Matériel",
                description = "Privilégie le décodeur vidéo du téléphone ou de la TV.",
                selected = playerSettings.decoderPreference == DecoderPreference.HARDWARE,
                onClick = { onPlayerSettingsChanged(playerSettings.copy(decoderPreference = DecoderPreference.HARDWARE)) }
            )
            PlayerSettingRow(
                title = "Logiciel",
                description = "Décode par le processeur; peut aider avec certains appareils ou flux.",
                selected = playerSettings.decoderPreference == DecoderPreference.SOFTWARE,
                onClick = { onPlayerSettingsChanged(playerSettings.copy(decoderPreference = DecoderPreference.SOFTWARE)) }
            )
            Text(
                "Si le décodeur choisi échoue au démarrage, Media3 peut essayer un autre décodeur disponible.",
                color = Muted, fontSize = 12.sp
            )
            HorizontalDivider(color = Color(0xFF4A4036))
            Text("Audio et affichage", color = Cream, fontWeight = FontWeight.SemiBold)
            Text(
                "OpenSL ES et OpenGL sont gérés par les sorties Android du lecteur intégré; ils ne sont pas réglables séparément ici.",
                color = Muted, fontSize = 12.sp
            )
        }
        SettingsSection("Mes sources", "Les identifiants Xtream restent chiffrés sur cet appareil.") {
            if (profiles.isEmpty()) {
                Text("Aucune source ajoutée.", color = Muted)
            } else profiles.forEach { profile ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF171411)).padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, color = Cream, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(profile.kind, color = Muted, fontSize = 12.sp)
                    }
                    Text(
                        if (profile.id == active?.id) "ACTIF" else "CHOISIR",
                        color = if (profile.id == active?.id) Cyan else Cream,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.tvFocusable().clickable { onSelect(profile) }.padding(8.dp)
                    )
                    Text(
                        "×", color = Muted, fontSize = 20.sp,
                        modifier = Modifier.tvFocusable().clickable { onDelete(profile) }.padding(horizontal = 8.dp)
                    )
                }
            }
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = Cream)
            ) { Text("＋  Ajouter une source") }
        }
        SettingsSection("Mises à jour") {
            Text("Vérifier les nouvelles versions du projet.", color = Muted, fontSize = 13.sp)
            OutlinedButton(onClick = onCheckUpdate, enabled = updateStatus != "Recherche de mise à jour…", modifier = Modifier.fillMaxWidth()) {
                Text("Rechercher une mise à jour")
            }
            updateStatus?.let { Text(it, color = Cyan, fontSize = 12.sp) }
        }
        SettingsSection("Confidentialité réseau") {
            Text(
                "HOME IPTV peut utiliser un VPN déjà configuré sur Android ou Fire TV. Configure-le dans les réglages système avant de lire tes flux.",
                color = Muted, fontSize = 13.sp
            )
            OutlinedButton(onClick = onVpn, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir les réglages VPN") }
        }
        Text(
            "HOME IPTV ne fournit pas de chaînes. L’app lit les flux de tes sources.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 18.dp)
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .border(1.dp, Color(0xFF41372F), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text(title, color = Cream, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, color = Muted, fontSize = 12.sp) }
        content()
    }
}

@Composable
private fun PlayerSettingRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Rust.copy(alpha = .16f) else Color(0xFF171411))
            .border(1.dp, if (selected) Rust else Color(0xFF41372F), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Rust, unselectedColor = Muted)
        )
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(title, color = Cream, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onSave: (String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("M3U") }
    var endpoint by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    val dialogTextHeight = minOf(450.dp, (LocalConfiguration.current.screenHeightDp * .55f).dp)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ajouter une source") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = dialogTextHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Nom du profil") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("M3U", "Xtream").forEach { item -> FilterChip(selected = kind == item, onClick = { kind = item }, label = { Text(item) }) }
            }
            OutlinedTextField(endpoint, { endpoint = it; validationError = null }, modifier = Modifier.fillMaxWidth(), label = { Text(if (kind == "M3U") "URL de la playlist" else "Adresse du serveur") }, singleLine = true)
            if (kind == "Xtream") {
                OutlinedTextField(user, { user = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Identifiant") }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Mot de passe") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            }
            Text("HTTP et HTTPS sont acceptés. En HTTP, les identifiants Xtream et les flux ne sont pas chiffrés.", color = Muted, fontSize = 11.sp)
            validationError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton(onClick = {
        val uri = android.net.Uri.parse(endpoint.trim())
        val validHttpUrl = (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
        validationError = when {
            name.isBlank() -> "Donne un nom à cette source."
            !validHttpUrl -> "Entre une adresse HTTP ou HTTPS complète avec un nom de serveur valide."
            kind == "Xtream" && (user.isBlank() || pass.isBlank()) -> "Renseigne l’identifiant et le mot de passe Xtream."
            else -> null
        }
        if (validationError == null) onSave(name.trim(), kind, endpoint.trim(), user.trim(), pass)
    }) { Text("Tester et ajouter") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}

@Composable
private fun CatalogScreen(
    section: String,
    sourceKind: String,
    sourceKey: String,
    favorites: List<FavoriteContent>,
    records: List<Any>,
    categories: List<XtreamCategory>,
    selectedGroup: String?,
    loading: Boolean,
    searchLoading: Boolean,
    searchResults: List<XtreamItem>,
    message: String,
    filmItem: XtreamItem?,
    filmDetails: XtreamMediaDetails?,
    m3uFilm: PlaylistEntry?,
    seriesItem: XtreamItem?,
    seriesDetails: XtreamSeriesDetails?,
    selectedSeason: String?,
    onSelectSeason: (String) -> Unit,
    onBackDetails: () -> Unit,
    onPlayFilm: (XtreamItem) -> Unit,
    onPlayM3uFilm: (PlaylistEntry) -> Unit,
    onToggleFavorite: (FavoriteContent) -> Unit,
    favoriteXtreamUrl: (String, XtreamItem) -> String,
    onSearch: (String) -> Unit,
    onSelectGroup: (String) -> Unit,
    onPlayM3u: (PlaylistEntry) -> Unit,
    onPlayXtream: (XtreamItem) -> Unit,
    onFilm: (XtreamItem) -> Unit,
    onSeries: (XtreamItem) -> Unit,
    onPlayEpisode: (XtreamEpisodeInfo) -> Unit
) {
    var searchText by remember(section) { mutableStateOf("") }
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val isTelevision = LocalContext.current.isTelevisionDevice()
    val compact = !isTelevision && screenWidth < 600
    val showingDetail = seriesItem != null || filmItem != null || m3uFilm != null
    val categoryRailWidth = if (!isTelevision && screenWidth < 700) 148.dp else 220.dp
    var variantChoices by remember(section) { mutableStateOf<List<VariantChoice>?>(null) }
    val query = normalizeSearchText(searchText.trim())
    LaunchedEffect(query, sourceKind) {
        if (sourceKind == "Xtream" && query.length >= 2) {
            delay(500)
            onSearch(query)
        } else {
            onSearch("")
        }
    }

    val m3uEntries = remember(records) { records.filterIsInstance<PlaylistEntry>() }
    val groups = if (sourceKind == "M3U") m3uEntries.map { it.group }.distinct().sorted() else categories.map { it.name }
    val filteredM3u = m3uEntries.filter { entry ->
        val groupMatches = query.isNotBlank() || selectedGroup == null || selectedGroup == entry.group
        groupMatches && (query.isBlank() || normalizeSearchText(entry.name + " " + entry.group).contains(query))
    }
    val groupedM3u = remember(filteredM3u) { groupM3uVariants(filteredM3u) }
    val displayedItems = if (query.isNotBlank()) searchResults else records.filterIsInstance<XtreamItem>()
    val groupedXtream = remember(displayedItems, section) { groupXtreamVariants(displayedItems, section) }
    val categoryName: (XtreamItem) -> String = { item ->
        categories.firstOrNull { item.categoryId in it.ids }?.name ?: selectedGroup ?: "Autres"
    }
    fun chooseM3uGroup(group: M3uVariantGroup) {
        if (group.entries.size == 1) {
            onPlayM3u(group.entries.first())
        } else {
            variantChoices = group.entries.mapIndexed { index, entry ->
                val descriptors = listOf(entry.group, qualityOrLanguageHint(entry.name)).filterNotNull().filter { it.isNotBlank() }
                VariantChoice(entry.name, descriptors.distinct().joinToString(" · ").ifBlank { "Flux alternatif ${index + 1}" }) {
                    onPlayM3u(entry)
                }
            }
        }
    }
    fun chooseXtreamGroup(group: XtreamVariantGroup) {
        if (group.items.size == 1) {
            val item = group.items.first()
            when (section) { "Séries" -> onSeries(item); "Films" -> onFilm(item); else -> onPlayXtream(item) }
        } else {
            variantChoices = group.items.mapIndexed { index, item ->
                val descriptors = listOf(
                    categoryName(item),
                    item.year,
                    item.extension?.let { it.uppercase().removePrefix(".") }
                ).filterNotNull().filter { it.isNotBlank() }
                VariantChoice(item.name, descriptors.distinct().joinToString(" · ").ifBlank { "Flux alternatif ${index + 1}" }) {
                    when (section) { "Séries" -> onSeries(item); "Films" -> onFilm(item); else -> onPlayXtream(item) }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 12.dp else 20.dp, vertical = 12.dp)) {
        if (!showingDetail && compact) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (loading || searchLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                label = { Text("Rechercher") },
                placeholder = { Text("Chaîne, film ou série") },
                trailingIcon = {
                    if (searchText.isNotEmpty()) TextButton(onClick = { searchText = "" }) { Text("×") }
                    else Text("⌕", color = Cyan, fontSize = 21.sp)
                }
            )
        } else if (!showingDetail) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.widthIn(min = 220.dp, max = 360.dp),
                    singleLine = true,
                    label = { Text("Tout rechercher") },
                    placeholder = { Text("Chaîne, film ou série") },
                    trailingIcon = {
                        if (searchText.isNotEmpty()) TextButton(onClick = { searchText = "" }) { Text("×") }
                        else Text("⌕", color = Cyan, fontSize = 21.sp)
                    }
                )
                if (loading || searchLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        if (!showingDetail) Spacer(Modifier.height(12.dp))
        @Composable
        fun CatalogContent(modifier: Modifier = Modifier) {
            Column(modifier) {
                if (seriesItem != null) {
                    SeriesDetailScreen(seriesItem, seriesDetails, selectedSeason, loading, onSelectSeason, onBackDetails, onPlayEpisode)
                } else if (filmItem != null || m3uFilm != null) {
                    FilmDetailScreen(filmItem, filmDetails, m3uFilm, loading, onBackDetails, onPlayFilm, onPlayM3uFilm)
                } else if (sourceKind == "M3U") {
                    if (filteredM3u.isEmpty() && !loading) Text(if (message.isNotBlank()) message else "Aucun contenu trouvé.", color = Muted, modifier = Modifier.padding(16.dp))
                    LazyVerticalGrid(columns = GridCells.Adaptive(if (compact) 138.dp else 128.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(groupedM3u, key = { it.key }) { group ->
                            val entry = group.entries.first()
                            val kind = when (section) { "TV en direct" -> "live"; "Séries" -> "series"; else -> "vod" }
                            val key = stableFavoriteKey(kind, entry.name, entry.group, sourceKey)
                            CatalogGridCard(entry.name, entry.group, entry.logo, landscape = section == "TV en direct", variantCount = group.entries.size,
                                favorite = favorites.any { it.key == key }, onToggleFavorite = {
                                    onToggleFavorite(FavoriteContent(key, kind, entry.name, entry.group, entry.logo, entry.url, entry.group, sourceKey))
                                }) {
                                chooseM3uGroup(group)
                            }
                        }
                    }
                } else {
                    if (displayedItems.isEmpty() && !loading && !searchLoading) {
                        val text = when {
                            sourceKind.isBlank() -> "Ajoute une source dans Paramètres pour charger les chaînes et les catégories."
                            message.isNotBlank() -> message
                            query.isNotBlank() -> "Aucun contenu trouvé."
                            selectedGroup == null -> "Choisis une catégorie pour afficher ses contenus."
                            else -> "Cette catégorie ne contient aucun contenu."
                        }
                        Text(text, color = Muted, modifier = Modifier.padding(16.dp))
                    } else {
                        LazyVerticalGrid(columns = GridCells.Adaptive(if (compact) 138.dp else 128.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(groupedXtream, key = { it.key }) { group ->
                                val item = group.items.first()
                                val category = categoryName(item)
                                val kind = when (section) { "TV en direct" -> "live"; "Séries" -> "series"; else -> "vod" }
                                val key = stableFavoriteKey(kind, item.name, category, sourceKey)
                                val subtitle = if (query.isNotBlank()) category else (item.year ?: category)
                                CatalogGridCard(item.name, subtitle, item.logo, landscape = section == "TV en direct", variantCount = group.items.size,
                                    favorite = favorites.any { it.key == key }, onToggleFavorite = {
                                        val contentUrl = if (kind == "series") "series:${item.id}" else favoriteXtreamUrl(kind, item)
                                        onToggleFavorite(FavoriteContent(key, kind, item.name, item.year ?: category, item.logo,
                                            contentUrl, category, sourceKey))
                                    }) {
                                    chooseXtreamGroup(group)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showingDetail) {
            CatalogContent(Modifier.weight(1f).fillMaxWidth())
        } else if (compact) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text("CATÉGORIES", color = Cyan, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    if (sourceKind == "M3U") {
                        CategoryChip("Toutes", selectedGroup == null, query.isNotBlank()) { onSelectGroup("") }
                        groups.forEach { group -> CategoryChip(group, selectedGroup == group && query.isBlank(), false) { onSelectGroup(group) } }
                    } else {
                        categories.forEach { category -> CategoryChip(category.name, selectedGroup == category.name && query.isBlank(), false) { onSelectGroup(category.name) } }
                    }
                    if (groups.isEmpty() && categories.isEmpty()) Text(
                        if (sourceKind.isBlank()) "Source à configurer dans Paramètres" else "Aucune catégorie reçue",
                        color = Muted, fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
                CatalogContent(Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.width(categoryRailWidth).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1B1713))
                        .verticalScroll(rememberScrollState()).padding(12.dp)
                ) {
                    Text("CATÉGORIES", color = Cyan, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                    if (sourceKind == "M3U") {
                        CategoryButton("Toutes", selectedGroup == null, query.isNotBlank()) { onSelectGroup("") }
                        groups.forEach { group -> CategoryButton(group, selectedGroup == group && query.isBlank(), false) { onSelectGroup(group) } }
                    } else {
                        categories.forEach { category -> CategoryButton(category.name, selectedGroup == category.name && query.isBlank(), false) { onSelectGroup(category.name) } }
                    }
                    if (groups.isEmpty() && categories.isEmpty()) Text(
                        if (sourceKind.isBlank()) "Source à configurer dans Paramètres" else "Aucune catégorie reçue",
                        color = Muted, modifier = Modifier.padding(8.dp)
                    )
                }
                CatalogContent(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
    variantChoices?.let { choices ->
        AlertDialog(
            onDismissRequest = { variantChoices = null },
            title = { Text("Plusieurs versions disponibles") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    choices.forEach { choice ->
                        TextButton(
                            onClick = { variantChoices = null; choice.onSelect() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(choice.title, color = Cream, fontWeight = FontWeight.SemiBold)
                                Text(choice.details, color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { variantChoices = null }) { Text("Fermer") } }
        )
    }
}


@Composable
private fun BackToCategoriesButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp),
        shape = RoundedCornerShape(9.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Rust),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Rust),
        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp)
    ) {
        Text("←  $label", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FilmDetailScreen(
    item: XtreamItem?,
    details: XtreamMediaDetails?,
    m3uItem: PlaylistEntry?,
    loading: Boolean,
    onBack: () -> Unit,
    onPlayXtream: (XtreamItem) -> Unit,
    onPlayM3u: (PlaylistEntry) -> Unit
) {
    val title = details?.title ?: item?.name ?: m3uItem?.name.orEmpty()
    val poster = details?.poster ?: item?.logo ?: m3uItem?.logo
    val plot = details?.plot ?: item?.plot
    val compact = isCompactLayout()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackToCategoriesButton("Films", onBack)
            Spacer(Modifier.weight(1f))
        }
        if (loading && details == null && item != null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Rust)
        if (compact) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    NetworkArtwork(poster, Modifier.width(112.dp).height(164.dp), landscape = false)
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Cream, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        val metadata = listOfNotNull(details?.releaseDate ?: item?.year, details?.duration, details?.genre)
                            .filter { it.isNotBlank() }.joinToString("  ·  ")
                        if (metadata.isNotBlank()) Text(metadata, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                        details?.rating?.takeIf(String::isNotBlank)?.let { Text("★  $it", color = Cyan, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp)) }
                        details?.director?.takeIf(String::isNotBlank)?.let { DetailLine("Réalisation", it) }
                    }
                }
                details?.cast?.takeIf(String::isNotBlank)?.let { DetailLine("Avec", it) }
                SynopsisPanel(plot, compact = true, loading = loading, modifier = Modifier.padding(top = 8.dp))
            }
            Button(
                onClick = { item?.let(onPlayXtream); m3uItem?.let(onPlayM3u) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = Cream)
            ) { Text("▶  Lire le film") }
        } else {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                NetworkArtwork(poster, Modifier.width(145.dp).fillMaxHeight(.72f), landscape = false)
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 12.dp)) {
                    Text(title, color = Cream, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    val metadata = listOfNotNull(details?.releaseDate ?: item?.year, details?.duration, details?.genre)
                        .filter { it.isNotBlank() }.joinToString("  ·  ")
                    if (metadata.isNotBlank()) Text(metadata, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    details?.rating?.takeIf(String::isNotBlank)?.let { Text("★  $it", color = Cyan, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp)) }
                    details?.director?.takeIf(String::isNotBlank)?.let { DetailLine("Réalisation", it) }
                    details?.cast?.takeIf(String::isNotBlank)?.let { DetailLine("Avec", it) }
                    SynopsisPanel(plot, compact = false, loading = loading, modifier = Modifier.padding(top = 12.dp))
                    Button(
                        onClick = { item?.let(onPlayXtream); m3uItem?.let(onPlayM3u) },
                        modifier = Modifier.padding(top = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = Cream)
                    ) { Text("▶  Lire le film") }
                }
            }
        }
    }
    }
}

@Composable
private fun SeriesDetailScreen(
    item: XtreamItem,
    details: XtreamSeriesDetails?,
    selectedSeason: String?,
    loading: Boolean,
    onSelectSeason: (String) -> Unit,
    onBack: () -> Unit,
    onPlayEpisode: (XtreamEpisodeInfo) -> Unit
) {
    val media = details?.media
    val compact = isCompactLayout()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackToCategoriesButton("Séries", onBack)
            Spacer(Modifier.weight(1f))
        }
        if (loading && details == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Rust)
        if (compact) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    NetworkArtwork(media?.poster ?: item.logo, Modifier.width(78.dp).height(112.dp), landscape = false)
                    Column(Modifier.weight(1f)) {
                        Text(media?.title ?: item.name, color = Cream, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                        val metadata = listOfNotNull(media?.releaseDate ?: item.year, media?.genre)
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        if (metadata.isNotBlank()) Text(metadata, color = Muted, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                        media?.rating?.takeIf(String::isNotBlank)?.let { Text("★  $it", color = Cyan, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp)) }
                        media?.director?.takeIf(String::isNotBlank)?.let { Text("Réalisation : $it", color = Muted, fontSize = 10.sp, maxLines = 1) }
                        media?.cast?.takeIf(String::isNotBlank)?.let { Text("Avec : $it", color = Muted, fontSize = 10.sp, maxLines = 1) }
                    }
                }
                val compactPlot = media?.plot ?: item.plot
                SynopsisPanel(compactPlot, compact = true, loading = loading, modifier = Modifier.padding(top = 5.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    details?.seasons.orEmpty().forEach { season ->
                        TextButton(
                            onClick = { onSelectSeason(season.number) },
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (selectedSeason == season.number) Rust else Panel)
                        ) { Text("Saison ${season.number}", color = Cream, fontSize = 11.sp) }
                    }
                }
                val episodes = details?.seasons?.firstOrNull { it.number == selectedSeason }?.episodes.orEmpty()
                if (episodes.isEmpty() && !loading) Text("Aucun épisode dans cette saison.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(episodes, key = { "${selectedSeason}-${it.item.id}" }) { episode ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel)
                                .border(1.dp, Color(0xFF3D352D), RoundedCornerShape(9.dp))
                                .tvFocusable().clickable { onPlayEpisode(episode) }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NetworkArtwork(episode.thumbnail, Modifier.width(90.dp).height(54.dp), landscape = true)
                            Column(Modifier.weight(1f)) {
                                Text("S${selectedSeason ?: "?"}E${episode.number} · ${episode.item.name}", color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2)
                                val sub = listOfNotNull(episode.duration, episode.item.plot).joinToString(" · ")
                                if (sub.isNotBlank()) Text(sub, color = Muted, fontSize = 10.sp, maxLines = 1)
                            }
                            Text("▶", color = Rust, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 5.dp))
                        }
                    }
                }
            }
        } else {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NetworkArtwork(media?.poster ?: item.logo, Modifier.width(if (compact) 92.dp else 132.dp).height(if (compact) 130.dp else 185.dp), landscape = false)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(media?.title ?: item.name, color = Cream, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                val metadata = listOfNotNull(media?.releaseDate ?: item.year, media?.genre)
                    .filter { it.isNotBlank() }.joinToString("  ·  ")
                if (metadata.isNotBlank()) Text(metadata, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                media?.rating?.takeIf(String::isNotBlank)?.let { Text("★  $it", color = Cyan, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                media?.director?.takeIf(String::isNotBlank)?.let { DetailLine("Réalisation", it) }
                media?.cast?.takeIf(String::isNotBlank)?.let { DetailLine("Avec", it) }
                val plot = media?.plot ?: item.plot
                if (!plot.isNullOrBlank()) SynopsisPanel(plot, compact = false, loading = loading, modifier = Modifier.padding(top = 7.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    details?.seasons.orEmpty().forEach { season ->
                        TextButton(
                            onClick = { onSelectSeason(season.number) },
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (selectedSeason == season.number) Rust else Panel)
                        ) { Text("Saison ${season.number}", color = Cream, fontSize = 12.sp) }
                    }
                }
                val episodes = details?.seasons?.firstOrNull { it.number == selectedSeason }?.episodes.orEmpty()
                if (episodes.isEmpty() && !loading) Text("Aucun épisode trouvé pour cette saison.", color = Muted, modifier = Modifier.padding(top = 8.dp))
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(episodes, key = { "${selectedSeason}-${it.item.id}" }) { episode ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel)
                                .border(1.dp, Color(0xFF3D352D), RoundedCornerShape(9.dp))
                                .tvFocusable().clickable { onPlayEpisode(episode) }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            NetworkArtwork(episode.thumbnail, Modifier.width(112.dp).height(64.dp), landscape = true)
                            Column(Modifier.weight(1f)) {
                                Text("S${selectedSeason ?: "?"}E${episode.number} · ${episode.item.name}", color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                                val sub = listOfNotNull(episode.duration, episode.item.plot).joinToString(" · ")
                                if (sub.isNotBlank()) Text(sub, color = Muted, fontSize = 11.sp, maxLines = 2)
                            }
                            Text("▶", color = Rust, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
        }
        }
    }
    }
}

@Composable
private fun SynopsisPanel(
    synopsis: String?,
    compact: Boolean,
    loading: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(1.dp, Color(0xFF4A4036), RoundedCornerShape(10.dp))
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 8.dp else 10.dp)
    ) {
        Text("Synopsis", color = Cyan, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.SemiBold)
        if (!synopsis.isNullOrBlank()) {
            Text(
                synopsis,
                color = Cream.copy(alpha = .92f),
                fontSize = if (compact) 12.sp else 14.sp,
                lineHeight = if (compact) 17.sp else 20.sp,
                modifier = Modifier.fillMaxWidth()
                    .then(if (compact) Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState()) else Modifier)
                    .padding(top = 4.dp)
            )
        } else if (!loading) {
            Text("Aucun synopsis fourni par cette source.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Text("$label : $value", color = Muted, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 5.dp))
}

@Composable
private fun CategoryButton(title: String, selected: Boolean, disabled: Boolean, onClick: () -> Unit) {
    Text(
        title,
        color = if (selected) Cream else Muted,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(if (selected) Rust.copy(alpha = .34f) else Color.Transparent)
            .tvFocusable().clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp)
    )
}

@Composable
private fun Modifier.tvFocusable(onFocused: (Boolean) -> Unit = {}): Modifier = composed {
    val isTelevision = LocalContext.current.isTelevisionDevice()
    var focused by remember { mutableStateOf(false) }
    this.onFocusChanged {
        focused = isTelevision && it.isFocused
        onFocused(focused)
    }.drawWithContent {
            drawContent()
            if (focused) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2f
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(9.dp.toPx(), 9.dp.toPx()),
                    style = Stroke(width = stroke)
                )
            }
        }
}

@Composable
private fun HeaderNavigation(
    section: String,
    onNavigate: (String) -> Unit
) {
    val compact = isCompactLayout()
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF12110F))
            .padding(horizontal = if (compact) 8.dp else 18.dp, vertical = if (compact) 5.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 12.dp)
    ) {
        Image(
            painterResource(R.drawable.home_iptv_mark),
            "HOME IPTV",
            Modifier.size(if (compact) 48.dp else 60.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit
        )
        HeaderSeparator(compact)
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 9.dp)
        ) {
            listOf("Accueil", "TV", "Films", "Séries").forEach { label ->
                val destination = if (label == "TV") "TV en direct" else label
                val selected = destination == section
                val focused = remember(label) { mutableStateOf(false) }
                Text(
                    label,
                    color = if (selected || focused.value) Cream else Muted,
                    fontSize = if (compact) 11.sp else 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(9.dp))
                        .background(
                            when {
                                selected -> Rust
                                focused.value -> Rust.copy(alpha = .16f)
                                else -> Color.Transparent
                            }
                        )
                        .tvFocusable { focused.value = it }.clickable { onNavigate(destination) }
                        .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 10.dp else 12.dp)
                )
                if (label != "Séries") HeaderSeparator(compact)
            }
        }
        HeaderSeparator(compact)
        Text(
            "⚙",
            color = if (section == "Paramètres") Rust else Cream,
            fontSize = if (compact) 21.sp else 26.sp,
            modifier = Modifier.clip(RoundedCornerShape(9.dp)).tvFocusable()
                .clickable { onNavigate("Paramètres") }
                .padding(horizontal = if (compact) 7.dp else 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun HeaderSeparator(compact: Boolean) {
    Box(Modifier.width(1.dp).height(if (compact) 22.dp else 28.dp).background(Rust.copy(alpha = .72f)))
}

@Composable
private fun SectionHeader(section: String) {
    val heading = when (section) {
        "Accueil" -> "Le grand écran, à ta façon."
        "TV en direct" -> "Télévision en direct"
        "Films" -> "Films"
        "Séries" -> "Séries"
        "Favoris" -> "Tes favoris"
        "Historique" -> "Récemment regardé"
        "Paramètres" -> "Paramètres"
        else -> "HOME IPTV"
    }
    val compact = isCompactLayout()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L)
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .height(if (compact) 42.dp else 48.dp)
            .padding(horizontal = if (compact) 14.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            heading,
            color = Cream,
            fontSize = if (compact) 17.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)),
            color = Muted,
            fontSize = if (compact) 11.sp else 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun HomeDashboard(
    inProgress: List<RecentPlayback>,
    noSource: Boolean,
    favoriteCount: Int,
    historyCount: Int,
    onOpenFavorites: () -> Unit,
    onOpenHistory: (String?) -> Unit,
    onResume: (RecentPlayback) -> Unit
) {
    val compact = isCompactLayout()
    val recentCardWidth = if (compact) minOf(278.dp, (LocalConfiguration.current.screenWidthDp - 40).dp) else 220.dp
    val films = inProgress.filter { it.kind == "vod" }
    val series = inProgress.filter { it.kind == "series" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (compact) 14.dp else 20.dp, vertical = 12.dp)) {
        Text("À regarder", color = Cream, fontSize = if (compact) 21.sp else 23.sp, fontWeight = FontWeight.Bold)
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            DashboardActionButton("♥", "Favoris", favoriteCount, onOpenFavorites)
            DashboardActionButton("◷", "Historique · Lecture en cours", historyCount, { onOpenHistory(null) })
        }
        if (noSource) {
            Text("Ajoute une source dans ⚙ Paramètres pour commencer.", color = Muted, modifier = Modifier.padding(top = 8.dp))
        } else if (films.isEmpty() && series.isEmpty()) {
            Text("Les films et séries en cours apparaîtront ici après quelques instants de lecture.", color = Muted, modifier = Modifier.padding(top = 12.dp))
        } else {
            if (films.isNotEmpty()) ResumePreviewGroup(
                title = "Films en cours", entries = films, width = recentCardWidth,
                onResume = onResume, onSeeMore = { onOpenHistory("vod") }
            )
            if (series.isNotEmpty()) ResumePreviewGroup(
                title = "Séries en cours", entries = series, width = recentCardWidth,
                onResume = onResume, onSeeMore = { onOpenHistory("series") }
            )
        }
    }
}

@Composable
private fun ResumePreviewGroup(
    title: String,
    entries: List<RecentPlayback>,
    width: Dp,
    onResume: (RecentPlayback) -> Unit,
    onSeeMore: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Cream, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (entries.size > 1) TextButton(onClick = onSeeMore) {
                Text("Voir la suite  ›", color = Rust, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            entries.take(1).forEach { item -> RecentCard(item, width = width, onClick = { onResume(item) }) }
        }
    }
}

@Composable
private fun DashboardActionButton(icon: String, label: String, count: Int, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF51463C)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Panel, contentColor = Cream),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = Cyan, fontSize = 21.sp, modifier = Modifier.padding(end = 12.dp))
            Text(label, color = Cream, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(count.toString(), color = Cyan, fontWeight = FontWeight.Bold)
            Text("  ›", color = Muted, fontSize = 20.sp)
        }
    }
}

@Composable
private fun SavedLibraryScreen(
    title: String,
    subtitle: String,
    entries: List<RecentPlayback>,
    favoriteCounts: Map<String, Int> = emptyMap(),
    initialHistoryKind: String? = null,
    emptyMessage: String,
    onPlay: (RecentPlayback) -> Unit,
    onRemove: ((RecentPlayback) -> Unit)? = null,
    onOpenSeries: (RecentPlayback) -> Unit
) {
    val compact = isCompactLayout()
    var selectedFavoriteKind by remember(title, entries) { mutableStateOf(entries.firstOrNull()?.kind ?: "live") }
    var selectedHistoryKind by remember(title, initialHistoryKind) { mutableStateOf(initialHistoryKind) }
    val showingFavorites = title == "Mes favoris"
    val showingHistory = title == "Historique"
    val visibleEntries = when {
        showingFavorites -> entries.filter { it.kind == selectedFavoriteKind }
        showingHistory && selectedHistoryKind != null -> entries.filter { it.kind == selectedHistoryKind }
        else -> entries
    }
    Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 14.dp else 22.dp, vertical = 12.dp)) {
        Text(title, color = Cream, fontSize = if (compact) 21.sp else 25.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        if (showingFavorites) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("live" to "TV", "vod" to "Films", "series" to "Séries").forEach { (kind, label) ->
                    FilterChip(
                        selected = selectedFavoriteKind == kind,
                        onClick = { selectedFavoriteKind = kind },
                        label = { Text("$label · ${favoriteCounts[kind] ?: entries.count { it.kind == kind }}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Rust, selectedLabelColor = Cream,
                            containerColor = Panel, labelColor = Muted
                        )
                    )
                }
            }
        }
        if (showingHistory) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "Tout", "vod" to "Films", "series" to "Séries").forEach { (kind, label) ->
                    val count = if (kind == null) entries.size else entries.count { it.kind == kind }
                    FilterChip(
                        selected = selectedHistoryKind == kind,
                        onClick = { selectedHistoryKind = kind },
                        label = { Text("$label · $count") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Rust, selectedLabelColor = Cream,
                            containerColor = Panel, labelColor = Muted
                        )
                    )
                }
            }
        }
        if (visibleEntries.isEmpty()) {
            Text(if (showingFavorites && entries.isNotEmpty()) "Aucun favori dans cette catégorie." else emptyMessage,
                color = Muted, modifier = Modifier.padding(top = 12.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (compact) 140.dp else 160.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(visibleEntries, key = { index, entry -> "${entry.kind}|${entry.title}|${entry.subtitle}|${entry.url}|$index" }) { _, entry ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(8.dp)) {
                        CatalogGridCard(entry.title, entry.subtitle, entry.image, landscape = entry.kind == "live") {
                            if (entry.kind == "series" && entry.url.startsWith("series:")) onOpenSeries(entry) else onPlay(entry)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (entry.kind) { "live" -> "TV"; "series" -> "SÉRIE"; else -> "FILM" },
                                color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                            )
                            onRemove?.let { remove ->
                                TextButton(onClick = { remove(entry) }) { Text("Retirer", color = Muted, fontSize = 11.sp) }
                            }
                        }
                        if (entry.kind != "live" && entry.positionMs > 0L) {
                            val episode = Regex("(?i)S(\\d+)E(\\d+)").find(entry.subtitle)
                            val episodeLabel = episode?.let { "Saison ${it.groupValues[1]} · Épisode ${it.groupValues[2]}" }
                            Text(
                                episodeLabel ?: "Position ${entry.positionMs / 3_600_000L}h ${(entry.positionMs / 60_000L) % 60}min",
                                color = Muted, fontSize = 10.sp
                            )
                            if (episode != null) {
                                val episodeTitle = entry.subtitle.substringAfter("·", "").trim()
                                if (episodeTitle.isNotBlank()) Text(episodeTitle, color = Muted, fontSize = 10.sp, maxLines = 1)
                            }
                            TextButton(onClick = { onPlay(entry) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                                Text("▶  Reprendre", color = Rust, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCard(item: RecentPlayback, width: Dp, onClick: () -> Unit) {
    val label = when (item.kind) { "live" -> "DERNIÈRE CHAÎNE"; "series" -> "REPRENDRE LA SÉRIE"; else -> "REPRENDRE LE FILM" }
    Column(
        Modifier.width(width).clip(RoundedCornerShape(14.dp)).background(Panel).tvFocusable().clickable(onClick = onClick).padding(10.dp)
    ) {
        NetworkArtwork(item.image, Modifier.fillMaxWidth().height(132.dp), landscape = item.kind == "live")
        Text(label, color = Cyan, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 9.dp))
        Text(item.title, color = Cream, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
        if (item.subtitle.isNotBlank()) Text(item.subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        if (item.kind != "live" && item.positionMs > 0L) {
            val minutes = item.positionMs / 60_000L
            Text("Reprendre à ${minutes / 60}h ${minutes % 60}min", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun CatalogGridCard(
    title: String,
    subtitle: String,
    image: String?,
    landscape: Boolean,
    variantCount: Int = 1,
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
        .border(1.dp, Color(0xFF302A24), RoundedCornerShape(12.dp))
        .tvFocusable().clickable(onClick = onClick).padding(8.dp)) {
        Box(Modifier.fillMaxWidth().height(if (landscape) 82.dp else 146.dp).clip(RoundedCornerShape(9.dp))) {
            NetworkArtwork(image, Modifier.fillMaxSize(), landscape)
            if (variantCount > 1) {
                Text(
                    "$variantCount versions",
                    color = Cream,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Ink.copy(alpha = .9f))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
            onToggleFavorite?.let { toggle ->
                Text(
                    if (favorite) "♥" else "♡",
                    color = if (favorite) Rust else Cream,
                    fontSize = 20.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .clip(RoundedCornerShape(20.dp)).background(Ink.copy(alpha = .88f))
                        .tvFocusable().clickable(onClick = toggle).padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Text(title, color = Cream, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 7.dp))
        if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun NetworkArtwork(url: String?, modifier: Modifier = Modifier, landscape: Boolean) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = if (url.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching { loadArtwork(url)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF29231E)), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().then(if (landscape) Modifier.padding(8.dp) else Modifier),
                contentScale = if (landscape) ContentScale.Fit else ContentScale.Crop
            )
        }
        if (bitmap == null) Text(if (landscape) "▶" else "HOME IPTV", color = if (landscape) Rust else Muted, fontSize = if (landscape) 30.sp else 11.sp)
    }
}

private fun loadArtwork(url: String): Bitmap? {
    artworkCache.get(url)?.let { return it }
    val connection = (URL(url).openConnection() as? HttpURLConnection) ?: return null
    connection.connectTimeout = 6000
    connection.readTimeout = 6000
    connection.setRequestProperty("User-Agent", "HOME IPTV Android")
    try {
        val code = connection.responseCode
        if (code !in 200..299) return null
        if ((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L) > 2L * 1024 * 1024) return null
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 2 * 1024 * 1024) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = maxOf(1, maxOf(bounds.outWidth / 600, bounds.outHeight / 700))
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.also { artworkCache.put(url, it) }
    } catch (_: Exception) {
        return null
    } finally { connection.disconnect() }
}

private val variantTagPattern = Regex("(?i)\\b(?:full\\s*hd|8k|4k|uhd|fhd|hd|sd|2160p|1080p|720p|480p|hevc|h\\.?265|x265)\\b")

private fun variantTitleKey(value: String): String =
    normalizeSearchText(value.replace(variantTagPattern, " "))
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun qualityOrLanguageHint(value: String): String? =
    variantTagPattern.find(value)?.value?.uppercase()?.replace(Regex("[^A-Z0-9]"), "")?.let { tag ->
        when (tag) {
            "8K" -> "8K"
            "4K", "UHD", "2160P" -> "4K"
            "FULLHD", "FHD", "1080P" -> "FHD"
            "HD", "720P" -> "HD"
            "SD", "480P" -> "SD"
            else -> null // Codec labels such as HEVC are not separate quality choices.
        }
    }

private val genericVariantTitles = setOf("", "chaine", "sans titre", "untitled", "unknown")

private fun groupM3uVariants(entries: List<PlaylistEntry>): List<M3uVariantGroup> =
    entries.distinctBy { entry ->
        val base = variantTitleKey(entry.name)
        "$base|${normalizeSearchText(entry.group)}|${qualityOrLanguageHint(entry.name).orEmpty()}"
    }.groupBy { entry ->
        val titleKey = variantTitleKey(entry.name)
        entry.tvgId?.trim()?.takeIf { it.isNotBlank() }
            ?.let { "id:${normalizeSearchText(it)}" }
            ?: if (titleKey in genericVariantTitles) "url:${entry.url}" else "title:$titleKey"
    }.map { (key, variants) -> M3uVariantGroup(key, variants) }

private fun groupXtreamVariants(items: List<XtreamItem>, section: String): List<XtreamVariantGroup> =
    items.distinctBy { item ->
        val base = variantTitleKey(item.name)
        "$base|${item.year.orEmpty()}|${qualityOrLanguageHint(item.name).orEmpty()}"
    }.groupBy { item ->
        val titleKey = variantTitleKey(item.name)
        if (titleKey in genericVariantTitles) "id:${item.id}" else {
            val year = if (section == "TV en direct") "" else item.year.orEmpty()
            "title:$titleKey|year:$year"
        }
    }.map { (key, variants) -> XtreamVariantGroup(key, variants) }

private fun normalizeSearchText(value: String): String =
    Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

@Composable
private fun CategoryChip(title: String, selected: Boolean, disabled: Boolean, onClick: () -> Unit) {
    Text(
        title,
        color = if (selected) Cream else Muted,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) Rust else Panel)
            .tvFocusable().clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp)
    )
}

@Composable
private fun CatalogRow(title: String, subtitle: String, logo: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).tvFocusable().clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = Cream, fontWeight = FontWeight.Medium); if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 11.sp) }
        Text(if (logo.isNullOrBlank()) "▶" else "▶", color = Rust, fontSize = 18.sp)
    }
}
