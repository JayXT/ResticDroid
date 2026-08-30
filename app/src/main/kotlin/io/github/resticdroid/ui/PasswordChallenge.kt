package io.github.resticdroid.ui

import kotlinx.coroutines.CompletableDeferred

class PasswordChallenge(
    val title: String,
    val subtitle: String,
    private val verify: (String) -> Boolean,
    private val outcome: CompletableDeferred<Boolean>,
) {
    fun submit(password: String): Boolean {
        val accepted = verify(password)
        if (accepted) outcome.complete(true)
        return accepted
    }

    fun cancel() {
        outcome.complete(false)
    }
}
