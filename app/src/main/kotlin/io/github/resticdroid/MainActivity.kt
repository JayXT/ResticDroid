package io.github.resticdroid

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.resticdroid.ui.AppViewModel
import io.github.resticdroid.ui.ResticDroidUi
import io.github.resticdroid.ui.theme.ResticDroidTheme

class MainActivity : FragmentActivity() {
    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ResticDroidTheme {
                ResticDroidUi(activity = this, scope = lifecycleScope)
            }
        }
    }

    // An unlock lasts as long as the app is in front of the user. Leaving it -
    // including the screen going off - puts the repositories back behind the
    // fingerprint, and drops the snapshot listings read under that unlock.
    override fun onStop() {
        super.onStop()
        model.lock()
    }
}
