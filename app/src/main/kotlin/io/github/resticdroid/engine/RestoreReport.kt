package io.github.resticdroid.engine

import io.github.resticdroid.restic.ResticItemError

internal object RestoreReport {
    // Restoring to "/" makes restic rebuild the whole path, so it tries to
    // chown Android's FUSE mount points and the kernel refuses. restic then
    // exits non-zero even though every file arrived intact.
    private val SYNTHETIC_ROOTS = setOf(
        "/", "/storage", "/storage/emulated", "/storage/emulated/0", "/sdcard", "/mnt", "/data",
    )

    private val METADATA_CALLS = setOf(
        "chown", "lchown", "chmod", "lchmod", "chtimes", "utimes", "lutimes", "utimensat",
    )

    fun isBenign(error: ResticItemError): Boolean =
        error.syscall in METADATA_CALLS &&
            error.item.trimEnd('/').ifEmpty { "/" } in SYNTHETIC_ROOTS

    fun summarise(target: String, problems: List<ResticItemError>): String {
        val where = if (target == "/") "Restored in place" else "Restored into $target"
        val real = problems.filterNot(::isBenign)
        if (real.isEmpty()) return where

        val noun = if (real.size == 1) "item" else "items"
        val detail = real.take(2).joinToString("; ") { it.toString() }
        val more = if (real.size > 2) " …" else ""
        return "$where, but ${real.size} $noun could not be written: $detail$more"
    }
}
