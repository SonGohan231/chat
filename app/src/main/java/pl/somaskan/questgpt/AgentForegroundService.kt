package pl.somaskan.questgpt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import pl.somaskan.questgpt.adb.AdbVisionMonitor
import pl.somaskan.questgpt.adb.AgentAccessPolicy
import pl.somaskan.questgpt.adb.QuestAgentRuntime

class AgentForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        AgentAccessPolicy.refreshRuntime()
        AdbVisionMonitor.start()
        QuestAgentRuntime.serviceRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AgentServiceController.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        AdbVisionMonitor.start()
        QuestAgentRuntime.serviceRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        QuestAgentRuntime.serviceRunning = false
        AdbVisionMonitor.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "QuestGPT Agent",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Utrzymuje lokalny ADB Vision i agenta QuestGPT, gdy panel nie jest na pierwszym planie."
            }
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("QuestGPT Agent aktywny")
        .setContentText("ADB Vision działa w tle. Wrażliwe akcje nadal wymagają potwierdzenia w panelu.")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Wyłącz agenta",
            PendingIntent.getService(
                this,
                2,
                Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    companion object {
        const val ACTION_START = "pl.somaskan.questgpt.AGENT_START"
        const val ACTION_STOP = "pl.somaskan.questgpt.AGENT_STOP"
        private const val CHANNEL_ID = "questgpt_agent"
        private const val NOTIFICATION_ID = 2401
    }
}
