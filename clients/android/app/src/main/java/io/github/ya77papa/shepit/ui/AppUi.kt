package io.github.ya77papa.shepit.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ya77papa.shepit.BuildConfig
import io.github.ya77papa.shepit.ShepitApp
import io.github.ya77papa.shepit.data.Backup
import io.github.ya77papa.shepit.data.Contact
import io.github.ya77papa.shepit.data.Content
import io.github.ya77papa.shepit.data.Message
import io.github.ya77papa.shepit.data.VoicePlayer
import io.github.ya77papa.shepit.data.VoiceRecorder
import io.github.ya77papa.shepit.service.Notifications
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.ya77papa.shepit.data.ChannelRec
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen { CONTACTS, CHAT, SETTINGS, GROUP_INFO, NEW_GROUP, CHANNEL, NEW_CHANNEL, FIND_CHANNEL, SHARE }

@Composable
fun AppUi(
    app: ShepitApp, act: MainActivity, pendingInvite: MutableState<Pair<String, String?>?>, openRequest: MutableState<String?>,
    inviteCode: MutableState<String?>, showUpdate: MutableState<Boolean>, channelLink: MutableState<Pair<String, String?>?>,
    shared: MutableState<String?>,
) {
    val repo = app.repo
    var hasAccount by remember { mutableStateOf(repo.hasAccount()) }
    var loaded by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(hasAccount) {
        if (hasAccount) { runCatching { repo.load() }; loaded = true; act.onAccountReady() }
    }
    LaunchedEffect(Unit) { app.calls.error.collect { if (it.isNotEmpty()) { snack.showSnackbar(it); app.calls.error.value = "" } } }

    Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
        CallBanner(app, act)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showUpdate.value) UpdateDialog(app, act) { showUpdate.value = false }
            if (!hasAccount) {
                Welcome(app, pendingInvite.value, inviteCode.value, onDone = { hasAccount = true }, onError = { scope.launch { snack.showSnackbar(it) } })
            } else if (loaded) {
                Main(app, act, pendingInvite, openRequest, channelLink, shared, onUpdate = { showUpdate.value = true }, onLogout = { hasAccount = false; loaded = false }, toast = { scope.launch { snack.showSnackbar(it) } })
            }
        }
        }
    }
}

// ---------------- вітання ----------------

