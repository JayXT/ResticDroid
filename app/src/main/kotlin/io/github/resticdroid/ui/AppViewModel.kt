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
import io.github.resticdroid.engine.ProviderError
import io.github.resticdroid.engine.RepositoryWork
import io.github.resticdroid.engine.Repositories
import io.github.resticdroid.engine.RestoreReport
import io.github.resticdroid.restic.SftpLocation
import io.github.resticdroid.restic.ResticCommand
import io.github.resticdroid.restic.ResticExit
import io.github.resticdroid.restic.ResticSnapshot
import io.github.resticdroid.restic.lockCleanupWarning
import io.github.resticdroid.secret.BiometricGate
import io.github.resticdroid.secret.SecretStore
import io.github.resticdroid.util.Redact
import io.github.resticdroid.work.BackupWorker
import io.github.resticdroid.work.Runs
import io.github.resticdroid.work.RunLog
import io.github.resticdroid.work.Scheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    suspend fun generateSshIdentity(): String = app.restic.generateSshIdentity()

    suspend fun probeSshHost(endpoint: SftpLocation): String =
        app.restic.probeSshHost(endpoint)

    fun hasPassword(destinationId: String): Boolean =
        app.secrets.has(SecretStore.passwordAlias(destinationId))

    fun credential(destinationId: String, key: String): String =
        app.secrets.get(SecretStore.credentialAlias(destinationId, key)).orEmpty()

    suspend fun testConnection(
        destination: Destination,
        password: String,
        credentials: Map<String, String>,
    ): Result<String> = RepositoryWork.run {
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
                else -> error(ProviderError.explain(destination, result.humanError()))
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    suspend fun createRepository(
        destination: Destination,
        password: String,
        credentials: Map<String, String>,
    ): Result<String> = RepositoryWork.run {
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
                error(ProviderError.explain(destination, result.humanError()))
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    suspend fun snapshots(
        destinationId: String,
        refresh: Boolean = false,
    ): Result<List<ResticSnapshot>> {
        if (!refresh) listings[destinationId]?.let { return Result.success(it) }
        return RepositoryWork.run {
            runCatching {
                app.restic.executeJson(repository(destinationId), ResticCommand.snapshots())
                    .map(ResticSnapshot::from)
                    .sortedByDescending { it.instant }
            }.explained(destinationId).onSuccess { listings[destinationId] = it }
        }
    }

    /**
     * restic's own message, unless the storage provider refused the credentials.
     *
     * Applied to the result rather than to each error(): every repository call
     * below can fail this way, and they should all say the same thing when it
     * happens.
     */
    private fun <T> Result<T>.explained(destinationId: String): Result<T> {
        exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return recoverCatching { cause ->
            val destination = config.value.destination(destinationId) ?: throw cause
            val message = cause.message.orEmpty()
            val better = ProviderError.explain(destination, message)
            if (better == message) throw cause
            throw IllegalStateException(better, cause)
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

    /** Keep a small display list, but drain restic so it can release its lock. */
    suspend fun files(
        destinationId: String,
        snapshotId: String,
    ): Result<FileListing> = RepositoryWork.run {
        runCatching {
            val repo = repository(destinationId)
            val log = RunLog.open("files-$destinationId")
            val secrets = repo.env.values + repo.password
            fun safe(text: String) = Redact.text(text, secrets)
            log.line("listing snapshot $snapshotId")
            try {
                val listing = app.restic.stream(repo, ResticCommand.ls(snapshotId))
                    .readFiles { log.line(safe(it)) }
                listing.copy(warning = listing.warning?.let(::safe))
                    .also { lastFiles = snapshotId to it }
            } catch (e: CancellationException) {
                log.line("listing cancelled")
                throw e
            } catch (e: Exception) {
                log.line("listing failed: ${safe(e.message.orEmpty())}")
                throw IllegalStateException(safe(e.message ?: "File listing failed"), e)
            } finally {
                RunLog.prune(config.value.settings.logRetention)
            }
        }.explained(destinationId)
    }

    suspend fun forgetSnapshot(destinationId: String, snapshotId: String): Result<String> =
        RepositoryWork.run {
            runCatching {
                val repo = repository(destinationId)
                val result = app.restic.execute(repo, ResticCommand.forget(listOf(snapshotId)))
                if (!result.isSuccess) error(result.humanError())
                stale(destinationId)
                val warning = lockCleanupWarning(result.stderr)?.let {
                    Redact.text(it, repo.env.values + repo.password)
                }
                warning?.let { "Snapshot forgotten, but cleanup failed: $it" }
                    ?: "Snapshot forgotten. Prune the repository to reclaim the space."
            }.explained(destinationId)
        }

    suspend fun diff(destinationId: String, from: String, to: String): Result<String> =
        RepositoryWork.run {
            runCatching {
                val repo = repository(destinationId)
                Redact.text(app.restic.stream(repo, ResticCommand.diff(from, to)).readDiff(), repo.env.values + repo.password)
            }.explained(destinationId)
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
    ): Result<String> = RepositoryWork.run {
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
        }.explained(destinationId)
    }

    fun originalLocationTarget(): String = "/"

    fun copyRestoreTarget(): String =
        java.io.File(Environment.getExternalStorageDirectory(), "ResticDroid/restored").absolutePath

    fun saveSettings(settings: Settings) {
        viewModelScope.launch(Dispatchers.IO) { app.configStore.save(settings) }
    }

    companion object {
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
