package com.streambox.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.streambox.mobile.ui.theme.AppAccent
import com.streambox.mobile.ui.theme.AppError
import com.streambox.mobile.ui.theme.AppSurfaceHigh
import com.streambox.mobile.ui.theme.AppTextMuted
import com.streambox.mobile.ui.theme.AppTextSecondary

/** Pulsing skeleton used while content loads (no external shimmer dependency). */
@Composable
private fun Modifier.shimmer(): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "alpha",
    )
    return this
        .alpha(alpha)
        .background(AppSurfaceHigh, RoundedCornerShape(10.dp))
}

@Composable
private fun SkeletonPoster(modifier: Modifier = Modifier) {
    Box(modifier = modifier.shimmer().clip(RoundedCornerShape(12.dp))) {
        Column(Modifier.aspectRatio(2f / 3f)) {}
    }
}

/** Home-style skeleton: hero card + rails. */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(200.dp).shimmer(),
        )
        Spacer(Modifier.height(20.dp))
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { SkeletonPoster(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** Detail-style skeleton: backdrop + title block. */
@Composable
fun DetailSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(260.dp).shimmer())
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(0.7f).height(26.dp).shimmer())
            Box(Modifier.fillMaxWidth(0.4f).height(16.dp).shimmer())
            repeat(3) {
                Box(Modifier.fillMaxWidth().height(13.dp).shimmer())
            }
        }
    }
}

/** Search/watchlist grid skeleton. */
@Composable
fun GridSkeleton(modifier: Modifier = Modifier, cells: Int = 6) {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = false,
    ) {
        items(cells) { SkeletonPoster(Modifier.fillMaxWidth()) }
    }
}

/** Shared error block with retry (Phase 2 scope item 7). */
@Composable
fun ErrorState(
    title: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StateIcon(icon = Icons.Filled.Warning, tint = AppError)
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text("Retry", fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Shared empty-state block (search no-results, empty watchlist). */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.PlayArrow,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StateIcon(icon = icon, tint = AppAccent)
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StateIcon(icon: ImageVector, tint: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .size(58.dp)
            .background(tint.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
    }
}
