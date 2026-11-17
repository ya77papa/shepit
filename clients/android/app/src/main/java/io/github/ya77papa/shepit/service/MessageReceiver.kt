package io.github.ya77papa.shepit.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import io.github.ya77papa.shepit.ShepitApp
import io.github.ya77papa.shepit.ui.MainActivity
import kotlinx.coroutines.launch

/** Кнопки сповіщення про повідомлення: «Відповісти», «Прочитано», змахнути. */
class MessageReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val peer = intent.getStringExtra(MainActivity.EXTRA_PEER) ?: return
        val app = ctx.applicationContext as ShepitApp
        val repo = app.repo
        when (intent.action) {
            Notifications.ACTION_DISMISS -> Notifications.cancelMessages(ctx, peer)
            Notifications.ACTION_READ -> {
                Notifications.cancelMessages(ctx, peer)
                val done = goAsync()
                repo.scope.launch { try { runCatching { if (repo.load()) repo.markRead(peer) } } finally { done.finish() } }
            }
            Notifications.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifications.KEY_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                val done = goAsync()
                repo.scope.launch {
                    try {
                        val ok = runCatching {
                            check(repo.load())
                            repo.sendText(peer, text)
                            repo.markRead(peer) // відповів — отже, прочитав
                        }.isSuccess
                        // Показати відповідь у сповіщенні (або що не вдалося) — інакше Android крутить «надсилається».
                        Notifications.showMessage(
                            ctx, peer, repo.nameOf(peer), null, peer.startsWith("g:"),
                            Notifications.Line(null, "Я", null, if (ok) text else "Не надіслано: $text", System.currentTimeMillis()),
                            hidden = false,
                        )
                    } finally {
                        done.finish()
                    }
                }
            }
        }
    }
}
