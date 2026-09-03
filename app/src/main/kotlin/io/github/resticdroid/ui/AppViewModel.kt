package io.github.resticdroid.ui

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.resticdroid.ResticDroidApp
import io.github.resticdroid.config.Config
import io.github.resticdroid.config.ConfigPaths
import io.github.resticdroid.config.Destination
import io.github.resticdroid.config.Profile
import io.github.resticdroid.config.Settings
import io.github.resticdroid.engine.Repositories
import io.github.resticdroid.engine.RestoreReport
import io.github.resticdroid.restic.ResticCommand
import io.github.resticdroid.restic.ResticEvent
import io.github.resticdroid.restic.ResticExit
import io.github.resticdroid.restic.ResticSnapshot
import io.github.resticdroid.secret.BiometricGate
import io.github.resticdroid.secret.SecretStore
import io.github.resticdroid.work.BackupWorker
import io.github.resticdroid.work.Runs
import io.github.resticdroid.work.Scheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object EnoughFiles : RuntimeException(null, null, false, false)

data class SnapshotFile(val path: String, val size: Long, val directory: Boolean)

data class FileListing(val files: List<SnapshotFile>, val truncated: Boolean)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<ResticDroidApp>()

    val config: StateFlow<Config> get() = app.configStore.state
    val progress: StateFlow<Map<String, BackupWorker.Progress>> get() = BackupWorker.progress

    val nextRuns: StateFlow<Map<String, Long>> =
        Scheduler.nextRuns(application)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _challenge = MutableStateFlow<PasswordChallenge?>(null)
    val challenge: StateFlow<PasswordChallenge?> = _challenge.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _storageGranted = MutableStateFlow(hasStorageAccess(application))
    val storageGranted: StateFlow<Boolean> = _storageGranted.asStateFlow()

    fun refreshPermissions() {
        _storageGranted.value = hasStorageAccess(getApplication())
        if (_storageGranted.value) {
            viewModelScope.launch(Dispatchers.IO) {
                app.configStore.initialiseIfEmpty()
                Scheduler.sync(getApplication(), app.configStore.load())
            }
        }
    }

    fun say(text: String?) {
        _message.value = text
    }

    /**
     * Runs [block] while the whole app says [label].
     *
     * Forgetting a snapshot or restoring one is a round trip to the repository:
     * seconds on a good connection, minutes on a bad one. Without this the
     * screen simply sits there, and the natural response is to press again.
     */
    suspend fun <T> working(label: String, block: suspend () -> T): T = try {
        _busy.value = label
        block()
    } finally {
        _busy.value = null
    }

    /**
     * What the user has already proved, and what has already been read, for as
     * long as the app is in front of them.
     *
     * Both are cleared by [lock] when the app leaves the foreground. Listing a
     * repository is a network round trip, and asking for a fingerprint again
     * because somebody pressed Back is a prompt that protects nothing - the
     * screen behind it was already unlocked a second ago.
     *
     * Both are concurrent: they are filled on an IO thread while [lock] empties
     * them on the main one as the app leaves the foreground.
     */
    private val unlocked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val listings = java.util.concurrent.ConcurrentHashMap<String, List<ResticSnapshot>>()

    // One snapshot's files, not every snapshot's: a listing can be thousands of
    // entries, and the case worth serving is stepping back into the snapshot
    // just looked at.
    @Volatile
    private var lastFiles: Pair<String, FileListing>? = null

    init {
        viewModelScope.launch { Runs.changed.collect(::stale) }
    }

    fun lock() {
        unlocked.clear()
        listings.clear()
        lastFiles = null
    }

    /** The files of a snapshot already listed this session, if it is that one. */
    fun cachedFiles(snapshotId: String): FileListing? =
        lastFiles?.takeIf { it.first == snapshotId }?.second

    /** True once the user has proved themselves for this repository, this session. */
    suspend fun unlock(activity: FragmentActivity, destinationId: String, title: String, subtitle: String): Boolean {
        if (destinationId in unlocked) return true
        if (!authenticate(activity, title, subtitle, destinationId)) return false
        unlocked += destinationId
        return true
    }

    fun requireAuth(): Boolean =
        config.value.settings.requireAuth || app.secrets.isAuthLatched()

    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        destinationId: String? = null,
    ): Boolean {
        if (!requireAuth()) return true

        when (val result = BiometricGate.authenticate(activity, title, subtitle)) {
            BiometricGate.Result.Success -> return true
            // Cancelled is a refusal, not a fallback: otherwise dismissing the
            // prompt would pass on a device with no repository password yet.
            BiometricGate.Result.Cancelled -> return false
            BiometricGate.Result.Unavailable -> Unit
            is BiometricGate.Result.Failed -> {
                say(result.message)
                return false
            }
        }

        if (!app.secrets.hasAnyPassword()) return true

        val outcome = CompletableDeferred<Boolean>()
        _challenge.value = PasswordChallenge(
            title = title,
            subtitle = "Enter the password for this repository.",
            verify = { candidate ->
                if (destinationId != null) {
                    app.secrets.matchesPassword(destinationId, candidate)
                } else {
                    app.secrets.matchesAnyPassword(candidate)
                }
            },
            outcome = outcome,
        )
        return try {
            outcome.await()
        } finally {
            _challenge.value = null
        }
    }

    suspend fun setRequireAuth(activity: FragmentActivity, enabled: Boolean) {
        unlocked.clear()
        if (!enabled) {
            val allowed = authenticate(
                activity,
                "Turn off confirmation",
                "Confirm it is you before the requirement is removed.",
            )
            if (!allowed) {
                say("Cancelled")
                return
            }
        }
        app.secrets.setAuthLatch(enabled)
        saveSettings(config.value.settings.copy(requireAuth = enabled))
    }

    fun resticVersion(): String = app.restic.bundledVersion()

    fun resticLicence(): String = app.restic.bundledLicence()

    fun configRoot(): String = ConfigPaths.root().absolutePath

    fun saveProfile(profile: Profile) {
        viewModelScope.launch(Dispatchers.IO) {
            app.configStore.save(profile)
            Scheduler.sync(getApplication(), app.configStore.load())
            say("Saved ${profile.name}")
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            Scheduler.cancel(getApplication(), id)
            app.configStore.deleteProfile(id)
        }
    }

    fun runNow(profileId: String) {
        // The listing is dropped when the run finishes, not now: the snapshot
        // it would add does not exist yet.
        Scheduler.runNow(getApplication(), profileId)
        say("Backup queued")
    }

    fun stop(profileId: String) {
        Scheduler.stopRun(getApplication(), profileId)
    }

    fun saveDestination(
        destination: Destination,
        password: String?,
        credentials: Map<String, String>,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            app.configStore.save(destination)
            password?.takeIf { it.isNotEmpty() }?.let {
                app.secrets.put(SecretStore.passwordAlias(destination.id), it)
            }
            credentials.forEach { (key, value) ->
                app.secrets.put(SecretStore.credentialAlias(destination.id, key), value)
            }
            say("Saved ${destination.name}")
        }
    }

    fun deleteDestination(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            app.secrets.removeAllFor(id)
            Repositories.forgetCredentials(id, ConfigPaths.credentialDir(getApplication()))
            app.configStore.deleteDestination(id)
        }
    }

    fun hasPassword(destinationId: String): Boolean =
        app.secrets.has(SecretStore.passwordAlias(destinationId))

    fun credential(destinationId: String, key: String): String =
        app.secrets.get(SecretStore.credentialAlias(destinationId, key)).orEmpty()

    suspend fun testConnection(
        destination: Destination,
        password: String,
        credentials: Map<String, String>,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val repository = Repositories.openWith(
                destination, password, app.secrets,
                ConfigPaths.credentialDir(getApplication()), credentials,
            )
            val result = app.restic.execute(repository, ResticCommand.catConfig())
            when {
                result.isSuccess -> "Repository opened."
                result.exitCode == ResticExit.REPOSITORY_NOT_FOUND ->
                    error("No repository at that location yet. Use \"Create repository\" instead.")
                else -> error(result.humanError())
            }
        }
    }

    suspend fun createRepository(
        destination: Destination,
        password: String,
        credentials: Map<String, String>,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val effective = Repositories.password(destination, password, app.secrets)
            if (effective.length < MIN_PASSWORD) {
                error("Choose a password of at least $MIN_PASSWORD characters. It is the only thing standing between your backups and whoever gets hold of them.")
            }
            val repository = Repositories.openWith(
                destination, effective, app.secrets,
                ConfigPaths.credentialDir(getApplication()), credentials,
            )
            val result = app.restic.execute(repository, ResticCommand.init())
            if (result.isSuccess) {
                "Repository created."
            } else {
                error(result.humanError())
            }
        }
    }

    suspend fun snapshots(
        destinationId: String,
        refresh: Boolean = false,
    ): Result<List<ResticSnapshot>> {
        if (!refresh) listings[destinationId]?.let { return Result.success(it) }
        return withContext(Dispatchers.IO) {
            runCatching {
                app.restic.executeJson(repository(destinationId), ResticCommand.snapshots())
                    .map(ResticSnapshot::from)
                    .sortedByDescending { it.time }
            }.onSuccess { listings[destinationId] = it }
        }
    }

    /** Anything that changes what a listing would say drops the cached one. */
    fun stale(destinationId: String) {
        listings.remove(destinationId)
    }

    /** One repository, opened. Every snapshot operation below needs exactly this. */
    private fun repository(destinationId: String) =
        config.value.destination(destinationId)
            ?.let { Repositories.openOrFail(it, app.secrets, ConfigPaths.credentialDir(getApplication())) }
            ?.getOrThrow()
            ?: error("destination not found")

    /**
     * The files in a snapshot, capped.
     *
     * A snapshot of a phone's storage can hold hundreds of thousands of nodes,
     * and nobody scrolls that far. Reaching the cap throws out of the collector,
     * which cancels the flow and kills restic - reading the rest and discarding
     * it would cost the same metered bytes as showing it.
     */
    suspend fun files(
        destinationId: String,
        snapshotId: String,
        limit: Int = FILE_LIMIT,
    ): Result<FileListing> = withContext(Dispatchers.IO) {
        runCatching {
            val repo = repository(destinationId)
            val out = mutableListOf<SnapshotFile>()
            var truncated = false
            var exit: Int? = null
            var problem: String? = null

            try {
                app.restic.stream(repo, ResticCommand.ls(snapshotId)).collect { event ->
                    when (event) {
                        is ResticEvent.Json -> {
                            val o = event.obj
                            if (!o.has("path")) return@collect
                            if (out.size >= limit) { truncated = true; throw EnoughFiles }
                            out += SnapshotFile(
                                path = o.optString("path"),
                                size = o.optLong("size"),
                                directory = o.optString("type") == "dir",
                            )
                        }
                        is ResticEvent.ItemError -> problem = problem ?: event.message
                        is ResticEvent.Finished -> exit = event.exitCode
                        else -> Unit
                    }
                }
            } catch (_: EnoughFiles) {
                // The cap, not a failure.
            }

            val code = exit
            if (!truncated && code != null && !ResticExit.isSuccess(code)) {
                error(problem ?: ResticExit.describe(code))
            }
            FileListing(out, truncated).also { lastFiles = snapshotId to it }
        }
    }

    suspend fun forgetSnapshot(destinationId: String, snapshotId: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val result = app.restic.execute(repository(destinationId), ResticCommand.forget(listOf(snapshotId)))
                if (!result.isSuccess) error(result.humanError())
                stale(destinationId)
                "Snapshot forgotten. Prune the repository to reclaim the space."
            }
        }

    suspend fun diff(destinationId: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val result = app.restic.execute(repository(destinationId), ResticCommand.diff(from, to))
                if (!result.isSuccess) error(result.humanError())
                val lines = result.stdout.trim().lines()
                if (lines.size <= DIFF_LINE_LIMIT) {
                    lines.joinToString("\n").ifEmpty { "No differences." }
                } else {
                    lines.take(DIFF_LINE_LIMIT).joinToString("\n") +
                        "\n\n… and ${lines.size - DIFF_LINE_LIMIT} more lines."
                }
            }
        }

    suspend fun pruneNow(activity: FragmentActivity, destinationId: String) {
        val name = config.value.destination(destinationId)?.name.orEmpty()
        if (!authenticate(activity, "Prune repository", "Confirm it is you before pruning $name.", destinationId)) {
            return
        }
        Scheduler.pruneNow(getApplication(), destinationId)
        stale(destinationId)
        say("Prune queued")
    }

    suspend fun restore(
        destinationId: String,
        snapshotId: String,
        target: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val destination = config.value.destination(destinationId) ?: error("destination not found")
            val repository = Repositories.openOrFail(
                    destination, app.secrets, ConfigPaths.credentialDir(getApplication()),
                ).getOrThrow()
            val result = app.restic.execute(repository, ResticCommand.restore(snapshotId, target))
            val problems = result.itemErrors()

            when {
                result.isSuccess -> RestoreReport.summarise(target, problems)

                // A restore in place always fails to chown Android's synthetic
                // roots, so a non-zero exit alone proves nothing. Anything
                // restic states as its reason, or any item error that is not
                // one of those, is a real failure.
                result.exitError() != null -> error(result.humanError())

                problems.isNotEmpty() && problems.all(RestoreReport::isBenign) ->
                    RestoreReport.summarise(target, problems)

                else -> error(result.humanError())
            }
        }
    }

    fun originalLocationTarget(): String = "/"

    fun copyRestoreTarget(): String =
        java.io.File(Environment.getExternalStorageDirectory(), "ResticDroid/restored").absolutePath

    fun saveSettings(settings: Settings) {
        viewModelScope.launch(Dispatchers.IO) { app.configStore.save(settings) }
    }

    companion object {
        private const val FILE_LIMIT = 5_000
        private const val DIFF_LINE_LIMIT = 2_000

        const val MIN_PASSWORD: Int = 8

        fun hasStorageAccess(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
    }
}
