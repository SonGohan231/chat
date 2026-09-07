package pl.somaskan.questgpt

import android.app.Application
import android.content.Context

class QuestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        @Volatile
        lateinit var appContext: Context
            private set
    }
}
