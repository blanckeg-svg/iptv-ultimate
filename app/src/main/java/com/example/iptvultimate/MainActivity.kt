package com.example.iptvultimate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

private val Bg = Color(0xFF101116)
private val SurfaceDark = Color(0xFF1B1D26)
private val Accent = Color(0xFF31D7C0)
private val AdultWords = listOf("adult", "xxx", "18+", "porn", "erotic", "adulte")

private data class XtreamAccount(val server: String, val username: String, val password: String) {
    val base: String get() = server.trimEnd('/')
    fun api(action: String? = null, extra: Map<String, String> = emptyMap()): String {
        val p = linkedMapOf("username" to username, "password" to password)
        if (action != null) p["action"] = action
        p.putAll(extra)
        return "$base/player_api.php?" + p.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    }
    fun stream(type: String, id: String, ext: String): String = "$base/$type/$username/$password/$id.$ext"
}
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun markCloudLocalEdit(prefs: android.content.SharedPreferences) { prefs.edit().putLong("cloud_last_local_edit", System.currentTimeMillis()).apply() }
private data class MediaEntry(val id: String, val name: String, val category: String = "Autres", val poster: String? = null, val url: String = "", val kind: String = "live", val ext: String = "ts", val archiveAvailable: Boolean = false, val archiveDays: Int = 0)
private data class EpgEntry(val title: String, val description: String, val start: String, val end: String)
private data class Episode(val id: String, val title: String, val season: String, val episodeNumber: String, val extension: String, val url: String)
private data class XtreamData(val live: List<MediaEntry>, val movies: List<MediaEntry>, val series: List<MediaEntry>, val liveCats: List<String>, val movieCats: List<String>, val seriesCats: List<String>)

class MainActivity : ComponentActivity() {
    private var activePlayer: ExoPlayer? = null
    private var secondPlayer: ExoPlayer? = null
    private val prefs by lazy { getSharedPreferences("iptv_ultimate", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val savedServer = prefs.getString("server", "") ?: ""
        val savedUser = prefs.getString("user", "") ?: ""
        val savedPass = prefs.getString("pass", "") ?: ""
        setContent {
            var account by remember { mutableStateOf<XtreamAccount?>(if (savedServer.isNotBlank() && savedUser.isNotBlank()) XtreamAccount(savedServer, savedUser, savedPass) else null) }
            var current by remember { mutableStateOf<MediaEntry?>(null) }
            var second by remember { mutableStateOf<MediaEntry?>(null) }
            var resumeAt by remember { mutableLongStateOf(0L) }
            var playerMode by remember { mutableStateOf(false) }
            var multiMode by remember { mutableStateOf(false) }
            var activeTheme by remember { mutableStateOf(prefs.getString("theme", "Turquoise") ?: "Turquoise") }
            val accent = when (activeTheme) { "Violet" -> Color(0xFFB79CFF); "Orange" -> Color(0xFFFFB15E); "Blue" -> Color(0xFF69A9FF); else -> Accent }
            MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = Bg, surface = SurfaceDark)) {
                when {
                    multiMode && account != null -> MultiViewScreen(account!!, current, second, onBack = { multiMode = false }, onPick = { if (current == null) current = it else second = it }, onPlay = { entry -> current = entry; multiMode = false; playerMode = true })
                    playerMode && current != null && account != null -> PlayerScreen(entry = current!!, account = account!!, prefs = prefs, onBack = { playerMode = false; activePlayer = null }, onResume = { position -> resumeAt = position }, onPlayer = { activePlayer = it })
                    account == null -> ConnectScreen(onConnect = { a -> account = a; prefs.edit().putString("server", a.server).putString("user", a.username).putString("pass", a.password).apply() })
                    else -> HomeScreen(account!!, prefs, activeTheme, accent,
                        onPlay = { entry -> current = entry; playerMode = true },
                        onMulti = { entry -> current = entry; second = null; multiMode = true },
                        onDisconnect = { account = null; prefs.edit().remove("server").remove("user").remove("pass").apply() },
                        onTheme = { activeTheme = it; prefs.edit().putString("theme", it).apply(); markCloudLocalEdit(prefs) },
                        onExternal = { entry -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url))) } })
                }
            }
        }
    }

    override fun onStop() { super.onStop(); activePlayer?.pause(); secondPlayer?.pause() }
    override fun onDestroy() { activePlayer = null; secondPlayer = null; super.onDestroy() }
}

