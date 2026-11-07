package me.kavishdevar.librepods.data.app

import kotlinx.serialization.Serializable

@Serializable
data class IslandSettings(
    val enableExpanded: Boolean = true,
    val keepCompactVisible: Boolean = false,

    val showListeningModeControl: Boolean = false,

    // 0 sizes and negative radius or offset mean automatic (see resolved()): a 64 dp pill, up to
    // 420 dp wide, 6 dp under the status bar.
    val expandedHeight: Float = 0f,
    val expandedWidth: Float = 0f,
    val expandedBorderRadius: Float = -1f,
    val expandedBorderWidth: Float = 0f,
    val expandedBackgroundColor: OverlayBackgroundColor = OverlayBackgroundColor.Default(OverlayType.Island),
    val expandedBackgroundAlpha: Float = 0.95f,
    val expandedUseMonochrome: Boolean = false,
    val expandedOpenAppOnTap: Boolean = true,
    val expandedBatteryStyle: OverlayBatteryStyle = OverlayBatteryStyle.CIRCLE_TEXT,

    val fontSize: Float = 17f,

    val compactHeight: Float = 85f,
    val compactWidth: Float = 300f,
    val compactBorderRadius: Float = 100f,
    val compactBorderWidth: Float = 1f,
    val compactBackgroundColor: OverlayBackgroundColor = OverlayBackgroundColor.Default(OverlayType.Island),
    val compactBackgroundAlpha: Float = 1f,
    val compactUseMonochrome: Boolean = false,
    val compactOpenAppOnTap: Boolean = true,
    val compactBatteryStyle: OverlayBatteryStyle = OverlayBatteryStyle.CIRCLE,

    val offsetY: Float = -1f,
    val offsetX: Float = 0f
)

/**
 * These settings with the automatic values filled in for a screen [screenWidthPx] wide at [density]
 * pixels per dp: the expanded island becomes a 64 dp pill (taller with listening mode controls),
 * at most 420 dp wide with 12 dp margins, 6 dp under the status bar ([statusBarHeightPx] tall).
 */
fun IslandSettings.resolved(density: Float, screenWidthPx: Int, statusBarHeightPx: Int): IslandSettings {
    val height = if (expandedHeight <= 0f) (64f + if (showListeningModeControl) 72f else 0f) * density else expandedHeight
    return copy(
        expandedWidth = if (expandedWidth <= 0f) minOf(screenWidthPx - 24f * density, 420f * density) else expandedWidth,
        expandedHeight = height,
        expandedBorderRadius = if (expandedBorderRadius < 0f) {
            if (showListeningModeControl) 32f * density else height / 2f
        } else expandedBorderRadius,
        offsetY = if (offsetY < 0f) statusBarHeightPx + 6f * density else offsetY,
    )
}