@Composable
private fun Welcome(app: ShepitApp, invite: Pair<String, String?>?, linkCode: String?, onDone: () -> Unit, onError: (String) -> Unit) {
    val repo = app.repo
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var restoreData by remember { mutableStateOf<ByteArray?>(null) }
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("open") }
    var code by remember(linkCode) { mutableStateOf(linkCode ?: "") }
    LaunchedEffect(Unit) { mode = repo.registrationMode() }
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreData = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        Image(androidx.compose.ui.res.painterResource(io.github.ya77papa.shepit.R.drawable.ic_launcher_fg), null, Modifier.size(96.dp))
        Text("Шепіт", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (invite != null) {
            Surface(color = Color(0xFF1F2B18), shape = RoundedCornerShape(10.dp)) {
                Text("${invite.second ?: "Хтось"} запрошує тебе поспілкуватися в Шепоті.", Modifier.padding(12.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
        Text("Месенджер без номера телефону. Твоя «адреса» — криптографічний ключ, який створюється прямо на цьому телефоні.", color = Muted)
        Spacer(Modifier.height(20.dp))
        if (restoreData == null && mode == "closed") {
            Surface(color = Color(0xFF2B2A14), shape = RoundedCornerShape(10.dp)) {
                Text("Реєстрацію нових користувачів на цьому сервері зараз закрито.", Modifier.padding(12.dp))
            }
            TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("У мене вже є акаунт — відновити з резервної копії") }
        } else if (restoreData == null) {
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("Як тебе називати (необов'язково)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Ім'я бачать лише ті, кого ти запросиш. Сервер його не знає.", color = Muted, fontSize = 12.sp)
            if (mode == "invite") {
                Spacer(Modifier.height(12.dp))
                if (linkCode != null) {
                    Text("Код запрошення: $linkCode", fontFamily = FontFamily.Monospace)
                } else {
                    OutlinedTextField(code, { code = it.take(20) }, label = { Text("Код запрошення: XXXX-XXXX-XXXX") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Реєстрація на цьому сервері — лише за запрошенням. Код є в посиланні, яке тобі надіслали.", color = Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val c = io.github.ya77papa.shepit.data.Api.normalizeInviteCode(code)
                    if (mode == "invite" && c == null) {
                        onError(if (code.isBlank()) "Потрібен код запрошення" else "Код запрошення має вигляд XXXX-XXXX-XXXX")
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        runCatching { repo.register(name, c) }
                            .onSuccess {
                                if (invite != null) runCatching { repo.addContact(invite.first, invite.second); repo.sendHello(invite.first) }
                                onDone()
                            }
                            .onFailure { onError(it.message ?: "Помилка") }
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (busy) "Створюю ключі…" else if (invite != null) "Створити акаунт і відповісти" else "Створити акаунт") }
            TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("У мене вже є акаунт — відновити з резервної копії") }
        } else {
            Text("Файл резервної копії обрано.")
            OutlinedTextField(password, { password = it }, label = { Text("Пароль резервної копії") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { repo.importBackup(restoreData!!, password) }
                            .onSuccess { onDone() }
                            .onFailure { onError(it.message ?: "Помилка") }
                        busy = false
                    }
                },
                enabled = !busy && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (busy) "Розшифровую…" else "Відновити акаунт") }
            TextButton(onClick = { restoreData = null }) { Text("Назад") }
        }
        Spacer(Modifier.height(24.dp))
        Text("Відкритий код · версія ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
    }
}

// ---------------- головний екран ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Main(
    app: ShepitApp, act: MainActivity, pendingInvite: MutableState<Pair<String, String?>?>, openRequest: MutableState<String?>,
    channelLink: MutableState<Pair<String, String?>?>, shared: MutableState<String?>,
    onUpdate: () -> Unit, onLogout: () -> Unit, toast: (String) -> Unit,
) {
    val repo = app.repo
    val contacts by repo.contacts.collectAsState()
    var screen by remember { mutableStateOf(Screen.CONTACTS) }
    /** Відкрита бесіда: ідентичність контакту або "g:<id>" для групи. */
    var peer by remember { mutableStateOf<String?>(null) }
    var channel by remember { mutableStateOf<ChannelRec?>(null) }
    var channelSub by remember { mutableStateOf(true) }
    var showInvite by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    /** Чернетка для відкритої бесіди/каналу (текст, яким поділились). */
    var draftFor by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun openChat(p: String) { peer = p; screen = Screen.CHAT }
    LaunchedEffect(shared.value) { if (shared.value != null) screen = Screen.SHARE }
    fun home() { screen = Screen.CONTACTS; peer = null; channel = null }

    /** Канал за ідентифікатором (і ключем): підписаний — одразу, інакше попередній перегляд. */
    fun openChannel(id: String, key: String?) {
        repo.channels.value.firstOrNull { it.id == id }?.let { channel = it; channelSub = true; screen = Screen.CHANNEL; return }
        scope.launch {
            runCatching { withContext(kotlinx.coroutines.Dispatchers.IO) { repo.api.channels.fetch(id, key) } }
                .onSuccess { r ->
                    if (r == null) toast("Посилання на зашифрований канал без ключа або з неправильним ключем")
                    else { channel = r.first; channelSub = false; screen = Screen.CHANNEL }
                }
                .onFailure { toast(it.message ?: "Помилка") }
        }
    }

    LaunchedEffect(openRequest.value, contacts) {
        val p = openRequest.value ?: return@LaunchedEffect
        if (p.startsWith("g:") || contacts.any { it.identity == p }) { openChat(p); openRequest.value = null }
    }
    LaunchedEffect(channelLink.value) {
        val l = channelLink.value ?: return@LaunchedEffect
        channelLink.value = null
        openChannel(l.first, l.second)
    }
    // Новий контакт з'явився (перше привітання), а нічого не відкрито — відкрити.
    LaunchedEffect(Unit) {
        repo.incoming.collect { m -> if (screen == Screen.CONTACTS && peer == null && !m.peer.startsWith("g:") && repo.contacts.value.any { it.identity == m.peer }) openChat(m.peer) }
    }
    // Нові дописи в каналах — раз на 2 хв, поки застосунок відкритий.
    LaunchedEffect(Unit) { while (true) { runCatching { repo.checkChannels() }; delay(120_000) } }
    LaunchedEffect(screen, peer) { MainActivity.openPeer = if (screen == Screen.CHAT) peer else null }

    BackHandler(enabled = screen != Screen.CONTACTS) {
        when (screen) {
            Screen.GROUP_INFO -> screen = Screen.CHAT
            else -> home()
        }
    }

    pendingInvite.value?.let { inv ->
        AlertDialog(
            onDismissRequest = { pendingInvite.value = null },
            title = { Text("Запрошення") },
            text = { Text("${inv.second ?: "Контакт"} запрошує тебе. Додати і привітатися?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingInvite.value = null
                    scope.launch {
                        val c = repo.addContact(inv.first, inv.second)
                        if (c == null) { toast("Це твоє власне запрошення або хибне посилання"); return@launch }
                        runCatching { repo.sendHello(c.identity) }.onFailure { toast(it.message ?: "Помилка") }
                        openChat(c.identity)
                    }
                }) { Text("Додати") }
            },
            dismissButton = { TextButton(onClick = { pendingInvite.value = null }) { Text("Не зараз") } },
        )
    }
    if (showInvite) InviteDialog(app) { showInvite = false }
    if (showLink) OpenLinkDialog(
        onClose = { showLink = false },
        onLink = { text ->
            val ch = io.github.ya77papa.shepit.data.Channels.parseLink(text)
            val inv = repo.parseInvite(text)
            when {
                ch != null -> { showLink = false; openChannel(ch.first, ch.second) }
                inv != null -> { showLink = false; pendingInvite.value = inv }
                else -> toast("Це не посилання Шепоту на канал чи запрошення")
            }
        },
    )
    if (showAdd) AddDialog(app, onDone = { c -> showAdd = false; if (c != null) openChat(c.identity) }, toast)

    when (screen) {
        Screen.CONTACTS -> ContactsScreen(
            app, contacts, onUpdate, onOpen = { openChat(it) }, onChannel = { channel = it; channelSub = true; screen = Screen.CHANNEL },
            onInvite = { showInvite = true }, onAdd = { showAdd = true }, onSettings = { screen = Screen.SETTINGS },
            onNewGroup = { screen = Screen.NEW_GROUP }, onNewChannel = { screen = Screen.NEW_CHANNEL }, onFindChannel = { screen = Screen.FIND_CHANNEL }, act,
            onOpenLink = { showLink = true },
        )
        Screen.CHAT -> peer?.let { p ->
            val initial = draftFor?.takeIf { it.first == p }?.second
            ChatScreen(app, act, p, onBack = { home() }, onGroupInfo = { screen = Screen.GROUP_INFO }, toast, initialDraft = initial)
        }
        Screen.SHARE -> ShareScreen(
            app, shared.value.orEmpty(),
            onBack = { shared.value = null; home() },
            onChat = { p -> draftFor = p to shared.value.orEmpty(); shared.value = null; openChat(p) },
            onChannel = { c -> draftFor = "c:${c.id}" to shared.value.orEmpty(); shared.value = null; channel = c; channelSub = true; screen = Screen.CHANNEL },
        )
        Screen.GROUP_INFO -> peer?.let { p -> GroupInfoScreen(app, p.removePrefix("g:"), onBack = { screen = Screen.CHAT }, onDeleted = { home() }, toast) }
        Screen.SETTINGS -> SettingsScreen(app, act, onBack = { home() }, onUpdate = onUpdate, onLogout = onLogout, toast)
        Screen.NEW_GROUP -> NewGroupScreen(app, onBack = { home() }, onCreated = { openChat("g:${it.id}") }, toast)
        Screen.NEW_CHANNEL -> NewChannelScreen(app, onBack = { home() }, onCreated = { channel = it; channelSub = true; screen = Screen.CHANNEL }, toast)
        Screen.FIND_CHANNEL -> FindChannelScreen(app, onBack = { home() }, onOpen = { openChannel(it, null) }, toast)
        Screen.CHANNEL -> channel?.let { c ->
            ChannelScreen(
                app, c, channelSub, initialDraft = draftFor?.takeIf { it.first == "c:${c.id}" }?.second, onBack = { home() },
                onSubscribe = { rec -> scope.launch { repo.saveChannel(rec); channelSub = true; toast("Ти підписався(-лась) на «${rec.name}»") } },
                onGone = { home() }, toast = toast,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactsScreen(
    app: ShepitApp, contacts: List<Contact>, onUpdate: () -> Unit, onOpen: (String) -> Unit, onChannel: (ChannelRec) -> Unit,
    onInvite: () -> Unit, onAdd: () -> Unit, onSettings: () -> Unit,
    onNewGroup: () -> Unit, onNewChannel: () -> Unit, onFindChannel: () -> Unit, act: MainActivity, onOpenLink: () -> Unit = {},
) {
    val repo = app.repo
    val connected by repo.connected.collectAsState()
    val groups by repo.groups.collectAsState()
    val channels by repo.channels.collectAsState()
    val presence by app.repo.presence.collectAsState()
    // Непрочитане — з бази; оновлюється з кожною зміною в бесідах.
    var unread by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(Unit) {
        suspend fun load() { unread = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.repo.db.unreadCounts() } }
        load()
        app.repo.changed.collect { load() }
    }
    var bigAvatar by remember { mutableStateOf<Pair<String, String?>?>(null) }
    bigAvatar?.let { (n, a) -> AvatarViewer(n, a) { bigAvatar = null } }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarView(repo.myName.ifEmpty { "?" }, repo.myAvatar.ifEmpty { null }, 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(repo.myName.ifEmpty { "Шепіт" }, fontWeight = FontWeight.Bold)
                        Text("Мій ID: ${repo.short(repo.core?.fingerprint() ?: "")}" + if (connected) " · на зв'язку" else " · з'єднання…", fontSize = 12.sp, color = Muted)
                    }
                }
            },
            actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.Add, "Створити") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Запросити друга") }, onClick = { menu = false; onInvite() })
                        DropdownMenuItem(text = { Text("Додати за ключем") }, onClick = { menu = false; onAdd() })
                        DropdownMenuItem(text = { Text("Нова група") }, enabled = contacts.isNotEmpty(), onClick = { menu = false; onNewGroup() })
                        DropdownMenuItem(text = { Text("Новий канал") }, onClick = { menu = false; onNewChannel() })
                        DropdownMenuItem(text = { Text("Знайти канал") }, onClick = { menu = false; onFindChannel() })
                        DropdownMenuItem(text = { Text("Відкрити посилання") }, onClick = { menu = false; onOpenLink() })
                    }
                }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Налаштування") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        val update by app.updater.available.collectAsState()
        update?.let { u ->
            Surface(color = Color(0xFF1F2B18), modifier = Modifier.fillMaxWidth().clickable(onClick = onUpdate)) {
                Text("Доступна нова версія ${u.versionName} — торкнись, щоб оновити", Modifier.padding(12.dp))
            }
        }
        if (!Notifications.canFullScreen(act)) {
            Surface(color = Color(0xFF2B2A14), modifier = Modifier.fillMaxWidth().clickable { act.askFullScreen() }) {
                Text("Дозволь Шепоту показувати вхідні дзвінки на весь екран — торкнись тут", Modifier.padding(12.dp))
            }
        }
        if (contacts.isEmpty() && groups.isEmpty() && channels.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Як почати спілкуватися", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("1. Натисни «Запросити друга» і надішли посилання будь-яким способом або покажи QR-код.\n\n2. Друг відкриває посилання і створює акаунт — ви одразу в контактах одне в одного.\n\n3. Пишіть і дзвоніть. Для впевненості звірте «код безпеки» наживо.")
                Spacer(Modifier.height(20.dp))
                Button(onClick = onInvite, colors = ButtonDefaults.buttonColors(containerColor = CalmButton, contentColor = Color(0xFFE6EDF5)), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Запросити друга") }
                TextButton(onClick = onFindChannel, modifier = Modifier.fillMaxWidth()) { Text("або знайди публічний канал") }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                if (contacts.isNotEmpty()) item { SectionLabel("Контакти") }
                items(contacts, key = { it.identity }) { c ->
                    val p = presence[c.identity]
                    ListItem(
                        leadingContent = { AvatarView(c.name, c.avatar, 44.dp, online = p?.on == true, unread = unread[c.identity] ?: 0) { bigAvatar = c.name to c.avatar } },
                        headlineContent = { Text(c.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            val st = io.github.ya77papa.shepit.data.PresenceText.of(p)
                            if (st != null) Text(st, fontSize = 12.sp, color = if (p?.on == true) OnlineGreen else Muted)
                            else Text(repo.short(c.fingerprint) + "…", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
                        },
                        modifier = Modifier.clickable { onOpen(c.identity) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                if (groups.isNotEmpty()) item { SectionLabel("Групи") }
                items(groups, key = { "g:" + it.id }) { g ->
                    ListItem(
                        leadingContent = { AvatarView(g.name, g.avatar, 44.dp, unread = unread["g:" + g.id] ?: 0) { bigAvatar = g.name to g.avatar } },
                        headlineContent = { Text(g.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(if (g.left) "ти не в групі" else "${g.members.size} учасн.", fontSize = 12.sp, color = Muted) },
                        modifier = Modifier.clickable { onOpen("g:${g.id}") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                if (channels.isNotEmpty()) item { SectionLabel("Канали") }
                items(channels, key = { "c:" + it.id }) { c ->
                    ListItem(
                        leadingContent = { AvatarView(c.name, c.avatar, 44.dp, unread = (c.lastSeq - c.readSeq).coerceIn(0, 1000).toInt()) },
                        headlineContent = { Text(c.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(if (c.mine) "мій канал" else if (c.public) "публічний" else "зашифрований", fontSize = 12.sp, color = Muted) },
                        modifier = Modifier.clickable { onChannel(c) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            Button(onClick = onInvite, colors = ButtonDefaults.buttonColors(containerColor = CalmButton, contentColor = Color(0xFFE6EDF5)), modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) { Text("Запросити друга") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp), color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
}

@Composable
private fun InviteDialog(app: ShepitApp, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var mine by remember { mutableStateOf<io.github.ya77papa.shepit.data.Repo.MyInvite?>(null) }
    LaunchedEffect(Unit) { mine = app.repo.myInvite() }
    val url = remember(mine) { app.repo.inviteUrl(mine?.code) }
    val qr = remember(url) { qrBitmap(url) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Запросити друга") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(qr, "QR-код запрошення", Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)).background(Color.White))
                Spacer(Modifier.height(8.dp))
                Text("Друг сканує QR камерою або відкриває посилання. Воно містить лише твій публічний ключ.", color = Muted, fontSize = 13.sp)
                mine?.let { m ->
                    val note = when {
                        m.mode == "invite" && m.code != null -> "Реєстрація на сервері — за запрошенням. Посилання містить твій одноразовий код (діє 30 днів)" + if (m.left > 0) "; ще можна створити ${m.left}." else "."
                        m.mode == "invite" -> "Твої запрошення закінчились. Друг, який уже має Шепіт, може додати тебе за цим посиланням; новому користувачеві потрібен код від адміністратора."
                        m.mode == "closed" -> "Реєстрацію нових користувачів закрито — посилання спрацює лише для тих, хто вже має Шепіт."
                        else -> null
                    }
                    if (note != null) { Spacer(Modifier.height(6.dp)); Text(note, fontSize = 13.sp, color = if (m.code == null) Color(0xFFE0B45A) else Muted) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                (ctx.applicationContext as io.github.ya77papa.shepit.ShepitApp).lock.expectReturn(); ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Пиши мені в Шепоті — без номера телефону, з наскрізним шифруванням: $url"), "Поділитися"))
            }) { Text("Поділитися") }
        },
        dismissButton = {
            TextButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Шепіт", url))
                onClose()
            }) { Text("Копіювати") }
        },
    )
}

@Composable
private fun AddDialog(app: ShepitApp, onDone: (Contact?) -> Unit, toast: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Додати за ключем") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Ім'я") }, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text("Ключ (05…) або посилання") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val inv = app.repo.parseInvite(key)
                val c = inv?.let { app.repo.addContact(it.first, name.ifBlank { it.second }) }
                if (c == null) toast("Це не схоже на ключ Шепоту чи посилання-запрошення") else onDone(c)
            }) { Text("Додати") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Скасувати") } },
    )
}

// ---------------- розмова ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(app: ShepitApp, act: MainActivity, peer: String, onBack: () -> Unit, onGroupInfo: () -> Unit, toast: (String) -> Unit, initialDraft: String? = null) {
    val repo = app.repo
    val contacts by repo.contacts.collectAsState()
    val presence by repo.presence.collectAsState()
    val groups by repo.groups.collectAsState()
    val group = groups.firstOrNull { "g:${it.id}" == peer }
    val isGroup = peer.startsWith("g:")
    val contact = contacts.firstOrNull { it.identity == peer }
    val title = group?.name ?: contact?.name ?: repo.nameOf(peer)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf(emptyList<Message>()) }
    var draft by remember(peer) { mutableStateOf(initialDraft ?: "") }
    var busy by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableLongStateOf(-1L) }
    val callState by app.calls.state.collectAsState()
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var emojiOpen by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Message?>(null) }
    var showAvatar by remember { mutableStateOf(false) }
    /** На яке повідомлення відповідаю (цитата над полем введення). */
    var replyTo by remember(peer) { mutableStateOf<Message?>(null) }
    var flashId by remember { mutableLongStateOf(-1L) }
    fun takeReply() = replyTo?.let { repo.replyTo(peer, it) }.also { replyTo = null }
    fun who(id: String) = if (id == repo.core?.identity()) "Ти" else if (group != null) displayName(app, contacts, group, id) else title
    fun jumpTo(mid: String) = scope.launch {
        val i = messages.indexOfFirst { it.mid == mid }
        if (i < 0) { toast("Оригінал уже видалено"); return@launch }
        list.animateScrollToItem(i)
        flashId = messages[i].id
        delay(1200)
        flashId = -1L
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val caption = draft.trim().ifEmpty { null }
        busy = true
        scope.launch {
            runCatching {
                val p = io.github.ya77papa.shepit.data.Photo.prepare(ctx, uri)
                repo.sendImage(peer, p.jpeg, p.w, p.h, caption, takeReply())
            }.onSuccess { failed -> draft = ""; emojiOpen = false; if (failed > 0) toast("Не вдалося доставити $failed учасникам — спробуй пізніше") }
                .onFailure { toast(it.message ?: "Помилка") }
            busy = false
        }
    }
    val myId = remember { repo.core?.identity() }
    fun react(m: Message, e: String) = scope.launch { runCatching { repo.react(m, e) }.onFailure { toast(it.message ?: "Помилка") } }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // clear-me | clear-all | contact

    fun runOp(op: suspend () -> Unit) {
        scope.launch { runCatching { op() }.onFailure { toast(it.message ?: "Помилка") } }
    }

    suspend fun reload() {
        messages = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.db.messages(peer) }
        if (messages.isNotEmpty()) list.scrollToItem(messages.size - 1)
        if (repo.visibleChat == peer) repo.markRead(peer)
    }
    // Бесіда «переглянута», лише коли застосунок на екрані й не заблокований.
    val locked by app.lock.locked.collectAsState()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsState()
    val onScreen = resumed.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && !locked
    LaunchedEffect(peer, onScreen) {
        if (onScreen) {
            repo.visibleChat = peer
            io.github.ya77papa.shepit.service.Notifications.cancelMessages(ctx, peer)
            runCatching { repo.markRead(peer) }
        } else if (repo.visibleChat == peer) repo.visibleChat = null
    }
    // Тихі звуки, поки бесіда на екрані.
    val sounds = remember { ChatSounds(ctx) }
    DisposableEffect(Unit) { onDispose { sounds.release() } }
    LaunchedEffect(peer, onScreen) {
        if (!onScreen) return@LaunchedEffect
        launch { repo.sent.collect { if (it == peer && repo.chatSounds) sounds.sent() } }
        repo.incoming.collect { if (it.peer == peer && repo.chatSounds) sounds.received() }
    }
    DisposableEffect(peer) { onDispose { if (repo.visibleChat == peer) repo.visibleChat = null } }
    val readReceipts = remember { repo.readReceipts }
    fun tickOf(m: Message): Pair<String, String>? {
        if (!m.out || m.kind == "call") return null
        if (m.status == "sending") return "sending" to "Надсилається"
        if (m.status == "failed") return "failed" to "Не надіслано. Натисни, щоб повторити"
        val others = if (group != null) group.members.map { it.id }.filter { it != myId } else listOf(peer)
        if (others.isEmpty()) return "sent" to "Надіслано"
        val dlv = m.dlv + m.rd
        val nd = others.count { it in dlv }
        val nr = others.count { it in m.rd }
        val detail = if (group != null) " (доставлено $nd з ${others.size}${if (readReceipts) ", переглянуто $nr" else ""})" else ""
        return when {
            readReceipts && nr == others.size -> "read" to "Переглянуто$detail"
            nd == others.size -> "delivered" to "Доставлено$detail"
            else -> "sent" to (if (group != null) "Надіслано" else "На сервері, ще не доставлено") + detail
        }
    }
    fun retry(m: Message) = scope.launch {
        runCatching { repo.retry(m) }.onSuccess { failed -> if (failed > 0) toast("Не вдалося доставити $failed учасникам — спробуй пізніше") }
            .onFailure { toast(it.message ?: "Помилка") }
    }
    LaunchedEffect(peer) {
        reload()
        repo.changed.collect { if (it == peer) reload() }
    }
    LaunchedEffect(recording) { while (recording) { recMs = recorder.elapsed(); if (recMs >= VoiceRecorder.MAX_MS) { recording = false; recorder.stop()?.let { (d, dur) -> val re = takeReply(); scope.launch { runCatching { repo.sendVoice(peer, recorder.mime, dur, d, re) }.onFailure { toast(it.message ?: "Помилка") } } } }; delay(200) } }
    DisposableEffect(Unit) { onDispose { VoicePlayer.stop(); recorder.stop(cancel = true) } }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Аватар — переглянути великим; ім'я — перейменувати (у групі — учасники).
                    val pres = if (isGroup) null else presence[peer]
                    AvatarView(title, group?.avatar ?: contact?.avatar, 36.dp, online = pres?.on == true, ring = MaterialTheme.colorScheme.surface) { showAvatar = true }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.clickable { if (isGroup) onGroupInfo() else rename = true }) {
                        Text(title, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (group != null) Text("${group.members.size} учасн.", fontSize = 12.sp, color = Muted)
                        else io.github.ya77papa.shepit.data.PresenceText.of(pres)?.let { Text(it, fontSize = 12.sp, color = if (pres?.on == true) OnlineGreen else Muted) }
                    }
                }
            },
            actions = {
                if (isGroup) {
                    IconButton(onClick = onGroupInfo) { Icon(Icons.Default.Groups, "Учасники") }
                } else {
                IconButton(onClick = { showSafety = !showSafety }) { Icon(Icons.Default.Lock, "Код безпеки") }
                IconButton(enabled = callState == io.github.ya77papa.shepit.call.CallManager.State.IDLE, onClick = {
                    if (!act.hasMic()) { act.micPermission.launch(Manifest.permission.RECORD_AUDIO); return@IconButton }
                    app.calls.start(peer)
                    ctx.startActivity(Intent(ctx, CallActivity::class.java))
                }) { Icon(Icons.Default.Call, "Голосовий дзвінок") }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Ще") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (isGroup) {
                            DropdownMenuItem(text = { Text("Учасники й налаштування") }, onClick = { menu = false; onGroupInfo() })
                            DropdownMenuItem(text = { Text("Очистити історію в мене") }, onClick = { menu = false; confirm = "clear-me" })
                        } else {
                        DropdownMenuItem(text = { Text("Очистити бесіду в мене") }, onClick = { menu = false; confirm = "clear-me" })
                        DropdownMenuItem(text = { Text("Очистити в мене і в ${title}") }, onClick = { menu = false; confirm = "clear-all" })
                        DropdownMenuItem(text = { Text("Видалити контакт", color = Danger) }, onClick = { menu = false; confirm = "contact" })
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        if (showSafety && !isGroup) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Цей код однаковий у вас обох. Порівняйте його наживо або по телефону: якщо збігається — ніхто не підмінив ключі.", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(remember(peer) { repo.safetyNumber(peer) }, fontFamily = FontFamily.Monospace)
                }
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = list) {
            items(messages, key = { it.id }) { m ->
                Bubble(m, playing == m.id, sender = if (group != null && !m.out && m.kind != "call") displayName(app, contacts, group, m.sender) else null, myId = myId, onReact = { e -> react(m, e) }, onLongPress = { actionsFor = m }, onOpen = { viewing = m }, tick = tickOf(m), onRetry = { retry(m) },
                    quoteWho = m.re?.let { who(it.by) }, onQuote = { m.re?.let { jumpTo(it.id) } }, flash = flashId == m.id, counts = group != null) {
                    VoicePlayer.toggle(ctx, m.id, m.voice ?: return@Bubble, m.voiceMime) { playing = VoicePlayer.playingId }
                    playing = VoicePlayer.playingId
                }
            }
        }
        if (group?.left == true) Surface(color = MaterialTheme.colorScheme.surface) {
            Text("Ти не в цій групі — писати сюди не можна.", Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), color = Muted)
        } else Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
            replyTo?.let { r ->
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Quote("Відповідь: ${if (r.out) "Ти" else if (group != null) displayName(app, contacts, group, r.sender) else title}", repo.replyTo(peer, r)?.t ?: r.text, Modifier.weight(1f))
                    IconButton(onClick = { replyTo = null }) { Icon(Icons.Default.Close, "Скасувати відповідь", tint = Muted) }
                }
            }
            if (emojiOpen && !recording) EmojiPanel { e -> draft += e }
            Row(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                if (recording) {
                    Text("Запис ${Content.fmtDuration(recMs)} / ${Content.fmtDuration(VoiceRecorder.MAX_MS)}", Modifier.weight(1f).padding(8.dp))
                    TextButton(onClick = { recording = false; recorder.stop(cancel = true) }) { Text("Скасувати") }
                    Button(onClick = {
                        recording = false
                        recorder.stop()?.let { (d, dur) -> val re = takeReply(); scope.launch { runCatching { repo.sendVoice(peer, recorder.mime, dur, d, re) }.onFailure { toast(it.message ?: "Помилка") } } }
                    }) { Text("Надіслати") }
                } else {
                    IconButton(onClick = { emojiOpen = !emojiOpen }) {
                        Icon(if (emojiOpen) Icons.Default.Keyboard else Icons.Default.EmojiEmotions, "Емодзі", tint = if (emojiOpen) Accent else Muted)
                    }
                    IconButton(enabled = !busy, onClick = {
                        app.lock.expectReturn()
                        pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Default.AddPhotoAlternate, "Надіслати фото", tint = Muted) }
                    OutlinedTextField(draft, { draft = it }, placeholder = { Text("Повідомлення") }, modifier = Modifier.weight(1f), maxLines = 5)
                    if (draft.isNotBlank()) {
                        IconButton(enabled = !busy, onClick = {
                            val t = draft.trim(); busy = true
                            scope.launch {
                                val re = takeReply()
                                runCatching { repo.sendText(peer, t, re) }.onSuccess { failed -> draft = ""; if (failed > 0) toast("Не вдалося доставити $failed учасникам — спробуй пізніше") }.onFailure { toast(it.message ?: "Помилка") }
                                busy = false
                            }
                        }) { Icon(Icons.AutoMirrored.Filled.Send, "Надіслати", tint = Accent) }
                    } else {
                        IconButton(onClick = {
                            if (!act.hasMic()) { act.micPermission.launch(Manifest.permission.RECORD_AUDIO); return@IconButton }
                            runCatching { recorder.start(); recording = true }.onFailure { toast("Не вдалося почати запис") }
                        }) { Icon(Icons.Default.Mic, "Голосове повідомлення") }
                    }
                }
            }
            }
        }
    }
    viewing?.let { m -> PhotoViewer(m, onClose = { viewing = null }, toast = toast) }
    if (showAvatar) AvatarViewer(title, group?.avatar ?: contact?.avatar) { showAvatar = false }
    actionsFor?.let { m ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text("Повідомлення") },
            text = {
                Column {
                    if (m.mid != null && m.kind != "call") {
                        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Content.QUICK_REACTIONS.forEach { e ->
                                val mine = myId != null && m.reactions[myId] == e
                                Box(
                                    Modifier.clip(CircleShape).background(if (mine) Accent.copy(alpha = 0.25f) else Color.Transparent)
                                        .clickable { actionsFor = null; react(m, e) }.padding(4.dp),
                                ) { Text(e, fontSize = 24.sp) }
                            }
                        }
                    }
                    if (m.kind != "voice" && m.kind != "call" && m.kind != "image") Text(m.text, maxLines = 4, color = Muted)
                    if (m.out && m.mid == null && m.kind != "call") Text("Це повідомлення надіслане старою версією — видалити його можна лише в себе.", fontSize = 12.sp, color = Muted)
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (m.mid != null && m.kind != "call" && group?.left != true) TextButton(onClick = { actionsFor = null; replyTo = m }) { Text("Відповісти") }
                    if (m.out && m.status == "failed") TextButton(onClick = { actionsFor = null; retry(m) }) { Text("Надіслати ще раз") }
                    if (m.out && m.mid != null) TextButton(onClick = { actionsFor = null; runOp { repo.deleteMessage(m, everywhere = true) } }) { Text("Видалити у всіх", color = Danger) }
                    TextButton(onClick = { actionsFor = null; runOp { repo.deleteMessage(m, everywhere = false) } }) { Text("Видалити в мене") }
                    if (m.kind == "text") TextButton(onClick = {
                        actionsFor = null
                        ctx.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Шепіт", m.text))
                    }) { Text("Копіювати") }
                    TextButton(onClick = { actionsFor = null }) { Text("Скасувати") }
                }
            },
        )
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(when (what) { "contact" -> "Видалити контакт?"; else -> "Очистити бесіду?" }) },
            text = {
                Text(when (what) {
                    "clear-me" -> if (isGroup) "Історія групи «$title» зникне з цього телефона." else "Уся бесіда з ${title} зникне лише в тебе. У співрозмовника все залишиться."
                    "clear-all" -> "Уся бесіда зникне і в тебе, і в ${title}. Цього не скасувати."
                    else -> "Контакт ${title} і вся бесіда з ним зникнуть у тебе. Якщо ця людина напише знову, вона з'явиться як новий контакт."
                })
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    runOp {
                        when (what) {
                            "clear-me" -> repo.clearChat(peer, everywhere = false)
                            "clear-all" -> repo.clearChat(peer, everywhere = true)
                            else -> { repo.deleteContact(peer); onBack() }
                        }
                    }
                }) { Text(if (what == "contact") "Видалити" else "Очистити", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Скасувати") } },
        )
    }
    if (rename) {
        var n by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Ім'я контакту") },
            text = { OutlinedTextField(n, { n = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (n.isNotBlank()) repo.rename(peer, n); rename = false }) { Text("Зберегти") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Скасувати") } },
        )
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale("uk"))

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Message, playing: Boolean, sender: String? = null, myId: String? = null,
    onReact: (String) -> Unit = {}, onLongPress: () -> Unit, onOpen: () -> Unit = {},
    tick: Pair<String, String>? = null, onRetry: () -> Unit = {},
    quoteWho: String? = null, onQuote: () -> Unit = {}, flash: Boolean = false, counts: Boolean = true, onPlay: () -> Unit,
) {
    val system = m.kind == "call"
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalAlignment = when { system -> Alignment.CenterHorizontally; m.out -> Alignment.End; else -> Alignment.Start },
    ) {
        if (system) {
            Text(m.text, color = Muted, fontSize = 13.sp, modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress))
            return@Column
        }
        if (sender != null) Text(sender, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
        if (m.re != null && quoteWho != null) Quote(quoteWho, m.re.t, Modifier.widthIn(max = 300.dp).padding(bottom = 3.dp).clickable(onClick = onQuote))
        Surface(
            color = if (m.out) OutBubble else InBubble, shape = RoundedCornerShape(14.dp),
            border = if (flash) androidx.compose.foundation.BorderStroke(2.dp, Accent) else null,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp))
                .combinedClickable(onClick = { if (m.kind == "voice") onPlay() else if (m.kind == "image") onOpen() }, onLongClick = onLongPress),
        ) {
            if (m.kind == "image") {
                val bytes = m.voice
                val bmp = remember(m.id) { bytes?.let { io.github.ya77papa.shepit.data.Photo.decode(it, 720) } }
                Column(Modifier.padding(4.dp)) {
                    if (bmp != null) Image(
                        bmp.asImageBitmap(), "Фото",
                        Modifier.widthIn(max = 280.dp).heightIn(max = 380.dp).clip(RoundedCornerShape(11.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    ) else Text("Фото пошкоджене", Modifier.padding(8.dp), color = Muted)
                    if (m.text != "Фото") Text(m.text, Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                }
            } else if (m.kind == "voice") {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("${Content.fmtDuration(m.voiceDur)}")
                }
            } else {
                Text(m.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        if (m.reactions.isNotEmpty()) {
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                m.reactions.entries.groupBy { it.value }.forEach { (e, who) ->
                    val mine = who.any { it.key == myId }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (mine) Accent.copy(alpha = 0.18f) else InBubble,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (mine) Accent else Color(0xFF243246)),
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onReact(e) },
                    ) { Text(if (counts) "$e ${who.size}" else e, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 13.sp) }
                }
            }
        }
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(timeFmt.format(Date(m.at)), color = Muted, fontSize = 11.sp)
            if (tick != null) Ticks(tick.first, tick.second, onRetry)
        }
    }
}

