package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.theme.ErrorColor
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.feature.settings.R
import java.text.DateFormat
import java.util.Date

/**
 * Settings > provider > Channel clean-up: scan a provider's live channels and hide the ones that
 * keep failing. Hosted as a dialog so it needs no route of its own.
 */
@Composable
internal fun ChannelCleanupDialog(
    providerId: Long,
    providerName: String,
    onDismiss: () -> Unit,
    viewModel: ChannelCleanupViewModel = hiltViewModel(),
) {
    LaunchedEffect(providerId) { viewModel.bind(providerId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scanFocus = remember { FocusRequester() }
    // The dialog body scrolls, so the lazy list needs a finite height of its own.
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.4f).dp

    PremiumDialog(
        title = stringResource(R.string.settings_channel_cleanup_title),
        subtitle = providerName,
        onDismissRequest = onDismiss,
        widthFraction = 0.7f,
        heightFraction = 0.92f,
        bodyHeightFraction = 0.7f,
        initialBodyFocusRequester = scanFocus,
        content = {
            ChannelCleanupScanCard(
                scan = state.scan,
                errorRes = state.errorRes,
                onScan = viewModel::startScan,
                onStop = viewModel::stopScan,
                actionFocus = scanFocus,
            )
            Text(
                text = stringResource(R.string.channel_cleanup_suggestions_title),
                style = MaterialTheme.typography.titleMedium,
                color = Primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (state.groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.channel_cleanup_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim,
                )
            } else {
                ChannelCleanupToggleRow(
                    label = stringResource(R.string.channel_cleanup_select_all),
                    checked = state.allSelected,
                    onClick = viewModel::toggleAll,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = listMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.groups.forEach { group ->
                        item(key = "group-${group.categoryName}") {
                            ChannelCleanupToggleRow(
                                label = group.categoryName
                                    ?: stringResource(R.string.channel_cleanup_uncategorized),
                                hint = stringResource(R.string.channel_cleanup_select_category),
                                checked = state.isGroupSelected(group),
                                onClick = { viewModel.toggleCategory(group) },
                            )
                        }
                        items(group.rows, key = { row -> "channel-${row.channelId}" }) { row ->
                            ChannelCleanupToggleRow(
                                label = row.name,
                                hint = stringResource(
                                    R.string.channel_cleanup_row_summary,
                                    stringResource(channelCleanupStatusLabelRes(row.status)),
                                    stringResource(R.string.channel_cleanup_failed_days, row.failedDays),
                                ),
                                checked = row.channelId in state.selectedIds,
                                onClick = { viewModel.toggleChannel(row.channelId) },
                                indent = 20,
                            )
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.channel_cleanup_close),
                onClick = onDismiss,
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.channel_cleanup_hide_selected, state.selectedCount),
                onClick = viewModel::requestHide,
                enabled = state.selectedCount > 0,
                emphasized = true,
            )
        },
    )

    if (state.showConfirm) {
        PremiumDialog(
            title = stringResource(R.string.channel_cleanup_confirm_title),
            onDismissRequest = viewModel::dismissConfirm,
            content = {
                Text(
                    text = stringResource(R.string.channel_cleanup_confirm_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                )
            },
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = viewModel::dismissConfirm,
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.channel_cleanup_confirm_button),
                    onClick = viewModel::confirmHide,
                    emphasized = true,
                )
            },
        )
    }
}

@Composable
private fun ChannelCleanupScanCard(
    scan: ChannelCleanupScanUi,
    errorRes: Int?,
    onScan: () -> Unit,
    onStop: () -> Unit,
    actionFocus: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (scan) {
            is ChannelCleanupScanUi.Idle -> {
                Text(
                    text = scan.lastFinishedAt?.let {
                        stringResource(
                            R.string.channel_cleanup_last_finished,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)),
                        )
                    } ?: stringResource(R.string.channel_cleanup_never_scanned),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                )
                if (scan.gaveUp) {
                    Text(
                        text = stringResource(R.string.channel_cleanup_gave_up),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ErrorColor,
                    )
                }
            }
            is ChannelCleanupScanUi.Running -> {
                Text(
                    text = stringResource(R.string.channel_cleanup_progress, scan.scanned, scan.total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                )
            }
            is ChannelCleanupScanUi.Paused -> {
                Text(
                    text = stringResource(R.string.channel_cleanup_progress, scan.scanned, scan.total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                )
                Text(
                    text = stringResource(channelCleanupPausedLabelRes(scan.reason)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Primary,
                )
            }
        }
        // One chip at one position for every scan state: Scan turns into Stop (and back) on the same
        // focused node, so pressing it never drops D-pad focus and nothing has to steal it back.
        val idle = scan is ChannelCleanupScanUi.Idle
        CompactSettingsActionChip(
            label = stringResource(
                if (idle) R.string.channel_cleanup_scan_button else R.string.channel_cleanup_stop_button
            ),
            accent = if (idle) Primary else ErrorColor,
            onClick = if (idle) onScan else onStop,
            modifier = Modifier.focusRequester(actionFocus),
        )
        errorRes?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = ErrorColor,
            )
        }
        Text(
            text = stringResource(R.string.channel_cleanup_scan_note),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim,
        )
    }
}

@Composable
private fun ChannelCleanupToggleRow(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    hint: String = "",
    indent: Int = 0,
) {
    SettingsActionSurface(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (12 + indent).dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = label, style = MaterialTheme.typography.titleSmall, color = OnSurface)
                if (hint.isNotBlank()) {
                    Text(text = hint, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                }
            }
        }
    }
}
