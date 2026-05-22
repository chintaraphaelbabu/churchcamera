package com.raphael.androidwebcambridge

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raphael.androidwebcambridge.bridge.BridgeState
import com.raphael.androidwebcambridge.bridge.BridgeViewModel
import com.raphael.androidwebcambridge.ui.theme.AndroidWebcamBridgeTheme
import com.raphael.androidwebcambridge.ui.CameraScreen

class MainActivity : ComponentActivity() {
    private var bridgeViewModel: BridgeViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: BridgeViewModel = viewModel()
            bridgeViewModel = vm
            AndroidWebcamBridgeTheme {
                CameraScreen()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val vm = bridgeViewModel ?: return super.onKeyDown(keyCode, event)
        val state = vm.state.value

        when (state.activeRail) {
            BridgeState.RailType.FOCUS -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        vm.setFocusDistance((state.settings.focusDistanceDiopters + state.settings.focusVelocity).coerceAtMost(10f))
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        vm.setFocusDistance((state.settings.focusDistanceDiopters - state.settings.focusVelocity).coerceAtLeast(0f))
                        return true
                    }
                }
            }
            BridgeState.RailType.ZOOM -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        vm.setZoom((state.settings.physicalZoomRatio + state.settings.zoomVelocity).coerceAtMost(5f))
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        vm.setZoom((state.settings.physicalZoomRatio - state.settings.zoomVelocity).coerceAtLeast(1f))
                        return true
                    }
                }
            }
            BridgeState.RailType.ISO -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        val next = if (state.settings.iso == 0) 100 else (state.settings.iso + 100).coerceAtMost(6400)
                        vm.setIso(next)
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        val next = (state.settings.iso - 100).coerceAtLeast(0)
                        vm.setIso(next)
                        return true
                    }
                }
            }
            BridgeState.RailType.SHUTTER -> {
                val shutterList = listOf(0, 1, 2, 4, 8, 15, 30, 60, 120, 250, 500)
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        val currentIndex = shutterList.indexOf(state.settings.shutterSpeedMs)
                        if (currentIndex < shutterList.size - 1) {
                            vm.setShutterSpeed(shutterList[currentIndex + 1])
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        val currentIndex = shutterList.indexOf(state.settings.shutterSpeedMs)
                        if (currentIndex > 0) {
                            vm.setShutterSpeed(shutterList[currentIndex - 1])
                        }
                        return true
                    }
                }
            }
            else -> {}
        }
        return super.onKeyDown(keyCode, event)
    }
}
