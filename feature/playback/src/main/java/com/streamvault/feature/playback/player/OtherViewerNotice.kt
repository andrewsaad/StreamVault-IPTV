package com.streamvault.feature.playback.player

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.domain.sync.OtherViewer
import com.streamvault.feature.playback.R
import java.util.Date

/** "Kitchen is watching beIN 1 on this account (since 19:02)." */
@Composable
internal fun otherViewerText(viewer: OtherViewer): String {
    val since = DateFormat.getTimeFormat(LocalContext.current).format(Date(viewer.sinceMs))
    return stringResource(R.string.player_buffering_hint_other_viewer, viewer.deviceName, viewer.channelName, since)
}

@Composable
internal fun OtherViewerLine(viewer: OtherViewer, modifier: Modifier = Modifier) {
    Text(
        text = otherViewerText(viewer),
        style = MaterialTheme.typography.bodySmall,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = modifier.widthIn(max = 420.dp)
    )
}

/** Shown over the error overlay, where the buffering box (and its line) is not. */
@Composable
internal fun OtherViewerBanner(viewer: OtherViewer) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        OtherViewerLine(
            viewer = viewer,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}
