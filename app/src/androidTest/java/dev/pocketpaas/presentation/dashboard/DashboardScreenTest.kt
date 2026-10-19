package dev.pocketpaas.presentation.dashboard

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeCapabilities
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.remote.RemoteServerState
import dev.pocketpaas.domain.remote.RemoteServerStatus
import dev.pocketpaas.presentation.theme.PocketPaaSTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Device tests: exercise controls and live composition rather than comparing implementation details. */
class DashboardScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun masterStopRemainsAvailableDuringDeployment() {
        var requested: Boolean? = null
        render(ready.copy(operationBusy = true, operationDescription = "ZIP kuruluyor"), onMaster = { requested = it })

        compose.onNodeWithTag("master-switch").assertIsOn().assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(false, requested) }
    }

    @Test
    fun startupCanBeStoppedButStoppingCannotBeInterrupted() {
        var state by mutableStateOf(ready.copy(runtime = RemoteServerState(RemoteServerStatus.STARTING)))
        var requested: Boolean? = null
        render(stateProvider = { state }, onMaster = { requested = it })

        compose.onNodeWithTag("master-switch").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(false, requested)
            state = state.copy(runtime = RemoteServerState(RemoteServerStatus.STOPPING))
        }
        compose.onNodeWithTag("master-switch").assertIsNotEnabled()
    }

    @Test
    fun failedRuntimeOffersCleanupInsteadOfStartingAnotherSession() {
        var requested: Boolean? = null
        render(ready.copy(runtime = RemoteServerState(RemoteServerStatus.FAILED)), onMaster = { requested = it })

        compose.onNodeWithTag("master-switch").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("retry-cleanup").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(false, requested) }
    }

    @Test
    fun lanAccessCanOnlyChangeBeforeServerStarts() {
        var state by mutableStateOf(ready.copy(runtime = RemoteServerState()))
        var changed = false
        render(stateProvider = { state }, onLan = { changed = it })

        compose.onNodeWithTag("lan-access").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(changed)
            state = state.copy(runtime = RemoteServerState(RemoteServerStatus.RUNNING))
        }
        compose.onNodeWithTag("lan-access").assertIsNotEnabled()
    }

    @Test
    fun floatingActionButtonRequestsProjectForm() {
        var opens = 0
        render(ready, onAdd = { opens++ })

        compose.onNodeWithTag("add-project").performClick()

        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun projectTypeAndZipRequireAnExplicitDeployPress() {
        var state by mutableStateOf(ready.copy(showAddProject = true))
        var deployments = 0
        render(
            stateProvider = { state },
            onDomain = { state = state.copy(draft = state.draft.copy(domainName = it)) },
            onType = { state = state.copy(draft = state.draft.copy(projectType = it)) },
            onDeploy = { deployments++ },
        )

        compose.onNodeWithTag("project-type").performClick()
        compose.onNodeWithText("Node.js").performClick()
        compose.onNodeWithTag("project-domain").performTextInput("my-app")
        compose.onNodeWithTag("deploy-project").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(ProjectType.NODEJS, state.draft.projectType)
            assertEquals("my-app", state.draft.domainName)
            state = state.copy(draft = state.draft.copy(zipUri = "content://test/project.zip", zipName = "project.zip"))
        }
        compose.onNodeWithTag("zip-name").assertTextContains("project.zip")
        compose.runOnIdle { assertEquals(0, deployments) }
        compose.onNodeWithTag("deploy-project").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, deployments) }
    }

    @Test
    fun repositoryUpdatesAppearInOpenLogsWithoutReopeningSheet() {
        var state by mutableStateOf(ready.copy(selectedSiteId = sampleSite.id, sites = listOf(sampleSite)))
        render(stateProvider = { state })

        compose.onNodeWithTag("live-logs").assertTextContains("ready", substring = true)
        compose.runOnIdle { state = state.copy(sites = listOf(sampleSite.copy(logs = "boot\nready\nrequest received"))) }
        compose.onNodeWithTag("live-logs").assertTextContains("request received", substring = true)
        compose.onNodeWithTag("follow-logs").assertIsOn().performClick().assertIsOff()
    }

    @Test
    fun copyingApiKeyRequiresAnExplicitUserAction() {
        var requests = 0
        render(ready, onCopyKey = { requests++ })

        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithTag("copy-api-key").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun missingTermuxPermissionPreventsStartingServer() {
        var requested: Boolean? = null
        render(
            ready.copy(runtime = RemoteServerState(), capabilities = RuntimeCapabilities(true, false, emptyList())),
            onMaster = { requested = it },
        )

        compose.onNodeWithTag("master-switch").assertIsNotEnabled()
        compose.runOnIdle { assertNull(requested) }
    }

    private fun render(
        state: DashboardUiState = ready,
        stateProvider: () -> DashboardUiState = { state },
        onMaster: (Boolean) -> Unit = {},
        onLan: (Boolean) -> Unit = {},
        onAdd: () -> Unit = {},
        onDomain: (String) -> Unit = {},
        onType: (ProjectType) -> Unit = {},
        onDeploy: () -> Unit = {},
        onCopyKey: () -> Unit = {},
    ) {
        compose.setContent {
            PocketPaaSTheme {
                DashboardScreen(
                    state = stateProvider(),
                    onMasterSwitch = onMaster,
                    onLanAccessChanged = onLan,
                    onRetryNetwork = {},
                    onRefresh = {},
                    onRequestTermuxPermission = {},
                    onAddProject = onAdd,
                    onDismissAddProject = {},
                    onDomainChanged = onDomain,
                    onProjectTypeChanged = onType,
                    onZipSelected = { _: Uri -> },
                    onDeploy = onDeploy,
                    onSiteSelected = {},
                    onDismissLogs = {},
                    onStartSite = {},
                    onStopSite = {},
                    onCancelOperation = {},
                    onCopyApiKey = onCopyKey,
                    onNoticeConsumed = {},
                )
            }
        }
    }

    private val ready = DashboardUiState(
        runtime = RemoteServerState(RemoteServerStatus.RUNNING, "127.0.0.1"),
        capabilities = RuntimeCapabilities(true, true, listOf("arm64-v8a")),
        initializing = false,
        loadingSites = false,
    )
    private val sampleSite = Site(7, "my-app", 9001, ProjectType.NODEJS, 324, null, SiteStatus.RUNNING, "boot\nready", 0)
}
