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

    /** Google's case symbol, for Pixel Buds without a render of their own. */
    val caseOutline: Int = R.drawable.img_pixel_buds_case

    private fun isPro2(metadata: PixelBudsMetadata) = metadata.deviceType == 6 || metadata.deviceType == null

    /** The case as the card shows it: the Pro 2 model in its own white finish. */
    fun case(metadata: PixelBudsMetadata): Int =
        if (isPro2(metadata)) R.drawable.img_pixel_buds_pro_2_case_white else caseOutline

    // Each bud on its own, rendered from Google's Pro 2 model (research/pixelbuds/turntable,
    // stills.html); only Hazel so far, other colours fall back to the pair.
    fun leftBud(metadata: PixelBudsMetadata): Int? =
        if (isPro2(metadata) && (metadata.sku == null || metadata.sku == 10)) R.drawable.img_pixel_buds_pro_2_left_dark_haze else null

    fun rightBud(metadata: PixelBudsMetadata): Int? =
        if (isPro2(metadata) && (metadata.sku == null || metadata.sku == 10)) R.drawable.img_pixel_buds_pro_2_right_dark_haze else null
    val icon: Int = R.drawable.ic_pixel_buds
    val caseIcon: Int = R.drawable.ic_pixel_buds_case
    val leftIcon: Int = R.drawable.ic_pixel_buds_left
    val rightIcon: Int = R.drawable.ic_pixel_buds_right

    // The Pro 2's buds and case turning around, rendered from Google's 3D model: Hazel buds, the
    // case in the model's own white finish (the other colours use it too until rendered).
    val pro2Turntable: Int = R.raw.pixel_buds_pro_2_hazel_card

    // Google's "take your buds out" animation (Lottie), for the other Pixel Buds' case card.
    val takeOutLight: Int = R.raw.pixel_buds_take_out_light
    val takeOutDark: Int = R.raw.pixel_buds_take_out_dark
}
