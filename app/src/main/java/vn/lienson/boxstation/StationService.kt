package vn.lienson.boxstation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class StationService : Service() {

    private var httpServer: StationHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var rebootJob: Job? = null

    companion object {
        const val CHANNEL_ID = "box_station_channel"
        const val NOTIFICATION_ID = 8888
        const val ACTION_START = "vn.lienson.boxstation.ACTION_START"
        const val ACTION_STOP = "vn.lienson.boxstation.ACTION_STOP"
        var isServiceRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.i("Service", "Khởi tạo BoxStation Service")

        // 1. Giữ CPU không bị sleep
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BoxStation::WakeLock")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
            AppLogger.d("Service", "Đã kích hoạt WakeLock giữ Box hoạt động 24/7")
        } catch (e: Exception) {
            AppLogger.e("Service", "Lỗi WakeLock: ${e.message}")
        }

        // 2. Khởi động HTTP Server trên cổng 8888
        httpServer = StationHttpServer(this, 8888)
        httpServer?.start()
        isServiceRunning = true

        // 3. Tự động kiểm tra lịch khởi động lại mỗi 3h sáng
        rebootJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                try {
                    AutoRebootHelper.checkAndTriggerDailyReboot(applicationContext)
                } catch (e: Exception) {
                    // pass
                }
                delay(30_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val ip = SystemManagerHelper.getPreferredIp()
        val contentText = "Mini NAS & Web Installer đang online tại http://$ip:8888"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BoxStation - FPT Play Box")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BoxStation Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kênh duy trì dịch vụ BoxStation và chia sẻ ổ cứng 24/7"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        rebootJob?.cancel()
        rebootJob = null
        httpServer?.stop()
        httpServer = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // pass
        }
        AppLogger.i("Service", "BoxStation Service đã dừng")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
