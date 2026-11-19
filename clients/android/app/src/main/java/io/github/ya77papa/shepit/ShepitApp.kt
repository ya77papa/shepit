package io.github.ya77papa.shepit

import android.app.Application
import io.github.ya77papa.shepit.call.CallManager
import io.github.ya77papa.shepit.data.Repo
import io.github.ya77papa.shepit.service.Notifications

class ShepitApp : Application() {
    lateinit var repo: Repo
        private set
    val calls: CallManager by lazy { CallManager(this, repo) }
    /** Робота без інтернету (mesh). */
    val mesh: io.github.ya77papa.shepit.mesh.MeshRunner by lazy { io.github.ya77papa.shepit.mesh.MeshRunner(this, repo) }
    val lock: io.github.ya77papa.shepit.security.AppLock by lazy { io.github.ya77papa.shepit.security.AppLock(this, repo.db) }
    val updater: io.github.ya77papa.shepit.update.Updater by lazy { io.github.ya77papa.shepit.update.Updater(this, repo.db) }

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
        Notifications.createChannels(this)
        calls // підписатися на сигнали дзвінків одразу
    }
}
