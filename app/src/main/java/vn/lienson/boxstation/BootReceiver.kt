package vn.lienson.boxstation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        AppLogger.i("Boot", "Nhận tín hiệu khởi động hệ thống: $action")

        val serviceIntent = Intent(context, StationService::class.java).apply {
            this.action = StationService.ACTION_START
        }

        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            AppLogger.i("Boot", "🟢 Đã tự động kích hoạt BoxStation Service khi khởi động Box!")
        } catch (e: Exception) {
            AppLogger.e("Boot", "Lỗi khởi động Service từ BootReceiver: ${e.message}", e)
        }
    }
}
