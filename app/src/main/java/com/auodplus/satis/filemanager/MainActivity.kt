package com.auodplus.satis.filemanager


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 1001
        private const val REQUEST_CODE_CAMERA_PERMISSION = 1002
        private const val REQUEST_CODE_STORAGE_PERMISSION = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE) // 移除標題
        window.setBackgroundDrawableResource(android.R.color.transparent)

        if (checkAndRequestPermissions()) {
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val intent = Intent(this, FloatingService::class.java)
            startService(intent)
            finish()
        } else {
            setContentView(R.layout.activity_main)
        }
    }

    private fun startFloatingServiceAndClose() {
        val intent = Intent(this, FloatingService::class.java)
        startService(intent)
        finish()
    }

    private fun checkAndRequestPermissions(): Boolean {
        var allPermissionsGranted = true

        // 檢查 Overlay 權限
        if (!checkOverlayPermission()) {
            requestOverlayPermission()
            allPermissionsGranted = false
        }

        // 檢查相機權限
        if (!checkCameraPermission()) {
            requestCameraPermission()
            allPermissionsGranted = false
        }

        // 檢查存儲權限
        if (!checkAndRequestStoragePermission()) {
            allPermissionsGranted = false
        }

        return allPermissionsGranted
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION)
        } else {
            Toast.makeText(this, "Overlay permission is not required for your Android version.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CODE_CAMERA_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_CAMERA_PERMISSION, REQUEST_CODE_STORAGE_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (checkAndRequestPermissions()) {
                        startFloatingServiceAndClose()
                    }
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            if (checkOverlayPermission()) {
                if (checkAndRequestPermissions()) {
                    startFloatingServiceAndClose()
                }
            } else {
                Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAndRequestStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 不需要 WRITE_EXTERNAL_STORAGE 權限
            return true
        }

        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return true
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(permission),
            REQUEST_CODE_STORAGE_PERMISSION
        )
        return false
    }
}
