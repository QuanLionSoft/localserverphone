package dev.pocketpaas

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.pocketpaas.core.di.ApplicationScope
import dev.pocketpaas.domain.usecase.InitializePlatform
import java.util.concurrent.CancellationException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class ApplicationClass : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var initializePlatform: InitializePlatform
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            try {
                initializePlatform()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e("PocketPaaS", "Platform initialization failed", exception)
            }
        }
    }
}
