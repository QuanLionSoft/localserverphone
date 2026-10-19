package dev.pocketpaas.data.setup.android

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketpaas.core.di.IoDispatcher
import dev.pocketpaas.domain.setup.SetupPreferences
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class AndroidSetupPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SetupPreferences {
    override suspend fun isCompleted(): Boolean = withContext(ioDispatcher) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(COMPLETED, false)
    }

    override suspend fun markCompleted(): Unit = withContext(ioDispatcher) {
        if (!context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(COMPLETED, true).commit()) {
            throw IOException("Setup preference could not be saved.")
        }
    }

    private companion object {
        const val FILE = "setup"
        const val COMPLETED = "completed_v2"
    }
}
