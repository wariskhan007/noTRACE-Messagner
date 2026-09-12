package com.notrace.messenger

import android.app.Application
import com.notrace.messenger.workers.DisappearingMessageWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.sqlcipher.database.SQLiteDatabase

class NoTraceApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        SQLiteDatabase.loadLibs(this) // must happen once, before any NoTraceDatabase access
        container = AppContainer(this, applicationScope)
        container.startNetworking()
        DisappearingMessageWorker.schedule(this)
    }
}
