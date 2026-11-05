package com.github.caiheyu.farview.session

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.ContextCompat
import com.github.caiheyu.farview.camera.*
import com.github.caiheyu.farview.media.VideoRecorder
import com.github.caiheyu.farview.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class SessionManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = 20)
    val events = mutableEvents.asSharedFlow()
    @Volatile private var camera: CameraEngine? = null
    @Volatile private var peer: Peer? = null
    @Volatile private var server: SSLServerSocket? = null
    @Volatile private var format: VideoFormat? = null
    @Volatile private var clientJob: Job? = null
    private var serverJob: Job? = null
    private var run = AtomicInteger(0)
    @Volatile private var token: String? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastRtt = 0L
    @Volatile private var congestedAt = 0L
    @Volatile private var cameraTexture: SurfaceTexture? = null
    @Volatile private var viewerOutput: Surface? = null
    private var lastBadQrHash = 0
    private var lastBadQrAt = 0L
    private val commands = ConcurrentHashMap<Int, Long>()
    private val commandSequence = AtomicInteger(0)
    private val latestZoomCommand = AtomicInteger(0)
    private val qualityPolicy = QualityPolicy()
    @Volatile private var changingQuality = false
    private val recorder: VideoRecorder = VideoRecorder(context,
        onStatus = { recording, waiting -> mutableState.update { it.copy(recording = recording, waitingForKeyframe = waiting,
            recordingAt = if (recording && !it.recording) System.currentTimeMillis() else it.recordingAt) } },
        onMessage = ::message,
        onSaved = { uri -> mutableState.update { it.copy(lastClip = uri, savedClips = it.savedClips + 1) } },
        onSaving = { saving -> mutableState.update { it.copy(saving = saving) }
            scope.launch { mutableState.update { it.copy(pendingRecordings = recorder.pendingCount()) } }
        })
    val decoder = VideoDecoder(::message) { sendKeyframe() }

    init {
        scope.launch { mutableState.update { it.copy(pendingRecordings = recorder.pendingCount()) } }
        scope.launch { while (isActive) { delay(1000); tick() } }
    }

    fun message(text: String) { android.util.Log.i("FarviewEvent", text); mutableEvents.tryEmit(text) }
    fun dismissError() { mutableState.update { it.copy(error = null) } }

    fun chooseCamera() {
        if (state.value.hasSession) return
        mutableState.update { it.copy(role = Role.CAMERA, stage = Stage.PREPARING, error = null) }
        try { ContextCompat.startForegroundService(context, Intent(context, CaptureService::class.java).setAction("camera")) }
        catch (e: Exception) { fail("无法启动拍摄服务：${e.message}") }
    }
    fun chooseViewer() { if (!state.value.hasSession) mutableState.update { it.copy(role = Role.VIEWER, stage = Stage.SCAN, error = null) } }
    fun home() { if (state.value.hasSession) end() else mutableState.update { it.copy(role = null, stage = Stage.HOME) } }

    fun scanned(text: String): Boolean {
        if (state.value.stage != Stage.SCAN) return false
        return try {
            val pairing = Pairing.parse(text)
            mutableState.update { it.copy(pairing = pairing, stage = Stage.CONNECTING, error = null) }
            ContextCompat.startForegroundService(context, Intent(context, CaptureService::class.java).setAction("viewer").putExtra("pairing", pairing.encode()))
            true
        } catch (e: Exception) {
            if (lastBadQrHash != text.hashCode() || System.currentTimeMillis() - lastBadQrAt > 3000) {
                lastBadQrHash = text.hashCode(); lastBadQrAt = System.currentTimeMillis(); message(e.message ?: "配对码无效")
            }
            false
        }
    }

    fun beginCamera() { scope.launch { mutex.withLock { if (camera == null) prepareCamera() } } }
    fun refreshPairing() {
        if (state.value.stage != Stage.WAITING) return
        scope.launch { mutex.withLock { cleanup(); prepareCamera() } }
    }
    private fun prepareCamera() {
        val id = run.incrementAndGet()
        try {
            mutableState.update { it.copy(role = Role.CAMERA, stage = Stage.PREPARING, pairing = null, error = null) }
            val hosts = Pairing.localAddresses()
            check(hosts.isNotEmpty()) { "未找到热点或局域网地址。请先开启热点，或连接另一台手机的热点后重试。" }
            val tls = SecureTransport()
            val socket = tls.server(); server = socket
            val secret = Pairing.newToken(); token = secret
            val engine = CameraEngine(context,
                onFormat = { f -> if (id == run.get()) {
                    format = f
                    peer?.resetVideo()
                    peer?.send(Packet.text(Packet.FORMAT, f.encode()))
                    mutableState.update { it.copy(videoWidth = f.width, videoHeight = f.height, rotation = f.rotation) }
                } },
                onFrame = { frame -> if (id == run.get()) { lastFrameAt = System.currentTimeMillis(); peer?.video(frame) } },
                onState = { if (id == run.get()) publishCameraState() },
                onError = { error -> if (id == run.get()) fail(error) })
            camera = engine
            qualityPolicy.reset(SystemClock.elapsedRealtime())
            mutableState.update { it.copy(stage = Stage.WAITING, pairing = Pairing(hosts, socket.localPort, secret, tls.fingerprint),
                lenses = engine.lenses, lensId = engine.lens.id, zoom = engine.zoom, torch = false, quality = engine.quality, qualityMode = "auto", videoStalled = true) }
            engine.start()
            serverJob = scope.launch { accept(socket, id, secret) }
        } catch (e: Exception) {
            cleanup()
            mutableState.update { it.copy(role = null, stage = Stage.HOME, error = e.message ?: "无法准备相机") }
            stopService()
        }
    }

    private fun accept(socket: SSLServerSocket, id: Int, secret: String) {
        while (id == run.get() && !socket.isClosed) {
            val client = try { socket.accept() as SSLSocket } catch (_: Exception) { break }
            try {
                client.soTimeout = 5000
                client.startHandshake()
                val hello = Packet.read(DataInputStream(client.inputStream))
                val j = JSONObject(hello.text())
                val valid = hello.type == Packet.HELLO && j.optInt("v") == 1 && MessageDigest.isEqual(
                    j.optString("token").toByteArray(), secret.toByteArray())
                if (!valid || peer != null || id != run.get()) {
                    Packet.write(DataOutputStream(client.outputStream), Packet.text(Packet.ERROR, if (!valid) "配对码已失效，请重新扫码" else "拍摄端已有观看连接"))
                    client.close(); continue
                }
                installPeer(client, id, Role.CAMERA)
                qualityPolicy.reset(SystemClock.elapsedRealtime())
                publishCameraState()
                format?.let { peer?.send(Packet.text(Packet.FORMAT, it.encode())) }
                camera?.keyframe()
            } catch (e: Exception) { android.util.Log.w("Farview", "Incoming TLS session failed", e); runCatching { client.close() } }
        }
    }

    fun beginViewer(text: String) {
        if (clientJob?.isActive == true) return
        clientJob = scope.launch {
            val pairing = try { Pairing.parse(text) } catch (e: Exception) { fail(e.message ?: "无效的配对码"); return@launch }
            val id = run.incrementAndGet()
            var attempt = 0
            while (isActive && run.get() == id) {
                if (peer == null) {
                    attempt++
                    mutableState.update { it.copy(stage = if (it.connectedAt == 0L) Stage.CONNECTING else Stage.RECONNECTING, reconnectAttempt = attempt, pendingCommand = false) }
                    var connected = false
                    for (host in pairing.hosts) {
                        if (id != run.get()) break
                        var socket: SSLSocket? = null
                        try {
                            socket = SecureTransport.connect(pairing, host)
                            Packet.write(DataOutputStream(socket.outputStream), Packet.text(Packet.HELLO, JSONObject().put("v", 1).put("token", pairing.token).toString()))
                            if (id != run.get()) { socket.close(); break }
                            installPeer(socket, id, Role.VIEWER)
                            connected = true; break
                        } catch (e: Exception) { android.util.Log.w("Farview", "Local connection failed", e); runCatching { socket?.close() } }
                    }
                    if (!connected && attempt == 3 && isActive && run.get() == id && !state.value.ending) message("尚未连通，请确认两台手机在同一热点，且热点允许设备互访。仍在重试。")
                }
                delay(if (peer == null) (attempt * 700L).coerceAtMost(5000) else 500)
            }
        }
    }

    private fun installPeer(socket: SSLSocket, id: Int, role: Role) {
        lateinit var current: Peer
        current = Peer(socket,
            onPacket = { packet -> if (run.get() == id && peer === current) receive(packet, role) },
            onClosed = { error -> if (run.get() == id && peer === current && !state.value.ending) {
                if (error != null) android.util.Log.w("Farview", "Local session interrupted", error)
                peer = null; commands.clear()
                recorder.stop("连接中断，当前录像已结束；重连后不会自动续录")
                decoder.clear()
                mutableState.update { it.copy(stage = Stage.RECONNECTING, videoStalled = true, pendingCommand = false, recording = false) }
            } },
            requestKeyframe = { camera?.keyframe() },
            onCongestion = { congestedAt = System.currentTimeMillis(); qualityPolicy.congestion(SystemClock.elapsedRealtime()) })
        peer = current
        lastFrameAt = 0; lastRtt = 0
        if (role == Role.CAMERA) mutableState.update { it.copy(stage = Stage.CONNECTED, connectedAt = System.currentTimeMillis(), videoStalled = false) }
        current.start()
    }

    private fun receive(packet: Packet, role: Role) {
        when (packet.type) {
            Packet.PING -> peer?.send(Packet(Packet.PONG, packet.bytes))
            Packet.PONG -> lastRtt = (SystemClock.elapsedRealtime() - packet.text().toLong()).coerceIn(0, 60000)
            Packet.END -> { message("对方已结束连接"); end(notifyPeer = false) }
            Packet.ERROR -> { fail(packet.text().take(200)) }
            Packet.STATE -> if (role == Role.VIEWER) {
                val j = JSONObject(packet.text())
                val list = j.getJSONArray("lenses")
                require(list.length() in 1..32)
                val lenses = (0 until list.length()).map { Lens.parse(list.getJSONObject(it)) }
                require(lenses.all { it.zoomMin.isFinite() && it.zoomMax.isFinite() && it.zoomMin > 0 && it.zoomMax <= 200 && it.zoomMin <= it.zoomMax })
                val ack = j.optInt("ack")
                commands.remove(ack)
                val error = j.optString("error")
                if (error.isNotBlank()) message(error)
                mutableState.update { it.copy(role = Role.VIEWER, stage = Stage.CONNECTED, lenses = lenses,
                    lensId = j.getString("lens"), zoom = j.getDouble("zoom").toFloat(), torch = j.getBoolean("torch"),
                    quality = j.getInt("quality"), qualityMode = j.getString("mode"),
                    pendingCommand = commands.isNotEmpty(), connectedAt = if (it.connectedAt == 0L) System.currentTimeMillis() else it.connectedAt) }
            }
            Packet.COMMAND -> if (role == Role.CAMERA) executeCommand(JSONObject(packet.text()))
            Packet.FORMAT -> if (role == Role.VIEWER) {
                val f = VideoFormat.decode(packet.text()); format = f
                recorder.format(f); decoder.configure(f)
                mutableState.update { it.copy(videoWidth = f.width, videoHeight = f.height, rotation = f.rotation) }
            }
            Packet.FRAME -> if (role == Role.VIEWER) {
                val frame = EncodedFrame.parse(packet)
                if (frame.generation == format?.generation) {
                    lastFrameAt = System.currentTimeMillis()
                    recorder.frame(frame); decoder.frame(frame)
                    if (state.value.videoStalled && (!decoder.isAttached ||
                        (decoder.lastRenderedAt > 0 && System.currentTimeMillis() - decoder.lastRenderedAt < 3500))) {
                        mutableState.update { it.copy(videoStalled = false) }
                    }
                }
            }
        }
    }

    private fun executeCommand(j: JSONObject) {
        val engine = camera ?: return
        val ack = j.optInt("id")
        val done: (String?) -> Unit = { error -> publishCameraState(ack, error) }
        when (j.getString("command")) {
            "keyframe" -> engine.keyframe()
            "torch" -> engine.controls(newTorch = j.getBoolean("value"), done = done)
            "zoom" -> engine.controls(newZoom = j.getDouble("value").toFloat(), done = done)
            "lens" -> {
                val next = engine.lenses.find { it.id == j.getString("value") }
                if (next != null && state.value.qualityMode != "auto" && engine.quality !in next.qualities) {
                    done("该镜头不支持当前锁定画质，请先切回自动画质"); return
                }
                engine.changeLens(j.getString("value"), done)
            }
            "quality" -> {
                val mode = j.getString("value")
                if (mode !in listOf("auto", "720", "1080")) { done("无效的画质选项"); return }
                val target = if (mode == "auto") (if (720 in engine.lens.qualities) 720 else engine.quality) else mode.toInt()
                if (target !in engine.lens.qualities) { done("当前镜头不支持该分辨率"); return }
                mutableState.update { it.copy(qualityMode = mode) }
                qualityPolicy.reset(SystemClock.elapsedRealtime())
                engine.changeQuality(target, done)
            }
            else -> done("不支持的远程操作")
        }
    }

    private fun publishCameraState(ack: Int = 0, error: String? = null) {
        val c = camera ?: return
        mutableState.update { it.copy(lenses = c.lenses, lensId = c.lens.id, zoom = c.zoom, torch = c.torch, quality = c.quality) }
        val j = JSONObject().put("lenses", JSONArray(c.lenses.map { it.json() })).put("lens", c.lens.id)
            .put("zoom", c.zoom.toDouble()).put("torch", c.torch).put("quality", c.quality)
            .put("mode", state.value.qualityMode).put("ack", ack).put("error", error ?: "")
        peer?.send(Packet.text(Packet.STATE, j.toString()))
    }

    fun command(name: String, value: Any) {
        if (state.value.stage != Stage.CONNECTED) return
        if (state.value.role == Role.CAMERA) { executeCommand(JSONObject().put("command", name).put("value", value)); return }
        val id = commandSequence.incrementAndGet()
        if (name == "zoom") commands.remove(latestZoomCommand.getAndSet(id))
        commands[id] = System.currentTimeMillis()
        mutableState.update { it.copy(pendingCommand = true) }
        peer?.send(Packet.text(Packet.COMMAND, JSONObject().put("id", id).put("command", name).put("value", value).toString()))
    }
    private fun sendKeyframe() { if (state.value.role == Role.VIEWER) peer?.send(Packet.text(Packet.COMMAND, "{\"command\":\"keyframe\"}")) }

    fun toggleRecording() { scope.launch {
        try {
            if (state.value.recording) recorder.stop()
            else {
                check(state.value.stage == Stage.CONNECTED && !state.value.videoStalled) { "请等待实时画面恢复后再录像" }
                recorder.start(); sendKeyframe()
            }
            mutableState.update { it.copy(pendingRecordings = recorder.pendingCount()) }
        } catch (e: Exception) { message(e.message ?: "无法开始录像") }
    } }
    fun recoverRecordings() { scope.launch {
        val incomplete = recorder.recover()
        mutableState.update { it.copy(pendingRecordings = recorder.pendingCount()) }
        if (incomplete > 0) message("已尝试恢复；$incomplete 个异常中断的片段尚不可播放，原文件已保留")
    } }

    fun cameraPreview(texture: SurfaceTexture?) {
        if (cameraTexture === texture) return
        cameraTexture = texture; camera?.preview(texture)
    }
    fun viewerSurface(surface: Surface?) {
        if (viewerOutput === surface) return
        viewerOutput = surface; decoder.attach(surface)
    }

    private fun tick() {
        val p = peer ?: return
        val now = System.currentTimeMillis()
        if (now - p.lastReceived > 7000 || now - p.lastSent > 7000) { p.close(); return }
        p.send(Packet.text(Packet.PING, SystemClock.elapsedRealtime().toString()))
        val stale = lastFrameAt == 0L || now - lastFrameAt > 3500 ||
            (state.value.role == Role.VIEWER && decoder.isAttached && (decoder.lastRenderedAt == 0L || now - decoder.lastRenderedAt > 3500))
        val weak = lastRtt > 250 || now - congestedAt < 5000
        mutableState.update { it.copy(videoStalled = stale, weakNetwork = weak) }
        val expired = commands.filterValues { now - it > 6000 }.keys
        if (expired.isNotEmpty()) {
            expired.forEach { commands.remove(it) }; mutableState.update { it.copy(pendingCommand = commands.isNotEmpty()) }
            message("设备未确认操作，请等待连接恢复后重试")
        }
        val c = camera
        if (c != null && state.value.qualityMode == "auto" && !changingQuality && !stale) {
            val target = qualityPolicy.target(SystemClock.elapsedRealtime(), c.quality, lastRtt, 1080 in c.lens.qualities)
            if (target != c.quality && target in c.lens.qualities) {
                changingQuality = true
                c.changeQuality(target) { changingQuality = false; publishCameraState(); message("自动画质已调整为 ${target}p") }
            }
        }
    }

    fun end(notifyPeer: Boolean = true) {
        if (state.value.ending) return
        mutableState.update { it.copy(ending = true) }
        scope.launch { mutex.withLock {
            if (notifyPeer) { peer?.send(Packet.text(Packet.END, "ended")); delay(150) }
            cleanup()
            mutableState.update { SessionState(savedClips = it.savedClips, lastClip = it.lastClip, pendingRecordings = recorder.pendingCount()) }
            stopService()
        } }
    }
    fun fail(error: String) {
        scope.launch { mutex.withLock {
            cleanup()
            mutableState.update { SessionState(savedClips = it.savedClips, lastClip = it.lastClip, pendingRecordings = recorder.pendingCount(), error = error) }
            stopService()
        } }
    }
    private fun cleanup() {
        run.incrementAndGet()
        serverJob?.cancel(); serverJob = null
        runCatching { server?.close() }; server = null
        clientJob?.cancel(); clientJob = null
        val previous = peer; peer = null; previous?.close()
        recorder.stop()
        recorder.awaitSaves(); recorder.clearFormat()
        camera?.close(); camera = null; cameraTexture = null
        decoder.clear(); format = null; lastFrameAt = 0; commands.clear(); token = null; changingQuality = false
    }
    private fun stopService() { context.stopService(Intent(context, CaptureService::class.java)) }
    fun serviceDestroyed() { if (state.value.hasSession && !state.value.ending) fail("拍摄服务已停止，请重新连接。已完成的录像片段会保留。") }
}
