package io.github.xeno_hf.rex

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import rx.session.SessionState

class RxApp : Application() {
    lateinit var repo: Repo; private set
    lateinit var platform: AndroidPlatform; private set

    override fun onCreate() {
        super.onCreate()
        platform = AndroidPlatform(this)
        repo = Repo(platform)
        repo.loadRegistry()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(RxService.CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }
}

/**
 * 前景服務：連線期間保持程序與 CPU 存活（螢幕關掉照樣錄）。
 * Session 本身由 Repo 持有，服務只負責「活著」與通知列狀態。
 */
class RxService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            repo().disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        // GPS 開著且有權限才加 location 類型（螢幕關掉也繼續定位）；沒權限就加會被系統拒
        val r = repo()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (r.gpsEnabled.value && r.gps.hasPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        startForeground(1, build(getString(R.string.notif_connecting)), type)
        // 程序被系統殺掉後重建時沒有連線，不要留一個空轉的通知
        if (repo().session == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RX:session").apply {
                setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
            }
        }
        if (job == null) {
            val repo = repo()
            job = repo.scope.launch {
                combine(repo.state, repo.recording) { s, r -> s to r }.collect { (s, r) ->
                    val text = when (s) {
                        SessionState.Streaming -> getString(R.string.st_connected)
                        is SessionState.Arming, is SessionState.Connecting -> getString(R.string.notif_connecting)
                        is SessionState.NoBroadcast -> getString(R.string.notif_waiting)
                        is SessionState.Reconnecting -> getString(R.string.notif_reconnecting)
                        else -> getString(R.string.not_connected)
                    } + if (r != null) getString(R.string.notif_recording) else ""
                    getSystemService(NotificationManager::class.java).notify(1, build(text))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RxService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rex)
            .setContentTitle("ReX")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    /** 使用者從最近使用清單滑掉 App：沒開「關閉後繼續連線」就整個停掉。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val repo = repo()
        if (!repo.runAfterClose) {
            repo.disconnect()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        job?.cancel(); job = null
        wake?.takeIf { it.isHeld }?.release(); wake = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "rx_session"
        const val ACTION_STOP = "io.github.xeno_hf.rex.STOP"
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, RxService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, RxService::class.java))
    }
}

fun Context.repo(): Repo = (applicationContext as RxApp).repo
