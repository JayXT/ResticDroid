package io.github.resticdroid

import android.app.Application
import androidx.work.Configuration
import io.github.resticdroid.config.ConfigStore
import io.github.resticdroid.restic.Restic
import io.github.resticdroid.secret.SecretStore
import io.github.resticdroid.work.Notifications
import io.github.resticdroid.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class ResticDroidApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(
                if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN
            )
            .build()

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val configStore: ConfigStore by lazy { ConfigStore(this) }
    val secrets: SecretStore by lazy { SecretStore(this) }
    val restic: Restic by lazy { Restic.from(this) }

    override fun onCreate() {
        super.onCreate()

        Notifications.ensureChannels(this)

        configStore.startWatching(scope)
        configStore.state
            .drop(1)
            .onEach { Scheduler.sync(this, it) }
            .launchIn(scope)

        scope.launch { runCatching { configStore.load() } }
    }
}
