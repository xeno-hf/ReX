package io.github.xeno_hf.rex

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 手機 GPS（XTune 同款：Phone_GPS_* 四個虛擬通道，loga 接在 ECU 欄位後面）。
 * 只用系統 LocationManager，不依賴 Google Play 服務。
 */
class AndroidGps(private val ctx: Context) : PhoneGps {
    override val fix = MutableStateFlow<PhoneGps.Fix?>(null)
    private var running = false

    private val listener = LocationListener { l: Location ->
        fix.value = PhoneGps.Fix(
            l.latitude, l.longitude,
            if (l.hasSpeed()) l.speed * 3.6 else 0.0,
            if (l.hasAltitude()) l.altitude else 0.0,
            l.elapsedRealtimeNanos,
        )
    }

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun start() {
        if (running || !hasPermission()) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, listener, Looper.getMainLooper())
        running = true
    }

    override fun stop() {
        if (!running) return
        ctx.getSystemService(LocationManager::class.java)?.removeUpdates(listener)
        running = false
        fix.value = null
    }

    /** 3 秒內的定位才算數（隧道、地下室斷訊就回 null）。 */
    override fun fresh(): PhoneGps.Fix? = fix.value?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedNanos < PhoneGps.FRESH_NANOS }
}