/** Цитата: смужка зліва, хто написав, уривок в один рядок. */
@Composable
private fun Quote(who: String, text: String, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)).background(Color.White.copy(alpha = 0.06f)).height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Accent))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(who, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(text, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** Галочки (варіант A): годинник — надсилається, одна — на сервері, дві — доставлено, дві кольорові — переглянуто. */
@Composable
private fun Ticks(state: String, title: String, onRetry: () -> Unit) {
    val (icon, tint) = when (state) {
        "sending" -> Icons.Default.Schedule to Muted
        "failed" -> Icons.Default.ErrorOutline to Danger
        "read" -> Icons.Default.DoneAll to Accent
        "delivered" -> Icons.Default.DoneAll to Muted
        else -> Icons.Default.Done to Muted
    }
    val mod = Modifier.padding(start = 3.dp).size(15.dp)
    Icon(icon, title, tint = tint, modifier = if (state == "failed") mod.clickable(onClick = onRetry) else mod)
}

/** Фото на весь екран: масштаб пальцями, «Зберегти» — у файл, який обере користувач. */
@Composable
private fun PhotoViewer(m: Message, onClose: () -> Unit, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ShepitApp
    val bytes = m.voice ?: return
    val bmp = remember(m.id) { io.github.ya77papa.shepit.data.Photo.decode(bytes, 2048) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Не вдалося зберегти")
        }.onSuccess { toast("Збережено") }.onFailure { toast(it.message ?: "Не вдалося зберегти") }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (bmp != null) Image(
                bmp.asImageBitmap(), "Фото",
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = {
                    app.lock.expectReturn()
                    save.launch("shepit-${SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date(m.at))}.jpg")
                }) { Text("Зберегти") }
                OutlinedButton(onClick = onClose) { Text("Закрити") }
            }
        }
    }
}

