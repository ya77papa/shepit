package io.github.ya77papa.shepit

import android.app.Application
import io.github.ya77papa.shepit.call.CallManager
import io.github.ya77papa.shepit.data.Repo
import io.github.ya77papa.shepit.service.Notifications

class ShepitApp : Application() {
    lateinit var repo: Repo
        private set
    val calls: CallManager by lazy { CallManager(this, repo) }

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
        Notifications.createChannels(this)
        calls // підписатися на сигнали дзвінків одразу
    }
}
