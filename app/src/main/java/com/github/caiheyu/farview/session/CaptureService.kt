package com.github.caiheyu.farview.session

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.github.caiheyu.farview.FarviewApplication
import com.github.caiheyu.farview.MainActivity
import com.github.caiheyu.farview.R

class CaptureService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private val session get() = (application as FarviewApplication).session
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("session", "拍摄与本地连接", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { session.end(); return START_NOT_STICKY }
        val camera = intent?.action == "camera"
        try {
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this, "session")
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(if (camera) "遥映 · 相机正在使用中" else "遥映 · 本地观看连接中")
                .setContentText(if (camera) "拍摄与传输持续运行，轻触返回应用" else "本地视频与录像运行中，轻触返回应用")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(0, "结束连接", stop).build()
            val type = if (camera && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                else if (!camera && Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            ServiceCompat.startForeground(this, 10, notification, type)
            @Suppress("WakelockTimeout")
            if (wake == null) wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Farview:session").apply { acquire() }
            if (camera) session.beginCamera() else intent?.getStringExtra("pairing")?.let(session::beginViewer)
        } catch (e: Exception) { session.fail("无法启动前台拍摄服务：${e.message}"); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (wake?.isHeld == true) wake?.release()
        wake = null; session.serviceDestroyed()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
