package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.domain.manager.FavoriteSyncError
import com.streamvault.domain.manager.FavoriteSyncJoinMode
import com.streamvault.domain.manager.FavoriteSyncSetupResult
import com.streamvault.domain.manager.FavoriteSyncStatus
import com.streamvault.feature.settings.R
import java.text.DateFormat
import java.util.Date

/** Favorites sync block inside Settings > Backup: toggle, pairing, Sync now and status. */
@Composable
internal fun FavoriteSyncSettings(
    viewModel: FavoriteSyncSettingsViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val setupResult by viewModel.setupResult.collectAsStateWithLifecycle()
    var showSetup by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SwitchSettingsRow(
            label = stringResource(R.string.settings_favorites_sync_title),
            value = stringResource(R.string.settings_favorites_sync_description),
            checked = status.enabled,
            onCheckedChange = { checked ->
                if (checked) {
                    viewModel.clearSetupResult()
                    showSetup = true
                } else {
                    viewModel.disable()
                }
            },
        )
        if (status.enabled) {
            ClickableSettingsRow(
                label = stringResource(R.string.settings_favorites_sync_server),
                value = status.serverUrl,
                onClick = {
                    viewModel.clearSetupResult()
                    showSetup = true
                },
            )
            ClickableSettingsRow(
                label = stringResource(R.string.settings_favorites_sync_now),
                value = if (status.running) stringResource(R.string.settings_favorites_sync_syncing) else "",
                enabled = !status.running,
                onClick = viewModel::syncNow,
            )
            SettingsRow(
                label = stringResource(R.string.settings_favorites_sync_status),
                value = favoriteSyncStatusText(status),
            )
            if (!status.secretsEncrypted) {
                Text(
                    text = stringResource(R.string.settings_favorites_sync_not_encrypted),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }

    if (showSetup) {
        LaunchedEffect(setupResult) {
            if (setupResult == FavoriteSyncSetupResult.SAVED) showSetup = false
        }
        FavoriteSyncSetupDialog(
            initialServerUrl = status.serverUrl,
            hasExistingPairing = status.configured,
            saving = saving,
            setupResult = setupResult,
            onDismiss = { if (!saving) showSetup = false },
            onSave = viewModel::enable,
        )
    }
}

@Composable
private fun FavoriteSyncSetupDialog(
    initialServerUrl: String,
    hasExistingPairing: Boolean,
    saving: Boolean,
    setupResult: FavoriteSyncSetupResult?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, FavoriteSyncJoinMode) -> Unit,
) {
    var serverUrl by rememberSaveable { mutableStateOf(initialServerUrl) }
    // Token and passphrase are deliberately not saveable: they must not land in saved state.
    var token by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var joinMode by rememberSaveable { mutableStateOf(FavoriteSyncJoinMode.MERGE) }

    PremiumDialog(
        title = stringResource(R.string.settings_favorites_sync_setup_title),
        subtitle = stringResource(R.string.settings_favorites_sync_setup_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.6f,
        heightFraction = 0.82f,
        bodyHeightFraction = 0.66f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EpgSourceTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    placeholder = stringResource(R.string.settings_favorites_sync_url_hint),
                    keyboardType = KeyboardType.Uri,
                )
                EpgSourceTextField(
                    value = token,
                    onValueChange = { token = it },
                    placeholder = stringResource(R.string.settings_favorites_sync_token_hint),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardType = KeyboardType.Password,
                )
                EpgSourceTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    placeholder = if (hasExistingPairing) {
                        stringResource(R.string.settings_favorites_sync_passphrase_keep_hint)
                    } else {
                        stringResource(R.string.settings_favorites_sync_passphrase_hint)
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardType = KeyboardType.Password,
                )
                Text(
                    text = stringResource(R.string.settings_favorites_sync_join_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                FavoriteSyncJoinMode.entries.forEach { mode ->
                    SwitchSettingsRow(
                        label = stringResource(mode.labelRes()),
                        value = "",
                        checked = joinMode == mode,
                        onCheckedChange = { joinMode = mode },
                    )
                }
                setupResult?.errorRes()?.let { error ->
                    Text(
                        text = stringResource(error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = onDismiss,
                    enabled = !saving,
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_favorites_sync_save),
                    onClick = { onSave(serverUrl, token, passphrase, joinMode) },
                    enabled = !saving && serverUrl.isNotBlank() && token.isNotBlank() &&
                        (passphrase.isNotEmpty() || hasExistingPairing),
                    emphasized = true,
                )
            }
        },
    )
}

/** Shown before a restore when favorites sync is on, because the restore reaches every TV. */
@Composable
internal fun FavoriteSyncRestoreConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PremiumDialog(
        title = stringResource(R.string.settings_favorites_sync_restore_title),
        subtitle = stringResource(R.string.settings_favorites_sync_restore_message),
        onDismissRequest = onDismiss,
        content = {},
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = onDismiss,
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_favorites_sync_restore_confirm),
                    onClick = onConfirm,
                    emphasized = true,
                )
            }
        },
    )
}

@Composable
private fun favoriteSyncStatusText(status: FavoriteSyncStatus): String {
    val parts = mutableListOf<String>()
    parts += status.error?.let { stringResource(it.labelRes()) }
        ?: status.lastSyncAtMs?.let {
            stringResource(
                R.string.settings_favorites_sync_last_sync,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)),
            )
        }
        ?: stringResource(R.string.settings_favorites_sync_never)
    if (status.hasPendingChanges) parts += stringResource(R.string.settings_favorites_sync_pending)
    if (status.unmatchedCount > 0) {
        parts += stringResource(R.string.settings_favorites_sync_unmatched, status.unmatchedCount)
    }
    return parts.joinToString("  ·  ")
}

private fun FavoriteSyncJoinMode.labelRes(): Int = when (this) {
    FavoriteSyncJoinMode.MERGE -> R.string.settings_favorites_sync_join_merge
    FavoriteSyncJoinMode.USE_SERVER -> R.string.settings_favorites_sync_join_server
    FavoriteSyncJoinMode.UPLOAD_THIS_TV -> R.string.settings_favorites_sync_join_upload
}

private fun FavoriteSyncSetupResult.errorRes(): Int? = when (this) {
    FavoriteSyncSetupResult.SAVED -> null
    FavoriteSyncSetupResult.INVALID_URL -> R.string.settings_favorites_sync_invalid_url
    FavoriteSyncSetupResult.MISSING_TOKEN -> R.string.settings_favorites_sync_missing_token
    FavoriteSyncSetupResult.PASSPHRASE_TOO_SHORT -> R.string.settings_favorites_sync_passphrase_short
}

private fun FavoriteSyncError.labelRes(): Int = when (this) {
    FavoriteSyncError.SERVER_UNREACHABLE -> R.string.settings_favorites_sync_error_unreachable
    FavoriteSyncError.UNAUTHORIZED -> R.string.settings_favorites_sync_error_unauthorized
    FavoriteSyncError.PAIRING_MISMATCH -> R.string.settings_favorites_sync_error_pairing
    FavoriteSyncError.TOO_LARGE -> R.string.settings_favorites_sync_error_too_large
    FavoriteSyncError.SERVER_ERROR -> R.string.settings_favorites_sync_error_server
    FavoriteSyncError.UNSUPPORTED_DOCUMENT -> R.string.settings_favorites_sync_error_unsupported
    FavoriteSyncError.CONFLICT -> R.string.settings_favorites_sync_error_conflict
}