// ---------------- налаштування ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(app: ShepitApp, act: MainActivity, onBack: () -> Unit, onUpdate: () -> Unit, onLogout: () -> Unit, toast: (String) -> Unit) {
    val repo = app.repo
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(repo.myName) }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingBackup by remember { mutableStateOf<ByteArray?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var battery by remember { mutableStateOf(act.ignoringBattery()) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = pendingBackup
        if (uri != null && data != null) {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            toast("Резервну копію збережено. Зберігай файл і пароль окремо.")
        }
        pendingBackup = null
    }
    LifecycleResume { battery = act.ignoringBattery() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = {
                    val changed = repo.myName != name.trim()
                    repo.myName = name
                    if (changed) repo.scope.launch { repo.sendProfile() } // нове ім'я — контактам
                    onBack()
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            },
            title = { Text("Налаштування") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Section("Моє фото") {
                var avatar by remember { mutableStateOf(repo.myAvatar) }
                val pick = rememberAvatarPicker(toast) { a ->
                    repo.myAvatar = a; avatar = a
                    repo.sendProfile()
                    toast("Фото оновлено і надіслано контактам")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AvatarView(name.ifEmpty { "?" }, avatar.ifEmpty { null }, 64.dp)
                    OutlinedButton(onClick = pick) { Text("Обрати") }
                    if (avatar.isNotEmpty()) TextButton(onClick = { repo.myAvatar = ""; avatar = ""; scope.launch { repo.sendProfile() } }) { Text("Прибрати") }
                }
                Text("Бачать лише твої контакти: фото йде їм зашифрованим, сервер його не бачить.", color = Muted, fontSize = 12.sp)
            }
            Section("Моє ім'я") {
                OutlinedTextField(name, { name = it.take(60) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Бачать лише ті, кого ти запрошуєш. Сервер його не знає.", color = Muted, fontSize = 12.sp)
            }
            Section("Мій ID") { Text(repo.core?.fingerprint() ?: "", fontFamily = FontFamily.Monospace) }
            Section("Дзвінки, коли застосунок закритий") {
                Text("Шепіт тримає з'єднання з сервером без сервісів Google (тихе сповіщення «на зв'язку»).", color = Muted, fontSize = 13.sp)
                if (!battery) OutlinedButton(onClick = { act.askBattery() }) { Text("Дозволити працювати у фоні") } else Text("Фонова робота дозволена")
                if (!Notifications.canFullScreen(ctx)) OutlinedButton(onClick = { act.askFullScreen() }) { Text("Дозволити дзвінки на весь екран") } else Text("Дзвінки на весь екран дозволені")
            }
            Section("Дзвінки: діагностика") {
                var check by remember { mutableStateOf("") }
                var checking by remember { mutableStateOf(false) }
                val callLog by app.calls.log.collectAsState()
                OutlinedButton(enabled = !checking, onClick = {
                    checking = true; check = ""
                    scope.launch { check = app.calls.checkRelay(); checking = false }
                }) { Text(if (checking) "Перевіряю…" else "Перевірити ретранслятор") }
                if (check.isNotEmpty()) Text(check, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (callLog.isNotEmpty()) {
                    Text("Журнал останнього дзвінка (без вмісту розмови):", color = Muted, fontSize = 13.sp)
                    Text(callLog.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    OutlinedButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Шепіт: журнал дзвінка", "Шепіт ${BuildConfig.VERSION_NAME}\n" + callLog.joinToString("\n")))
                        toast("Журнал скопійовано")
                    }) { Text("Копіювати журнал") }
                }
            }
            Section("Блокування") {
                val lock = app.lock
                var pinOn by remember { mutableStateOf(lock.pinEnabled) }
                var bioOn by remember { mutableStateOf(lock.bioEnabled) }
                var delayMs by remember { mutableLongStateOf(lock.delayMs) }
                var secure by remember { mutableStateOf(lock.secureScreen) }
                var pinDialog by remember { mutableStateOf<String?>(null) } // set | off
                val bioOk = remember { lock.biometricAvailable() }
                Text("Хто тримає розблокований телефон, не відкриє Шепіт без PIN-коду чи відбитка. Вхідні дзвінки можна прийняти й без розблокування. Поки блокування увімкнене, сповіщення не показують текст повідомлень.", color = Muted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PIN-код", Modifier.weight(1f))
                    Switch(checked = pinOn, onCheckedChange = { pinDialog = if (it) "set" else "off" })
                }
                if (pinOn) TextButton(onClick = { pinDialog = "change" }) { Text("Змінити PIN-код") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Відбиток пальця")
                        if (!bioOk) Text("Недоступно: на телефоні немає сканера або не додано відбиток.", color = Muted, fontSize = 12.sp)
                        else if (!pinOn) Text("Запасний варіант — блокування самого телефона.", color = Muted, fontSize = 12.sp)
                    }
                    Switch(enabled = bioOk, checked = bioOn, onCheckedChange = { on ->
                        if (!on) { lock.bioEnabled = false; bioOn = false }
                        else lock.promptBiometric(act, "Підтверди відбиток", onSuccess = { lock.bioEnabled = true; bioOn = true }, onFallback = {})
                    })
                }
                if (pinOn || bioOn) {
                    Text("Блокувати після виходу з застосунку", color = Muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0L to "Одразу", 60_000L to "Через 1 хв", 300_000L to "Через 5 хв").forEach { (ms, label) ->
                            FilterChip(selected = delayMs == ms, onClick = { lock.delayMs = ms; delayMs = ms }, label = { Text(label) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Повідомляти про перегляд")
                        Text("Якщо вимкнути, ти теж не бачитимеш, чи переглянули твої. «Доставлено» працює завжди.", color = Muted, fontSize = 12.sp)
                    }
                    var rr by remember { mutableStateOf(app.repo.readReceipts) }
                    Switch(checked = rr, onCheckedChange = { app.repo.readReceipts = it; rr = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Показувати, коли я в мережі")
                        Text("Якщо вимкнути, інші бачитимуть лише «востаннє нещодавно», і ти так само не бачитимеш їхній час.", color = Muted, fontSize = 12.sp)
                    }
                    var sp by remember { mutableStateOf(app.repo.showPresence) }
                    Switch(checked = sp, onCheckedChange = {
                        app.repo.showPresence = it; sp = it
                        app.repo.scope.launch { app.repo.presenceBeat(online = true); app.repo.refreshPresence() }
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Звуки в бесіді")
                        Text("Тихий сигнал «надіслано» й «отримано», коли бесіда відкрита. У беззвучному режимі мовчить.", color = Muted, fontSize = 12.sp)
                    }
                    var cs by remember { mutableStateOf(app.repo.chatSounds) }
                    Switch(checked = cs, onCheckedChange = { app.repo.chatSounds = it; cs = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Приховувати вміст")
                        Text("У списку відкритих застосунків і на скріншотах (скріншоти Шепоту стануть неможливими).", color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = secure, onCheckedChange = { lock.secureScreen = it; secure = it; act.applySecureFlag() })
                }
                pinDialog?.let { mode ->
                    var step by remember(mode) { mutableStateOf(if (mode == "set") "new" else "current") }
                    var first by remember(mode) { mutableStateOf("") }
                    var msg by remember(mode) { mutableStateOf("") }
                    var checking by remember(mode) { mutableStateOf(false) }
                    AlertDialog(
                        onDismissRequest = { pinDialog = null },
                        title = { Text(when (step) { "current" -> "Поточний PIN-код"; "new" -> "Новий PIN-код (4–8 цифр)"; else -> "Повтори PIN-код" }) },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                if (msg.isNotEmpty()) Text(msg, color = Danger, fontSize = 13.sp)
                                Spacer(Modifier.height(8.dp))
                                key(step) {
                                    PinPad(if (step == "current") lock.pinLength else if (step == "repeat") first.length else 0, enabled = !checking) { pin ->
                                        when (step) {
                                            "current" -> {
                                                checking = true
                                                scope.launch {
                                                    val ok = withContext(kotlinx.coroutines.Dispatchers.Default) { lock.checkPin(pin) }
                                                    checking = false
                                                    when {
                                                        !ok -> msg = if (lock.waitSeconds() > 0) "Забагато спроб — зачекай ${lock.waitSeconds()} с" else "Неправильний PIN-код"
                                                        mode == "off" -> { lock.clearPin(); pinOn = false; pinDialog = null }
                                                        else -> { msg = ""; step = "new" }
                                                    }
                                                }
                                            }
                                            "new" -> { first = pin; msg = ""; step = "repeat" }
                                            else -> if (pin == first) {
                                                checking = true
                                                scope.launch {
                                                    withContext(kotlinx.coroutines.Dispatchers.Default) { lock.setPin(pin) }
                                                    checking = false; pinOn = true; pinDialog = null
                                                    toast("PIN-код збережено")
                                                }
                                            } else { msg = "PIN-коди не збігаються — спробуй ще раз"; first = ""; step = "new" }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = { TextButton(onClick = { pinDialog = null }) { Text("Скасувати") } },
                    )
                }
            }
            Section("Резервна копія") {
                Text("Файл з ключами, контактами й історією, зашифрований паролем. Сумісний з веб-версією.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(pw1, { pw1 = it }, label = { Text("Пароль (щонайменше ${Backup.MIN_PASSWORD} символів)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pw2, { pw2 = it }, label = { Text("Повтори пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && pw1.length >= Backup.MIN_PASSWORD, onClick = {
                    if (pw1 != pw2) { toast("Паролі не збігаються"); return@Button }
                    busy = true
                    scope.launch {
                        runCatching { repo.exportBackup(pw1) }
                            .onSuccess { pendingBackup = it; pw1 = ""; pw2 = ""; (ctx.applicationContext as io.github.ya77papa.shepit.ShepitApp).lock.expectReturn(); save.launch("shepit-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.shepit-backup") }
                            .onFailure { toast(it.message ?: "Помилка") }
                        busy = false
                    }
                }) { Text(if (busy) "Шифрую…" else "Зберегти у файл") }
            }
            Section("Версія й оновлення") {
                val upd = app.updater
                val available by upd.available.collectAsState()
                var auto by remember { mutableStateOf(upd.auto) }
                var checking by remember { mutableStateOf(false) }
                var status by remember { mutableStateOf("") }
                Text("${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT.take(7)}) · відкритий код: github.com/ya77papa/shepit", color = Muted, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Перевіряти оновлення автоматично", Modifier.weight(1f))
                    Switch(checked = auto, onCheckedChange = { auto = it; upd.auto = it })
                }
                Text("Раз на 12 год застосунок питає GitHub про нову версію (GitHub бачить лише IP-адресу). Оновлення ставиться тільки після твого підтвердження і тільки якщо підписане тим самим ключем.", color = Muted, fontSize = 12.sp)
                if (available != null) {
                    Button(onClick = onUpdate) { Text("Оновити до ${available!!.versionName}") }
                } else {
                    OutlinedButton(enabled = !checking, onClick = {
                        checking = true; status = ""
                        scope.launch {
                            status = runCatching { upd.check() }.fold({ if (it == null) "У тебе найновіша версія" else "" }, { "Не вдалося перевірити: ${it.message}" })
                            checking = false
                        }
                    }) { Text(if (checking) "Перевіряю…" else "Перевірити оновлення") }
                    if (status.isNotEmpty()) Text(status, fontSize = 13.sp)
                }
            }
            Button(onClick = { confirmLogout = true }, colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Text("Вийти й стерти ключі з цього телефона")
            }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("Стерти акаунт?") },
        text = { Text("Без резервної копії ключі буде втрачено назавжди.") },
        confirmButton = { TextButton(onClick = { repo.wipe(); confirmLogout = false; ctx.stopService(Intent(ctx, io.github.ya77papa.shepit.service.ShepitService::class.java)); onLogout() }) { Text("Стерти", color = Danger) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Скасувати") } },
    )
}

@Composable
private fun UpdateDialog(app: ShepitApp, act: MainActivity, onClose: () -> Unit) {
    val upd = app.updater
    val rel by upd.available.collectAsState()
    val progress by upd.progress.collectAsState()
    val err by upd.error.collectAsState()
    val scope = rememberCoroutineScope()
    var canInstall by remember { mutableStateOf(upd.canInstall()) }
    var checked by remember { mutableStateOf(rel != null) }
    LifecycleResume { canInstall = upd.canInstall() }
    LaunchedEffect(Unit) { if (rel == null) { runCatching { upd.check() }; checked = true } }
    AlertDialog(
        onDismissRequest = { if (progress == null) onClose() },
        title = { Text("Оновлення Шепоту") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val r = rel
                when {
                    !checked -> Text("Перевіряю…")
                    r == null -> Text("У тебе найновіша версія (${BuildConfig.VERSION_NAME}).")
                    else -> {
                        Text("Нова версія: ${r.versionName} (зараз ${BuildConfig.VERSION_NAME}), ${r.size / 1_000_000} МБ.")
                        if (!canInstall) Text("Спершу дозволь Шепоту встановлювати оновлення — це одноразове налаштування Android.", color = Muted, fontSize = 13.sp)
                        progress?.let { p ->
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            Text(if (p >= 1f) "Перевіряю й встановлюю…" else "Завантажую… ${(p * 100).toInt()}%", fontSize = 13.sp)
                        }
                    }
                }
                if (err.isNotEmpty()) Text(err, color = Danger, fontSize = 13.sp)
            }
        },
        confirmButton = {
            val r = rel
            if (r != null && progress == null) {
                if (!canInstall) TextButton(onClick = { upd.openInstallPermission(act) }) { Text("Дозволити") }
                else TextButton(onClick = { scope.launch { runCatching { upd.downloadAndInstall(r) } } }) { Text("Оновити") }
            }
        },
        dismissButton = { if (progress == null) TextButton(onClick = onClose) { Text(if (rel == null) "Закрити" else "Пізніше") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
        content()
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
private fun LifecycleResume(onResume: () -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) onResume() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}


/** Панель емодзі над полем введення (ті самі групи, що у веб-версії). */
@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(44.dp),
        modifier = Modifier.fillMaxWidth().height(240.dp).padding(horizontal = 8.dp),
    ) {
        Content.EMOJI.forEach { (title, list) ->
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(title, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp))
            }
            items(list.size) { i ->
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { onPick(list[i]) }, contentAlignment = Alignment.Center) {
                    Text(list[i], fontSize = 24.sp)
                }
            }
        }
    }
}


/** «Поділитися» з іншого застосунку: кому надіслати (текст стане чернеткою — можна доповнити). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareScreen(app: ShepitApp, text: String, onBack: () -> Unit, onChat: (String) -> Unit, onChannel: (ChannelRec) -> Unit) {
    val repo = app.repo
    val contacts by repo.contacts.collectAsState()
    val groups by repo.groups.collectAsState()
    val channels by repo.channels.collectAsState()
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Скасувати") } },
            title = { Text("Надіслати в Шепіт", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Surface(color = InBubble, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text, Modifier.padding(12.dp), maxLines = 6, color = Muted)
        }
        LazyColumn(Modifier.weight(1f)) {
            if (contacts.isNotEmpty()) item { SectionLabel("Контакти") }
            items(contacts, key = { it.identity }) { c ->
                ListItem(
                    leadingContent = { AvatarView(c.name, c.avatar, 40.dp) }, headlineContent = { Text(c.name) },
                    modifier = Modifier.clickable { onChat(c.identity) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            val active = groups.filter { !it.left }
            if (active.isNotEmpty()) item { SectionLabel("Групи") }
            items(active, key = { "g:" + it.id }) { g ->
                ListItem(
                    leadingContent = { AvatarView(g.name, g.avatar, 40.dp) }, headlineContent = { Text(g.name) },
                    modifier = Modifier.clickable { onChat("g:${g.id}") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            val mine = channels.filter { it.mine }
            if (mine.isNotEmpty()) item { SectionLabel("Мої канали") }
            items(mine, key = { "c:" + it.id }) { c ->
                ListItem(
                    leadingContent = { AvatarView(c.name, c.avatar, 40.dp) }, headlineContent = { Text(c.name) },
                    modifier = Modifier.clickable { onChannel(c) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            if (contacts.isEmpty() && active.isEmpty() && mine.isEmpty()) item { Text("Немає кому надіслати — спершу додай контакт.", Modifier.padding(16.dp), color = Muted) }
        }
    }
}


/** Вставити посилання Шепоту (канал чи запрошення), отримане будь-яким способом. */
@Composable
private fun OpenLinkDialog(onClose: () -> Unit, onLink: (String) -> Unit) {
    val ctx = LocalContext.current
    var text by remember {
        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        mutableStateOf(if ("#c=" in clip || "#add=" in clip) clip.trim() else "")
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Відкрити посилання") },
        text = {
            Column {
                Text("Встав посилання на канал або запрошення, яке тобі надіслали.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it }, placeholder = { Text("https://…/#c=…") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onLink(text.trim()) }) { Text("Відкрити") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Скасувати") } },
    )
}

/** Дзвінок триває, а екран дзвінка згорнуто: повернутися або завершити. */
@Composable
private fun CallBanner(app: ShepitApp, act: MainActivity) {
    val st by app.calls.state.collectAsState()
    val peer by app.calls.peer.collectAsState()
    if (st == io.github.ya77papa.shepit.call.CallManager.State.IDLE) return
    val name = remember(peer) { if (peer.isEmpty()) "" else app.repo.nameOf(peer) }
    Surface(color = Color(0xFF1B5E3A), modifier = Modifier.fillMaxWidth().clickable {
        act.startActivity(android.content.Intent(act, CallActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (st == io.github.ya77papa.shepit.call.CallManager.State.INCOMING) "Вхідний дзвінок: $name — торкнись" else "Дзвінок з $name — торкнись, щоб повернутися",
                Modifier.weight(1f), color = Color.White, maxLines = 1,
            )
            TextButton(onClick = {
                if (st == io.github.ya77papa.shepit.call.CallManager.State.INCOMING) app.calls.reject() else app.calls.hangup()
            }) { Text("Завершити", color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
    }
}
