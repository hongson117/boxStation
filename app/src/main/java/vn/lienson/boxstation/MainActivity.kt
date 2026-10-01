package vn.lienson.boxstation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvServerUrl: TextView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvAllIps: TextView
    private lateinit var tvDrivesList: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var btnPermission: Button
    private lateinit var btnRestartService: Button
    private lateinit var btnRebootBox: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServerUrl = findViewById(R.id.tvServerUrl)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvAllIps = findViewById(R.id.tvAllIps)
        tvDrivesList = findViewById(R.id.tvDrivesList)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        btnPermission = findViewById(R.id.btnPermission)
        btnRestartService = findViewById(R.id.btnRestartService)
        btnRebootBox = findViewById(R.id.btnRebootBox)

        btnPermission.setOnClickListener {
            checkAndRequestStoragePermission()
        }

        btnRestartService.setOnClickListener {
            restartStationService()
            Toast.makeText(this, "Đang khởi động lại dịch vụ BoxStation...", Toast.LENGTH_SHORT).show()
        }

        btnRebootBox.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Khởi động lại Box")
                .setMessage("Bạn có chắc chắn muốn khởi động lại thiết bị ngay bây giờ?")
                .setPositiveButton("Khởi động lại") { _, _ ->
                    val (ok, msg) = SystemManagerHelper.rebootBox(this)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }

        startStationService()
    }

    override fun onResume() {
        super.onResume()
        updateDashboard()
    }

    private fun startStationService() {
        val serviceIntent = Intent(this, StationService::class.java).apply {
            action = StationService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "Lỗi khởi động StationService: ${e.message}", e)
        }
    }

    private fun restartStationService() {
        val stopIntent = Intent(this, StationService::class.java).apply {
            action = StationService.ACTION_STOP
        }
        stopService(stopIntent)

        tvServerUrl.postDelayed({
            startStationService()
            updateDashboard()
        }, 1000)
    }

    private fun updateDashboard() {
        val preferredIp = SystemManagerHelper.getPreferredIp()
        tvServerUrl.text = "http://$preferredIp:8888"

        val ips = SystemManagerHelper.getIpAddresses()
        val ipSummary = ips.entries.joinToString("  |  ") { "${it.key}: ${it.value}" }
        tvAllIps.text = if (ipSummary.isNotEmpty()) ipSummary else "Đang ngắt kết nối mạng"

        // Quét ổ cứng
        val drives = StorageManagerHelper.getStorageDrives(this)
        if (drives.isEmpty()) {
            tvDrivesList.text = "Không tìm thấy ổ cứng nào được gắn."
        } else {
            val sb = StringBuilder()
            drives.forEachIndexed { i, d ->
                val icon = if (d.isUsb) "💾" else "📱"
                sb.append("$icon ${d.name}\n")
                sb.append("   • Đường dẫn: ${d.path}\n")
                sb.append("   • Dung lượng: Còn trống ${d.freeFormatted} / Tổng ${d.totalFormatted} (Đã dùng ${d.usedPercent}%)\n")
                if (i < drives.size - 1) sb.append("\n")
            }
            tvDrivesList.text = sb.toString()
        }

        // Quyền lưu trữ Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                btnPermission.text = "✅ Đã Có Toàn Quyền Ổ Cứng"
                btnPermission.isEnabled = true
                btnPermission.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
            } else {
                btnPermission.text = "🔑 Cấp Quyền Đọc Ổ Cứng (MANAGE)"
                btnPermission.isEnabled = true
                btnPermission.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_orange))
            }
        } else {
            btnPermission.text = "✅ Đã Có Quyền Bộ Nhớ"
            btnPermission.isEnabled = false
        }

        // Thông tin thiết bị
        val sys = SystemManagerHelper.getSystemInfo(this)
        tvDeviceInfo.text = "${sys.deviceModel} | ${sys.androidVersion} | RAM: Còn trống ${sys.freeRam} / ${sys.totalRam} | Uptime: ${sys.uptime}"
    }

    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            } else {
                Toast.makeText(this, "Ứng dụng đã có đầy đủ quyền đọc/ghi mọi ổ cứng!", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Android 10 trở xuống không cần cấp thêm quyền này.", Toast.LENGTH_SHORT).show()
        }
    }
}
