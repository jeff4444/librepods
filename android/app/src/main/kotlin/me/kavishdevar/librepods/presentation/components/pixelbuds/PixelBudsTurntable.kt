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
 * A looping turntable of the Pixel Buds Pro 2 for the case card, laid out like the AirPods card's
 * video (buds centred at 28% of the width, the open case at 71%, where the card puts their
 * batteries): an animated WebP (transparent, 960 x 540, 120 frames at 30 fps) rendered from Google's
 * own 3D model of the Pro 2 (largo.glb in the Pixel Buds app), the buds posed with its
 * Tilted_Buds_Zoom_In animation and coloured from its per-colour material table, the case's shell
 * in the same colour, under its own lighting map. See research/pixelbuds/turntable.
 */
@Composable
fun PixelBudsTurntable(@RawRes resId: Int, modifier: Modifier = Modifier, aspectRatio: Float = 960f / 540f) {
    AndroidView(
        modifier = modifier.aspectRatio(aspectRatio),
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
