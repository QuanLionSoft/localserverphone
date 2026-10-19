package dev.pocketpaas.presentation.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.pocketpaas.domain.model.NetworkServiceStatus
import dev.pocketpaas.domain.model.NetworkState
import dev.pocketpaas.domain.model.RuntimeCapabilities
import dev.pocketpaas.domain.remote.RemoteServerState
import dev.pocketpaas.domain.remote.RemoteServerStatus
import dev.pocketpaas.domain.setup.TermuxPermissionState
import dev.pocketpaas.domain.setup.TermuxPermissionStatus
import dev.pocketpaas.presentation.dashboard.DashboardUiState
import dev.pocketpaas.presentation.theme.PocketPaaSTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Device tests exercise the controls; they do not assume that opening Termux pastes or runs a command. */
class SetupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theFourStepsHaveAccessibleNavigationAndAnIdentifiableVersion() {
        render()

        compose.onNodeWithTag("setup-version").assertTextContains("0.4.0-debug")
        compose.onNodeWithContentDescription("Adım 1 / 4: Termux").assertIsSelected()
        compose.onNodeWithTag("setup-previous").assertIsNotEnabled()
        compose.onNodeWithTag("setup-next").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Adım 2 / 4: İzin").assertIsSelected()
        compose.onNodeWithTag("setup-previous").assertIsEnabled().performClick()
        compose.onNodeWithTag("setup-step-1").assertIsSelected()
        compose.onNodeWithTag("setup-step-4").performClick().assertIsSelected()
    }

    @Test
    fun missingTermuxOffersInstallationAndExplainsWhyStartingIsBlocked() {
        var installs = 0
        render(
            state = ready.copy(capabilities = RuntimeCapabilities(false, false, emptyList())),
            permissionStatus = TermuxPermissionStatus(TermuxPermissionState.TERMUX_MISSING),
            onInstall = { installs++ },
        )

        compose.onNodeWithTag("setup-install-termux").performScrollTo().performClick()
        compose.onNodeWithTag("setup-step-3").performClick()
        compose.onNodeWithTag("setup-copy-command").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("setup-step-4").performClick()
        compose.onNodeWithTag("setup-start").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("setup-start-blocked").performScrollTo().assertTextContains("1. adımda Termux", substring = true)

        compose.runOnIdle { assertEquals(1, installs) }
    }

    @Test
    fun permissionActionsAreVisibleEvenBeforeTermuxIsInstalled() {
        var settings = 0
        render(
            state = ready.copy(capabilities = RuntimeCapabilities(false, false, emptyList())),
            permissionStatus = TermuxPermissionStatus(TermuxPermissionState.TERMUX_MISSING),
            onPermissionSettings = { settings++ },
        )

        compose.onNodeWithTag("setup-step-2").performClick()
        compose.onNodeWithTag("setup-grant-permission").assertIsDisplayed().assertIsNotEnabled().assertTextContains("İzin iste")
        compose.onNodeWithTag("setup-open-permission-settings").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("setup-permission-status").performScrollTo().assertTextContains("Önce Termux", substring = true)

        compose.runOnIdle { assertEquals(1, settings) }
    }

    @Test
    fun requestingPermissionDoesNotOpenSettingsOrStartAnythingImplicitly() {
        var requests = 0
        var settings = 0
        var starts = 0
        render(
            state = ready.copy(capabilities = RuntimeCapabilities(true, false, emptyList())),
            permissionStatus = TermuxPermissionStatus(TermuxPermissionState.DENIED),
            onRequestPermission = { requests++ },
            onPermissionSettings = { settings++ },
            onStart = { starts++ },
        )

        compose.onNodeWithTag("setup-step-2").performClick()
        compose.onNodeWithTag("setup-grant-permission").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("setup-open-permission-settings").assertIsDisplayed().assertTextContains("Pocket PaaS ayarlarını aç")

        compose.runOnIdle {
            assertEquals(1, requests)
            assertEquals(0, settings)
            assertEquals(0, starts)
        }
    }

    @Test
    fun grantedPermissionKeepsBothActionsAndExplainsWhyThereMayBeNoDialog() {
        var requests = 0
        var settings = 0
        render(
            permissionStatus = TermuxPermissionStatus(TermuxPermissionState.GRANTED, "0.118.3"),
            onRequestPermission = { requests++ },
            onPermissionSettings = { settings++ },
        )

        compose.onNodeWithTag("setup-step-2").performClick()
        compose.onNodeWithTag("setup-grant-permission").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("setup-open-permission-settings").assertIsDisplayed().performClick()
        compose.onNodeWithTag("setup-permission-status").performScrollTo().assertTextContains("İzin verilmiş")
        compose.onNodeWithTag("setup-permission-explanation").performScrollTo().assertTextContains("pencere açılmaması normaldir", substring = true)

        compose.runOnIdle {
            assertEquals(1, requests)
            assertEquals(1, settings)
        }
    }

    @Test
    fun grantingPermissionUpdatesItsStatusWithoutRemovingTheButtons() {
        var permission by mutableStateOf(TermuxPermissionStatus(TermuxPermissionState.DENIED))
        render(permissionProvider = { permission })

        compose.onNodeWithTag("setup-step-2").performClick()
        compose.onNodeWithTag("setup-permission-status").performScrollTo().assertTextContains("Henüz izin verilmedi")
        compose.runOnIdle { permission = TermuxPermissionStatus(TermuxPermissionState.GRANTED) }
        compose.onNodeWithTag("setup-permission-status").assertTextContains("İzin verilmiş")
        compose.onNodeWithTag("setup-grant-permission").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("setup-open-permission-settings").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun aTermuxVariantWithoutTheInterfaceOffersACompatibleDownloadInsteadOfAnImpossiblePermission() {
        var installs = 0
        var settings = 0
        render(
            permissionStatus = TermuxPermissionStatus(TermuxPermissionState.PERMISSION_UNAVAILABLE, "play-variant"),
            onInstall = { installs++ },
            onPermissionSettings = { settings++ },
        )

        compose.onNodeWithTag("setup-step-2").performClick()
        compose.onNodeWithTag("setup-grant-permission").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("setup-open-permission-settings").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("setup-permission-explanation").performScrollTo().assertTextContains("Play Store", substring = true)
        compose.onNodeWithTag("setup-compatible-termux").performScrollTo().performClick()
        compose.onNodeWithTag("setup-step-4").performClick()
        compose.onNodeWithTag("setup-start").assertIsNotEnabled()
        compose.onNodeWithTag("setup-start-blocked").performScrollTo().assertTextContains("uyumlu bir Termux", substring = true)

        compose.runOnIdle {
            assertEquals(1, installs)
            assertEquals(1, settings)
        }
    }

    @Test
    fun aDisabledTermuxOffersItsOwnApplicationInfoWithoutConfusingItWithPocketPaasPermissionSettings() {
        var termuxSettings = 0
        var pocketSettings = 0
        render(
            permissionStatus = TermuxPermissionStatus(TermuxPermissionState.TERMUX_DISABLED),
            onTermuxSettings = { termuxSettings++ },
            onPermissionSettings = { pocketSettings++ },
        )

        compose.onNodeWithTag("setup-step-2").performClick()
        compose.onNodeWithTag("setup-grant-permission").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("setup-open-termux-settings").performScrollTo().performClick()
        compose.onNodeWithTag("setup-open-permission-settings").assertIsDisplayed().performClick()
        compose.onNodeWithTag("setup-step-4").performClick()
        compose.onNodeWithTag("setup-start").assertIsNotEnabled()

        compose.runOnIdle {
            assertEquals(1, termuxSettings)
            assertEquals(1, pocketSettings)
        }
    }

    @Test
    fun permissionHelpCanBeExpandedWithoutLosingTheFixedActions() {
        render()

        compose.onNodeWithTag("setup-step-2").performClick()
        compose.onNodeWithTag("setup-permission-help").performScrollTo().performClick()
        compose.onNodeWithTag("setup-permission-help-content").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup-grant-permission").assertIsDisplayed()
        compose.onNodeWithTag("setup-open-permission-settings").assertIsDisplayed()
        compose.onNodeWithTag("setup-next").assertIsDisplayed()
        compose.onNodeWithTag("setup-previous").assertIsDisplayed()
    }

    @Test
    fun inspectingPermissionKeepsSettingsAvailableAndDoesNotRequestTwice() {
        var requests = 0
        render(inspectingPermission = true, onRequestPermission = { requests++ })

        compose.onNodeWithTag("setup-step-2").performClick()
        compose.onNodeWithTag("setup-grant-permission").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("setup-open-permission-settings").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("setup-permission-status").performScrollTo().assertTextContains("kontrol ediliyor", substring = true)

        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test
    fun openingTermuxOnlyOpensItWhileCopyingOnlyCopiesTheCommand() {
        var copies = 0
        var opens = 0
        var starts = 0
        render(onCopy = { copies++ }, onOpenTermux = { opens++ }, onStart = { starts++ })

        compose.onNodeWithTag("setup-step-3").performClick()
        compose.onNodeWithTag("setup-open-command-termux").performScrollTo().assertTextContains("Termux’u aç").performClick()
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(0, copies)
            assertEquals(0, starts)
        }
        compose.onNodeWithTag("setup-copy-command").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, copies)
            assertEquals(0, starts)
        }
    }

    @Test
    fun aCopiedCommandIsNotPresentedAsACompletedInstallation() {
        render(commandCopied = true)

        compose.onNodeWithTag("setup-step-3").performClick()
        compose.onNodeWithTag("setup-command-status").performScrollTo().assertTextContains("henüz çalıştırıldığı doğrulanmadı", substring = true)
        compose.onNodeWithTag("setup-copy-command").performScrollTo().assertIsEnabled()
    }

    @Test
    fun aCurrentDeniedDiagnosisOverridesStaleGrantedCapabilities() {
        render(permissionStatus = TermuxPermissionStatus(TermuxPermissionState.DENIED))

        compose.onNodeWithTag("setup-step-4").performClick()
        compose.onNodeWithTag("setup-start").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("setup-start-blocked").performScrollTo().assertTextContains("2. adımda", substring = true)
    }

    @Test
    fun startingRequiresAnExplicitPressOnTheLastPage() {
        var starts = 0
        render(onStart = { starts++ })

        compose.onNodeWithTag("setup-next").performClick()
        compose.onNodeWithTag("setup-next").performClick()
        compose.onNodeWithTag("setup-next").performClick()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("setup-start").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun aRunningApiDoesNotImplyThatNginxAndTheTunnelAreRunning() {
        var starts = 0
        var dashboards = 0
        render(
            state = ready.copy(
                runtime = RemoteServerState(RemoteServerStatus.RUNNING),
                network = NetworkState(nginxStatus = NetworkServiceStatus.FAILED, tunnelStatus = NetworkServiceStatus.STARTING),
            ),
            onStart = { starts++ },
            onDashboard = { dashboards++ },
        )

        compose.onNodeWithTag("setup-step-4").performClick()
        compose.onNodeWithTag("setup-api-status").performScrollTo().assertTextContains("Çalışıyor", substring = true)
        compose.onNodeWithTag("setup-nginx-status").performScrollTo().assertTextContains("Hata oluştu", substring = true)
        compose.onNodeWithTag("setup-tunnel-status").performScrollTo().assertTextContains("Bağlanıyor", substring = true)
        compose.onNodeWithTag("setup-start").assertIsDisplayed().assertTextContains("Paneli aç").performClick()

        compose.runOnIdle {
            assertEquals(0, starts)
            assertEquals(1, dashboards)
        }
    }

    @Test
    fun failedServerCanBeInspectedWithoutStartingAnotherSession() {
        var dashboards = 0
        render(
            state = ready.copy(runtime = RemoteServerState(RemoteServerStatus.FAILED)),
            onDashboard = { dashboards++ },
        )

        compose.onNodeWithTag("setup-step-4").performClick()
        compose.onNodeWithTag("setup-start").assertIsNotEnabled()
        compose.onNodeWithTag("setup-open-failed-dashboard").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(1, dashboards) }
    }

    @Test
    fun verificationBlocksAnotherStartAndKeepsTheErrorAvailable() {
        render(checking = true, verificationError = "Nginx bulunamadı; Termux kurulumunu yeniden çalıştırın.")

        compose.onNodeWithTag("setup-step-4").performClick()
        compose.onNodeWithTag("setup-start").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("setup-start-blocked").performScrollTo().assertTextContains("Araç kontrolü sürüyor", substring = true)
        compose.onNodeWithTag("setup-verification-error").performScrollTo().assertTextContains("Nginx bulunamadı", substring = true)
    }

    private fun render(
        state: DashboardUiState = ready,
        commandCopied: Boolean = false,
        checking: Boolean = false,
        verificationError: String? = null,
        permissionStatus: TermuxPermissionStatus? = null,
        permissionProvider: () -> TermuxPermissionStatus? = { permissionStatus },
        inspectingPermission: Boolean = false,
        onInstall: () -> Unit = {},
        onOpenTermux: () -> Unit = {},
        onCopy: () -> Unit = {},
        onRequestPermission: () -> Unit = {},
        onPermissionSettings: () -> Unit = {},
        onTermuxSettings: () -> Unit = {},
        onStart: () -> Unit = {},
        onDashboard: () -> Unit = {},
    ) {
        compose.setContent {
            PocketPaaSTheme {
                SetupScreen(
                    state = state,
                    setupCommand = "printf setup",
                    commandCopied = commandCopied,
                    onInstallTermux = onInstall,
                    onOpenTermux = onOpenTermux,
                    onCopySetupCommand = onCopy,
                    onRequestPermission = onRequestPermission,
                    onRefresh = {},
                    onStart = onStart,
                    onOpenDashboard = onDashboard,
                    checking = checking,
                    verificationError = verificationError,
                    onOpenPermissionSettings = onPermissionSettings,
                    onOpenTermuxSettings = onTermuxSettings,
                    versionLabel = "0.4.0-debug",
                    permissionStatus = permissionProvider(),
                    inspectingPermission = inspectingPermission,
                )
            }
        }
    }

    private val ready = DashboardUiState(
        initializing = false,
        capabilities = RuntimeCapabilities(true, true, emptyList()),
    )
}
