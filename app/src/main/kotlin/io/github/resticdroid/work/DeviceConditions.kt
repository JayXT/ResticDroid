package io.github.resticdroid.work

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.location.LocationManagerCompat
import io.github.resticdroid.config.Conditions

object DeviceConditions {
    sealed interface Verdict {
        object Satisfied : Verdict
        data class Unsatisfied(val reason: String) : Verdict
    }

    fun check(context: Context, conditions: Conditions): Verdict {
        if (conditions.minBatteryPercent > 0 && !isCharging(context)) {
            val level = batteryPercent(context)
            if (level in 0 until conditions.minBatteryPercent) {
                return Verdict.Unsatisfied(
                    "battery is $level%, profile requires ${conditions.minBatteryPercent}%"
                )
            }
        }

        if (conditions.wifiSsid.isNotEmpty()) {
            ssidAccessProblem(context)?.let { return Verdict.Unsatisfied(it) }
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                return Verdict.Unsatisfied("not connected to Wi-Fi")
            }
            val current = try {
                currentSsid(context)
            } catch (_: SecurityException) {
                return Verdict.Unsatisfied("Wi-Fi access denied; check the app's location permissions")
            } ?: return Verdict.Unsatisfied("Wi-Fi name unavailable; check location permissions and Location settings")
            if (current !in conditions.wifiSsid) {
                return Verdict.Unsatisfied("Wi-Fi network '$current' is not in the allowed list")
            }
        }

        return Verdict.Satisfied
    }

    private fun batteryPercent(context: Context): Int {
        val manager = context.getSystemService(BatteryManager::class.java)
        val fromManager = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (fromManager in 0..100) return fromManager

        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    private fun isCharging(context: Context): Boolean {
        val manager = context.getSystemService(BatteryManager::class.java)
        return manager?.isCharging ?: false
    }

    private fun ssidAccessProblem(context: Context): String? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "Wi-Fi name requires precise location permission"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return "Wi-Fi name requires location permission set to Allow all the time"
        }
        val location = context.getSystemService(LocationManager::class.java)
        if (location == null || !LocationManagerCompat.isLocationEnabled(location)) {
            return "turn on Location to read the Wi-Fi name"
        }
        return null
    }

    private fun currentSsid(context: Context): String? {
        @Suppress("DEPRECATION")
        val info = context.applicationContext
            .getSystemService(WifiManager::class.java)
            ?.connectionInfo
            ?: return null

        @Suppress("DEPRECATION")
        val ssid = info.ssid?.trim('"').orEmpty()
        return ssid.takeIf { it.isNotEmpty() && it != "<unknown ssid>" && it != "0x" }
    }
}
