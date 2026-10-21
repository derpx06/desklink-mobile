/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.mousepad

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import org.desklink.mobile.webrtc.WebRtcRuntime
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The remote desktop picture, drawn into a [TextureView] so it can be zoomed
 * and panned like any other view. libwebrtc's `SurfaceViewRenderer` cannot:
 * a SurfaceView's surface ignores view scaling. Frames stretch to the view, so
 * the caller sizes it to the picture's aspect ratio.
 */
class RemoteTextureRenderer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("DeskLink-desktop-view")

    init {
        renderer.init(WebRtcRuntime.eglContext(context), EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTextureListener = this
        isOpaque = false
    }

    override fun onFrame(frame: VideoFrame) = renderer.onFrame(frame)

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderer.createEglSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // The GL thread must stop drawing before the surface goes away.
        val released = CountDownLatch(1)
        renderer.releaseEglSurface { released.countDown() }
        released.await(1, TimeUnit.SECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    fun release() = renderer.release()
}
