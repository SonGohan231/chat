package pl.somaskan.questgpt2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) { Hub.stopAll() }
}
