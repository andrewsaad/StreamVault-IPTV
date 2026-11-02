package com.streamvault.feature.catalog.presentation.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.image.ChannelLogoBadge
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.feature.catalog.R
import com.streamvault.feature.catalog.api.CatalogLiveMatch

private val LiveBadgeColor = Color(0xFFE53935)

@Composable
internal fun DashboardLiveMatchesRow(
    title: String,
    matches: List<CatalogLiveMatch>,
    nowMs: Long,
    onMatchClick: (CatalogLiveMatch) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = BrowseColors.TextPrimary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(matches, key = { "${it.channel.id}:${it.startTimeMs}" }) { match ->
                DashboardLiveMatchCard(
                    match = match,
                    status = liveMatchStatus(match, nowMs),
                    onClick = { onMatchClick(match) }
                )
            }
        }
    }
}

@Composable
private fun DashboardLiveMatchCard(
    match: CatalogLiveMatch,
    status: LiveMatchStatus,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        modifier = Modifier
            .width(272.dp)
            .height(124.dp),
        shape = ClickableSurfaceDefaults.shape(BrowseShapes.Control),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = BrowseColors.SurfaceElevated,
            focusedContainerColor = BrowseColors.SurfaceEmphasis
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, BrowseColors.Focus),
                shape = BrowseShapes.Control
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(BrowseShapes.Control)
                ) {
                    ChannelLogoBadge(
                        channelName = match.channel.name,
                        logoUrl = match.channel.logoUrl,
                        shape = BrowseShapes.Control,
                        backgroundColor = BrowseColors.SurfaceEmphasis,
                        contentPadding = PaddingValues(3.dp),
                        textStyle = MaterialTheme.typography.labelSmall,
                        textColor = BrowseColors.TextPrimary,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = match.channel.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = BrowseColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                LiveMatchStatusBadge(status)
            }
            Text(
                text = match.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = BrowseColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            match.league?.let { league ->
                Text(
                    text = league,
                    style = MaterialTheme.typography.bodySmall,
                    color = BrowseColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            match.commentator?.let { commentator ->
                Text(
                    text = stringResource(R.string.dashboard_live_match_commentator, commentator),
                    style = MaterialTheme.typography.bodySmall,
                    color = BrowseColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LiveMatchStatusBadge(status: LiveMatchStatus) {
    val (label, background) = when (status) {
        LiveMatchStatus.Live -> stringResource(R.string.dashboard_live_match_live) to LiveBadgeColor
        is LiveMatchStatus.StartsIn ->
            stringResource(R.string.dashboard_live_match_in_minutes, status.minutes) to BrowseColors.BrandMuted
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .clip(BrowseShapes.Control)
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@StringRes
internal fun AppHomeDashboardShelf.languageShelfTitleResId(): Int = when (this) {
    AppHomeDashboardShelf.NEW_ARABIC_MOVIES -> R.string.dashboard_new_arabic_movies
    AppHomeDashboardShelf.NEW_EGYPTIAN_MOVIES -> R.string.dashboard_new_egyptian_movies
    AppHomeDashboardShelf.NEW_ENGLISH_MOVIES -> R.string.dashboard_new_english_movies
    AppHomeDashboardShelf.NEW_TURKISH_MOVIES -> R.string.dashboard_new_turkish_movies
    AppHomeDashboardShelf.NEW_ARABIC_SERIES -> R.string.dashboard_new_arabic_series
    AppHomeDashboardShelf.NEW_EGYPTIAN_SERIES -> R.string.dashboard_new_egyptian_series
    AppHomeDashboardShelf.NEW_ENGLISH_SERIES -> R.string.dashboard_new_english_series
    AppHomeDashboardShelf.NEW_TURKISH_SERIES -> R.string.dashboard_new_turkish_series
    else -> error("$this is not a language shelf")
}
