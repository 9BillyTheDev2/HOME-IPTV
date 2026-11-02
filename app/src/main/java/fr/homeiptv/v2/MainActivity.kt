package fr.homeiptv.v2

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.setContent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private val Ink = Color(0xFF101419)
private val Panel = Color(0xFF1C2329)
private val Cream = Color(0xFFF2EBDD)
private val Muted = Color(0xFFABB4B8)
private val Rust = Color(0xFFC95132)
private val Cyan = Color(0xFF65C9D7)

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply { setContent { HomeApp(SourceStore(this@MainActivity)) } })
    }
}

@Composable
private fun HomeApp(store: SourceStore) {
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
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as Activity
    val selectedTab = when (section) { "Films" -> "vod"; "Séries" -> "series"; else -> "live" }

    LaunchedEffect(active?.id, selectedTab) {
        records = emptyList(); selectedGroup = null; categoryOpen = false; xtreamCategories = emptyList(); seriesEpisodes = null; message = ""
        val profile = active ?: return@LaunchedEffect
        loading = true
        try {
            if (profile.kind == "M3U") {
                requireHttpsEndpoint(profile.endpoint)
                val raw = withContext(Dispatchers.IO) {
                    val connection = URL(profile.endpoint).openConnection() as HttpURLConnection
                    connection.connectTimeout = 12000; connection.readTimeout = 20000
                    try { if (connection.responseCode !in 200..299) error("Erreur HTTP ${connection.responseCode}"); connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
                    finally { connection.disconnect() }
                }
                val parsed = M3uParser.parse(raw).filter { m3uSection(it) == selectedTab }
                records = parsed
                if (parsed.isEmpty()) message = "Aucun contenu dans cette section de la playlist."
            } else {
                val client = XtreamClient(profile.endpoint, profile.user, profile.password)
                val categories = client.categories(if (selectedTab == "live") "live" else selectedTab)
                xtreamCategories = categories
                records = categories
                if (categories.isEmpty()) message = "Aucune catégorie fournie pour cette section."
            }
        } catch (e: Exception) { message = e.message ?: "Impossible de charger cette source." }
        loading = false
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Rust, onPrimary = Cream, onBackground = Cream, onSurface = Cream, secondary = Cyan)) {
        Row(Modifier.fillMaxSize().background(Ink)) {
            Column(Modifier.width(210.dp).fillMaxHeight().background(Color(0xFF151B20)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Image(painterResource(R.drawable.home_iptv_icon), "Logo HOME IPTV", Modifier.fillMaxWidth().height(112.dp), contentScale = ContentScale.Fit)
                listOf("Accueil", "TV en direct", "Films", "Séries", "Paramètres").forEach { item ->
                    val chosen = item == section
                    Text(item, color = if (chosen) Cream else Muted, fontSize = 15.sp, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (chosen) Rust.copy(alpha = .32f) else Color.Transparent).clickable { section = item }.focusable().padding(horizontal = 13.dp, vertical = 12.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("HOME IPTV · PERSONNEL", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp, vertical = 17.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(section.uppercase(), color = Cyan, fontSize = 12.sp, letterSpacing = 2.sp)
                    Text(active?.name ?: "Aucune source", color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(15.dp))
                when (section) {
                    "Paramètres" -> SettingsScreen(profiles, active, onAdd = { adding = true }, onSelect = { store.setActive(it.id); active = it }, onDelete = { store.delete(it.id); profiles = store.all(); active = store.active() }, onVpn = {
                        runCatching { activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                            .onFailure { runCatching { activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }
                    })
                    "Accueil" -> {
                        Hero()
                        Spacer(Modifier.height(18.dp))
                        Text("À regarder", color = Cream, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(if (active == null) "Ajoute ta playlist M3U ou tes accès Xtream dans Paramètres pour commencer." else "Choisis TV en direct, Films ou Séries dans le menu.", color = Muted, modifier = Modifier.padding(top = 8.dp))
                    }
                    else -> CatalogScreen(section, records, selectedGroup, onGroup = { selectedGroup = it }, loading = loading, message = message,
                        onPlayM3u = { activity.play(it.url) }, onLoadItems = { category ->
                            categoryOpen = true
                            selectedGroup = category.name
                            scope.launch {
                                loading = true; message = ""
                                try {
                                    val p = active ?: return@launch
                                    val data = XtreamClient(p.endpoint, p.user, p.password).items(selectedTab, category.ids)
                                    records = data
                                    if (data.isEmpty()) message = "Cette catégorie ne contient aucun élément."
                                } catch (e: Exception) { message = e.message ?: "Erreur de chargement." }
                                loading = false
                            }
                        }, onBrowseCategories = {
                            categoryOpen = false
                            selectedGroup = null
                            seriesEpisodes = null
                            records = xtreamCategories
                        }, onBackEpisodes = { seriesEpisodes = null }, onPlayXtream = { item ->
                            val p = active
                            if (p != null) {
                            val kind = if (section == "TV en direct") "live" else "vod"
                            activity.play(XtreamClient(p.endpoint, p.user, p.password).streamUrl(kind, item))
                            }
                        }, onSeries = { item ->
                            val p = active
                            if (p != null) scope.launch {
                                loading = true
                                try { seriesEpisodes = XtreamClient(p.endpoint, p.user, p.password).episodes(item.id) }
                                catch (e: Exception) { message = e.message ?: "Impossible de charger les épisodes." }
                                loading = false
                            }
                        }, episodes = seriesEpisodes, onPlayEpisode = { ep ->
                            val p = active
                            if (p != null) activity.play(XtreamClient(p.endpoint, p.user, p.password).streamUrl("series", ep))
                        }, categoryOpen = categoryOpen)
                }
            }
        }
        if (adding) AddSourceDialog(onDismiss = { adding = false }, onSave = { name, kind, endpoint, user, pass ->
            scope.launch {
                loading = true; message = "Test de la source…"
                try {
                    val profile = SourceProfile(UUID.randomUUID().toString(), name, kind, endpoint, user, pass)
                    if (kind == "M3U") {
                        requireHttpsEndpoint(endpoint)
                        val raw = withContext(Dispatchers.IO) {
                            val c = URL(endpoint).openConnection() as HttpURLConnection
                            c.connectTimeout = 12000; c.readTimeout = 20000
                            try { if (c.responseCode !in 200..299) error("La playlist répond HTTP ${c.responseCode}"); c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } } finally { c.disconnect() }
                        }
                        if (M3uParser.parse(raw).isEmpty()) error("Le lien répond, mais aucune entrée M3U valide n’a été trouvée.")
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

private fun Activity.play(url: String) { startActivity(Intent(this, PlaybackActivity::class.java).putExtra(PlaybackActivity.EXTRA_URL, url)) }

private fun requireHttpsEndpoint(value: String) {
    val uri = android.net.Uri.parse(value.trim())
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "Cette source doit utiliser une adresse HTTPS complète. Les liens HTTP ne sont pas acceptés."
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
private fun SettingsScreen(profiles: List<SourceProfile>, active: SourceProfile?, onAdd: () -> Unit, onSelect: (SourceProfile) -> Unit, onDelete: (SourceProfile) -> Unit, onVpn: () -> Unit) {
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ajouter une source") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nom du profil") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("M3U", "Xtream").forEach { item -> FilterChip(selected = kind == item, onClick = { kind = item }, label = { Text(item) }) }
            }
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text(if (kind == "M3U") "URL de la playlist" else "Adresse du serveur") }, singleLine = true)
            if (kind == "Xtream") {
                OutlinedTextField(user, { user = it }, label = { Text("Identifiant") }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, label = { Text("Mot de passe") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            }
            Text("Les connexions HTTPS sont obligatoires pour protéger les identifiants et les flux.", color = Muted, fontSize = 11.sp)
        }
    }, confirmButton = { TextButton(onClick = {
        val uri = android.net.Uri.parse(endpoint.trim())
        val validHttpsUrl = uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()
        if (name.isNotBlank() && validHttpsUrl && (kind == "M3U" || (user.isNotBlank() && pass.isNotBlank()))) onSave(name.trim(), kind, endpoint.trim(), user.trim(), pass)
    }) { Text("Tester et ajouter") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}

@Composable
private fun CatalogScreen(
    section: String, records: List<Any>, selectedGroup: String?, onGroup: (String?) -> Unit, loading: Boolean, message: String,
    onPlayM3u: (PlaylistEntry) -> Unit, onLoadItems: (XtreamCategory) -> Unit, onBrowseCategories: () -> Unit,
    onBackEpisodes: () -> Unit, onPlayXtream: (XtreamItem) -> Unit,
    onSeries: (XtreamItem) -> Unit, episodes: List<XtreamItem>?, onPlayEpisode: (XtreamItem) -> Unit, categoryOpen: Boolean
) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(section, color = Cream, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        if (message.isNotBlank() && !loading) Text(message, color = Muted, modifier = Modifier.padding(top = 12.dp))
        if (categoryOpen && records.isEmpty()) TextButton(onClick = onBrowseCategories) { Text("← Retour aux catégories") }
        when {
            records.firstOrNull() is PlaylistEntry -> {
                val entries = records.filterIsInstance<PlaylistEntry>()
                val groups = entries.map { it.group }.distinct().sorted()
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterChip(selected = selectedGroup == null, onClick = { onGroup(null) }, label = { Text("Toutes") })
                    groups.forEach { group -> FilterChip(selected = selectedGroup == group, onClick = { onGroup(group) }, label = { Text(group) }) }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(entries.filter { selectedGroup == null || selectedGroup == it.group }, key = { it.url }) { entry -> CatalogRow(entry.name, entry.group, entry.logo, onClick = { onPlayM3u(entry) }) }
                }
            }
            records.firstOrNull() is XtreamCategory -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(records.filterIsInstance<XtreamCategory>(), key = { it.id }) { category -> CatalogRow(category.name, "Catégorie", null) { onLoadItems(category) } }
                }
            }
            records.firstOrNull() is XtreamItem -> {
                val items = records.filterIsInstance<XtreamItem>()
                if (episodes != null) {
                    TextButton(onClick = onBackEpisodes) { Text("← Retour aux séries") }
                    Text("Épisodes", color = Muted, modifier = Modifier.padding(vertical = 8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) { items(episodes, key = { it.id }) { ep -> CatalogRow(ep.name, ep.plot.orEmpty(), null) { onPlayEpisode(ep) } } }
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (categoryOpen) item { TextButton(onClick = onBrowseCategories) { Text("← Retour aux catégories") } }
                    items(items, key = { it.id }) { item ->
                        val series = section == "Séries"
                        CatalogRow(item.name, item.year ?: "", item.logo) { if (series) onSeries(item) else onPlayXtream(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(title: String, subtitle: String, logo: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).clickable(onClick = onClick).focusable().padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = Cream, fontWeight = FontWeight.Medium); if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 11.sp) }
        Text(if (logo.isNullOrBlank()) "▶" else "▶", color = Rust, fontSize = 18.sp)
    }
}
