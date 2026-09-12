package pl.somaskan.questgpt2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent

object Notifications {
    fun build(service: Service, title: String, description: String): Notification {
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("assistant", "Aktywne sesje QuestGPT", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(service, 1, Intent(service, MiniActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(service, if(service is ScreenService) 10 else 11,
            Intent(service, service.javaClass).setAction("STOP"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopAll = PendingIntent.getBroadcast(service, 40, Intent(service, StopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(service, "assistant").setSmallIcon(R.drawable.ic_quest)
            .setContentTitle(title).setContentText(description).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Zatrzymaj", stop).build())
            .addAction(Notification.Action.Builder(null, "Stop wszystko", stopAll).build()).build()
    }
}
