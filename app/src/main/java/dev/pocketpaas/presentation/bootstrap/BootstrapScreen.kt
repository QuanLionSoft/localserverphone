package dev.pocketpaas.presentation.bootstrap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pocketpaas.R

@Composable
fun BootstrapScreen(
    state: BootstrapState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.phase_one_description), style = MaterialTheme.typography.bodyLarge)
        when (state) {
            BootstrapState.Loading -> CircularProgressIndicator()
            BootstrapState.Failed -> {
                Text(stringResource(R.string.bootstrap_failed))
                Button(onClick = onRefresh) { Text(stringResource(R.string.retry)) }
            }
            is BootstrapState.Ready -> {
                Card {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.bootstrap_ready), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.database_ready))
                        Text(stringResource(R.string.ports, state.platform.apiPort, state.platform.nginxPort))
                        Text(stringResource(R.string.termux_status, stringResource(
                            if (state.platform.capabilities.termuxInstalled) R.string.installed else R.string.not_installed,
                        )))
                        Text(stringResource(R.string.command_permission_status, stringResource(
                            if (state.platform.capabilities.termuxCommandPermissionGranted) R.string.granted else R.string.not_granted,
                        )))
                        Text(stringResource(R.string.installation_id, state.platform.installationId))
                    }
                }
                Text(stringResource(R.string.runtime_requirement))
                Button(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
            }
        }
    }
}
