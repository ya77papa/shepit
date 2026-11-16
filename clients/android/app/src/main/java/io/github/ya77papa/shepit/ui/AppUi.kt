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
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen { CONTACTS, CHAT, SETTINGS }

@Composable
fun AppUi(app: ShepitApp, act: MainActivity, pendingInvite: MutableState<Pair<String, String?>?>, openRequest: MutableState<String?>, inviteCode: MutableState<String?>) {
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
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (!hasAccount) {
                Welcome(app, pendingInvite.value, inviteCode.value, onDone = { hasAccount = true }, onError = { scope.launch { snack.showSnackbar(it) } })
            } else if (loaded) {
                Main(app, act, pendingInvite, openRequest, onLogout = { hasAccount = false; loaded = false }, toast = { scope.launch { snack.showSnackbar(it) } })
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
        Text("🤫", fontSize = 56.sp)
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
                    Text("🎟 Код запрошення: $linkCode", fontFamily = FontFamily.Monospace)
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
    onLogout: () -> Unit, toast: (String) -> Unit,
) {
    val repo = app.repo
    val contacts by repo.contacts.collectAsState()
    var screen by remember { mutableStateOf(Screen.CONTACTS) }
    var current by remember { mutableStateOf<Contact?>(null) }
    var showInvite by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(openRequest.value, contacts) {
        val p = openRequest.value ?: return@LaunchedEffect
        contacts.firstOrNull { it.identity == p }?.let { current = it; screen = Screen.CHAT; openRequest.value = null }
    }
    // Новий контакт з'явився (перше привітання), а нічого не відкрито — відкрити.
    LaunchedEffect(Unit) {
        repo.incoming.collect { m -> if (screen == Screen.CONTACTS && current == null) repo.contacts.value.firstOrNull { it.identity == m.peer }?.let { current = it; screen = Screen.CHAT } }
    }
    LaunchedEffect(screen, current) { MainActivity.openPeer = if (screen == Screen.CHAT) current?.identity else null }

    BackHandler(enabled = screen != Screen.CONTACTS) { screen = Screen.CONTACTS; current = null }

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
                        current = c; screen = Screen.CHAT
                    }
                }) { Text("Додати") }
            },
            dismissButton = { TextButton(onClick = { pendingInvite.value = null }) { Text("Не зараз") } },
        )
    }
    if (showInvite) InviteDialog(app) { showInvite = false }
    if (showAdd) AddDialog(app, onDone = { c -> showAdd = false; if (c != null) { current = c; screen = Screen.CHAT } }, toast)

    when (screen) {
        Screen.CONTACTS -> ContactsScreen(app, contacts, onOpen = { current = it; screen = Screen.CHAT }, onInvite = { showInvite = true }, onAdd = { showAdd = true }, onSettings = { screen = Screen.SETTINGS }, act)
        Screen.CHAT -> current?.let { c -> ChatScreen(app, act, contacts.firstOrNull { it.identity == c.identity } ?: c, onBack = { screen = Screen.CONTACTS; current = null }, toast) }
        Screen.SETTINGS -> SettingsScreen(app, act, onBack = { screen = Screen.CONTACTS }, onLogout = onLogout, toast)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactsScreen(app: ShepitApp, contacts: List<Contact>, onOpen: (Contact) -> Unit, onInvite: () -> Unit, onAdd: () -> Unit, onSettings: () -> Unit, act: MainActivity) {
    val repo = app.repo
    val connected by repo.connected.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(repo.myName.ifEmpty { "Шепіт" }, fontWeight = FontWeight.Bold)
                    Text("Мій ID: ${repo.short(repo.core?.fingerprint() ?: "")}" + if (connected) " · на зв'язку" else " · з'єднання…", fontSize = 12.sp, color = Muted)
                }
            },
            actions = {
                IconButton(onClick = onAdd) { Icon(Icons.Default.PersonAdd, "Додати за ключем") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Налаштування") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        if (!Notifications.canFullScreen(act)) {
            Surface(color = Color(0xFF2B2A14), modifier = Modifier.fillMaxWidth().clickable { act.askFullScreen() }) {
                Text("⚠️ Дозволь Шепоту показувати вхідні дзвінки на весь екран — торкнись тут", Modifier.padding(12.dp))
            }
        }
        if (contacts.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Як почати спілкуватися", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("1. Натисни «Запросити друга» і надішли посилання будь-яким способом або покажи QR-код.\n\n2. Друг відкриває посилання і створює акаунт — ви одразу в контактах одне в одного.\n\n3. Пишіть і дзвоніть. Для впевненості звірте «код безпеки» наживо.")
                Spacer(Modifier.height(20.dp))
                Button(onClick = onInvite, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Запросити друга") }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(contacts, key = { it.identity }) { c ->
                    ListItem(
                        headlineContent = { Text(c.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(repo.short(c.fingerprint) + "…", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted) },
                        modifier = Modifier.clickable { onOpen(c) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            Button(onClick = onInvite, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) { Text("Запросити друга") }
        }
    }
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
                        m.mode == "invite" && m.code != null -> "🎟 Реєстрація на сервері — за запрошенням. Посилання містить твій одноразовий код (діє 30 днів)" + if (m.left > 0) "; ще можна створити ${m.left}." else "."
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
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Пиши мені в Шепоті — без номера телефону, з наскрізним шифруванням: $url"), "Поділитися"))
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
private fun ChatScreen(app: ShepitApp, act: MainActivity, contact: Contact, onBack: () -> Unit, toast: (String) -> Unit) {
    val repo = app.repo
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf(emptyList<Message>()) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableLongStateOf(-1L) }
    val callState by app.calls.state.collectAsState()

    suspend fun reload() {
        messages = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.db.messages(contact.identity) }
        if (messages.isNotEmpty()) list.scrollToItem(messages.size - 1)
    }
    LaunchedEffect(contact.identity) {
        reload()
        repo.changed.collect { if (it == contact.identity) reload() }
    }
    LaunchedEffect(recording) { while (recording) { recMs = recorder.elapsed(); if (recMs >= VoiceRecorder.MAX_MS) { recording = false; recorder.stop()?.let { (d, dur) -> scope.launch { runCatching { repo.sendVoice(contact.identity, recorder.mime, dur, d) }.onFailure { toast(it.message ?: "Помилка") } } } }; delay(200) } }
    DisposableEffect(Unit) { onDispose { VoicePlayer.stop(); recorder.stop(cancel = true) } }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            title = { Text(contact.name, Modifier.clickable { rename = true }, fontWeight = FontWeight.Bold) },
            actions = {
                IconButton(onClick = { showSafety = !showSafety }) { Icon(Icons.Default.Lock, "Код безпеки") }
                IconButton(enabled = callState == io.github.ya77papa.shepit.call.CallManager.State.IDLE, onClick = {
                    if (!act.hasMic()) { act.micPermission.launch(Manifest.permission.RECORD_AUDIO); return@IconButton }
                    app.calls.start(contact.identity)
                    ctx.startActivity(Intent(ctx, CallActivity::class.java))
                }) { Icon(Icons.Default.Call, "Голосовий дзвінок") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        if (showSafety) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Цей код однаковий у вас обох. Порівняйте його наживо або по телефону: якщо збігається — ніхто не підмінив ключі.", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(remember(contact.identity) { repo.safetyNumber(contact.identity) }, fontFamily = FontFamily.Monospace)
                }
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = list) {
            items(messages, key = { it.id }) { m -> Bubble(m, playing == m.id) {
                VoicePlayer.toggle(ctx, m.id, m.voice ?: return@Bubble, m.voiceMime) { playing = VoicePlayer.playingId }
                playing = VoicePlayer.playingId
            } }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                if (recording) {
                    Text("🔴 Запис ${Content.fmtDuration(recMs)} / ${Content.fmtDuration(VoiceRecorder.MAX_MS)}", Modifier.weight(1f).padding(8.dp))
                    TextButton(onClick = { recording = false; recorder.stop(cancel = true) }) { Text("Скасувати") }
                    Button(onClick = {
                        recording = false
                        recorder.stop()?.let { (d, dur) -> scope.launch { runCatching { repo.sendVoice(contact.identity, recorder.mime, dur, d) }.onFailure { toast(it.message ?: "Помилка") } } }
                    }) { Text("Надіслати") }
                } else {
                    OutlinedTextField(draft, { draft = it }, placeholder = { Text("Повідомлення") }, modifier = Modifier.weight(1f), maxLines = 5)
                    if (draft.isNotBlank()) {
                        IconButton(enabled = !busy, onClick = {
                            val t = draft.trim(); busy = true
                            scope.launch {
                                runCatching { repo.sendText(contact.identity, t) }.onSuccess { draft = "" }.onFailure { toast(it.message ?: "Помилка") }
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
    if (rename) {
        var n by remember { mutableStateOf(contact.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Ім'я контакту") },
            text = { OutlinedTextField(n, { n = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (n.isNotBlank()) repo.rename(contact.identity, n); rename = false }) { Text("Зберегти") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Скасувати") } },
        )
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale("uk"))

@Composable
private fun Bubble(m: Message, playing: Boolean, onPlay: () -> Unit) {
    val system = m.kind == "call"
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalAlignment = when { system -> Alignment.CenterHorizontally; m.out -> Alignment.End; else -> Alignment.Start },
    ) {
        if (system) {
            Text(m.text, color = Muted, fontSize = 13.sp)
            return@Column
        }
        Surface(color = if (m.out) OutBubble else InBubble, shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 300.dp)) {
            if (m.kind == "voice") {
                Row(Modifier.clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("🎤 ${Content.fmtDuration(m.voiceDur)}")
                }
            } else {
                Text(m.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        Text(timeFmt.format(Date(m.at)), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

// ---------------- налаштування ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(app: ShepitApp, act: MainActivity, onBack: () -> Unit, onLogout: () -> Unit, toast: (String) -> Unit) {
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
            navigationIcon = { IconButton(onClick = { repo.myName = name; onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            title = { Text("Налаштування") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Section("Моє ім'я") {
                OutlinedTextField(name, { name = it.take(60); repo.myName = name }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Бачать лише ті, кого ти запрошуєш. Сервер його не знає.", color = Muted, fontSize = 12.sp)
            }
            Section("Мій ID") { Text(repo.core?.fingerprint() ?: "", fontFamily = FontFamily.Monospace) }
            Section("Дзвінки, коли застосунок закритий") {
                Text("Шепіт тримає з'єднання з сервером без сервісів Google (тихе сповіщення «на зв'язку»).", color = Muted, fontSize = 13.sp)
                if (!battery) OutlinedButton(onClick = { act.askBattery() }) { Text("🔋 Дозволити працювати у фоні") } else Text("✅ Фонова робота дозволена")
                if (!Notifications.canFullScreen(ctx)) OutlinedButton(onClick = { act.askFullScreen() }) { Text("📞 Дозволити дзвінки на весь екран") } else Text("✅ Дзвінки на весь екран дозволені")
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
            Section("Резервна копія") {
                Text("Файл з ключами, контактами й історією, зашифрований паролем. Сумісний з веб-версією.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(pw1, { pw1 = it }, label = { Text("Пароль (щонайменше ${Backup.MIN_PASSWORD} символів)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pw2, { pw2 = it }, label = { Text("Повтори пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && pw1.length >= Backup.MIN_PASSWORD, onClick = {
                    if (pw1 != pw2) { toast("Паролі не збігаються"); return@Button }
                    busy = true
                    scope.launch {
                        runCatching { repo.exportBackup(pw1) }
                            .onSuccess { pendingBackup = it; pw1 = ""; pw2 = ""; save.launch("shepit-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.shepit-backup") }
                            .onFailure { toast(it.message ?: "Помилка") }
                        busy = false
                    }
                }) { Text(if (busy) "Шифрую…" else "💾 Зберегти у файл") }
            }
            Section("Версія") { Text("${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT.take(7)}) · відкритий код: github.com/ya77papa/shepit", color = Muted, fontSize = 13.sp) }
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
