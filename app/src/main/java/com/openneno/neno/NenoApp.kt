package com.openneno.neno

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.openneno.neno.data.NenoDb
import com.openneno.neno.data.NoteRepository
import com.openneno.neno.data.Settings
import com.openneno.neno.sync.SyncEngine
import com.openneno.neno.widget.WidgetUpdater
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class NenoApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var repository: NoteRepository
        private set
    lateinit var sync: SyncEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        repository = NoteRepository(this, NenoDb(this))
        sync = SyncEngine(repository, settings)
        repository.onLocalChange = { sync.scheduleSync() }
        repository.onDataChanged = { WidgetUpdater.requestUpdate(this) }

        // 前台时轮询远端; 切到后台时立即推送, 推送不完的交给 WorkManager
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                sync.startPolling()
            }

            override fun onStop(owner: LifecycleOwner) {
                sync.stopPolling()
                sync.launch {
                    sync.syncNow()
                    if (sync.hasPendingChanges()) SyncWorker.enqueueOnce(this@NenoApp)
                }
            }
        })
        SyncWorker.schedulePeriodic(this)

        // 桌面组件跟随主题色
        kotlinx.coroutines.MainScope().launch {
            settings.appearance.drop(1).collect { WidgetUpdater.requestUpdate(this@NenoApp) }
        }
    }

    companion object {
        lateinit var instance: NenoApp
            private set
    }
}

val Context.app: NenoApp get() = applicationContext as NenoApp

/** 后台同步: 有网络时推送未完成的改动, 并定期拉取, 让桌面组件保持最新 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        if (!app.sync.isConfigured) return Result.success()
        app.sync.syncNow()
        val status = app.sync.status.value
        return if (status.error.isNotEmpty() && !status.authError && app.sync.hasPendingChanges()) Result.retry() else Result.success()
    }

    companion object {
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueueOnce(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "neno-sync-once", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
            )
        }

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "neno-sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(constraints).build()
            )
        }
    }
}
