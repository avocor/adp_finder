package com.auodplus.satis.filemanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 1001
        private const val REQUEST_CODE_STORAGE_PERMISSION = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        checkAndRequestPermissions()
    }

    private fun startFileManager() {
        Toast.makeText(this, "所有權限已授予，啟動 File Manager...", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, FloatingService::class.java)
        startService(intent)
        finish()
    }

    /**
     * 檢查所有必要權限
     */
    private fun checkAndRequestPermissions() {
        var allPermissionsGranted = true

        // 檢查懸浮視窗權限
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            allPermissionsGranted = false
        }

        // 檢查 Storage 權限
        if (!checkStoragePermission()) {
            requestStoragePermission()
            allPermissionsGranted = false
        }

        if (allPermissionsGranted) {
            startFileManager()
        }
    }

    /**
     * 檢查 Storage 權限
     */
    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 發起 Storage 權限請求
     */
    private fun requestStoragePermission() {
        // Android 11 (R) 以上，需要 MANAGE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
                // 先彈出對話框，解釋 MIUI 如何手動開啟「全部檔案存取」
                showMiuiStorageGuideDialog()
            } else {
                // 其他廠牌，直接嘗試開啟「Manage App All Files Access」頁
                openManageAppAllFilesAccess()
            }
        } else {
            // Android 10 及以下
            val permissions = mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                REQUEST_CODE_STORAGE_PERMISSION
            )
        }
    }

    /**
     * 嘗試開啟 Android 11+ 的「Manage App All Files Access」頁面
     * 若無法處理，就退回舊的 ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
     */
    private fun openManageAppAllFilesAccess() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                // 新版可以指定「某個 App 的全部檔案存取」
                addCategory(Intent.CATEGORY_DEFAULT)
                data = Uri.parse("package:$packageName")
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivityForResult(intent, REQUEST_CODE_STORAGE_PERMISSION)
            } else {
                // 如果沒辦法處理，改用舊的
                openStandardStorageSettings()
            }
        } catch (e: Exception) {
            // 發生例外也改用舊的
            openStandardStorageSettings()
        }
    }

    /**
     * 開啟舊的「管理所有檔案」設定頁 (可能跳到一般的設定列表)
     */
    private fun openStandardStorageSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, REQUEST_CODE_STORAGE_PERMISSION)
        } else {
            Toast.makeText(
                this,
                "無法處理 MANAGE_EXTERNAL_STORAGE 請求，請手動到系統設定中開啟。",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    /**
     * MIUI：先顯示教學對話框，告知使用者如何進入隱私保護 / 安全中心
     */
    private fun showMiuiStorageGuideDialog() {
        val message = """
            在 MIUI 系統中，若看不到「全部檔案存取」選項，請嘗試以下路徑：
            
            1. 開啟「設定 (Settings)」
            2. 找到並點擊「隱私保護 (Privacy protection)」或「安全中心 (Security)」
            3. 尋找「特別許可權 (Special permissions)」或「全部檔案存取 (Manage all files)」
            4. 在清單中找到本應用：$packageName
            5. 開啟「全部檔案存取」或「檔案和媒體 (Files and media)」
            
            如果仍找不到，請多留意「安全中心」裡的其他選項。
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("小米 (MIUI) 權限設定引導")
            .setMessage(message)
            .setPositiveButton("前往設定") { _, _ ->
                // 嘗試開啟 MIUI 權限編輯器
                openMiuiPermissionEditor()
            }
            .setNegativeButton("取消") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 嘗試開啟 MIUI 權限編輯器
     * 若失敗，退回到 openManageAppAllFilesAccess()
     */
    private fun openMiuiPermissionEditor() {
        try {
            val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", packageName)
            }
            if (miuiIntent.resolveActivity(packageManager) != null) {
                startActivityForResult(miuiIntent, REQUEST_CODE_STORAGE_PERMISSION)
            } else {
                // 如果找不到，就用「Manage App All Files Access」頁面
                openManageAppAllFilesAccess()
            }
        } catch (e: Exception) {
            openManageAppAllFilesAccess()
        }
    }

    /**
     * 請求懸浮視窗權限
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION)
            } else {
                Toast.makeText(
                    this,
                    "無法處理 SYSTEM_ALERT_WINDOW 請求，請手動設定。",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    /**
     * 權限被拒後的提示對話框
     */
    private fun showPermissionDeniedDialog(
        title: String,
        message: String,
        retryAction: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("重試") { _, _ -> retryAction() }
            .setNegativeButton("離開") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    //region onActivityResult / onRequestPermissionsResult

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_OVERLAY_PERMISSION -> {
                if (Settings.canDrawOverlays(this)) {
                    checkAndRequestPermissions()
                } else {
                    showPermissionDeniedDialog(
                        "懸浮視窗權限被拒",
                        "請允許懸浮視窗權限，否則無法正常運作。",
                        ::requestOverlayPermission
                    )
                }
            }
            REQUEST_CODE_STORAGE_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        checkAndRequestPermissions()
                    } else {
                        // 再次顯示 MIUI 指引對話框或一般對話框
                        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
                            showMiuiStorageGuideDialog()
                        } else {
                            showPermissionDeniedDialog(
                                "存取權限被拒",
                                "請允許完整存取儲存空間權限，否則無法正常運作。",
                                ::requestStoragePermission
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) {
            // Android 10 以下會在這裡處理結果
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkAndRequestPermissions()
            } else {
                if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
                    showMiuiStorageGuideDialog()
                } else {
                    showPermissionDeniedDialog(
                        "存取權限被拒",
                        "請允許存取儲存空間權限，否則無法正常運作。",
                        ::requestStoragePermission
                    )
                }
            }
        }
    }

    //endregion
}
