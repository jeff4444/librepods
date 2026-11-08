package me.kavishdevar.librepods.presentation.overlays

import android.annotation.SuppressLint
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.Layout
import android.view.RoundedCorner
import android.view.Display
import android.hardware.display.DisplayManager
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.TextureView
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.data.app.OverlayBackgroundColor
import me.kavishdevar.librepods.data.app.ProximityCardSettings
import me.kavishdevar.librepods.data.app.resolve
import me.kavishdevar.librepods.presentation.components.apple.BatteryIndicator
import me.kavishdevar.librepods.presentation.components.apple.BatteryView
import me.kavishdevar.librepods.presentation.components.primitives.MaterialButtonStyle
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import android.view.Surface as AndroidSurface
import me.kavishdevar.librepods.core.R as coreR

@SuppressLint("LocalContextResourcesRead", "LocalContextConfigurationRead")
@Composable
fun ProximityCardContent(
    name: String,
    batteries: Set<Battery>,
    settings: ProximityCardSettings,

    @DrawableRes primaryImageRes: Int = coreR.drawable.img_airpods_pro_3_buds,
    @DrawableRes caseImageRes: Int? = null,
    @RawRes videoResId: Int = coreR.raw.airpods_pro_2_prox_loop,
    @RawRes lottieLightRes: Int = 0,
    @RawRes lottieDarkRes: Int = 0,
    animation: (@Composable (Modifier) -> Unit)? = null,

    cardState: ProximityCardState,
    onConnect: () -> Unit,
    onClosed: () -> Unit,
    airPodsConnected: Boolean = false
) {
    val visibilityState = remember {
        MutableTransitionState(false)
    }

    LaunchedEffect(cardState) {
        visibilityState.targetState = cardState == ProximityCardState.VISIBLE
    }

    LaunchedEffect(visibilityState.isIdle, visibilityState.currentState) {
        if (visibilityState.isIdle && !visibilityState.currentState) {
            onClosed()
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }

    val backgroundColor = settings.backgroundColor.resolve().copy(alpha = settings.backgroundAlpha)

    val darkTheme = backgroundColor.luminance() < 0.5f
    val textColor = if (settings.backgroundColor is OverlayBackgroundColor.Default) if (darkTheme) Color.White else Color.Black else contentColorFor(backgroundColor)

    val context = LocalContext.current

    val videoResId = remember(darkTheme) {
        // 0: this device has no video (Pixel Buds), use the pictures instead.
        if (videoResId == 0) return@remember 0
        val config = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (darkTheme) Configuration.UI_MODE_NIGHT_YES
                else Configuration.UI_MODE_NIGHT_NO
        }

        val themedContext = context.createConfigurationContext(config)

        themedContext.resources.getIdentifier(
            context.resources.getResourceEntryName(videoResId),
            "raw",
            context.packageName
        )
    }

    val draggableState = rememberDraggableState { delta ->
        coroutineScope.launch {
            val newOffset = (offsetY.value + delta).coerceAtLeast(0f)
            offsetY.snapTo(newOffset)
        }
    }

    val density = LocalDensity.current

    // A width or height of 0 and a negative radius mean automatic, the default: an iOS-style card
    // across the screen, inset by CARD_INSET, as tall as its content, with corners concentric
    // with the screen's.
    val autoWidth = settings.width <= 0f
    val autoHeight = settings.height <= 0f
    val widthDp = with(density) { settings.width.toDp() }
    val heightDp = with(density) { settings.height.toDp() }
    val concentricRadius = rememberConcentricCardRadius(CARD_INSET)
    val borderRadiusDp = if (settings.borderRadius < 0f) concentricRadius else with(density) { settings.borderRadius.toDp() }

    val shape = RoundedCornerShape(borderRadiusDp)

    Box(
        modifier = if (autoHeight) {
            Modifier
                .fillMaxWidth()
                .padding(CARD_INSET)
        } else {
            Modifier
                .fillMaxSize()
                .padding(16.dp)
        },
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visibleState = visibilityState,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 300)
            ),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 250)
            )
        ) {
            Surface(
                modifier = Modifier
                    .then(if (autoWidth) Modifier.fillMaxWidth() else Modifier.width(widthDp))
                    .then(if (autoHeight) Modifier else Modifier.height(heightDp))
                    .offset { IntOffset(0, offsetY.value.roundToInt()) }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = draggableState,
                        onDragStopped = { velocity ->
                            coroutineScope.launch {
                                if (offsetY.value > 120f || velocity > 1000f) {
                                    offsetY.animateTo(600f, tween(150))
                                    onClosed()
                                } else {
                                    offsetY.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessLow
                                        )
                                    )
                                }
                            }
                        }
                    )
                    .clip(shape)
                    .background(backgroundColor)
                    .then(
                        if (settings.borderWidth > 0f) {
                            Modifier.border(settings.borderWidth.dp, textColor.copy(0.2f), shape)
                        } else {
                            Modifier
                        }
                    ),
                color = backgroundColor,
                shape = shape
            ) {
                if (settings.showCloseButton){
                    // Only as wide as the card: filling the height would stretch an automatic card
                    // to the whole window.
                    Box(if (autoHeight) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = LocalIcons.current.Close,
                            contentDescription = stringResource(R.string.hide_prox_card),
                            tint = textColor,
                            modifier = Modifier
                                .clickable(
                                    onClick = {
                                        coroutineScope.launch {
                                            offsetY.animateTo(600f, tween(150))
                                            onClosed()
                                        }
                                    }
                                )
                                .align(Alignment.TopEnd)
                                .offset(x = (-24).dp, y = 24.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(textColor.copy(alpha = 0.2f))
                                .padding(10.dp)
                        )
                    }
                }
                Column(
                    modifier = if (autoHeight) {
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 22.dp, bottom = 26.dp)
                    } else {
                        Modifier
                            .fillMaxSize()
                            .padding(28.dp)
                    },
                    verticalArrangement = Arrangement.spacedBy(if (autoHeight) 14.dp else 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.headlineSmallEmphasized.copy(fontWeight = FontWeight.SemiBold),
                        color = textColor,
                        maxLines = 2,
                        softWrap = true,
                        modifier = Modifier.padding(horizontal = if (autoHeight) 56.dp else 32.dp),
                        textAlign = TextAlign.Center
                    )

                    Box(
                        modifier = if (autoHeight) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        if (settings.useVideo && (videoResId != 0 || lottieLightRes != 0 || animation != null)) {
                            VideoBatteryLayout(
                                batteryList = batteries,
                                videoResId = videoResId,
                                animation = animation,
                                lottieResId = if (darkTheme && lottieDarkRes != 0) lottieDarkRes else lottieLightRes,
                                surfaceColor = backgroundColor,
                                fitContent = autoHeight
                            )
                        } else {
                            BatteryView(
                                batteryList = batteries,
                                primaryImageRes = primaryImageRes,
                                caseImageRes = caseImageRes,
                                surfaceColor = backgroundColor
                            )
                        }
                    }

                    AnimatedVisibility(settings.showConnectButton && !airPodsConnected) {
                        StyledButton(
                            onClick = onConnect,
                            modifier = Modifier
                                .then(if (autoHeight) Modifier.padding(horizontal = 24.dp) else Modifier)
                                .fillMaxWidth()
                                .height(48.dp),
                            materialButtonStyle = MaterialButtonStyle.Outlined
                        ) {
                            Text(
                                text = stringResource(R.string.connect),
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoBatteryLayout(
    batteryList: Set<Battery>,
    @RawRes videoResId: Int,
    @RawRes lottieResId: Int = 0,
    animation: (@Composable (Modifier) -> Unit)? = null,
    surfaceColor: Color,
    // Size to the video across the full width, with each battery centered under its object.
    fitContent: Boolean = false
) {
    val headsetBattery = batteryList.find { it.component == BatteryComponent.HEADSET }

    Column(
        modifier = if (fitContent) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .widthIn(max = 350.dp)
                .fillMaxWidth()
                .fillMaxHeight()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (fitContent) 14.dp else 16.dp)
    ) {
        Box(
            modifier = if (fitContent) {
                Modifier.fillMaxWidth()
            } else {
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            },
            contentAlignment = Alignment.Center
        ) {
            if (animation != null) {
                animation(Modifier.fillMaxWidth())
            } else if (lottieResId != 0) {
                LottieLoop(resId = lottieResId, modifier = Modifier.fillMaxWidth())
            } else {
                LoopingVideoView(
                    videoResId = videoResId,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (headsetBattery != null) {
            BatteryIndicator(
                battery = headsetBattery,
                surfaceColor = surfaceColor,
                showIcon = false
            )
        } else {
            val left = batteryList.find { it.component == BatteryComponent.LEFT }
            val right = batteryList.find { it.component == BatteryComponent.RIGHT }
            val case = batteryList.find { it.component == BatteryComponent.CASE }

            val singleDisplayed = left != null && right != null && left.status == right.status && (left.level - right.level).absoluteValue < 3

            val budsGroup: @Composable () -> Unit = {
                    if (singleDisplayed) {
                        BatteryIndicator(
                            battery = left,
                            surfaceColor = surfaceColor,
                            showIcon = false
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (left != null && left.level > 0) {
                                BatteryIndicator(
                                    battery = left,
                                    surfaceColor = surfaceColor
                                )
                            }

                            if (left != null && right != null && left.level > 0 && right.level > 0) {
                                Spacer(modifier = Modifier.width(16.dp))
                            }

                            if (right != null && right.level > 0) {
                                BatteryIndicator(
                                    battery = right,
                                    surfaceColor = surfaceColor
                                )
                            }
                        }
                    }
            }
            val caseGroup: @Composable () -> Unit = {
                if (case != null) {
                    BatteryIndicator(
                        battery = case,
                        surfaceColor = surfaceColor,
                        showIcon = !singleDisplayed
                    )
                }
            }

            if (fitContent) {
                // In the video the buds sit at 28% of the width and the case at 71%.
                CenteredAtFractions(
                    fractions = listOf(0.28f, 0.71f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(contentAlignment = Alignment.Center) { budsGroup() }
                    Box(contentAlignment = Alignment.Center) { caseGroup() }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) { budsGroup() }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) { caseGroup() }
                }
            }
        }
    }
}

/** A Lottie animation looping at its own aspect ratio (Pixel Buds' cards use Google's). */
@Composable
private fun LottieLoop(@RawRes resId: Int, modifier: Modifier = Modifier) {
    val composition by com.airbnb.lottie.compose.rememberLottieComposition(
        com.airbnb.lottie.compose.LottieCompositionSpec.RawRes(resId)
    )
    val ratio = composition?.bounds?.let { if (it.height() > 0) it.width().toFloat() / it.height() else null } ?: (312f / 230f)
    com.airbnb.lottie.compose.LottieAnimation(
        composition = composition,
        iterations = com.airbnb.lottie.compose.LottieConstants.IterateForever,
        modifier = modifier.aspectRatio(ratio)
    )
}

@Composable
fun LoopingVideoView(
    @RawRes videoResId: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isVideoReady by remember { mutableStateOf(false) }
    // Read the size before the first frame, so the card has its final height when it slides in.
    val aspectRatio = remember(videoResId) {
        val retriever = MediaMetadataRetriever()
        try {
            val afd = context.resources.openRawResourceFd(videoResId)
            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull()
            if (w != null && h != null && h > 0f) w / h else 16f / 9f
        } catch (_: Exception) {
            16f / 9f
        } finally {
            retriever.release()
        }
    }

    AndroidView(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .alpha(if (isVideoReady) 1f else 0f),
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var mediaPlayer: MediaPlayer? = null

                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        val surface = AndroidSurface(surfaceTexture)
                        mediaPlayer = MediaPlayer().apply {
                            val uri = Uri.parse("android.resource://${ctx.packageName}/$videoResId")
                            setDataSource(ctx, uri)
                            setSurface(surface)
                            isLooping = true
                            setOnInfoListener { _, what, _ ->
                                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                    isVideoReady = true
                                }
                                false
                            }
                            setOnPreparedListener { mp ->
                                mp.start()
                            }
                            prepareAsync()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        mediaPlayer?.release()
                        mediaPlayer = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                        if (!isVideoReady) isVideoReady = true
                        val mp = mediaPlayer ?: return
                        if (mp.isPlaying && mp.duration > 0 && mp.currentPosition >= mp.duration - 60) {
                            mp.seekTo(0)
                        }
                    }
                }
            }
        }
    )
}

private val CARD_INSET = 8.dp

/** The card's corner radius that keeps an even gap to the screen's rounded corners. */
@Composable
private fun rememberConcentricCardRadius(inset: Dp): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(density) {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val screenCornerPx = display?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
        with(density) { (screenCornerPx.toDp() - inset).coerceAtLeast(28.dp) }
    }
}

/** Lays children out side by side, each centered at its fraction of the width. */
@Composable
private fun CenteredAtFractions(
    fractions: List<Float>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        // Each child gets an equal share of the width, so one that fills its width still centers
        // its content on its fraction.
        val share = if (measurables.isEmpty()) width else width / measurables.size
        val placeables = measurables.map {
            it.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = share))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val center = (fractions.getOrElse(index) { 0.5f } * width).roundToInt()
                val x = (center - placeable.width / 2).coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                placeable.placeRelative(x, (height - placeable.height) / 2)
            }
        }
    }
}
