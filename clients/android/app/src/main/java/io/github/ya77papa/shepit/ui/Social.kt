package io.github.ya77papa.shepit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ya77papa.shepit.ShepitApp
import io.github.ya77papa.shepit.data.ChannelRec
import io.github.ya77papa.shepit.data.Channels
import io.github.ya77papa.shepit.data.Group
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- аватар ----------------

private val avatarPalette = listOf(0xFF5B8DEF, 0xFFE0884B, 0xFF4BB38A, 0xFFB46BD6, 0xFFD6577A, 0xFF4BA3C7).map { Color(it) }

/** Фото (JPEG base64) або кольорове коло з ініціалами — як у веб-версії. */
@Composable
fun AvatarView(name: String, b64: String?, size: Dp = 40.dp) {
    val bmp = remember(b64) {
        b64?.let { runCatching { Base64.decode(it, Base64.DEFAULT).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } }.getOrNull() }
    }
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        val initials = name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
        val color = avatarPalette[(name.fold(0L) { h, c -> (h * 31 + c.code) and 0xFFFFFFFFL } % avatarPalette.size).toInt()]
        Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(initials, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.4f).sp)
        }
    }
}

/** Квадрат 192×192 з центру, JPEG ≤ ~48 КБ, base64 — як у веб-версії. */
suspend fun makeAvatar(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 192) sample *= 2
    val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: error("Не вдалося відкрити зображення")
    val side = minOf(src.width, src.height)
    val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    val small = Bitmap.createScaledBitmap(square, 192, 192, true)
    for (q in listOf(82, 70, 55)) {
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, q, out)
        if (out.size() <= 48_000) return@withContext Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
    error("Не вдалося стиснути фото")
}

/** Вибір фото системним засобом (без дозволу на всі файли). */
@Composable
fun rememberAvatarPicker(onError: (String) -> Unit, onPicked: suspend (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { runCatching { onPicked(makeAvatar(ctx, uri)) }.onFailure { onError(it.message ?: "Помилка") } }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
        title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1) },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

// ---------------- групи ----------------

/** Ім'я учасника без звернень до бази: з контактів, інакше як назвав адмін. */
fun displayName(app: ShepitApp, contacts: List<io.github.ya77papa.shepit.data.Contact>, g: Group?, id: String?): String {
    val repo = app.repo
    return when {
        id == null -> ""
        id == repo.core?.identity() -> repo.myName.ifEmpty { "Я" }
        else -> contacts.firstOrNull { it.identity == id }?.name ?: g?.members?.firstOrNull { it.id == id }?.name
            ?: "Контакт ${repo.short(io.github.ya77papa.shepit.core.fingerprintOf(id))}"
    }
}

fun photoOf(app: ShepitApp, contacts: List<io.github.ya77papa.shepit.data.Contact>, id: String): String? =
    if (id == app.repo.core?.identity()) app.repo.myAvatar.ifEmpty { null } else contacts.firstOrNull { it.identity == id }?.avatar

@Composable
fun NewGroupScreen(app: ShepitApp, onBack: () -> Unit, onCreated: (Group) -> Unit, toast: (String) -> Unit) {
    val repo = app.repo
    val contacts by repo.contacts.collectAsState()
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Bar("Нова група", onBack)
        Column(Modifier.padding(16.dp).weight(1f)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Назва групи") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Учасники (до 100). Сервер не знає ні про групу, ні про її склад: повідомлення шифрується кожному окремо.", color = Muted, fontSize = 13.sp)
            LazyColumn(Modifier.weight(1f)) {
                items(contacts, key = { it.identity }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = if (c.identity in picked) picked - c.identity else picked + c.identity }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(c.identity in picked, null)
                        Spacer(Modifier.width(8.dp))
                        AvatarView(c.name, c.avatar, 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(c.name)
                    }
                }
            }
            Button(
                enabled = !busy && name.isNotBlank() && picked.isNotEmpty() && picked.size <= 99,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { repo.createGroup(name, picked.toList()) }.onSuccess(onCreated).onFailure { toast(it.message ?: "Помилка") }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (busy) "Створюю…" else "Створити групу") }
        }
    }
}

