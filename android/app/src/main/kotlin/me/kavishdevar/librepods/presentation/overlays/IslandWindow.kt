/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.data.app.DesignSystem
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import me.kavishdevar.librepods.data.app.resolved
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.data.app.Design
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.data.app.NightTheme
import me.kavishdevar.librepods.services.LibrePodsAccessibilityService
import kotlin.math.roundToInt

enum class IslandType {
    CONNECTED,
    TAKING_OVER,
    MOVED_TO_REMOTE,
    MOVED_TO_OTHER_DEVICE,
}

enum class IslandState {
    COMPACT,
    EXPANDED,
    CLOSE
}

@Suppress("unused")
class IslandWindow(
    private var context: Context,
    private var appSettings: AppSettingsEntity
) {
    private var windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private var composeView: ComposeView? = null
    private var lifecycleOwner: IslandLifecycleOwner? = null

    private var autoCloseHandler: Handler? = null
    private var autoCloseRunnable: Runnable? = null

    private var currentAppSettings by mutableStateOf(appSettings)

    private var name by mutableStateOf("[Test] AirPods Pro")
    private var type by mutableStateOf(IslandType.CONNECTED)
    private var batteryLevel by mutableIntStateOf(70)
    private var reversed by mutableStateOf(false)
    private var otherDeviceName by mutableStateOf(null as String?)

    private var showOffListeningMode by mutableStateOf(false)
    private var noiseControlModeValue by mutableIntStateOf(4)
    private var adaptiveStrength by mutableIntStateOf(30)

    private var onNoiseControlModeChanged: (Int) -> Unit = { noiseControlModeValue = it }
    private var onAdaptiveStrengthChanged: (Int) -> Unit = { adaptiveStrength = it }

    private var openApp: () -> Unit = {}
    private var imageRes by mutableStateOf(null as Int?)
    private var monochromeIconRes by mutableStateOf(null as Int?)
    private var batteries by mutableStateOf(emptyList<BannerBattery>())

    /** The Material design shows a banner instead of the island. */
    private val usesBanner: Boolean get() = currentAppSettings.designSystem == DesignSystem.Material

    var usingAccessibilityService by mutableStateOf(false)
        private set

    init {
        usingAccessibilityService = context is LibrePodsAccessibilityService
    }

    var islandState by mutableStateOf(IslandState.CLOSE)
        private set

    val isVisible: Boolean
        get() = composeView?.parent != null && composeView?.visibility == View.VISIBLE

    fun updateContext(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updateContext(context) }
            return
        }

        this.context = context
        this.usingAccessibilityService = context is LibrePodsAccessibilityService
        this.windowManager = context.getSystemService(WindowManager::class.java)

        updateWindowParams(islandState)

        composeView?.let { view ->
            if (view.parent != null) {
                windowManager.removeView(view)
            }
        }

        setupComposeView()

        if (composeView?.parent == null) {
            val params = createLayoutParams(islandState)
            try {
                lifecycleOwner?.start()
                windowManager.addView(composeView, params)
                composeView?.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("IslandWindow", "Error adding overlay view: $e")
                // Start over next time: a view whose window was never added would otherwise be
                // reused with its lifecycle already running.
                runCatching { lifecycleOwner?.stop() }
                composeView = null
                lifecycleOwner = null
            }
        }
    }

    fun updateSettings(appSettings: AppSettingsEntity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updateSettings(appSettings) }
            return
        }

        this.appSettings = appSettings
        this.currentAppSettings = appSettings

        updateWindowParams(islandState)
    }

    fun updateBattery(batteryPercentage: Int) {
        this.batteryLevel = batteryPercentage
    }

    fun updateBatteries(batteries: List<BannerBattery>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updateBatteries(batteries) }
            return
        }
        this.batteries = batteries
    }

    fun updateNoiseControl(mode: Int, strength: Int, showOff: Boolean = showOffListeningMode) {
        this.noiseControlModeValue = mode
        this.adaptiveStrength = strength
        this.showOffListeningMode = showOff
    }

    fun changeState(state: IslandState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { changeState(state) }
            return
        }

        islandState = state
        updateWindowParams(state)
    }

    fun show(
        name: String = this.name,
        batteryLevel: Int = this.batteryLevel,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = this.reversed,
        otherDeviceName: String? = this.otherDeviceName,
        onReverseAction: () -> Unit = {},

        openApp: () -> Unit = this.openApp,

        showOffListeningMode: Boolean = this.showOffListeningMode,
        noiseControlModeValue: Int = this.noiseControlModeValue,
        onNoiseControlModeChanged: (Int) -> Unit = this.onNoiseControlModeChanged,
        adaptiveStrength: Int = this.adaptiveStrength,
        onAdaptiveStrengthChanged: (Int) -> Unit = this.onAdaptiveStrengthChanged,

        forcedState: IslandState? = null,
        keepOpen: Boolean = false,
        imageRes: Int? = null,
        monochromeIconRes: Int? = null,
        batteries: List<BannerBattery> = emptyList(),
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                show(name, batteryLevel, type, reversed, otherDeviceName, onReverseAction,
                    openApp, showOffListeningMode, noiseControlModeValue, onNoiseControlModeChanged,
                    adaptiveStrength, onAdaptiveStrengthChanged, forcedState, keepOpen, imageRes, monochromeIconRes,
                    batteries)
            }
            return
        }
        this.batteries = batteries

        Log.d("IslandWindow", "Showing island window: accessibility = $usingAccessibilityService")

        this.batteryLevel = batteryLevel
        this.imageRes = imageRes
        this.monochromeIconRes = monochromeIconRes

        this.name = name
        this.type = type
        this.reversed = reversed
        this.otherDeviceName = otherDeviceName

        this.openApp = openApp

        this.showOffListeningMode = showOffListeningMode
        this.noiseControlModeValue = noiseControlModeValue
        this.adaptiveStrength = adaptiveStrength

        this.onNoiseControlModeChanged = {
            this.noiseControlModeValue = it
            onNoiseControlModeChanged(it)
        }

        this.onAdaptiveStrengthChanged = {
            this.adaptiveStrength = it
            onAdaptiveStrengthChanged(it)
        }

        if (composeView == null) {
            setupComposeView()
        }

        // Size the window for the state the island opens in, not the one it is leaving: an
        // expanded island in a window sized for a closed one is cut off at the sides.
        val targetState = forcedState?: if (appSettings.islandSettings.enableExpanded) IslandState.EXPANDED else IslandState.COMPACT

        if (composeView?.parent == null) {
            val params = createLayoutParams(targetState)
            try {
                lifecycleOwner?.start()
                windowManager.addView(composeView, params)
                composeView?.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("IslandWindow", "Error adding overlay view: $e")
                // Start over next time: a view whose window was never added would otherwise be
                // reused with its lifecycle already running.
                runCatching { lifecycleOwner?.stop() }
                composeView = null
                lifecycleOwner = null
            }
        } else {
            updateWindowParams(targetState)
        }

        islandState = targetState

        if (!keepOpen) resetTimer()
    }

    private fun updateWindowParams(state: IslandState) {
        // Keep the open size while the island animates closed: a window shrinking to wrap content
        // under the animation made the pill jump and clip as it closed. The view is removed after.
        if (state == IslandState.CLOSE) return
        if (composeView?.parent != null) {
            val params = createLayoutParams(state)
            windowManager.updateViewLayout(composeView, params)
        }
    }

    private fun resolvedIslandSettings(settings: IslandSettings): IslandSettings {
        val metrics = windowManager.currentWindowMetrics
        return settings.resolved(
            density = context.resources.displayMetrics.density,
            screenWidthPx = metrics.bounds.width(),
            statusBarHeightPx = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        )
    }

    private fun createLayoutParams(state: IslandState = islandState): WindowManager.LayoutParams {
        val windowType = if (context is android.accessibilityservice.AccessibilityService) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        if (usesBanner) {
            val metrics = windowManager.currentWindowMetrics
            val density = context.resources.displayMetrics.density
            return WindowManager.LayoutParams(
                // Full width up to a card's (the banner pads itself), as tall as it needs.
                metrics.bounds.width().coerceAtMost((504 * density).roundToInt()),
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // Just under the status bar, like Android's own banners.
                y = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val islandSettings = resolvedIslandSettings(appSettings.islandSettings)
        return WindowManager.LayoutParams(
            // Room for the opening spring's overshoot and the press scale, within the screen.
            if (state == IslandState.EXPANDED) {
                (islandSettings.expandedWidth * 1.08f).toInt().coerceAtMost(windowManager.currentWindowMetrics.bounds.width())
            }
            else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = islandSettings.offsetX.roundToInt()
            y = islandSettings.offsetY.roundToInt()

            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun setupComposeView() {
        lifecycleOwner = IslandLifecycleOwner()

        composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)

            setContent {
                val darkTheme = when (currentAppSettings.nightMode) {
                    NightTheme.Dark -> true
                    NightTheme.Light -> false
                    NightTheme.System -> isSystemInDarkTheme()
                }

                LibrePodsTheme(
                    design = Design(
                        system = currentAppSettings.designSystem,
                        colorStyle = currentAppSettings.colorStyle,
                        overrideMaterialColor = currentAppSettings.overrideMaterialColor,
                        contrastLevel = currentAppSettings.contrastLevel
                    ),
                    accessibilitySettings = currentAppSettings.accessibilitySettings,
                    fontSettings = currentAppSettings.fontSettings,
                    darkTheme = darkTheme
                ) {
                    if (usesBanner) {
                        BannerContent(
                            name = name,
                            type = type,
                            batteryLevel = batteryLevel,
                            batteries = batteries,
                            reversed = reversed,
                            otherDeviceName = otherDeviceName,
                            islandState = islandState,
                            openApp = {
                                openApp()
                                close()
                            },
                            requestClose = { close() },
                            onClosed = { cleanupAndRemoveView() },
                            // Stays while it's being dragged.
                            holdOpen = { hold -> if (hold) cancelTimer() else resetTimer() },
                            monochromeIconRes = this@IslandWindow.monochromeIconRes,
                        )
                    } else IslandContent(
                        name = name,
                        type = type,
                        batteryLevel = batteryLevel,
                        reversed = reversed,
                        otherDeviceName = otherDeviceName,

                        islandSettings = resolvedIslandSettings(currentAppSettings.islandSettings),
                        islandState = islandState,

                        // Opening the app is the island's job done: close it (or go compact).
                        openApp = {
                            openApp()
                            if (appSettings.islandSettings.keepCompactVisible) changeState(IslandState.COMPACT) else close()
                        },
                        changeState = { state, updateBefore ->
                            if (updateBefore) updateWindowParams(state)
                            Log.d("Island", "Changing state: $islandState -> $state")
                            islandState = state
                            if (!updateBefore) updateWindowParams(state)
                        },
                        onClosed = { cleanupAndRemoveView() },
                        resetTimer = { resetTimer() },

                        showOffListeningMode = showOffListeningMode,
                        noiseControlModeValue = noiseControlModeValue,
                        onNoiseControlModeChanged = this@IslandWindow.onNoiseControlModeChanged,
                        adaptiveStrength = this@IslandWindow.adaptiveStrength,
                        onAdaptiveStrengthChanged = this@IslandWindow.onAdaptiveStrengthChanged,
                        imageRes = this@IslandWindow.imageRes,
                        monochromeIconRes = this@IslandWindow.monochromeIconRes
                    )
                }
            }
        }
    }

    private fun cancelTimer() {
        autoCloseRunnable?.let { autoCloseHandler?.removeCallbacks(it) }
    }

    private fun resetTimer() {
        autoCloseRunnable?.let { autoCloseHandler?.removeCallbacks(it) }
        autoCloseHandler = Handler(Looper.getMainLooper())
        autoCloseRunnable = Runnable {
            if (appSettings.islandSettings.keepCompactVisible) {
                islandState = IslandState.COMPACT
                updateWindowParams(islandState)
            } else {
                close()
            }
        }
        autoCloseHandler?.postDelayed(autoCloseRunnable!!, 4500)
    }

    fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { close() }
            return
        }

        autoCloseRunnable?.let { autoCloseHandler?.removeCallbacks(it) }
        islandState = IslandState.CLOSE
        updateWindowParams(islandState)
    }

    private fun cleanupAndRemoveView() {
        try {
            composeView?.let { view ->
                if (view.parent != null) {
                    windowManager.removeView(view)
                }
            }
            lifecycleOwner?.stop()
        } catch (e: Exception) {
            Log.e("IslandWindow", "Error removing view: $e")
        } finally {
            composeView = null
            lifecycleOwner = null
            islandState = IslandState.CLOSE
        }
    }
}

private class IslandLifecycleOwner: LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        field = LifecycleRegistry(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    // Safe to call again: restoring saved state twice throws, which used to leave the window
    // unable to show until the app restarted.
    fun start() {
        if (lifecycle.currentState == Lifecycle.State.INITIALIZED) {
            savedStateRegistryController.performRestore(null)
        }
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) return
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
