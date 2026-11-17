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

    fun showMessage(ctx: Context, name: String, peer: String, text: String) {
        val open = pi(ctx, 30, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PEER, peer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(ctx, CH_MESSAGES).setSmallIcon(R.drawable.ic_stat).setContentTitle("Шепіт").setContentText("Нове повідомлення").build(),
            )
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_MESSAGE, n) }
    }

    fun canFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}