@Composable
fun GroupInfoScreen(app: ShepitApp, groupId: String, onBack: () -> Unit, onDeleted: () -> Unit, toast: (String) -> Unit) {
    val repo = app.repo
    val groups by repo.groups.collectAsState()
    val contacts by repo.contacts.collectAsState()
    val g = groups.firstOrNull { it.id == groupId } ?: run { onBack(); return }
    val me = repo.core?.identity()
    val admin = me in g.admins && !g.left
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // leave | delete | remove:<id>
    var adding by remember { mutableStateOf(setOf<String>()) }

    fun op(f: suspend () -> Int) {
        busy = true
        scope.launch {
            runCatching { f() }.onSuccess { if (it > 0) toast("Не вдалося сповістити $it учасників — спробуй пізніше") }.onFailure { toast(it.message ?: "Помилка") }
            busy = false
        }
    }
    val pickAvatar = rememberAvatarPicker(toast) { a -> op { repo.setGroupAvatar(g.id, a) } }

    Column(Modifier.fillMaxSize()) {
        Bar(g.name, onBack)
        LazyColumn(Modifier.padding(horizontal = 16.dp).weight(1f)) {
            item {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AvatarView(g.name, g.avatar, 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(g.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("${g.members.size} учасн. · " + if (g.left) "ти не в групі" else if (admin) "ти адміністратор" else "учасник", color = Muted, fontSize = 13.sp)
                    }
                }
                if (admin) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { rename = true }) { Text("✎ Назва") }
                    OutlinedButton(enabled = !busy, onClick = pickAvatar) { Text("🖼 Фото") }
                }
                Spacer(Modifier.height(12.dp))
                Text("УЧАСНИКИ", color = Muted, fontSize = 12.sp)
            }
            items(g.members, key = { it.id }) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val n = displayName(app, contacts, g, m.id)
                    AvatarView(n, photoOf(app, contacts, m.id), 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n)
                        if (m.id in g.admins) Text("адмін", color = Muted, fontSize = 12.sp)
                    }
                    if (admin && m.id != me) {
                        TextButton(enabled = !busy, onClick = { op { repo.toggleAdmin(g.id, m.id) } }) { Text(if (m.id in g.admins) "зняти адміна" else "адмін", fontSize = 12.sp) }
                        IconButton(enabled = !busy, onClick = { confirm = "remove:${m.id}" }) { Icon(Icons.Default.Close, "Видалити з групи", tint = Danger) }
                    }
                }
            }
            if (admin) {
                val candidates = contacts.filter { c -> g.members.none { it.id == c.identity } }
                if (candidates.isNotEmpty()) {
                    item { Spacer(Modifier.height(12.dp)); Text("ДОДАТИ", color = Muted, fontSize = 12.sp) }
                    items(candidates, key = { "add-" + it.identity }) { c ->
                        Row(Modifier.fillMaxWidth().clickable { adding = if (c.identity in adding) adding - c.identity else adding + c.identity }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(c.identity in adding, null)
                            Text(c.name)
                        }
                    }
                    item {
                        Button(enabled = !busy && adding.isNotEmpty() && g.members.size + adding.size <= 100, onClick = {
                            val ids = adding.toList(); adding = emptySet(); op { repo.addToGroup(g.id, ids) }
                        }) { Text("Додати обраних") }
                    }
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                if (g.left) Button(onClick = { confirm = "delete" }, colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White), modifier = Modifier.fillMaxWidth()) { Text("Видалити групу з цього телефона") }
                else Button(onClick = { confirm = "leave" }, colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White), modifier = Modifier.fillMaxWidth()) { Text("Вийти з групи") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (rename) {
        var n by remember { mutableStateOf(g.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Назва групи") },
            text = { OutlinedTextField(n, { n = it.take(80) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { rename = false; if (n.isNotBlank()) op { repo.renameGroup(g.id, n) } }) { Text("Зберегти") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Скасувати") } },
        )
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what.startsWith("remove:")) "Видалити з групи?" else if (what == "leave") "Вийти з групи?" else "Видалити групу?") },
            text = {
                Text(when {
                    what.startsWith("remove:") -> "${repo.memberName(what.removePrefix("remove:"), g)} більше не отримуватиме повідомлень групи."
                    what == "leave" -> "Ти більше не отримуватимеш повідомлень з «${g.name}». Історія залишиться на телефоні."
                    else -> "Група «${g.name}» і її історія зникнуть з цього телефона."
                })
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    when {
                        what.startsWith("remove:") -> op { repo.removeFromGroup(g.id, what.removePrefix("remove:")) }
                        what == "leave" -> scope.launch { runCatching { repo.leaveGroup(g.id) }.onFailure { toast(it.message ?: "Помилка") } }
                        else -> scope.launch { repo.deleteGroupLocal(g.id); onDeleted() }
                    }
                }) { Text("Так", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Скасувати") } },
        )
    }
}

// ---------------- канали ----------------

@Composable
fun NewChannelScreen(app: ShepitApp, onBack: () -> Unit, onCreated: (ChannelRec) -> Unit, toast: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var public by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Bar("Новий канал", onBack)
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Назва каналу") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(about, { about = it.take(500) }, label = { Text("Опис (необов'язково)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            ChoiceRow(!public, "🔒 Зашифрований", "Читає лише той, хто має посилання: ключ у посиланні, сервер дописів не бачить.") { public = false }
            ChoiceRow(public, "📢 Публічний", "Дописи відкриті, канал знаходиться пошуком, читати можна без акаунта.") { public = true }
            Button(enabled = !busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                busy = true
                scope.launch {
                    runCatching { app.repo.createChannel(name, about, public) }.onSuccess(onCreated).onFailure { toast(it.message ?: "Помилка") }
                    busy = false
                }
            }) { Text(if (busy) "Створюю…" else "Створити канал") }
            Text("Ключ автора зберігається лише на цьому телефоні — зроби резервну копію, інакше втратиш можливість писати в канал.", color = Color(0xFFE0B45A), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0x1FF5C542) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Accent else Color(0xFF243246)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected, null)
            Spacer(Modifier.width(8.dp))
            Column { Text(title, fontWeight = FontWeight.SemiBold); Text(text, color = Muted, fontSize = 13.sp) }
        }
    }
}

