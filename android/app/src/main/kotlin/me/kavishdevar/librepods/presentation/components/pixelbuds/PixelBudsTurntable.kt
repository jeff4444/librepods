package me.kavishdevar.librepods.presentation.components.pixelbuds

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A looping turntable of the Pixel Buds Pro 2, like the AirPods card's video: an animated WebP
 * (transparent, 720 x 531, 120 frames at 30 fps) rendered from Google's own 3D model of the Pro 2
 * (largo.glb in the Pixel Buds app), posed with its Tilted_Buds_Zoom_In animation and coloured
 * from its per-colour material table, under its own lighting map. See research/pixelbuds.
 */
@Composable
fun PixelBudsTurntable(@RawRes resId: Int, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.aspectRatio(720f / 531f),
        factory = { context ->
            ImageView(context).apply {
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, resId))
                setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable) {
                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    drawable.start()
                }
            }
        }
    )
}
