package dev.pocketpaas.data.dashboard.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.PersistableBundle
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketpaas.domain.dashboard.DashboardDevice
import dev.pocketpaas.domain.dashboard.PickedArchive
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Singleton
class AndroidDashboardDevice @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DashboardDevice {
    override suspend fun describeArchive(uri: String): PickedArchive = withTimeout(10_000) {
        val parsed = Uri.parse(uri)
        require(parsed.scheme == "content")
        coroutineScope {
            val cancellation = CancellationSignal()
            val query = async(Dispatchers.IO) {
                context.contentResolver.query(
                    parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null, cancellation,
                ).use { cursor ->
                    var name = "project.zip"
                    var bytes: Long? = null
                    if (cursor?.moveToFirst() == true) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                            name = cursor.getString(nameColumn).orEmpty().take(160)
                                .map { if (it.isISOControl()) ' ' else it }.joinToString("").ifBlank { "project.zip" }
                        }
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) bytes = cursor.getLong(sizeColumn).takeIf { it >= 0 }
                    }
                    PickedArchive(uri, name, bytes)
                }
            }
            try { query.await() } finally {
                withContext(NonCancellable + Dispatchers.IO) { cancellation.cancel() }
                query.cancel()
            }
        }
    }

    override suspend fun copySensitive(text: String) = withContext(Dispatchers.Main.immediate) {
        val clip = ClipData.newPlainText("Pocket PaaS API anahtarı", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
}