@Composable
fun FindChannelScreen(app: ShepitApp, onBack: () -> Unit, onOpen: (String) -> Unit, toast: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Channels.Client.Found>?>(null) }
    val scope = rememberCoroutineScope()
    fun search() = scope.launch {
        runCatching { withContext(Dispatchers.IO) { app.repo.api.channels.search(q.trim()) } }.onSuccess { results = it }.onFailure { toast(it.message ?: "Помилка") }
    }
    LaunchedEffect(Unit) { search() }
    Column(Modifier.fillMaxSize()) {
        Bar("Публічні канали", onBack)
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, label = { Text("Назва або опис") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = { search() }) { Icon(Icons.Default.Search, "Шукати") }
        }
        LazyColumn(Modifier.weight(1f)) {
            results?.let { list ->
                if (list.isEmpty()) item { Text("Нічого не знайдено.", Modifier.padding(16.dp), color = Muted) }
                items(list, key = { it.id }) { f ->
                    ListItem(
                        leadingContent = { AvatarView(f.meta.name, f.meta.avatar, 40.dp) },
                        headlineContent = { Text(f.meta.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(f.meta.about.ifEmpty { "${f.posts} дописів" }, maxLines = 2, color = Muted) },
                        modifier = Modifier.clickable { onOpen(f.id) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        Text("Зашифровані канали в пошуку не з'являються — до них потрапляють лише за посиланням.", Modifier.padding(16.dp), color = Muted, fontSize = 12.sp)
    }
}

private val postTime = SimpleDateFormat("dd.MM HH:mm", Locale("uk"))

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelScreen(
    app: ShepitApp, initial: ChannelRec, subscribed: Boolean,
    onBack: () -> Unit, onSubscribe: (ChannelRec) -> Unit, onGone: () -> Unit, toast: (String) -> Unit,
) {
    val repo = app.repo
    val api = repo.api.channels
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val channels by repo.channels.collectAsState()
    val ch = channels.firstOrNull { it.id == initial.id } ?: initial
    var posts by remember { mutableStateOf(emptyList<Channels.Post>()) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf<Channels.Post?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) } // delete | unsubscribe
    val list = rememberLazyListState()

    suspend fun markRead() {
        val last = posts.lastOrNull()?.seq ?: return
        if (subscribed && last > ch.readSeq) repo.saveChannel(ch.copy(readSeq = last, lastSeq = maxOf(ch.lastSeq, last)))
    }
    LaunchedEffect(ch.id) {
        loading = true
        runCatching { withContext(Dispatchers.IO) { api.posts(ch, limit = 50) } }
            .onSuccess { posts = it; more = it.size == 50; if (it.isNotEmpty()) list.scrollToItem(it.size - 1); markRead() }
            .onFailure { toast(it.message ?: "Помилка") }
        loading = false
        while (true) {
            delay(10_000)
            val after = posts.lastOrNull()?.seq ?: 0
            val fresh = runCatching { withContext(Dispatchers.IO) { api.posts(ch, after = after, limit = 100) } }.getOrNull().orEmpty()
            if (fresh.isNotEmpty()) { posts = posts + fresh; list.animateScrollToItem(posts.size - 1); markRead() }
        }
    }
    val pickAvatar = rememberAvatarPicker(toast) { a ->
        withContext(Dispatchers.IO) { api.putMeta(ch, Channels.Meta(ch.name, ch.about, a)) }
        repo.saveChannel(ch.copy(avatar = a))
    }
    val link = remember(ch.id, ch.key) { repo.channelLink(ch) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Bar(ch.name, onBack) {
            if (!subscribed) TextButton(onClick = { onSubscribe(ch) }) { Text("Підписатися") }
            IconButton(onClick = { info = true }) { Icon(Icons.Default.Info, "Про канал") }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = list) {
            if (more) item {
                TextButton(onClick = {
                    scope.launch {
                        val first = posts.firstOrNull()?.seq ?: return@launch
                        val prev = runCatching { withContext(Dispatchers.IO) { api.posts(ch, before = first, limit = 50) } }.getOrNull().orEmpty()
                        more = prev.size == 50; posts = prev + posts
                    }
                }) { Text("Старіші дописи") }
            }
            if (posts.isEmpty()) item { Text(if (loading) "Завантаження…" else "Дописів ще немає.", Modifier.padding(24.dp), color = Muted) }
            items(posts, key = { it.seq }) { p ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Surface(
                        color = InBubble, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = {}, onLongClick = { if (ch.mine || p.text != null) actions = p }),
                    ) { Text(p.text ?: "🔒 не вдалося розшифрувати", Modifier.padding(12.dp), color = if (p.text == null) Muted else Color.Unspecified) }
                    Text(postTime.format(Date(p.at)), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
        if (ch.mine) Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it.take(4000) }, placeholder = { Text("Допис у канал") }, modifier = Modifier.weight(1f), maxLines = 6)
                IconButton(enabled = !busy && draft.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { api.publish(ch, draft.trim()) } }
                            .onSuccess {
                                draft = ""
                                val fresh = runCatching { withContext(Dispatchers.IO) { api.posts(ch, after = posts.lastOrNull()?.seq ?: 0) } }.getOrNull().orEmpty()
                                posts = posts + fresh
                                if (posts.isNotEmpty()) list.animateScrollToItem(posts.size - 1)
                            }
                            .onFailure { toast(it.message ?: "Помилка") }
                        busy = false
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Опублікувати", tint = Accent) }
            }
        }
    }

    actions?.let { p ->
        AlertDialog(
            onDismissRequest = { actions = null },
            title = { Text("Допис") },
            text = { Text(p.text ?: "", maxLines = 4, color = Muted) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (ch.mine) TextButton(onClick = {
                        actions = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { api.deletePost(ch, p.seq) } }
                                .onSuccess { posts = posts.filter { it.seq != p.seq } }.onFailure { toast(it.message ?: "Помилка") }
                        }
                    }) { Text("Видалити для всіх", color = Danger) }
                    if (p.text != null) TextButton(onClick = {
                        actions = null
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Шепіт", p.text))
                    }) { Text("Копіювати") }
                    TextButton(onClick = { actions = null }) { Text("Скасувати") }
                }
            },
        )
    }

    if (info) {
        val qr = remember(link) { qrBitmap(link) }
        AlertDialog(
            onDismissRequest = { info = false },
            title = { Text(ch.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AvatarView(ch.name, ch.avatar, 64.dp)
                    if (ch.about.isNotEmpty()) Text(ch.about)
                    Text(
                        if (ch.public) "📢 Публічний канал: дописи відкриті, сервер їх бачить, канал знаходиться пошуком."
                        else "🔒 Зашифрований канал: читати може лише той, хто має посилання — ключ у ньому, сервер дописів не бачить.",
                        color = Muted, fontSize = 13.sp,
                    )
                    Image(qr, "QR-код каналу", Modifier.size(200.dp).clip(RoundedCornerShape(12.dp)).background(Color.White))
                    Text(link, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Канал «${ch.name}» у Шепоті: $link"), "Поділитися"))
                        }) { Text("Поділитися") }
                    }
                    if (ch.mine) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { edit = true }) { Text("✎ Опис") }
                            OutlinedButton(onClick = pickAvatar) { Text("🖼 Фото") }
                        }
                        TextButton(onClick = { confirm = "delete" }) { Text("Видалити канал", color = Danger) }
                        Text("Ти автор: ключ каналу зберігається лише на цьому телефоні і в резервній копії.", color = Color(0xFFE0B45A), fontSize = 12.sp)
                    } else if (subscribed) {
                        TextButton(onClick = { confirm = "unsubscribe" }) { Text("Відписатися") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { info = false }) { Text("Закрити") } },
        )
    }

    if (edit) {
        var n by remember { mutableStateOf(ch.name) }
        var a by remember { mutableStateOf(ch.about) }
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text("Канал") },
            text = {
                Column {
                    OutlinedTextField(n, { n = it.take(80) }, label = { Text("Назва") }, singleLine = true)
                    OutlinedTextField(a, { a = it.take(500) }, label = { Text("Опис") }, minLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    edit = false
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { api.putMeta(ch, Channels.Meta(n.trim().ifEmpty { ch.name }, a.trim(), ch.avatar)) }
                            repo.saveChannel(ch.copy(name = n.trim().ifEmpty { ch.name }, about = a.trim()))
                        }.onFailure { toast(it.message ?: "Помилка") }
                    }
                }) { Text("Зберегти") }
            },
            dismissButton = { TextButton(onClick = { edit = false }) { Text("Скасувати") } },
        )
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "delete") "Видалити канал?" else "Відписатися?") },
            text = { Text(if (what == "delete") "Канал «${ch.name}» і всі дописи зникнуть із сервера для всіх читачів." else "Канал «${ch.name}» зникне зі списку.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null; info = false
                    scope.launch {
                        runCatching {
                            if (what == "delete") withContext(Dispatchers.IO) { api.delete(ch) }
                            withContext(Dispatchers.IO) { repo.db.deleteChannel(ch.id) }
                            repo.refreshChannels()
                        }.onSuccess { onGone() }.onFailure { toast(it.message ?: "Помилка") }
                    }
                }) { Text(if (what == "delete") "Видалити" else "Відписатися", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Скасувати") } },
        )
    }
}
