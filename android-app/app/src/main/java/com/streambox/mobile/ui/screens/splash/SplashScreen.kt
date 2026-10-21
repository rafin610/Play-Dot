package com.streambox.mobile.ui.screens.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streambox.mobile.R
import com.streambox.mobile.ui.theme.AppAccent
import com.streambox.mobile.ui.theme.AppBackground
import com.streambox.mobile.ui.theme.AppSurfaceHigh
import com.streambox.mobile.ui.theme.AppTextMuted
import kotlinx.coroutines.delay

/**
 * Branded launch screen using the supplied full Play Dot logo (symbol +
 * wordmark) exactly as provided, then hands off to the home feed.
 */
@Composable
fun SplashScreen(onReady: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) {
        delay(1100)
        onReady()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.play_dot_logo),
            contentDescription = "Play Dot",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.66f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(40.dp)),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Movies & Series",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = AppTextMuted,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.height(26.dp))
        LinearProgressIndicator(
            modifier = Modifier
                .width(132.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(100)),
            color = AppAccent,
            trackColor = AppSurfaceHigh,
        )
        Spacer(Modifier.height(8.dp))
    }
}
