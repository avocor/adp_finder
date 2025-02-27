package com.auodplus.satis.filemanager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var switchAllowWindowTitle: Switch
    private lateinit var seekBarSensitivity: SeekBar
    private lateinit var btnSaveSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 設置透明，讓畫面剛開始時不可見
        window.decorView.alpha = 0f


        setContentView(R.layout.activity_settings)

        sharedPreferences = getSharedPreferences("FloatingCameraSettings", Context.MODE_PRIVATE)

        switchAllowWindowTitle = findViewById(R.id.switchAllowWindowTitle)
        seekBarSensitivity = findViewById(R.id.seekBarSensitivity)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)

        // 初始化設定值
        switchAllowWindowTitle.isChecked = sharedPreferences.getBoolean("allowWindowTitle", true)
        seekBarSensitivity.progress = sharedPreferences.getInt("sensitivityDistance", 400) - 200

        // 關閉 Floating Camera Service
        stopService(Intent(this, FloatingService::class.java))

        btnSaveSettings.setOnClickListener {
            val editor = sharedPreferences.edit()
            editor.putBoolean("allowWindowTitle", switchAllowWindowTitle.isChecked)
            editor.putInt("sensitivityDistance", seekBarSensitivity.progress + 200)
            editor.apply()

            finish() // 儲存後關閉設定頁面
        }

        // 延遲 0.8 秒後顯示畫面
        Handler(Looper.getMainLooper()).postDelayed({
            window.decorView.alpha = 1f
        }, 800)
    }

    override fun onDestroy() {
        super.onDestroy()

        // 重新開啟 Floating Camera Service
        startService(Intent(this, FloatingService::class.java))
    }

}
