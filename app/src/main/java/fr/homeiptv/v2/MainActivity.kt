package fr.homeiptv.v2

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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

private val Ink = Color(0xFF101419)
private val Panel = Color(0xFF1C2329)
private val Cream = Color(0xFFF2EBDD)
private val Muted = Color(0xFFABB4B8)
private val Rust = Color(0xFFC95132)
private val Cyan = Color(0xFF65C9D7)

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableStateOf(0)
    private lateinit var sourceStore: SourceStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceStore = SourceStore(this)
        setContent { HomeApp(sourceStore, resumeTick.value) }
    }

    override fun onResume() {
        super.onResume()
        if (::sourceStore.isInitialized) resumeTick.value++
    }
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
    var seriesEpisodes by remember { mutableStateOf<List<XtreamItem>?>(null) }
    var selectedSeries by remember { mutableStateOf<XtreamItem?>(null) }
    var recentContent by remember { mutableStateOf(store.recents()) }
    var searchResults by remember { mutableStateOf<List<XtreamItem>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as Activity
    val selectedTab = when (section) { "Films" -> "vod"; "Séries" -> "series"; else -> "live" }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var installingUpdate by remember { mutableStateOf(false) }

    LaunchedEffect(resumeTick) { recentContent = store.recents() }

    LaunchedEffect(Unit) {
        checkingUpdate = true
        availableUpdate = runCatching { UpdateManager.findUpdate() }.getOrNull()
        checkingUpdate = false
    }

    LaunchedEffect(active?.id, selectedTab) {
        records = emptyList()
        selectedGroup = null
        categoryOpen = false
        xtreamCategories = emptyList()
        seriesEpisodes = null
        selectedSeries = null
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
            }
        } catch (e: Exception) { message = e.message ?: "Impossible de charger cette source." }
        loading = false
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Rust, onPrimary = Cream, onBackground = Cream, onSurface = Cream, secondary = Cyan)) {
        Column(Modifier.fillMaxSize().background(Ink)) {
            HeaderNavigation(section, active?.name ?: "Aucune source", onNavigate = { section = it })
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
                        val result = runCatching { UpdateManager.findUpdate() }
                        checkingUpdate = false
                        availableUpdate = result.getOrNull()
                        if (result.isFailure) updateMessage = "Impossible de vérifier les mises à jour. Réessaie plus tard."
                        else if (availableUpdate == null) updateMessage = "Tu utilises déjà la dernière version publiée."
                    }
                }, updateStatus = when {
                    checkingUpdate -> "Recherche de mise à jour…"
                    availableUpdate != null -> "Version ${availableUpdate!!.versionName} disponible."
                    else -> null
                })
                "Accueil" -> HomeDashboard(recentContent, active == null, onResume = { recent ->
                    activity.play(recent.url, recent.kind, recent.title, recent.subtitle, recent.image, recent.category, recent.positionMs)
                })
                else -> CatalogScreen(
                    section = section,
                    sourceKind = active?.kind ?: "",
                    records = records,
                    categories = xtreamCategories,
                    selectedGroup = selectedGroup,
                    loading = loading,
                    searchLoading = searchLoading,
                    searchResults = searchResults,
                    message = message,
                    episodes = seriesEpisodes,
                    seriesTitle = selectedSeries?.name,
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
                        selectedGroup = name
                        seriesEpisodes = null
                        selectedSeries = null
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
                        activity.play(entry.url, selectedTab, entry.name, entry.group, entry.logo, entry.group)
                    },
                    onPlayXtream = { item ->
                        val kind = selectedTab
                        val p = active
                        if (p != null) activity.play(
                            XtreamClient(p.endpoint, p.user, p.password).streamUrl(kind, item),
                            kind, item.name, item.year ?: "", item.logo,
                            xtreamCategories.firstOrNull { item.categoryId in it.ids }?.name.orEmpty()
                        )
                    },
                    onSeries = { item ->
                        val p = active
                        selectedSeries = item
                        if (p != null) scope.launch {
                            loading = true
                            try { seriesEpisodes = XtreamClient(p.endpoint, p.user, p.password).episodes(item.id) }
                            catch (e: Exception) { message = e.message ?: "Impossible de charger les épisodes." }
                            loading = false
                        }
                    },
                    onBackEpisodes = { seriesEpisodes = null; selectedSeries = null },
                    onPlayEpisode = { episode ->
                        val p = active
                        val series = selectedSeries
                        if (p != null && series != null) activity.play(
                            XtreamClient(p.endpoint, p.user, p.password).streamUrl("series", episode),
                            "series", series.name, episode.name, series.logo,
                            xtreamCategories.firstOrNull { series.categoryId in it.ids }?.name.orEmpty()
                        )
                    }
                )
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
    category: String = "", positionMs: Long = 0L
) {
    startActivity(Intent(this, PlaybackActivity::class.java)
        .putExtra(PlaybackActivity.EXTRA_URL, url)
        .putExtra(PlaybackActivity.EXTRA_KIND, kind)
        .putExtra(PlaybackActivity.EXTRA_TITLE, title)
        .putExtra(PlaybackActivity.EXTRA_SUBTITLE, subtitle)
        .putExtra(PlaybackActivity.EXTRA_IMAGE, image)
        .putExtra(PlaybackActivity.EXTRA_CATEGORY, category)
        .putExtra(PlaybackActivity.EXTRA_POSITION, positionMs))
}

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
private fun SettingsScreen(profiles: List<SourceProfile>, active: SourceProfile?, onAdd: () -> Unit, onSelect: (SourceProfile) -> Unit, onDelete: (SourceProfile) -> Unit, onVpn: () -> Unit, onCheckUpdate: () -> Unit, updateStatus: String?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Mes sources", color = Cream, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Les identifiants restent chiffrés sur cet appareil.", color = Muted, modifier = Modifier.padding(top = 5.dp, bottom = 15.dp))
        profiles.forEach { profile ->
            Row(Modifier.fillMaxWidth().padding(bottom = 9.dp).clip(RoundedCornerShape(12.dp)).background(Panel).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(profile.name, color = Cream, fontWeight = FontWeight.SemiBold); Text(profile.kind, color = Muted, fontSize = 12.sp) }
                Text(if (profile.id == active?.id) "ACTIF" else "CHOISIR", color = if (profile.id == active?.id) Cyan else Cream, modifier = Modifier.clickable { onSelect(profile) }.focusable().padding(8.dp))
                Text("×", color = Muted, modifier = Modifier.clickable { onDelete(profile) }.focusable().padding(8.dp))
            }
        }
        Button(onClick = onAdd) { Text("＋ Ajouter une source") }
        OutlinedButton(onClick = onCheckUpdate, enabled = updateStatus != "Recherche de mise à jour…") {
            Text("Rechercher une mise à jour")
        }
        updateStatus?.let { Text(it, color = Cyan, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        Text("Confidentialité réseau", color = Cream, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
        Text("HOME IPTV peut utiliser un VPN déjà configuré sur Android ou Fire TV. Configure-le dans les réglages système avant de lire tes flux.", color = Muted, modifier = Modifier.padding(top = 5.dp, bottom = 10.dp))
        OutlinedButton(onClick = onVpn) { Text("Ouvrir les réglages VPN") }
        Text("HOME IPTV ne fournit pas de chaînes. L’app lit les flux de ta source.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ajouter une source") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nom du profil") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("M3U", "Xtream").forEach { item -> FilterChip(selected = kind == item, onClick = { kind = item }, label = { Text(item) }) }
            }
            OutlinedTextField(endpoint, { endpoint = it; validationError = null }, label = { Text(if (kind == "M3U") "URL de la playlist" else "Adresse du serveur") }, singleLine = true)
            if (kind == "Xtream") {
                OutlinedTextField(user, { user = it }, label = { Text("Identifiant") }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, label = { Text("Mot de passe") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
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
    records: List<Any>,
    categories: List<XtreamCategory>,
    selectedGroup: String?,
    loading: Boolean,
    searchLoading: Boolean,
    searchResults: List<XtreamItem>,
    message: String,
    episodes: List<XtreamItem>?,
    seriesTitle: String?,
    onSearch: (String) -> Unit,
    onSelectGroup: (String) -> Unit,
    onPlayM3u: (PlaylistEntry) -> Unit,
    onPlayXtream: (XtreamItem) -> Unit,
    onSeries: (XtreamItem) -> Unit,
    onBackEpisodes: () -> Unit,
    onPlayEpisode: (XtreamItem) -> Unit
) {
    var searchText by remember(section) { mutableStateOf("") }
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
    val categoryName: (XtreamItem) -> String = { item ->
        categories.firstOrNull { item.categoryId in it.ids }?.name ?: selectedGroup ?: "Autres"
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(section, color = Cream, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.width(220.dp).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Color(0xFF151B20))
                    .verticalScroll(rememberScrollState()).padding(12.dp)
            ) {
                Text("CATÉGORIES", color = Cyan, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                if (sourceKind == "M3U") {
                    CategoryButton("Toutes", selectedGroup == null, query.isNotBlank()) { onSelectGroup("") }
                    groups.forEach { group -> CategoryButton(group, selectedGroup == group && query.isBlank(), false) { onSelectGroup(group) } }
                } else {
                    categories.forEach { category -> CategoryButton(category.name, selectedGroup == category.name && query.isBlank(), false) { onSelectGroup(category.name) } }
                }
                if (groups.isEmpty() && categories.isEmpty()) Text("Aucune catégorie", color = Muted, modifier = Modifier.padding(8.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (episodes != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onBackEpisodes) { Text("← Séries") }
                        Text("Épisodes · ${seriesTitle.orEmpty()}", color = Cream, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    if (episodes.isEmpty() && !loading) Text("Aucun épisode trouvé.", color = Muted, modifier = Modifier.padding(16.dp))
                    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(episodes, key = { it.id }) { item ->
                            CatalogGridCard(item.name, item.plot ?: item.year.orEmpty(), item.logo, landscape = true) { onPlayEpisode(item) }
                        }
                    }
                } else if (sourceKind == "M3U") {
                    if (filteredM3u.isEmpty() && !loading) Text(if (message.isNotBlank()) message else "Aucun contenu trouvé.", color = Muted, modifier = Modifier.padding(16.dp))
                    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(filteredM3u, key = { it.url }) { entry ->
                            CatalogGridCard(entry.name, entry.group, entry.logo, landscape = section == "TV en direct") { onPlayM3u(entry) }
                        }
                    }
                } else {
                    val displayedItems = if (query.isNotBlank()) searchResults else records.filterIsInstance<XtreamItem>()
                    if (displayedItems.isEmpty() && !loading && !searchLoading) {
                        val text = when {
                            message.isNotBlank() -> message
                            query.isNotBlank() -> "Aucun contenu trouvé."
                            selectedGroup == null -> "Choisis une catégorie pour afficher ses contenus."
                            else -> "Cette catégorie ne contient aucun contenu."
                        }
                        Text(text, color = Muted, modifier = Modifier.padding(16.dp))
                    } else {
                        LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(displayedItems, key = { it.id }) { item ->
                                val subtitle = if (query.isNotBlank()) categoryName(item) else (item.year ?: categoryName(item))
                                CatalogGridCard(item.name, subtitle, item.logo, landscape = section == "TV en direct") {
                                    if (section == "Séries") onSeries(item) else onPlayXtream(item)
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
private fun CategoryButton(title: String, selected: Boolean, disabled: Boolean, onClick: () -> Unit) {
    Text(
        title,
        color = if (selected) Cream else Muted,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(if (selected) Rust.copy(alpha = .34f) else Color.Transparent)
            .clickable(enabled = !disabled, onClick = onClick).focusable()
            .padding(horizontal = 10.dp, vertical = 11.dp)
    )
}

@Composable
private fun HeaderNavigation(section: String, sourceName: String, onNavigate: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF151B20)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource(R.drawable.home_iptv_icon), "HOME IPTV", Modifier.size(width = 68.dp, height = 48.dp), contentScale = ContentScale.Fit)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Accueil", "TV en direct", "Films", "Séries").forEach { item ->
                val selected = item == section
                Text(
                    item,
                    color = if (selected) Cream else Muted,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Rust.copy(alpha = .28f) else Color.Transparent)
                        .clickable { onNavigate(item) }.focusable()
                        .padding(horizontal = 13.dp, vertical = 11.dp)
                )
            }
        }
        Text(sourceName, color = Muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.widthIn(max = 130.dp).padding(horizontal = 8.dp))
        Text(
            "⚙",
            color = if (section == "Paramètres") Cyan else Cream,
            fontSize = 23.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onNavigate("Paramètres") }
                .focusable().padding(horizontal = 13.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun HomeDashboard(recent: List<RecentPlayback>, noSource: Boolean, onResume: (RecentPlayback) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Hero()
        Spacer(Modifier.height(20.dp))
        Text("Reprendre", color = Cream, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        if (noSource) {
            Text("Ajoute une source dans ⚙ Paramètres pour commencer.", color = Muted, modifier = Modifier.padding(top = 8.dp))
        } else if (recent.isEmpty()) {
            Text("Tes dernières chaînes, films et séries apparaîtront ici.", color = Muted, modifier = Modifier.padding(top = 8.dp))
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                recent.sortedBy { listOf("live", "vod", "series").indexOf(it.kind) }.forEach { item ->
                    RecentCard(item, onClick = { onResume(item) })
                }
            }
        }
    }
}

@Composable
private fun RecentCard(item: RecentPlayback, onClick: () -> Unit) {
    val label = when (item.kind) { "live" -> "DERNIÈRE CHAÎNE"; "series" -> "REPRENDRE LA SÉRIE"; else -> "REPRENDRE LE FILM" }
    Column(
        Modifier.width(220.dp).clip(RoundedCornerShape(14.dp)).background(Panel).clickable(onClick = onClick).focusable().padding(10.dp)
    ) {
        NetworkArtwork(item.image, Modifier.fillMaxWidth().height(132.dp), landscape = false)
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
private fun CatalogGridCard(title: String, subtitle: String, image: String?, landscape: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable(onClick = onClick).focusable().padding(8.dp)) {
        NetworkArtwork(image, Modifier.fillMaxWidth().height(if (landscape) 92.dp else 174.dp), landscape)
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
    Box(modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF252B30)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (bitmap == null) Text(if (landscape) "▶" else "HOME IPTV", color = if (landscape) Rust else Muted, fontSize = if (landscape) 30.sp else 11.sp)
    }
}

private fun loadArtwork(url: String): Bitmap? {
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
    } catch (_: Exception) {
        return null
    } finally { connection.disconnect() }
}

private fun normalizeSearchText(value: String): String =
    Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

@Composable
private fun CatalogRow(title: String, subtitle: String, logo: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).clickable(onClick = onClick).focusable().padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = Cream, fontWeight = FontWeight.Medium); if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 11.sp) }
        Text(if (logo.isNullOrBlank()) "▶" else "▶", color = Rust, fontSize = 18.sp)
    }
}
