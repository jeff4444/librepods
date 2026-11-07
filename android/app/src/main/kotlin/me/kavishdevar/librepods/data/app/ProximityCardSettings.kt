package me.kavishdevar.librepods.data.app

import kotlinx.serialization.Serializable

@Serializable
data class ProximityCardSettings(
    // 0 (width, height) and a negative radius mean automatic: an iOS-style card across the screen,
    // as tall as its content, with corners concentric with the screen's.
    val width: Float = 0f,
    val height: Float = 0f,

    val borderRadius: Float = -1f,
    val borderWidth: Float = 0f,

    val backgroundColor: OverlayBackgroundColor = OverlayBackgroundColor.Default(OverlayType.ProximityCard),
    val backgroundAlpha: Float = 0.95f,

    val showConnectButton: Boolean = true,

    val batteryStyle: OverlayBatteryStyle = OverlayBatteryStyle.CIRCLE_TEXT,

    val offsetY: Float = 0f,

    val centerOnTablet: Boolean = true,

    val useVideo: Boolean = true,

    val requireBothBudsInCase: Boolean = true,

    val showCloseButton: Boolean = true
)
