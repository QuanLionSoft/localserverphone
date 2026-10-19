package dev.pocketpaas.data.remote.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.core.di.ApplicationScope
import dev.pocketpaas.domain.remote.RemoteServerRuntime
import dev.pocketpaas.domain.remote.RemoteServerState
import dev.pocketpaas.domain.remote.RemoteServerStatus
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Android owns the notification; the injected runtime owns Netty and every managed process. */
@AndroidEntryPoint
class RemoteServerService : Service() {
    @Inject lateinit var runtime: RemoteServerRuntime
    @Inject lateinit var config: ServerConfig
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var commandJob: Job? = null
    private var instance = 0L
    private var generation = 0L
    private var foreground = false
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        instance = INSTANCE_GENERATION.incrementAndGet()
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pocket PaaS sunucusu", NotificationManager.IMPORTANCE_LOW)
                .apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
        )
        serviceScope.launch {
            runtime.state.collect { state ->
                if (foreground && !destroyed) {
                    if (state.status == RemoteServerStatus.STOPPED && commandJob?.isActive != true) {
                        foreground = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        notificationManager.notify(NOTIFICATION_ID, notification(state))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action !in setOf(ACTION_START, ACTION_STOP, ACTION_START_NETWORK)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        // Promote before coroutine dispatch, key IO, database initialization, or Netty startup.
        if (!foreground) {
            val initial = notification(RemoteServerState(status = RemoteServerStatus.STARTING))
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, initial)
            }
            foreground = true
        }
        val action = requireNotNull(intent).action
        val host = if (intent.getBooleanExtra(EXTRA_LAN_ACCESS, false)) "0.0.0.0" else "127.0.0.1"
        request(action, host, intent.getBooleanExtra(EXTRA_START_NETWORK, false), startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        generation++
        commandJob?.cancel()
        serviceScope.cancel()
        // onDestroy cannot suspend, and Android can kill without invoking it. The Termux lease
        // remains the final cleanup mechanism if this application process disappears.
        if (runtime.state.value.status != RemoteServerStatus.STOPPED) {
            applicationScope.launch {
                withContext(NonCancellable) {
                    withTimeoutOrNull(DESTROY_CLEANUP_TIMEOUT_MILLIS) {
                        PROCESS_COMMANDS.withLock {
                            // A replacement service owns the same singleton runtime. An old
                            // onDestroy job must never terminate that replacement's session.
                            if (INSTANCE_GENERATION.get() == instance) runCatching { runtime.stop() }
                        }
                    }
                }
            }
        }
        super.onDestroy()
    }

    private fun request(action: String?, host: String, startNetwork: Boolean, startId: Int) {
        val ownGeneration = ++generation
        commandJob?.cancel()
        commandJob = serviceScope.launch {
            try {
                PROCESS_COMMANDS.withLock {
                    ensureActive()
                    when (action) {
                        ACTION_START -> {
                            runtime.start(host)
                            if (startNetwork) runtime.startNetwork()
                        }
                        ACTION_START_NETWORK -> runtime.startNetwork()
                        ACTION_STOP -> withContext(NonCancellable) { runtime.stop() }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Runtime retains its failed cleanup owners for the notification's retry action.
                if (ownGeneration == generation && foreground && !destroyed) {
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        notification(runtime.state.value),
                    )
                }
            }
            if (ownGeneration == generation && !destroyed && runtime.state.value.status == RemoteServerStatus.STOPPED) {
                foreground = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
    }

    private fun notification(state: RemoteServerState): Notification {
        val stopIntent = PendingIntent.getForegroundService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, RemoteServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state.status) {
            RemoteServerStatus.STOPPED -> "Sunucu durduruldu"
            RemoteServerStatus.STARTING -> "Sunucu başlatılıyor"
            RemoteServerStatus.RUNNING -> "Uzak API: ${state.bindHost ?: config.apiHost}:${config.apiPort}"
            RemoteServerStatus.STOPPING -> "Sunucu ve projeler durduruluyor"
            RemoteServerStatus.FAILED -> "Sunucu hatası; temizliği yeniden denemek için Durdur'a dokunun"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Pocket PaaS")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
                packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
                    setContentIntent(
                        PendingIntent.getActivity(
                            this@RemoteServerService,
                            OPEN_REQUEST_CODE,
                            launchIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }
            .addAction(Notification.Action.Builder(null, "Durdur", stopIntent).build())
            .build()
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    companion object {
        const val ACTION_START = "dev.pocketpaas.remote.START"
        const val ACTION_STOP = "dev.pocketpaas.remote.STOP"
        const val ACTION_START_NETWORK = "dev.pocketpaas.remote.START_NETWORK"
        const val EXTRA_LAN_ACCESS = "dev.pocketpaas.remote.LAN_ACCESS"
        const val EXTRA_START_NETWORK = "dev.pocketpaas.remote.START_NETWORK_WITH_API"
        private const val CHANNEL_ID = "pocketpaas_remote_server"
        private const val NOTIFICATION_ID = 6001
        private const val OPEN_REQUEST_CODE = 6002
        private const val STOP_REQUEST_CODE = 6003
        private const val DESTROY_CLEANUP_TIMEOUT_MILLIS = 120_000L
        private val PROCESS_COMMANDS = Mutex()
        private val INSTANCE_GENERATION = AtomicLong()
    }
}
