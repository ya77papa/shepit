package io.github.ya77papa.shepit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import io.github.ya77papa.shepit.R
import io.github.ya77papa.shepit.ui.CallActivity
import io.github.ya77papa.shepit.ui.MainActivity

object Notifications {
    const val CH_CALLS = "calls"
    const val CH_MESSAGES = "messages"
    const val CH_SERVICE = "service"
    const val ID_SERVICE = 1
    const val ID_CALL = 2
    const val CH_UPDATES = "updates"
    const val CH_ONGOING = "ongoing_call"
    private const val ID_ONGOING = 5
    private const val ID_UPDATE = 4
    private const val ID_MESSAGE = 3

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CALLS, "Дзвінки", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Вхідні дзвінки"
            setSound(null, null) // рингтон грає сам застосунок на гучності дзвінка
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Повідомлення", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Нові повідомлення"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Поточний дзвінок", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Повернутися до розмови або завершити її"
            setSound(null, null)
        })
        nm.createNotificationChannel(NotificationChannel(CH_UPDATES, "Оновлення", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Нова версія Шепоту"
        })
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "З'єднання", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Шепіт на зв'язку, щоб приймати дзвінки"
            setShowBadge(false)
        })
    }

    private fun pi(ctx: Context, code: Int, intent: Intent, activity: Boolean = true): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (activity) PendingIntent.getActivity(ctx, code, intent, flags) else PendingIntent.getBroadcast(ctx, code, intent, flags)
    }

    fun showUpdate(ctx: Context, version: String) {
        val open = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val n = NotificationCompat.Builder(ctx, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Доступна нова версія Шепоту")
            .setContentText("$version — торкнись, щоб оновити")
            .setContentIntent(pi(ctx, 40, open))
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_UPDATE, n) }
    }

    fun service(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Шепіт на зв'язку")
            .setContentText("Щоб дзвінки й повідомлення доходили одразу")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi(ctx, 10, Intent(ctx, MainActivity::class.java)))
            .build()

    /** Повноекранний вхідний дзвінок: будить екран навіть на заблокованому телефоні. */
    fun showIncomingCall(ctx: Context, name: String) {
        val full = pi(ctx, 20, Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val answer = pi(ctx, 21, Intent(ctx, CallActivity::class.java).putExtra(CallActivity.EXTRA_ACCEPT, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val decline = pi(ctx, 22, Intent(ctx, CallReceiver::class.java).setAction(CallReceiver.DECLINE), activity = false)
        val person = Person.Builder().setName(name).setImportant(true).build()
        val n = NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name)
            .setContentText("Вхідний дзвінок у Шепоті")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_CALL, n)
    }

    /** Дзвінок триває: сповіщення, з якого можна повернутися до розмови або завершити її. */
    fun showOngoingCall(ctx: Context, name: String, startedAt: Long?, status: String) {
        val open = pi(ctx, 50, Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        val hangup = pi(ctx, 51, Intent(ctx, CallReceiver::class.java).setAction(CallReceiver.HANGUP), activity = false)
        val b = NotificationCompat.Builder(ctx, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name)
            .setContentText(status)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Завершити", hangup)
        if (startedAt != null) b.setUsesChronometer(true).setWhen(startedAt).setShowWhen(true)
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_ONGOING, b.build()) }
    }

    fun cancelOngoingCall(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_ONGOING)
    }

    fun cancelIncomingCall(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_CALL)
    }

    // ---------- «Шепіт на зв'язку» ----------

    /** Чи видно постійне сповіщення служби (користувач міг вимкнути його канал). */
    fun serviceVisible(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        return (nm.getNotificationChannel(CH_SERVICE)?.importance ?: NotificationManager.IMPORTANCE_LOW) != NotificationManager.IMPORTANCE_NONE
    }

    /** Системні налаштування саме сповіщення «Шепіт на зв'язку» (там його можна вимкнути). */
    fun openServiceSettings(ctx: Context) {
        val i = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CH_SERVICE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { openAppSettings(ctx) }
    }

    fun openAppSettings(ctx: Context) {
        val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    // ---------- повідомлення ----------

    /** Рядок у сповіщенні бесіди. sender = null — це я (відповідь зі сповіщення). */
    class Line(val sender: String?, val name: String, val avatar: String?, val text: String, val at: Long)

    /** Непрочитане в сповіщеннях: бесіда → рядки (лише в пам'яті, на диск не пишеться). */
    private val history = mutableMapOf<String, MutableList<Line>>()

    const val ACTION_REPLY = "io.github.ya77papa.shepit.REPLY"
    const val ACTION_READ = "io.github.ya77papa.shepit.READ"
    const val KEY_REPLY = "reply"

    private fun messageId(peer: String) = 2000 + (peer.hashCode() and 0x7fff)

    private fun circle(b64: String?): IconCompat? = runCatching {
        val bytes = android.util.Base64.decode(b64 ?: return null, android.util.Base64.DEFAULT)
        val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(src.width, src.height, 256)
        val out = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        c.drawCircle(side / 2f, side / 2f, side / 2f, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, null, android.graphics.Rect(0, 0, side, side), p)
        IconCompat.createWithBitmap(out)
    }.getOrNull()

    /**
     * Нове повідомлення: дописати рядок у сповіщення бесіди й показати.
     * [hidden] — увімкнено блокування: без імен, тексту й кнопок.
     */
    fun showMessage(ctx: Context, peer: String, title: String, titleAvatar: String?, isGroup: Boolean, line: Line?, hidden: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val id = messageId(peer)
        val open = pi(ctx, id, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PEER, peer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        val b = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(ctx, CH_MESSAGES).setSmallIcon(R.drawable.ic_stat).setContentTitle("Шепіт").setContentText("Нове повідомлення").build(),
            )
            .setAutoCancel(true)
            .setContentIntent(open)
            .setDeleteIntent(pi(ctx, id + 1, Intent(ctx, MessageReceiver::class.java).setAction(ACTION_DISMISS).putExtra(MainActivity.EXTRA_PEER, peer), activity = false))
        val lines = synchronized(history) {
            val l = history.getOrPut(if (hidden) "" else peer) { mutableListOf() }
            if (line != null) { l += line; while (l.size > 12) l.removeAt(0) }
            l.toList()
        }
        if (hidden) {
            b.setContentTitle("Шепіт").setContentText(if (lines.size > 1) "Нових повідомлень: ${lines.size}" else "Нове повідомлення")
            runCatching { nm.notify(ID_MESSAGE, b.build()) }
            return
        }
        if (lines.isEmpty()) return
        val me = Person.Builder().setName("Я").setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me).setGroupConversation(isGroup)
        if (isGroup) style.setConversationTitle(title)
        val people = mutableMapOf<String, Person>()
        for (l in lines) {
            val who = l.sender?.let { s -> people.getOrPut(s) { Person.Builder().setKey(s).setName(l.name).setIcon(circle(l.avatar)).build() } }
            style.addMessage(l.text, l.at, who)
        }
        b.setStyle(style)
        if (isGroup) circle(titleAvatar)?.let { b.setLargeIcon(it.toIcon(ctx)) }
        // Відповісти прямо зі сповіщення; «Прочитано» — позначити переглянутим і прибрати.
        val replyPi = PendingIntent.getBroadcast(
            ctx, id + 2, Intent(ctx, MessageReceiver::class.java).setAction(ACTION_REPLY).putExtra(MainActivity.EXTRA_PEER, peer),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        b.addAction(
            NotificationCompat.Action.Builder(0, "Відповісти", replyPi)
                .addRemoteInput(androidx.core.app.RemoteInput.Builder(KEY_REPLY).setLabel("Повідомлення").build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build(),
        )
        b.addAction(
            NotificationCompat.Action.Builder(0, "Прочитано", pi(ctx, id + 3, Intent(ctx, MessageReceiver::class.java).setAction(ACTION_READ).putExtra(MainActivity.EXTRA_PEER, peer), activity = false))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build(),
        )
        // Моя відповідь зі сповіщення — без звуку.
        if (line?.sender == null && line != null) b.setOnlyAlertOnce(true).setSilent(true)
        runCatching { nm.notify(id, b.build()) }
    }

    const val ACTION_DISMISS = "io.github.ya77papa.shepit.DISMISS"

    /** Бесіду відкрито / прочитано / сповіщення змахнуто — забути рядки й прибрати. */
    fun cancelMessages(ctx: Context, peer: String) {
        synchronized(history) { history.remove(peer); history.remove("") }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        runCatching { nm.cancel(messageId(peer)); nm.cancel(ID_MESSAGE) }
    }

    fun hasMessages(peer: String) = synchronized(history) { history[peer]?.isNotEmpty() == true }

    fun canFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}
