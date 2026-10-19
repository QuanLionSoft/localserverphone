package dev.pocketpaas.data.setup

import dev.pocketpaas.data.process.termux.BridgeHttpException
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.setup.SetupVerification
import dev.pocketpaas.domain.setup.SetupVerifier
import javax.inject.Inject
import kotlinx.coroutines.withTimeout

/** Checks the authenticated Termux bridge without starting or stopping hosted processes. */
class TermuxSetupVerifier @Inject constructor(
    private val shell: ProcessExecutor,
) : SetupVerifier {
    override suspend fun verify(): SetupVerification = withTimeout(30_000L) {
        shell.runtimeLayout()
        val missing = mutableListOf<String>()
        for (tool in REQUIRED_TOOLS) {
            try {
                shell.resolveBinary(tool)
            } catch (failure: BridgeHttpException) {
                if (failure.status != 404) throw failure
                missing += tool
            }
        }
        SetupVerification(missing.toList())
    }

    private companion object {
        val REQUIRED_TOOLS = listOf("python", "nginx", "node", "npm", "cloudflared")
    }
}
