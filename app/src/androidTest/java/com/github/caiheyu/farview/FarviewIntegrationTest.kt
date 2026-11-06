package com.github.caiheyu.farview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.PowerManager
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.caiheyu.farview.network.Pairing
import com.github.caiheyu.farview.network.Peer
import com.github.caiheyu.farview.network.SecureTransport
import com.github.caiheyu.farview.network.LocalNetwork
import com.github.caiheyu.farview.session.*
import com.github.caiheyu.farview.ui.FarviewScreen
import com.github.caiheyu.farview.ui.VideoTexture
import com.github.caiheyu.farview.ui.theme.FarviewTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Camera2 -> H.264 -> pinned TLS -> viewer -> MP4 on a single emulator.
 * This validates the pipeline, not Wi-Fi hardware or vendor screen-off behavior. */
@RunWith(AndroidJUnit4::class)
class FarviewIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as FarviewApplication
    private val sender get() = app.session
    private var viewer: SessionManager? = null

    @Before fun permissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("input keyevent 224", "wm dismiss-keyguard")) {
            automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
        for (permission in listOf("android.permission.CAMERA", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE")) {
            automation.executeShellCommand("pm grant ${app.packageName} $permission").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
    }
    @After fun cleanup() { viewer?.end(); sender.end() }

    @Test fun homeAndAboutShowTheConfirmedProduct() {
        compose.onNodeWithText("作为拍摄端").assertIsDisplayed()
        compose.onNodeWithText("作为观看端").assertIsDisplayed()
        screenshot("home")
        compose.onNodeWithText("关于").performClick()
        compose.onNodeWithText("GitHub 开源项目").assertIsDisplayed()
        compose.onNodeWithText("github.com/caiheyu/Farview").assertIsDisplayed()
        screenshot("about")
    }

    @Test fun scannerCameraPreviewIsUprightAndKeepsItsAspectRatio() {
        compose.onNodeWithText("作为观看端").performClick()
        compose.onNodeWithText("对准拍摄端的二维码").assertIsDisplayed()
        var preview: VideoTexture? = null
        var observed: VideoTexture? = null
        var frameUpdates = 0
        compose.waitUntil(15000) {
            var receivedFrame = false
            compose.runOnUiThread {
                preview = findVideoTexture(compose.activity.window.decorView)
                preview?.let { view ->
                    if (observed !== view) {
                        val original = view.surfaceTextureListener!!
                        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener by original {
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                                frameUpdates++
                                original.onSurfaceTextureUpdated(texture)
                            }
                        }
                        observed = view
                    }
                    // Observe presented frames directly; legacy camera HAL timestamps
                    // can be zero or negative while the preview is visibly rendering.
                    receivedFrame = frameUpdates >= 2
                }
            }
            receivedFrame
        }
        compose.runOnUiThread {
            val view = preview!!
            assertEquals(Surface.ROTATION_0, view.display.rotation)
            val corners = floatArrayOf(0f, 0f, view.width.toFloat(), 0f, 0f, view.height.toFloat())
            view.getTransform(Matrix()).mapPoints(corners)
            // In the natural display orientation, the camera producer already
            // makes the image upright. Its top edge must stay horizontal.
            assertTrue(corners[2] > corners[0])
            assertEquals(corners[1], corners[3], .01f)
            assertEquals(corners[0], corners[4], .01f)
            assertTrue(corners[5] > corners[1])
            val cameras = app.getSystemService(CameraManager::class.java)
            val camera = cameras.cameraIdList.map(cameras::getCameraCharacteristics)
                .first { it[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }
            val swapsAxes = (camera[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0) % 180 != 0
            val expectedAspect = if (swapsAxes) view.imageHeight.toFloat() / view.imageWidth else view.imageWidth.toFloat() / view.imageHeight
            assertEquals(expectedAspect, (corners[2] - corners[0]) / (corners[5] - corners[1]), .01f)
        }
        screenshot("scanner")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("作为观看端").assertIsDisplayed()
    }

    @Test fun connectionFailureShowsTheActualReasonAndSafeDiagnostics() {
        compose.onNodeWithText("作为观看端").performClick()
        val hosts = LocalNetwork(app).advertisedHosts()
        val unusedPort = ServerSocket(0).use { it.localPort }
        val pairing = Pairing(hosts, unusedPort, "a".repeat(64), "b".repeat(64))
        assertTrue(sender.scanned(pairing.encode()))
        compose.waitUntil(12000) { sender.state.value.connectionIssue != null }
        compose.onNodeWithText("复制连接诊断").assertIsDisplayed()
        compose.onNodeWithText("拍摄端未接受连接", substring = true).assertIsDisplayed()
        val report = sender.connectionDiagnostics()
        assertTrue(report.contains(hosts.first()))
        assertTrue(report.contains(unusedPort.toString()))
        assertTrue(report.contains("Android"))
        assertFalse(report.contains(pairing.token))
        assertFalse(report.contains(pairing.fingerprint))
        screenshot("connection-failure")
        sender.end()
        compose.waitUntil(5000) { sender.state.value.stage == Stage.HOME }
    }

    @Test fun encryptedLiveControlRecordingSegmentationAndReconnect() {
        compose.onNodeWithText("作为拍摄端").performClick()
        compose.waitUntil(20000) { sender.state.value.stage == Stage.WAITING || sender.state.value.error != null }
        assertNull(sender.state.value.error)
        val pairing = sender.state.value.pairing!!
        compose.waitUntil(10000) { compose.onAllNodesWithText("让另一台手机扫码").fetchSemanticsNodes().isNotEmpty() }
        screenshot("pairing")
        val receiver = SessionManager(app); viewer = receiver
        receiver.beginViewer(pairing.encode())
        try { compose.waitUntil(25000) { receiver.state.value.stage == Stage.CONNECTED && !receiver.state.value.videoStalled } }
        catch (e: Exception) {
            fun inspect(manager: SessionManager): String = listOf("lastFrameAt", "format").joinToString { field -> "$field=" + manager.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(manager) }
            throw AssertionError("Sender ${sender.state.value.stage}/${sender.state.value.error} ${inspect(sender)}; viewer ${receiver.state.value.stage}/${receiver.state.value.error} ${inspect(receiver)}", e)
        }
        assertEquals(Stage.CONNECTED, sender.state.value.stage)
        Thread.sleep(1500)
        // The sender's own preview must stay upright like the scanner preview: camera2
        // already rotates TextureView buffers, so the stream rotation applies to the viewer only.
        var senderPreview: VideoTexture? = null
        compose.waitUntil(10000) {
            compose.runOnUiThread { senderPreview = findVideoTexture(compose.activity.window.decorView) }
            senderPreview != null
        }
        compose.runOnUiThread {
            val corners = floatArrayOf(0f, 0f, senderPreview!!.width.toFloat(), 0f, 0f, senderPreview!!.height.toFloat())
            senderPreview!!.getTransform(Matrix()).mapPoints(corners)
            assertTrue(corners[2] > corners[0])
            assertEquals(corners[1], corners[3], .01f)
        }
        screenshot("camera")

        compose.runOnUiThread { compose.activity.setContent { FarviewTheme { FarviewScreen(receiver, {}, receiver::toggleRecording) } } }
        compose.waitUntil(10000) { receiver.decoder.lastRenderedAt > 0 }
        screenshot("viewer")
        compose.onNodeWithText("旋转").performClick()
        compose.onNodeWithText("全屏").performClick()
        compose.onNodeWithText("退出全屏", substring = true).assertIsDisplayed()
        screenshot("fullscreen")
        compose.onNodeWithText("退出全屏", substring = true).performClick()

        val lens = receiver.state.value.lens!!
        if (lens.zoomMax > 1.1f) {
            receiver.command("zoom", 1.1)
            compose.waitUntil(6000) { !receiver.state.value.pendingCommand && receiver.state.value.zoom > 1.05f }
            assertEquals(1.1f, sender.state.value.zoom, .01f)
        }
        receiver.toggleRecording()
        compose.waitUntil(10000) { receiver.state.value.recording && !receiver.state.value.waitingForKeyframe }
        compose.onNodeWithText("全屏").performClick()
        compose.onNodeWithText("● REC", substring = true).assertIsDisplayed()
        screenshot("recording-fullscreen")
        compose.onNodeWithText("退出全屏", substring = true).performClick()
        Thread.sleep(1800)
        if (1080 in lens.qualities && receiver.state.value.quality != 1080) {
            receiver.command("quality", "1080")
            compose.waitUntil(15000) { receiver.state.value.quality == 1080 && receiver.state.value.savedClips >= 1 }
        } else {
            // Restart the actual camera stream to exercise format generations even on a one-lens emulator.
            receiver.command("lens", lens.id)
            compose.waitUntil(15000) { receiver.state.value.savedClips >= 1 && !receiver.state.value.pendingCommand }
        }
        assertTrue(receiver.state.value.recording)
        assertPlayableSilentVideo(app, receiver.state.value.lastClip!!)
        val savedBeforeStop = receiver.state.value.savedClips
        Thread.sleep(2000)
        receiver.toggleRecording()
        compose.waitUntil(10000) { !receiver.state.value.recording && receiver.state.value.savedClips > savedBeforeStop && !receiver.state.value.saving }
        assertPlayableSilentVideo(app, receiver.state.value.lastClip!!)

        receiver.toggleRecording()
        compose.waitUntil(10000) { receiver.state.value.recording && !receiver.state.value.waitingForKeyframe }
        val frameField = sender.javaClass.getDeclaredField("lastFrameAt").apply { isAccessible = true }
        val beforeSleep = frameField.getLong(sender)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            automation.executeShellCommand("input keyevent 223").close()
            Thread.sleep(2500)
            assertFalse("The emulator display must actually be off", app.getSystemService(PowerManager::class.java).isInteractive)
            assertTrue("Foreground capture must continue with the emulator display off", frameField.getLong(sender) > beforeSleep + 1000)
            assertTrue(receiver.state.value.recording)
        } finally {
            automation.executeShellCommand("input keyevent 224").close()
            automation.executeShellCommand("wm dismiss-keyguard").close()
        }
        Thread.sleep(1000)
        val savedBeforeDisconnect = receiver.state.value.savedClips
        val field = sender.javaClass.getDeclaredField("peer").apply { isAccessible = true }
        (field.get(sender) as Peer).close()
        compose.waitUntil(10000) { !receiver.state.value.recording }
        compose.waitUntil(25000) { receiver.state.value.stage == Stage.CONNECTED && !receiver.state.value.videoStalled }
        assertFalse("Reconnect must never resume recording", receiver.state.value.recording)
        compose.waitUntil(10000) { receiver.state.value.savedClips > savedBeforeDisconnect && !receiver.state.value.saving }
        screenshot("reconnected")
        assertPlayableSilentVideo(app, receiver.state.value.lastClip!!)
        receiver.end()
        compose.waitUntil(10000) { receiver.state.value.stage == Stage.HOME }
        assertFalse(receiver.state.value.recording)
    }

    @Test fun tlsRejectsAnUnpinnedCertificate() {
        val transport = SecureTransport()
        val server = transport.server()
        val executor = Executors.newSingleThreadExecutor()
        val accepted = executor.submit { runCatching { (server.accept() as javax.net.ssl.SSLSocket).use { it.soTimeout = 4000; it.startHandshake() } } }
        try {
            val wrong = Pairing(listOf("127.0.0.1"), server.localPort, "a".repeat(64), "0".repeat(64))
            assertThrows(Exception::class.java) { SecureTransport.connect(wrong, "127.0.0.1") }
            accepted.get(8, TimeUnit.SECONDS)
        } finally { server.close(); executor.shutdownNow() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(app.getExternalFilesDir(null), "test-evidence").apply { mkdirs() }
        Thread.sleep(250) // Allow the AndroidView and system compositor to present the last UI frame.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun findVideoTexture(view: View): VideoTexture? {
        if (view is VideoTexture) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findVideoTexture(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun assertPlayableSilentVideo(context: Context, uri: Uri) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
            assertTrue(format.getLong(MediaFormat.KEY_DURATION) > 500000)
            extractor.selectTrack(0)
            codec = MediaCodec.createDecoderByType("video/avc").apply { configure(format, null, null, 0); start() }
            val info = MediaCodec.BufferInfo()
            var decoded = false
            repeat(200) {
                val index = codec.dequeueInputBuffer(10000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val count = extractor.readSampleData(buffer, 0)
                    codec.queueInputBuffer(index, 0, count.coerceAtLeast(0), extractor.sampleTime.coerceAtLeast(0), if (count < 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    if (count >= 0) extractor.advance()
                }
                val output = codec.dequeueOutputBuffer(info, 10000)
                if (output >= 0) { decoded = decoded || info.size > 0; codec.releaseOutputBuffer(output, false) }
                if (decoded) return
            }
            assertTrue("Saved H.264 MP4 must produce a decoded frame", decoded)
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }
}
