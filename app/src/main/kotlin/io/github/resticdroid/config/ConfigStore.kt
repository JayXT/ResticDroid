package io.github.resticdroid.config

import android.content.Context
import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

class ConfigStore(private val context: Context) {
    private val _state = MutableStateFlow(Config.EMPTY)
    val state: StateFlow<Config> = _state.asStateFlow()

    private val reloads = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var observers: List<FileObserver> = emptyList()

    fun load(): Config {
        val readable = runCatching { ConfigPaths.root().canRead() }.getOrDefault(false)
        if (!readable) {
            val config = Config.EMPTY.copy(accessible = false)
            _state.value = config
            return config
        }
        ConfigPaths.ensure()

        val settings = ConfigPaths.settingsFile()
            .takeIf { it.isFile }
            ?.let { Settings.fromIni(Ini.parse(it.readTextOrEmpty())) }
            ?: Settings()

        val destinations = confFiles(ConfigPaths.destinationsDir()).map { file ->
            Destination.fromIni(file.stem(), Ini.parse(file.readTextOrEmpty()))
        }.sortedBy { it.name.lowercase() }

        val profiles = confFiles(ConfigPaths.profilesDir()).map { file ->
            Profile.fromIni(file.stem(), Ini.parse(file.readTextOrEmpty()))
        }.sortedBy { it.name.lowercase() }

        val config = Config(settings, destinations, profiles, accessible = true)
        _state.value = config
        return config
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun startWatching(scope: CoroutineScope) {
        stopWatching()
        reloads
            .debounce(WATCH_DEBOUNCE_MS)
            .onEach { runCatching { load() } }
            .launchIn(scope)

        scope.launch(Dispatchers.IO) { runCatching { load() } }

        observers = listOf(
            ConfigPaths.root(),
            ConfigPaths.destinationsDir(),
            ConfigPaths.profilesDir(),
            ConfigPaths.excludesDir(),
        ).mapNotNull { dir -> watcher(dir) }
        observers.forEach { runCatching { it.startWatching() } }
    }

    fun stopWatching() {
        observers.forEach { runCatching { it.stopWatching() } }
        observers = emptyList()
    }

    fun save(profile: Profile) {
        writeAtomically(File(ConfigPaths.profilesDir(), profile.id + ConfigPaths.CONFIG_SUFFIX), profile.toIni())
        load()
    }

    fun save(destination: Destination) {
        writeAtomically(
            File(ConfigPaths.destinationsDir(), destination.id + ConfigPaths.CONFIG_SUFFIX),
            destination.toIni(),
        )
        load()
    }

    fun save(settings: Settings) {
        writeAtomically(ConfigPaths.settingsFile(), settings.toIni())
        load()
    }

    fun deleteProfile(id: String) {
        File(ConfigPaths.profilesDir(), id + ConfigPaths.CONFIG_SUFFIX).delete()
        load()
    }

    fun deleteDestination(id: String) {
        File(ConfigPaths.destinationsDir(), id + ConfigPaths.CONFIG_SUFFIX).delete()
        load()
    }

    fun initialiseIfEmpty() {
        ConfigPaths.ensure()
        if (!ConfigPaths.settingsFile().exists()) save(Settings())
        val readme = File(ConfigPaths.root(), "README")
        if (!readme.exists()) writeAtomically(readme, README)
        load()
    }

    private fun confFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(ConfigPaths.CONFIG_SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun File.stem() = name.removeSuffix(ConfigPaths.CONFIG_SUFFIX)

    private fun File.readTextOrEmpty(): String = runCatching { readText() }.getOrDefault("")

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "." + target.name + ".tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                target.writeText(content)
                temp.delete()
            }
        }
    }

    private fun watcher(dir: File): FileObserver? {
        if (!dir.exists() && !dir.mkdirs()) return null
        @Suppress("DEPRECATION")
        return object : FileObserver(dir.absolutePath, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path.startsWith(".") && path.endsWith(".tmp")) return
                reloads.tryEmit(Unit)
            }
        }
    }

    private companion object {
        const val WATCH_DEBOUNCE_MS = 400L
        const val WATCH_MASK = FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE

        val README = """
            ResticDroid configuration
            =========================

            This directory is the application's configuration. There is no
            database and nothing to import or export: edit these files and the
            app picks up the change immediately.

              resticdroid.conf    global settings
              destinations.d/     one file per restic repository
              profiles.d/         one file per backup job
              exclude.d/          optional shared exclude-pattern files
              log/                one log per run

            Every file is 'key = value', one pair per line, '#' for comments.
            A key may repeat; that is how lists are written:

              path = /storage/emulated/0/DCIM
              path = /storage/emulated/0/Pictures

            Passwords, API keys and tokens are NOT here. They are held in the
            Android keystore, hardware-backed on devices that have a secure
            element, and are not readable by any other application. That means
            copying this directory to another device gives you the profiles but
            not the credentials, which is the intended trade-off.

            Deleting a profile here removes it from the app. Deleting a
            destination does NOT delete the repository it points at; your
            snapshots stay where they are.
        """.trimIndent() + "\n"
    }
}

data class Config(
    val settings: Settings,
    val destinations: List<Destination>,
    val profiles: List<Profile>,
    val accessible: Boolean,
) {
    fun destination(id: String): Destination? = destinations.firstOrNull { it.id == id }

    fun profile(id: String): Profile? = profiles.firstOrNull { it.id == id }

    companion object {
        val EMPTY: Config = Config(Settings(), emptyList(), emptyList(), accessible = false)
    }
}
