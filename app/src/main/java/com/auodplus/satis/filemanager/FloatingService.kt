package com.auodplus.satis.filemanager

import android.Manifest
import android.annotation.SuppressLint

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.*
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream
import kotlin.math.atan2
import kotlin.math.round
import kotlin.math.sqrt

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var windowTitle: LinearLayout
    private lateinit var windowContainer: FrameLayout
    private lateinit var textureContainer: FrameLayout

    private lateinit var resizeHandle:View
    private lateinit var moveHandle:View
    private lateinit var cameraManager: CameraManager
    private lateinit var availabilityCallback: CameraManager.AvailabilityCallback

    private lateinit var fabTogglePanel: FloatingActionButton
    private lateinit var controlPanel: LinearLayout


    private val cameraIds = mutableListOf<String>()
    private var currentCameraDevice: CameraDevice? = null
    private var currentSession: CameraCaptureSession? = null
    private var currentCameraId: String? = null
    private var isCameraRunning = true
    private var rotationAngle = 0f
    private var isFlippedHorizontally = false
    private var isFlippedVertically = false

    private var zoomLevel = 2.0f //default zoomLevel
    private var zoomStep = 1.0f //When use zoom in / zoom out
    private var maxZoomLevel = 4.0f //default maxZoomLevel if camera has not max zoom level in its characteristic

    private var isFullscreen = false
    private var previousWidth = 0
    private var previousHeight = 0

    private var gestureScaleFactor = 1f
    private var gestureRotationAngle = 0f
    private var gestureRotationBasedAngle = 0 //0,90,180,270
    private val gestureMatrix = Matrix()
    private var gestureLastDistance = 0f
    private var gestureInitialAngle = 0f

    private var isMultiTouchActive = false
    private val handlerForIdleWork = Handler(Looper.getMainLooper())
    private var allowWindowTitle:Boolean = false
    private var sensitivityDistance = 400 //200 is most fast for gesture sensitivity

    override fun onCreate() {
        super.onCreate()

        val sharedPreferences = getSharedPreferences("FloatingCameraSettings", Context.MODE_PRIVATE)
        allowWindowTitle = sharedPreferences.getBoolean("allowWindowTitle", false)
        sensitivityDistance = sharedPreferences.getInt("sensitivityDistance", 400)


        setupFloatingWindow()
        startForegroundService()

    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "camera_service_channel"
            val channelName = "Camera Service"
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, "camera_service_channel")
            .setContentTitle("Floating Camera")
            .setContentText("Floating camera is running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(1, notification)
    }

    private val buttonPanelHeight = 300 // 根據布局按鈕高度設置






    private fun setupFloatingWindow() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_ADPVisualizer)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutParams = WindowManager.LayoutParams(
            800, //init width
            450,  //init height
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )


        floatingView = LayoutInflater.from(themedContext).inflate(R.layout.floating_view, null)

        resizeHandle = floatingView.findViewById<View>(R.id.resizeHandle)
        moveHandle = floatingView.findViewById<View>(R.id.moveHandle)
        //textureContainer = floatingView.findViewById<FrameLayout>(R.id.textureContainer)
        windowContainer = floatingView.findViewById<FrameLayout>(R.id.windowContainer)
        windowTitle = floatingView.findViewById<LinearLayout>(R.id.windowTitle)
        if(!allowWindowTitle) windowTitle.visibility = View.GONE

        fabTogglePanel = floatingView.findViewById<FloatingActionButton>(R.id.fabTogglePanel)
        controlPanel = floatingView.findViewById<LinearLayout>(R.id.controlPanel)

        Log.i("[DEBUG]", "floatingView.setOnTouchListener ")

        floatingView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { notifyIdleWorkQuit() }
                MotionEvent.ACTION_UP -> { floatingView.performClick() }
            }
            false // 繼續讓其他 View 處理事件
        }

        windowContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    controlPanel.visibility = View.GONE
                    notifyIdleWorkQuit()
                    false // 繼續讓其他觸控事件傳遞
                }
                MotionEvent.ACTION_UP -> {
                    Handler(Looper.getMainLooper()).post {
                        controlPanel.visibility = View.GONE
                        notifyIdleWorkQuit()
                    }
                    true // 吃掉點擊事件
                }
                else -> false //將事件傳遞下去讓其它地方處理 Drag 功能
            }
        }



        val btnClose = floatingView.findViewById<View>(R.id.btnClose)
        val btnCloseWindow = floatingView.findViewById<View>(R.id.btnCloseWindow)
        val btnFullscreen = floatingView.findViewById<View>(R.id.btnFullscreen)
        val btnSettings = floatingView.findViewById<View>(R.id.btnSettings)
        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }



        btnClose.setOnClickListener (wrapWithNotify{ stopSelf() })

        btnCloseWindow.setOnClickListener (wrapWithNotify{ stopSelf() })
        btnCloseWindow.visibility = if(!allowWindowTitle) View.VISIBLE else View.GONE

        btnFullscreen.setOnClickListener (wrapWithNotify{ fullscreen() })


        setupWindowResizingListener(resizeHandle, layoutParams)


        if(allowWindowTitle) {
            setupWindowMovingListener(moveHandle, layoutParams)
            //textureContainer.setOnTouchListener { v, event -> handleTouchForGesture(v,event) }
        } else {
            setupWindowMovingListener(floatingView, layoutParams)
        }

        //textureContainer.setOnTouchListener { v, event -> handleTouchForGesture(v,event) }



        fabTogglePanel.setOnClickListener {
            notifyIdleWorkQuit()
            if (controlPanel.visibility == View.VISIBLE) {
                controlPanel.visibility = View.GONE
                //fabTogglePanel.setImageResource(R.drawable.ic_menu) // 切換回關閉圖示
            } else {
                controlPanel.visibility = View.VISIBLE
                //fabTogglePanel.setImageResource(android.R.drawable.ic_input_add) // 切換回添加圖示
            }
        }


        //adjustTextureContainerInUiThread()

        windowManager.addView(floatingView, layoutParams)

        notifyIdleWorkQuit()
    }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isMoving = false

    private fun handleSingleFingerDrag(event: MotionEvent, layoutParams: WindowManager.LayoutParams): Boolean {
        // 如果剛剛是雙指觸控，則不允許拖動
        if (isMultiTouchActive) {
            if (event.action == MotionEvent.ACTION_UP) {
                isMultiTouchActive = false // 當所有手指都抬起時，才允許視窗移動
            }
            return false
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                notifyIdleWorkQuit()
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isMoving = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()

                // 只有移動超過一定閾值才視為拖曳，避免影響點擊
                if (dx * dx + dy * dy > 25) {
                    isMoving = true
                }

                if (isMoving) {
                    layoutParams.x = initialX + dx
                    layoutParams.y = initialY + dy
                    windowManager.updateViewLayout(floatingView, layoutParams)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                isMoving = false
                return false
            }
        }
        return false
    }



    private fun resetWindowIdleWorkTimer() {
        Log.i("[DEBUG]", "resetWindowIdleWorkTimer() is called")
        // 先移除所有延遲的隱藏任務
        handlerForIdleWork.removeCallbacks(idleWorkRunnable)
        // 重新開始計時 2 秒後隱藏進入 window Idle 要作的事
        handlerForIdleWork.postDelayed(idleWorkRunnable, 2000)
    }
    private fun notifyIdleWorkQuit() {
        Log.i("[DEBUG]", "notifyIdleWorkQuit() called")
        handlerForIdleWork.post(idleWorkQuitRunnable)
        resetWindowIdleWorkTimer() // 重新開始計時
    }
    private val idleWorkRunnable = Runnable {
        Log.i("[DEBUG]", "idleWorkRunnable() is called")
        if(allowWindowTitle) windowTitle.visibility = View.INVISIBLE
        fabTogglePanel.visibility = View.INVISIBLE

    }
    private val idleWorkQuitRunnable = Runnable {
        Log.i("[DEBUG]", "idleWorkQuitRunnable() is called")
        if(!isFullscreen)  {

            if(allowWindowTitle) windowTitle.visibility = View.VISIBLE
        }
        fabTogglePanel.visibility = View.VISIBLE
    }

    private fun wrapWithNotify(action: () -> Unit): View.OnClickListener {
        return View.OnClickListener {
            notifyIdleWorkQuit() // 先執行
            action() // 再執行原本的動作
        }
    }


    fun applyCenterCrop(matrix: Matrix, viewWidth: Int, viewHeight: Int, contentWidth: Int, contentHeight: Int) {
        val scale: Float = maxOf(
            viewWidth.toFloat() / contentWidth,
            viewHeight.toFloat() / contentHeight
        )
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            (viewWidth - contentWidth * scale) / 2,
            (viewHeight - contentHeight * scale) / 2
        )
    }



    private fun setupWindowResizingListener(v:View, layoutParams: WindowManager.LayoutParams) {
        v.setOnTouchListener(object : View.OnTouchListener {

            private var lastUpdateTime = 0L

            private var initialWidth = 0
            private var initialHeight = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                notifyIdleWorkQuit()
                //如果是全營幕模式，則不處理 Resize
                if(isFullscreen) return true //吃掉事件，不執行任動作
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = layoutParams.width
                        initialHeight = layoutParams.height
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val currentTime = System.currentTimeMillis()
                        // 每 64 毫秒更新一次(15Hz)，避免頻繁觸發
                        if (currentTime - lastUpdateTime < 64) {
                            return true
                        }
                        lastUpdateTime = currentTime

                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()

                        layoutParams.width = maxOf(533, initialWidth + deltaX) // 限制最小寬度
                        layoutParams.height = maxOf(300, initialHeight + deltaY) // 限制最小高度

                        //adjustTextureContainerInUiThread()

                        windowManager.updateViewLayout(floatingView, layoutParams)

                        return true
                    }
                }
                return false
            }
        })
    }

    private fun setupWindowMovingListener(v:View, layoutParams: WindowManager.LayoutParams) {
        v.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoving = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        notifyIdleWorkQuit()
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        notifyIdleWorkQuit()
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if(dx*dx + dy*dy >25) {
                            isMoving = true
                        }
                        if(isMoving) {
                            layoutParams.x = initialX + dx
                            layoutParams.y = initialY + dy
                            windowManager.updateViewLayout(floatingView, layoutParams)
                        }
                        return true
                        /*
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        return true
                        */

                    }
                    MotionEvent.ACTION_UP -> {
                        v?.performClick()
                        return true
                    }
                }
                return false
            }
        })
    }





    private fun fullscreen() {
        if (isFullscreen) {
            floatingView.findViewById<LinearLayout>(R.id.controlPanel).visibility = View.VISIBLE

            val layoutParams = floatingView.layoutParams as WindowManager.LayoutParams
            layoutParams.width = previousWidth
            layoutParams.height = previousHeight
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN.inv()

            windowManager.updateViewLayout(floatingView, layoutParams)
            //adjustTextureContainerInUiThread()
        } else {
            floatingView.findViewById<LinearLayout>(R.id.controlPanel).visibility = View.GONE
            val layoutParams = floatingView.layoutParams as WindowManager.LayoutParams

            previousWidth = layoutParams.width
            previousHeight = layoutParams.height

            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            windowManager.updateViewLayout(floatingView, layoutParams)
            //adjustTextureContainerInUiThread()
        }

        isFullscreen = !isFullscreen
        if(allowWindowTitle)  windowTitle.visibility = if(isFullscreen) View.INVISIBLE else View.VISIBLE


    }

    private fun closeResources() {
        Log.i("DEBUG", "Start to close resources")
        //for example, closeCamera() if you have it
    }
    override fun onDestroy() {
        super.onDestroy()
        closeResources()
        windowManager.removeView(floatingView)
    }

    private fun calculateDistance(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun calculateAngle(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }










    private fun showRec(viewName:String, view:View ) {
        val ratio:Float = view.width.toFloat()/ view.height.toFloat()
        val roundedRatio = (round(ratio * 100) / 100).toFloat()
        Log.i("[LAYOUT]", "$viewName(W, H, W/H) =  ${view.width}, ${view.height}, $roundedRatio ")
    }
    private fun showInfo() {
        showRec ("windowContainer", windowContainer)
        //showRec ("textureContainer", textureContainer)

        Log.i("[LAYOUT]", "gestureScaleFactor=$gestureScaleFactor")
        Log.i("[LAYOUT]", "gestureRotationAngle=$gestureRotationAngle, gestureRotationBasedAngle=$gestureRotationBasedAngle")
        Log.i("[LAYOUT]", "-------")
    }










    override fun onBind(intent: Intent?): IBinder? = null
}

