package dev.pocketpaas.data.remote.android

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidApiKeyStoreTest {
    private lateinit var directory: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = File(application.cacheDir, "api-key-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        context = object : ContextWrapper(application) {
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun tokenSurvivesANewStoreAndHas256Bits() = runBlocking {
        val token = store().token()
        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
        assertEquals(token, store().token())
        assertEquals(token + "\n", persisted().readText(Charsets.US_ASCII))
    }

    @Test
    fun concurrentCallersShareOneToken() = runBlocking {
        val shared = store()
        val tokens = coroutineScope {
            List(100) { async(Dispatchers.Default) { shared.token() } }.awaitAll()
        }
        assertEquals(1, tokens.toSet().size)
    }

    @Test
    fun corruptTokenIsRejectedWithoutReplacement() = runBlocking {
        val damaged = "damaged-key\n"
        persisted().writeText(damaged, Charsets.US_ASCII)
        assertTrue(runCatching { store().token() }.exceptionOrNull() is IOException)
        assertEquals(damaged, persisted().readText(Charsets.US_ASCII))
    }

    @Test
    fun oversizedTokenFileIsRejectedWithoutReplacement() = runBlocking {
        val damaged = "x".repeat(513)
        persisted().writeText(damaged, Charsets.US_ASCII)
        assertTrue(runCatching { store().token() }.exceptionOrNull() is IOException)
        assertEquals(damaged, persisted().readText(Charsets.US_ASCII))
    }

    @Test
    fun unreadableExistingTokenIsRejectedWithoutRotation() = runBlocking {
        val token = store().token()
        Os.chmod(persisted().absolutePath, 0)
        try {
            assertTrue(runCatching { store().token() }.exceptionOrNull() is IOException)
        } finally {
            Os.chmod(persisted().absolutePath, 384)
        }
        assertEquals(token, store().token())
    }

    @Test
    fun interruptedAtomicWriteRestoresPreviousToken() = runBlocking {
        val token = store().token()
        val interrupted = AtomicFile(persisted()).startWrite()
        interrupted.write("partial".toByteArray(Charsets.US_ASCII))
        interrupted.close()
        assertEquals(token, store().token())
        assertFalse(File(directory, "remote-api.key.new").exists())
    }

    private fun store(): AndroidApiKeyStore = AndroidApiKeyStore(context, Dispatchers.IO)
    private fun persisted(): File = File(directory, "remote-api.key")
}
