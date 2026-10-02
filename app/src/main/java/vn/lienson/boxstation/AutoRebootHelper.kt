package vn.lienson.boxstation

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

object AutoRebootHelper {

    private const val PREF_NAME = "box_station_prefs"
    private const val KEY_ENABLED = "auto_reboot_enabled"
    private const val KEY_HOUR = "auto_reboot_hour"
    private const val KEY_MINUTE = "auto_reboot_minute"
    private const val KEY_LAST_DAY = "auto_reboot_last_day"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        AppLogger.i(
            "AUTO-REBOOT",
            if (enabled) "⏰ Đã BẬT lịch tự động khởi động lại lúc 03:00 sáng hàng ngày"
            else "⏰ Đã TẮT lịch tự động khởi động lại hàng ngày"
        )
    }

    fun getScheduleTime(context: Context): Pair<Int, Int> {
        val h = getPrefs(context).getInt(KEY_HOUR, 3)
        val m = getPrefs(context).getInt(KEY_MINUTE, 0)
        return Pair(h, m)
    }

    fun getStatusString(context: Context): String {
        return if (isEnabled(context)) {
            val (h, m) = getScheduleTime(context)
            val timeStr = String.format("%02d:%02d", h, m)
            "BẬT (03:00 sáng mỗi ngày)"
        } else {
            "TẮT"
        }
    }

    fun checkAndTriggerDailyReboot(context: Context) {
        if (!isEnabled(context)) return

        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val currentMinute = cal.get(Calendar.MINUTE)
        val (targetHour, targetMinute) = getScheduleTime(context)

        // Kiểm tra đúng khung giờ (từ 03:00 đến 03:02)
        if (currentHour == targetHour && currentMinute in targetMinute..(targetMinute + 2)) {
            val currentDay = cal.get(Calendar.DAY_OF_YEAR)
            val prefs = getPrefs(context)
            val lastDay = prefs.getInt(KEY_LAST_DAY, -1)

            if (lastDay != currentDay) {
                prefs.edit().putInt(KEY_LAST_DAY, currentDay).apply()
                AppLogger.i("AUTO-REBOOT", "⏰ [03:00 SÁNG] Đã đến lịch tự động khởi động lại hoàn toàn Box để làm mới hệ thống...")
                SystemManagerHelper.rebootBox(context)
            }
        }
    }
}
