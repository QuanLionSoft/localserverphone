package dev.pocketpaas.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformSettingsPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: PocketPaaSDatabase
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "platform-settings-test-${UUID.randomUUID()}.db"
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun concurrentFirstLaunchPreservesExactlyOneIdentityAcrossDatabaseReopen() = runBlocking {
        val candidates = List(32) { index ->
            PlatformSettingsEntity(
                installationId = "installation-$index",
                createdAtEpochMillis = 1000L + index,
            )
        }
        val results = coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) { database.platformSettingsDao().getOrCreate(candidate) }
            }.awaitAll()
        }

        assertEquals(1, results.distinct().size)
        val persisted = results.first()
        assertTrue(persisted in candidates)
        assertEquals(persisted, database.platformSettingsDao().getSettings())

        database.close()
        database = openDatabase()

        val newCandidate = PlatformSettingsEntity(
            installationId = "must-not-replace-persisted-installation",
            createdAtEpochMillis = 9999L,
        )
        assertEquals(persisted, database.platformSettingsDao().getOrCreate(newCandidate))
        assertEquals(persisted, database.platformSettingsDao().getSettings())
    }

    @Test
    fun nonSingletonCandidateCannotCreateAnotherSettingsRow() = runBlocking {
        val candidate = PlatformSettingsEntity(
            id = 2,
            installationId = "invalid-settings-row",
            createdAtEpochMillis = 1000L,
        )

        try {
            database.platformSettingsDao().getOrCreate(candidate)
            fail("Only the singleton settings ID may be persisted")
        } catch (_: IllegalArgumentException) {
            assertNull(database.platformSettingsDao().getSettings())
        }
    }

    private fun openDatabase(): PocketPaaSDatabase =
        Room.databaseBuilder(context, PocketPaaSDatabase::class.java, databaseName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
}
