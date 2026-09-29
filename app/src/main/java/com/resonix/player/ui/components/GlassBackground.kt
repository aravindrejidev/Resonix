package com.resonix.player.ui.components

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.resonix.player.ui.theme.ResonixBlack

/**
 * The "Apple-style liquid glass" ambient backdrop: the current track's
 * own album art, heavily blurred and darkened, diffusing into pitch
 * black — never a static/decorative image. Pass null while art is
 * loading or unavailable; the black background alone is a reasonable
 * default rather than an empty/broken-looking screen.
 */
@Composable
fun GlassBackground(
    albumArt: ImageBitmap?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().background(ResonixBlack)) {
        albumArt?.let { art ->
            Image(
                bitmap = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .let { base ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            base.blur(radius = 90.dp)
                        } else {
                            // Real-time blur needs RenderEffect (API 31+).
                            // Below that, the darkening scrim below still
                            // gives an ambient, on-brand look without it.
                            base
                        }
                    }
                    .alpha(0.5f)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.25f),
                            Color.Black.copy(alpha = 0.65f),
                            ResonixBlack
                        )
                    )
                )
        )
    }
}
