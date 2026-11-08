package me.kavishdevar.librepods.presentation.components.pixelbuds

import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.devices.PixelBudsMetadata

/**
 * Pixel Buds pictures, from Google's Pixel Buds app: its Pro 2 renders per colour (picked by the
 * colour code the buds report, the same mapping Google's app uses) and Google's bud and case
 * symbols. Never the AirPods art.
 */
object PixelBudsArt {
    fun buds(metadata: PixelBudsMetadata): Int = when (metadata.sku) {
        6 -> R.drawable.img_pixel_buds_pro_2_porcelain_white
        12 -> R.drawable.img_pixel_buds_pro_2_mojito
        11 -> R.drawable.img_pixel_buds_pro_2_raspberry
        17 -> R.drawable.img_pixel_buds_pro_2_sterling
        else -> R.drawable.img_pixel_buds_pro_2_dark_haze // 10, and Google's own default
    }

    val case: Int = R.drawable.img_pixel_buds_case
    val icon: Int = R.drawable.ic_pixel_buds
    val caseIcon: Int = R.drawable.ic_pixel_buds_case
    val leftIcon: Int = R.drawable.ic_pixel_buds_left
    val rightIcon: Int = R.drawable.ic_pixel_buds_right

    // The Pro 2's buds and case turning around, rendered from Google's 3D model (Hazel; the other
    // colours use it too until rendered).
    val pro2Turntable: Int = R.raw.pixel_buds_pro_2_hazel_card

    // Google's "take your buds out" animation (Lottie), for the other Pixel Buds' case card.
    val takeOutLight: Int = R.raw.pixel_buds_take_out_light
    val takeOutDark: Int = R.raw.pixel_buds_take_out_dark
}