@Composable
private fun ConnectScreen(onConnect: (XtreamAccount) -> Unit) {
    var server by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Bg).padding(22.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.LiveTv, null, tint = Accent, modifier = Modifier.size(48.dp))
        Text("IPTV ULTIMATE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 28.sp)
        Text("Connexion Xtream Codes", color = Color.LightGray)
        Spacer(Modifier.height(20.dp))
        Field(server, { server = it }, "URL du serveur", "https://serveur.example:port")
        Field(username, { username = it }, "Identifiant")
        OutlinedTextField(password, { password = it }, label = { Text("Mot de passe") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = fieldColors())
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            val a = XtreamAccount(server.trim(), username.trim(), password)
            if (!server.startsWith("http://") && !server.startsWith("https://")) error = "Indique une URL de serveur http:// ou https://"
            else if (username.isBlank() || password.isBlank()) error = "Identifiant et mot de passe requis."
            else { busy = true; error = ""; scope.launch { val result = withContext(Dispatchers.IO) { runCatching { XtreamApi.accountInfo(a) }.getOrElse { null } }; busy = false; if (result == true) onConnect(a) else error = "Connexion refusée ou serveur inaccessible. Vérifie les paramètres Xtream." } }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp)) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Se connecter") }
        Spacer(Modifier.height(12.dp)); Text("Utilise uniquement un abonnement et des flux auxquels tu es autorisé à accéder.", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable private fun HomeScreen(a: XtreamAccount, prefs: android.content.SharedPreferences, theme: String, accent: Color, onPlay: (MediaEntry) -> Unit, onMulti: (MediaEntry) -> Unit, onDisconnect: () -> Unit, onTheme: (String) -> Unit, onExternal: (MediaEntry) -> Unit) {
    var tab by remember { mutableStateOf("TV") }; var data by remember { mutableStateOf<XtreamData?>(null) }; var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }; var category by remember { mutableStateOf("Toutes") }; var epg by remember { mutableStateOf<List<EpgEntry>>(emptyList()) }; var epgChannel by remember { mutableStateOf<MediaEntry?>(null) }; var episodeList by remember { mutableStateOf<List<Episode>>(emptyList()) }; var episodeSeries by remember { mutableStateOf<MediaEntry?>(null) }; var episodeBusy by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }; var pin by remember { mutableStateOf(prefs.getString("parental_pin", "") ?: "") }; var pinInput by remember { mutableStateOf("") }; var unlocked by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(prefs.getString("profile", "Principal") ?: "Principal") }; var showProfile by remember { mutableStateOf(false) }; var newProfile by remember { mutableStateOf("") }
    var backupText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(a) { loading = true; runCatching { withContext(Dispatchers.IO) { XtreamApi.loadHome(a) } }.onSuccess { data = it; error = "" }.onFailure { error = "Impossible de charger le catalogue : ${it.message}" }; loading = false }
    val currentData = data
    val items = when (tab) { "TV" -> currentData?.live.orEmpty(); "Films" -> currentData?.movies.orEmpty(); "Séries" -> currentData?.series.orEmpty(); "Favoris" -> currentData?.let { it.live + it.movies + it.series } ?: emptyList(); else -> emptyList() }
    val cats = when (tab) { "TV" -> currentData?.liveCats.orEmpty(); "Films" -> currentData?.movieCats.orEmpty(); "Séries" -> currentData?.seriesCats.orEmpty(); else -> emptyList() }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    val visible = items.filter { it.name.contains(filter, true) && (category == "Toutes" || it.category == category) && (tab != "Favoris" || favorites.contains("${it.kind}:${it.id}")) && (unlocked || !AdultWords.any { w -> (it.name + " " + it.category).contains(w, true) }) }
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LiveTv, null, tint = accent, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) { Text("IPTV ULTIMATE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp); Text("Profil : $profile", color = Color.LightGray, fontSize = 11.sp) }
            IconButton(onClick = { showSettings = !showSettings }) { Icon(Icons.Default.Settings, "Paramètres", tint = Color.White) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("TV", "Replay", "Films", "Séries", "Favoris", "Guide TV", "Paramètres").forEach { name -> FilterChip(selected = tab == name, onClick = { tab = name; showSettings = name == "Paramètres"; category = "Toutes"; if (name == "Guide TV") { val first = currentData?.live?.firstOrNull(); if (first != null) { epgChannel = first; scope.launch { epg = withContext(Dispatchers.IO) { XtreamApi.epg(a, first.id) } } } }; if (name == "Paramètres") showSettings = true }, label = { Text(name) }) } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF8A80), modifier = Modifier.padding(vertical = 8.dp))
        if (showSettings || tab == "Paramètres") {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                item { Text("Personnalisation", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                item { Text("Thème", color = Color.LightGray); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Turquoise", "Violet", "Orange", "Blue").forEach { FilterChip(selected = theme == it, onClick = { onTheme(it) }, label = { Text(it) }) } } }
                item { Divider(color = Color.DarkGray) }
                item { Text("Profils locaux", color = Color.White, fontWeight = FontWeight.Bold) }
                item { Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(newProfile, { newProfile = it }, label = { Text("Nom du profil") }, modifier = Modifier.weight(1f)); Button(onClick = { if (newProfile.isNotBlank()) { profile = newProfile.trim(); prefs.edit().putString("profile", profile).apply(); markCloudLocalEdit(prefs); newProfile = "" } }) { Text("Créer / choisir") } } }
                item { Text("Profil actif : $profile. Les profils sont locaux à cet appareil.", color = Color.Gray, fontSize = 12.sp) }
                item { Divider(color = Color.DarkGray) }
                item { Text("Contrôle parental", color = Color.White, fontWeight = FontWeight.Bold) }
                item { OutlinedTextField(pinInput, { pinInput = it }, label = { Text("Nouveau code PIN (4 chiffres minimum)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()) }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Button(onClick = { if (pinInput.length >= 4 && pinInput.all(Char::isDigit)) { pin = pinInput; prefs.edit().putString("parental_pin", pin).apply(); markCloudLocalEdit(prefs); pinInput = ""; unlocked = false } }) { Text("Enregistrer PIN") }; Spacer(Modifier.width(8.dp)); Button(onClick = { if (pin.isNotBlank() && pinInput == pin) unlocked = true; pinInput = "" }) { Text("Déverrouiller adultes") } } }
                item { Text(if (unlocked) "Catégories adultes déverrouillées pour cette session." else "Filtrage simple par mots-clés ; il ne remplace pas un contrôle parental certifié." , color = Color.Gray, fontSize = 12.sp) }
                item { Divider(color = Color.DarkGray) }
                item { Text("Sauvegarde / synchronisation cloud WebDAV", color = Color.White, fontWeight = FontWeight.Bold) }
                item { Text("Configure un serveur WebDAV/Nextcloud que tu contrôles. La synchronisation transmet les préférences et la progression, jamais le mot de passe Xtream.", color = Color.Gray, fontSize = 12.sp) }
                item { var webdavUrl by remember { mutableStateOf(prefs.getString("webdav_url", "") ?: "") }; var webdavUser by remember { mutableStateOf(prefs.getString("webdav_user", "") ?: "") }; var webdavPass by remember { mutableStateOf(prefs.getString("webdav_pass", "") ?: "") }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(webdavUrl, { webdavUrl = it }, label = { Text("URL WebDAV complète du fichier JSON") }, placeholder = { Text("https://cloud.example/remote.php/dav/files/nom/iptv.json") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(webdavUser, { webdavUser = it }, label = { Text("Utilisateur WebDAV") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(webdavPass, { webdavPass = it }, label = { Text("Mot de passe / mot de passe d’application") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { prefs.edit().putString("webdav_url", webdavUrl.trim()).putString("webdav_user", webdavUser.trim()).putString("webdav_pass", webdavPass).apply(); if (webdavUrl.startsWith("https://") && webdavUser.isNotBlank() && webdavPass.isNotBlank()) { CloudSyncScheduler.enable(context = context); backupText = "Synchronisation automatique activée. Le premier échange aura lieu lors du prochain passage du service." } else backupText = "Saisis une URL HTTPS WebDAV, un utilisateur et un mot de passe." }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer et activer la synchronisation") }
                        Button(onClick = { CloudSyncScheduler.disable(context); prefs.edit().remove("webdav_url").remove("webdav_user").remove("webdav_pass").apply(); backupText = "Synchronisation désactivée." }, modifier = Modifier.fillMaxWidth()) { Text("Désactiver la synchronisation") }
                    }
                }
                item { Button(onClick = { backupText = JSONObject().put("server", a.server).put("username", a.username).put("profile", profile).put("theme", theme).put("favorites", JSONArray(prefs.getStringSet("favorites", emptySet<String>())?.toList() ?: emptyList())).toString(2) }) { Text("Préparer une sauvegarde JSON manuelle") } }
                item { if (backupText.isNotBlank()) OutlinedTextField(backupText, { backupText = it }, label = { Text("État / sauvegarde JSON (mot de passe Xtream exclu)") }, modifier = Modifier.fillMaxWidth(), minLines = 3) }
                item { Text("La synchronisation cloud nécessite un compte WebDAV/Nextcloud personnel. Utilise HTTPS et un mot de passe d’application.", color = Color.Gray, fontSize = 12.sp) }
                item { Divider(color = Color.DarkGray) }
                item { Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Changer de serveur / se déconnecter") } }
            }
        } else if (tab == "Guide TV") {
            Text("Guide TV — programme en cours et suivant", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(vertical = 10.dp))
            LazyColumn { items((currentData?.live.orEmpty()).take(100)) { ch -> Column(Modifier.fillMaxWidth().clickable { epgChannel = ch; scope.launch { epg = withContext(Dispatchers.IO) { XtreamApi.epg(a, ch.id) } } }.background(SurfaceDark, RoundedCornerShape(10.dp)).padding(10.dp)) { Text(ch.name, color = Color.White, fontWeight = FontWeight.Bold); if (ch.id == epgChannel?.id) { if (epg.isEmpty()) Text("Aucun programme EPG fourni par le serveur.", color = Color.Gray, fontSize = 12.sp); epg.forEach { Text("${it.start} – ${it.end}  ${it.title}", color = accent); if (it.description.isNotBlank()) Text(it.description, color = Color.LightGray, fontSize = 12.sp) } } else Text("Sélectionner pour charger le programme", color = Color.Gray, fontSize = 12.sp) }; Spacer(Modifier.height(6.dp)) } }
        } else if (tab == "Replay") {
            Text("Replay / Catch-up fourni par ton serveur IPTV", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(vertical = 10.dp))
            Text("Le Replay apparaît uniquement si la chaîne annonce une archive. La disponibilité réelle dépend de ton fournisseur.", color = Color.Gray, fontSize = 12.sp)
            val replayChannels = currentData?.live.orEmpty().filter { it.archiveAvailable && it.archiveDays > 0 }
            if (replayChannels.isEmpty()) Text("Aucune chaîne n'annonce de Replay (tv_archive) ou le fournisseur ne fournit pas cette information.", color = Color.LightGray, modifier = Modifier.padding(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(replayChannels, key = { "replay_${it.id}" }) { ch ->
                    Column(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(10.dp)).padding(12.dp)) {
                        Text(ch.name, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Archive annoncée : ${ch.archiveDays} jour(s)", color = Color.Gray, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                            listOf(60, 120, 180).filter { it <= ch.archiveDays * 24 * 60 }.forEach { mins ->
                                OutlinedButton(onClick = { val start = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(Date(System.currentTimeMillis() - mins * 60_000L)); val url = a.base + "/timeshift/${enc(a.username)}/${enc(a.password)}/$mins/${enc(start)}/${enc(ch.id)}.m3u8"; onPlay(MediaEntry(ch.id, "${ch.name} · Replay ${mins / 60} h", ch.category, ch.poster, url, "catchup", "m3u8")) }) { Text(if (mins < 120) "${mins} min" else "${mins / 60} h") }
                            }
                        }
                    }
                }
            }
        } else {
            OutlinedTextField(filter, { filter = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Rechercher") }, singleLine = true, colors = fieldColors())
            if (cats.isNotEmpty()) { Spacer(Modifier.height(6.dp)); androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { item { FilterChip(selected = category == "Toutes", onClick = { category = "Toutes" }, label = { Text("Toutes") }) }; items(cats.distinct()) { cat -> FilterChip(selected = category == cat, onClick = { category = cat }, label = { Text(cat, maxLines = 1) }) } } }
            Spacer(Modifier.height(8.dp))
            if (tab == "TV") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Text("Maintiens l'accès multiview en sélectionnant une chaîne", color = Color.Gray, fontSize = 11.sp) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(visible, key = { "${it.kind}_${it.id}" }) { entry ->
                    Row(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(10.dp)).clickable { if (entry.kind == "series") { episodeSeries = entry; episodeBusy = true; scope.launch { episodeList = withContext(Dispatchers.IO) { XtreamApi.episodes(a, entry.id) }; episodeBusy = false } } else onPlay(entry) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!entry.poster.isNullOrBlank()) AsyncImage(model = entry.poster, contentDescription = null, modifier = Modifier.size(if (tab == "TV") 48.dp else 68.dp)) else Icon(if (tab == "TV") Icons.Default.LiveTv else Icons.Default.Movie, null, tint = accent, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(entry.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2); Text(entry.category, color = Color.LightGray, fontSize = 12.sp) }
                        if (tab == "TV") IconButton(onClick = { onMulti(entry) }) { Icon(Icons.Default.ViewQuilt, "Multiview", tint = accent) }
                        IconButton(onClick = { val k = "${entry.kind}:${entry.id}"; favorites = if (favorites.contains(k)) favorites - k else favorites + k; prefs.edit().putStringSet("favorites", favorites).apply(); markCloudLocalEdit(prefs) }) { Icon(if (favorites.contains("${entry.kind}:${entry.id}")) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favori", tint = accent) }
                        IconButton(onClick = { onExternal(entry) }) { Icon(Icons.Default.OpenInNew, "Lecteur externe", tint = Color.LightGray) }
                    }
                }
            }
        }
    }

    if (episodeSeries != null) {
        AlertDialog(onDismissRequest = { episodeSeries = null }, title = { Text(episodeSeries!!.name) }, text = {
            if (episodeBusy) CircularProgressIndicator()
            else if (episodeList.isEmpty()) Text("Aucun épisode trouvé. Le fournisseur doit exposer les saisons et épisodes Xtream.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) { items(episodeList, key = { it.id }) { ep -> Text("S${ep.season} · E${ep.episodeNumber} — ${ep.title}", color = Color.White, modifier = Modifier.fillMaxWidth().clickable { onPlay(MediaEntry(ep.id, ep.title, "Saison ${ep.season}", null, ep.url, "episode", ep.extension)); episodeSeries = null }.padding(10.dp)) } }
        }, confirmButton = { TextButton(onClick = { episodeSeries = null }) { Text("Fermer") } })
    }
}

@Composable private fun PlayerScreen(entry: MediaEntry, account: XtreamAccount, prefs: android.content.SharedPreferences, onBack: () -> Unit, onResume: (Long) -> Unit, onPlayer: (ExoPlayer) -> Unit) {
    val context = LocalContext.current
    val key = "position_${account.username}_${entry.kind}_${entry.id}"
    val player = remember(entry.url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(entry.url)); prepare(); playWhenReady = true; onPlayer(this) } }
    var showResume by remember { mutableStateOf(false) }
    LaunchedEffect(player) { val pos = prefs.getLong(key, 0L); if (pos > 15000L) { player.pause(); showResume = true } }
    DisposableEffect(player) { onDispose { prefs.edit().putLong(key, player.currentPosition.coerceAtLeast(0)).apply(); markCloudLocalEdit(prefs); player.release() } }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().background(Bg).padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Text("‹ Retour", color = Color.White, modifier = Modifier.clickable { onBack() }.padding(8.dp)); Text(entry.name, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); IconButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url))) } }) { Icon(Icons.Default.OpenInNew, "Lecteur externe", tint = Accent) } }
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true; layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }, modifier = Modifier.fillMaxWidth().weight(1f))
        Text("Media3 · reprise locale activée", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
    }
    if (showResume) AlertDialog(onDismissRequest = { showResume = false; player.playWhenReady = true }, title = { Text("Reprendre la lecture ?") }, text = { Text("Une position précédente a été enregistrée.") }, confirmButton = { TextButton(onClick = { player.seekTo(prefs.getLong(key, 0L)); player.playWhenReady = true; showResume = false }) { Text("Reprendre") } }, dismissButton = { TextButton(onClick = { player.seekTo(0); player.playWhenReady = true; showResume = false }) { Text("Depuis le début") } })
}

@Composable private fun MultiViewScreen(a: XtreamAccount, first: MediaEntry?, second: MediaEntry?, onBack: () -> Unit, onPick: (MediaEntry) -> Unit, onPlay: (MediaEntry) -> Unit) {
    var channels by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }; var search by remember { mutableStateOf("") }
    LaunchedEffect(a) { channels = withContext(Dispatchers.IO) { runCatching { XtreamApi.live(a) }.getOrDefault(emptyList()) } }
    Column(Modifier.fillMaxSize().background(Bg).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Multiview", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("Retour", color = Accent, modifier = Modifier.clickable { onBack() }.padding(8.dp)) }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(first, second).forEachIndexed { idx, e -> Column(Modifier.weight(1f).fillMaxHeight().background(SurfaceDark, RoundedCornerShape(8.dp)).clickable { if (e != null) onPlay(e) }.padding(4.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { if (e != null) { SmallStreamPlayer(e); Text(e.name, color = Color.White, maxLines = 2, fontSize = 11.sp); Text("Toucher pour plein écran", color = Color.Gray, fontSize = 10.sp) } else { Icon(Icons.Default.LiveTv, null, tint = Accent, modifier = Modifier.size(30.dp)); Text("Choisir chaîne ${idx + 1}", color = Color.White) } } } }
        Text("Choisis une chaîne à affecter au prochain emplacement", color = Color.LightGray, fontSize = 12.sp)
        OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Rechercher une chaîne") })
        LazyColumn(Modifier.weight(1f)) { items(channels.filter { it.name.contains(search, true) }.take(100)) { e -> Text(e.name, color = Color.White, modifier = Modifier.fillMaxWidth().clickable { onPick(e) }.padding(8.dp)) } }
        Text("Multiview simultané expérimental : deux flux peuvent augmenter fortement la charge de la box.", color = Color.Gray, fontSize = 11.sp)
    }
}


