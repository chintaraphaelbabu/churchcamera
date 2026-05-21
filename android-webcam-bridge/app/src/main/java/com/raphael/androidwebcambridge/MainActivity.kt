package com.raphael.androidwebcambridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.raphael.androidwebcambridge.ui.theme.AndroidWebcamBridgeTheme
import com.raphael.androidwebcambridge.ui.CameraScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidWebcamBridgeTheme {
                CameraScreen()
            }
        }
    }
}