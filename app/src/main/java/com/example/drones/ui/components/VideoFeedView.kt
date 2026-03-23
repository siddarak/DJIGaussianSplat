package com.example.drones.ui.components

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.example.drones.sdk.VideoStreamManager

@Composable
fun VideoFeedView(
    modifier: Modifier = Modifier,
    isProductConnected: Boolean
) {
    var surfaceReady by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {

                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            // Surface is ready — attach to MSDK decoder
                            // VideoStreamManager handles cleanup of any previous surface
                            val surface = Surface(surfaceTexture)
                            VideoStreamManager.startVideoFeed(surface, width, height)
                            surfaceReady = true
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            // Orientation change — create a new surface with updated dimensions
                            // VideoStreamManager.startVideoFeed() removes old surface before attaching new
                            val surface = Surface(surfaceTexture)
                            VideoStreamManager.startVideoFeed(surface, width, height)
                        }

                        override fun onSurfaceTextureDestroyed(
                            surfaceTexture: SurfaceTexture
                        ): Boolean {
                            VideoStreamManager.stopVideoFeed()
                            surfaceReady = false
                            // Return true: we release the SurfaceTexture ourselves via VideoStreamManager
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                            // New frame rendered — no action needed
                        }
                    }
                }
            },
            // update lambda called on recomposition — no-op since TextureView manages itself
            update = {}
        )

        // Overlay hint — only visible when no video
        if (!isProductConnected || !surfaceReady) {
            Text(
                text = when {
                    !surfaceReady -> "Initializing video surface..."
                    !isProductConnected -> "Waiting for drone..."
                    else -> ""
                },
                color = Color.White.copy(alpha = 0.6f)
            )
        }

        // Cleanup on Composable disposal — removes all VideoStreamManager listeners
        DisposableEffect(Unit) {
            onDispose {
                VideoStreamManager.cleanup()
            }
        }
    }
}