@Composable private fun SmallStreamPlayer(entry: MediaEntry) {
    val context = LocalContext.current
    val player = remember(entry.url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(entry.url)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false; layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }, modifier = Modifier.fillMaxWidth().height(135.dp))
}

private object XtreamApi {
    private fun get(url: String): String { val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 25000; setRequestProperty("User-Agent", "IPTVUltimate/0.2 Android") }; return try { c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } } finally { c.disconnect() } }
    fun accountInfo(a: XtreamAccount): Boolean { val j = JSONObject(get(a.api())); return j.optJSONObject("user_info")?.optString("auth") == "1" }
    fun loadHome(a: XtreamAccount): XtreamData = XtreamData(live(a), movies(a), series(a), categories(a, "get_live_categories"), categories(a, "get_vod_categories"), categories(a, "get_series_categories"))
    private fun categories(a: XtreamAccount, action: String): List<String> = runCatching { val arr = JSONArray(get(a.api(action))); (0 until arr.length()).map { arr.getJSONObject(it).optString("category_name", "Autres") }.filter { it.isNotBlank() } }.getOrDefault(emptyList())
    fun live(a: XtreamAccount): List<MediaEntry> = parseStreams(a, "get_live_streams", "live", "ts")
    private fun movies(a: XtreamAccount): List<MediaEntry> = parseStreams(a, "get_vod_streams", "movie", "mp4")
    private fun series(a: XtreamAccount): List<MediaEntry> = parseStreams(a, "get_series", "series", "")
    private fun parseStreams(a: XtreamAccount, action: String, kind: String, ext: String): List<MediaEntry> {
        val categoryAction = when (kind) { "live" -> "get_live_categories"; "movie" -> "get_vod_categories"; else -> "get_series_categories" }
        val catMap = runCatching { val ca = JSONArray(get(a.api(categoryAction))); (0 until ca.length()).associate { val c = ca.getJSONObject(it); c.optString("category_id") to c.optString("category_name", "Autres") } }.getOrDefault(emptyMap())
        val arr = JSONArray(get(a.api(action))); val out = mutableListOf<MediaEntry>()
        for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; val id = o.optString(if (kind == "series") "series_id" else if (kind == "movie") "stream_id" else "stream_id"); if (id.isBlank() || id == "null") continue
            val name = o.optString("name", "Sans titre"); val catId = o.optString("category_id", ""); val cat = catMap[catId] ?: if (catId.isBlank()) "Autres" else "Catégorie $catId"
            val poster = o.optString("stream_icon").ifBlank { o.optString("cover").ifBlank { o.optString("cover_big") } }.takeIf { it.startsWith("http") }
            val url = when(kind) { "live" -> a.stream("live", id, ext); "movie" -> a.stream("movie", id, o.optString("container_extension", ext).ifBlank { ext }); else -> "" }
            out.add(MediaEntry(id, name, cat, poster, url, kind, o.optString("container_extension", ext).ifBlank { ext }, o.optInt("tv_archive", 0) == 1, o.optInt("tv_archive_duration", 0)))
        }; return out
    }
    fun episodes(a: XtreamAccount, seriesId: String): List<Episode> = runCatching {
        val root = JSONObject(get(a.api("get_series_info", mapOf("series_id" to seriesId))))
        val seasons = root.optJSONObject("episodes") ?: JSONObject()
        val out = mutableListOf<Episode>()
        val keys = seasons.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }
        for (season in keys) { val arr = seasons.optJSONArray(season) ?: continue; for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; val id = o.optString("id").ifBlank { o.optString("episode_id") }; if (id.isBlank()) continue; val ext = o.optString("container_extension", "mp4").ifBlank { "mp4" }; val title = o.optString("title", o.optString("name", "Épisode ${i+1}")); out.add(Episode(id, title, season, o.optString("episode_num", (i+1).toString()), ext, a.stream("series", id, ext))) } }
        out
    }.getOrDefault(emptyList())
    fun epg(a: XtreamAccount, streamId: String): List<EpgEntry> = runCatching { val j = JSONObject(get(a.api("get_short_epg", mapOf("stream_id" to streamId, "limit" to "10")))); val arr = j.optJSONArray("epg_listings") ?: JSONArray(); (0 until arr.length()).map { arr.getJSONObject(it) }.map { o -> EpgEntry(decode(o.optString("title")), decode(o.optString("description")), o.optString("start", ""), o.optString("end", "")) } }.getOrDefault(emptyList())
    private fun decode(s: String): String = runCatching { String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(s)
}

@Composable private fun Field(value: String, onValue: (String) -> Unit, label: String, placeholder: String = "") { OutlinedTextField(value, onValue, label = { Text(label) }, placeholder = { if (placeholder.isNotBlank()) Text(placeholder) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (label.contains("URL")) KeyboardType.Uri else KeyboardType.Text), colors = fieldColors()) }
@Composable private fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Accent, unfocusedBorderColor = Color.Gray, focusedLabelColor = Accent, unfocusedLabelColor = Color.LightGray, cursorColor = Accent)
