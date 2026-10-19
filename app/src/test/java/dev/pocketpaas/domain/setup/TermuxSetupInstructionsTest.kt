package dev.pocketpaas.domain.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxSetupInstructionsTest {
    @Test
    fun commandUsesOneQuotedSubshellAndUpgradesBeforeInstallingEveryRuntimePackage() {
        val command = TermuxSetupInstructions.command
        assertTrue(command.startsWith("sh -c '"))
        assertTrue(command.endsWith("'"))
        val body = command.removePrefix("sh -c '").dropLast(1).replace("'\\''", "'")
        assertEquals("set -eu", body.lineSequence().first())
        assertTrue(body.contains("pkg update -y\npkg upgrade -y\npkg install -y python nginx nodejs cloudflared"))
        assertTrue(body.contains("python - <<'POCKETPAAS_SETTINGS'"))
        assertTrue(body.contains("termux-reload-settings\nprintf"))
    }

    @Test
    fun installedBinariesMustRunBeforeSettingsAreChangedOrSuccessIsReported() {
        val body = TermuxSetupInstructions.command.removePrefix("sh -c '").dropLast(1).replace("'\\''", "'")
        val install = body.indexOf("pkg install -y python nginx nodejs cloudflared")
        val presence = body.indexOf("for binary in python nginx node npm cloudflared termux-reload-settings")
        val smoke = body.indexOf("python --version\nnginx -v\nnode --version\nnpm --version\ncloudflared --version")
        val settings = body.indexOf("python - <<'POCKETPAAS_SETTINGS'")
        val success = body.indexOf("printf '\\nKurulum tamamlandı.")

        assertTrue(install >= 0)
        assertTrue(presence > install)
        assertTrue(smoke > presence)
        assertTrue(settings > smoke)
        assertTrue(success > settings)
        assertTrue(body.contains("Kurulu tüm Termux paketleri güncelleniyor"))
    }

    @Test
    fun setupDoesNotDownloadShellScriptsOrRequestRoot() {
        val command = TermuxSetupInstructions.command
        assertFalse(command.contains("curl "))
        assertFalse(command.contains("wget "))
        assertFalse(command.contains("sudo "))
        assertFalse(command.contains("su -"))
        assertFalse(command.contains("termux-setup-storage"))
    }
}
