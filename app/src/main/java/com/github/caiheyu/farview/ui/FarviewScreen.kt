package com.github.caiheyu.farview.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.caiheyu.farview.BuildConfig
import com.github.caiheyu.farview.FarviewApplication
import com.github.caiheyu.farview.camera.QrScanner
import com.github.caiheyu.farview.session.*
import com.github.caiheyu.farview.ui.theme.*
import com.github.caiheyu.farview.update.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarviewScreen(session: SessionManager, chooseRole: (Role) -> Unit, record: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as Activity
    val updates = (context.applicationContext as FarviewApplication).updates
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var full by rememberSaveable { mutableStateOf(false) }
    var rotation by rememberSaveable { mutableIntStateOf(0) }
    var black by rememberSaveable { mutableStateOf(false) }
    var blackHelp by remember { mutableStateOf(false) }
    var endConfirm by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(session) { session.events.collect { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) } }
    LaunchedEffect(state.role) { full = false; rotation = 0; black = false }
    DisposableEffect(state.hasSession, state.stage, black, full) {
        val window = activity.window
        if (state.hasSession) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = if (black) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (full || black) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(panel != null || full || black || state.stage != Stage.HOME) {
        when { panel != null -> panel = null; full -> full = false; black -> black = false
            state.hasSession -> endConfirm = true; else -> session.home() }
    }
    Box(Modifier.fillMaxSize().background(if (full || black) Color.Black else Paper)) {
        if (black) {
            Column(Modifier.fillMaxSize().clickable { black = false }.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                FarIcon(Glyph.MOON, modifier = Modifier.size(30.dp), tint = Color(0xFF65705F))
                Spacer(Modifier.height(20.dp)); Text("黑屏拍摄中", color = Color(0xFF899382), fontSize = 18.sp)
                Text("轻触恢复界面\n按电源键可真正熄屏", color = Color(0xFF596151), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().then(if (!full) Modifier.safeDrawingPadding() else Modifier)) {
                when (state.stage) {
                    Stage.HOME -> HomeScreen({ chooseRole(Role.CAMERA) }, { chooseRole(Role.VIEWER) }, { panel = "about" })
                    Stage.SCAN -> {
                        AppHeader("扫描拍摄端", "仅本地", back = session::home, about = { panel = "about" })
                        ScannerScreen(session)
                    }
                    Stage.PREPARING -> {
                        AppHeader("准备拍摄", "仅本地", back = { endConfirm = true }, about = { panel = "about" })
                        CenterStatus("正在准备后置相机", "请先将两台手机连接同一热点")
                    }
                    Stage.WAITING -> {
                        AppHeader("等待观看端", "本地配对", back = { endConfirm = true }, about = { panel = "about" })
                        PairScreen(state, session::refreshPairing) { copyConnectionDiagnostics(context, session) }
                    }
                    Stage.CONNECTING -> {
                        AppHeader("正在连接", "仅本地", back = { endConfirm = true }, about = { panel = "about" })
                        CenterStatus("正在建立本地连接", "请保持拍摄端配对页打开\n第 ${state.reconnectAttempt.coerceAtLeast(1)} 次尝试") {
                            ConnectionDetails(state) { copyConnectionDiagnostics(context, session) }
                        }
                    }
                    Stage.CONNECTED, Stage.RECONNECTING -> {
                        LiveScreen(state, session, full, rotation, { full = !full }, { rotation = (rotation + 90) % 360 },
                            { panel = "quality" }, { panel = "about" }, { endConfirm = true }, { blackHelp = true }, record)
                    }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp))
        if (state.ending) Surface(Modifier.fillMaxSize(), color = Ink.copy(alpha = .84f)) {
            Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp))
                Text("正在保存录像并结束连接", color = Paper, modifier = Modifier.padding(top = 18.dp))
            }
        }
    }
    if (panel != null) ModalBottomSheet(onDismissRequest = { panel = null }, containerColor = Paper) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (panel == "quality") "传输画质" else "关于遥映", fontSize = 22.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { panel = null }) { FarIcon(Glyph.CLOSE, "关闭面板") }
            }
            if (panel == "quality") QualityPanel(state) { session.command("quality", it); panel = null }
            else AboutPanel(state, session, updates)
        }
    }
    if (endConfirm) AlertDialog(onDismissRequest = { endConfirm = false }, title = { Text("结束这次连接？") },
        text = { Text(if (state.recording) "当前正在录像。将先停止并保存这一段录像，再结束连接。下次使用需要重新扫码。" else "结束后返回首页，下次使用需要重新扫码，不会记住本次设备。") },
        confirmButton = { TextButton(onClick = { endConfirm = false; session.end() }) { Text(if (state.recording) "保存并结束" else "结束连接") } },
        dismissButton = { TextButton(onClick = { endConfirm = false }) { Text("继续使用") } })
    if (blackHelp) AlertDialog(onDismissRequest = { blackHelp = false }, title = { Text("让屏幕休息一下") },
        text = { Text("将隐藏预览并降低屏幕亮度，拍摄与传输继续。\n\n需要真正熄屏时，请按手机电源键。系统仍会显示拍摄状态通知；若系统暂停相机，观看端会提示画面中断。\n\nvivo / iQOO 如限制后台运行，请在系统电池设置中允许遥映后台高耗电。") },
        confirmButton = { TextButton(onClick = { blackHelp = false; black = true }) { Text("进入黑屏拍摄") } },
        dismissButton = { TextButton(onClick = { blackHelp = false }) { Text("暂不使用") } })
    state.error?.let { error -> AlertDialog(onDismissRequest = session::dismissError, title = { Text("暂时无法继续") }, text = { Text(error) },
        confirmButton = { TextButton(onClick = session::dismissError) { Text("知道了") } }) }
}

@Composable private fun Logo(size: Int = 34) {
    Box(Modifier.size(size.dp).background(Ink, RoundedCornerShape((size * .29f).dp)), contentAlignment = Alignment.Center) {
        Box(Modifier.size((size * .57f).dp).border(1.dp, Accent, RoundedCornerShape((size * .21f).dp)), contentAlignment = Alignment.Center) {
            Box(Modifier.size((size * .23f).dp).border(1.dp, Accent, CircleShape))
        }
    }
}

@Composable private fun HomeScreen(camera: () -> Unit, viewer: () -> Unit, about: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(); Text("遥映", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(start = 11.dp))
            Text("Farview", fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp).weight(1f))
            TextButton(onClick = about) { Text("关于", color = Sage, fontSize = 12.sp) }
        }
        Text("TWO PHONES. ONE VIEW.", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 44.dp))
        Text("换个角度，", fontSize = 37.sp, fontWeight = FontWeight.Normal, lineHeight = 51.sp, modifier = Modifier.padding(top = 14.dp))
        Text("就看见了。", fontSize = 37.sp, color = Sage, lineHeight = 51.sp)
        Text("一台手机拍摄，另一台实时查看。\n家具背后、狭窄角落，都能换个角度看。", fontSize = 13.sp, lineHeight = 23.sp, color = Muted, modifier = Modifier.padding(top = 17.dp))
        Spacer(Modifier.height(36.dp))
        RoleCard("作为拍摄端", "放到角落，传送眼前的画面", Glyph.CAMERA, true, camera)
        Spacer(Modifier.height(14.dp))
        RoleCard("作为观看端", "实时查看、远程控制与录像", Glyph.MONITOR, false, viewer)
        Spacer(Modifier.weight(1f).heightIn(min = 48.dp))
        Row(Modifier.padding(bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FarIcon(Glyph.WIFI, modifier = Modifier.size(19.dp), tint = Sage)
            Text("先让两台手机连接同一热点\n无需互联网 · 只传画面，不传声音", color = Sage, fontSize = 11.sp, lineHeight = 21.sp)
        }
    }
}

@Composable private fun RoleCard(title: String, subtitle: String, icon: Glyph, accent: Boolean, click: () -> Unit) {
    Surface(onClick = click, shape = RoundedCornerShape(20.dp), color = if (accent) Color(0xFFE7EEDD) else Color(0xFFEFF1E9),
        border = BorderStroke(1.dp, Color(0xFFE0E7D8)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(43.dp).background(if (accent) Color(0xFFD5E3C6) else Color(0xFFDFE6D7), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                FarIcon(icon, modifier = Modifier.size(23.dp), tint = Color(0xFF506448))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 7.dp))
            }
            FarIcon(Glyph.ARROW, modifier = Modifier.size(18.dp), tint = Sage)
        }
    }
}

@Composable private fun AppHeader(title: String, status: String, back: (() -> Unit)? = null, about: () -> Unit, end: (() -> Unit)? = null, warning: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconButton(onClick = back) { FarIcon(Glyph.BACK, "返回") } else Box(Modifier.padding(horizontal = 8.dp)) { Logo(28) }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Surface(color = if (warning) Color(0xFFF2E6D7) else Soft, shape = CircleShape) {
            Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(5.dp).background(if (warning) Color(0xFFAB8143) else Sage, CircleShape))
                Text(status, color = if (warning) Color(0xFF947542) else Sage, fontSize = 10.sp)
            }
        }
        IconButton(onClick = about, modifier = Modifier.size(42.dp)) { FarIcon(Glyph.INFO, "关于遥映", Modifier.size(19.dp), Sage) }
        if (end != null) IconButton(onClick = end, modifier = Modifier.size(40.dp)) { FarIcon(Glyph.CLOSE, "结束连接", Modifier.size(20.dp)) }
    }
    HorizontalDivider(color = Color(0xFFE3E9DA), thickness = .5.dp)
}

@Composable private fun ColumnScope.CenterStatus(title: String, subtitle: String, extra: @Composable ColumnScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().weight(1f).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(32.dp), color = Sage, strokeWidth = 2.dp)
        Text(title, fontSize = 21.sp, modifier = Modifier.padding(top = 26.dp))
        Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 23.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        extra()
    }
}

@Composable private fun ColumnScope.PairScreen(state: SessionState, refresh: () -> Unit, diagnostics: () -> Unit) {
    val text = state.pairing?.encode()
    val bitmap = remember(text) { text?.let {
        val bits = QRCodeWriter().encode(it, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.MARGIN to 2))
        Bitmap.createBitmap(bits.width, bits.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(bits.width * bits.height) { i -> if (bits[i % bits.width, i / bits.width]) android.graphics.Color.rgb(34, 47, 41) else android.graphics.Color.WHITE }, 0, bits.width, 0, 0, bits.width, bits.height)
        }
    } }
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("READY TO SHARE", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 14.dp))
        Text("让另一台手机扫码", fontSize = 25.sp, modifier = Modifier.padding(top = 16.dp))
        Text("在另一台手机选择「观看端」\n扫描下方二维码，建立本地连接", fontSize = 13.sp, lineHeight = 23.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White, border = BorderStroke(1.dp, Soft), modifier = Modifier.padding(top = 30.dp)) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), "本次会话配对二维码", Modifier.padding(12.dp).sizeIn(maxWidth = 270.dp, maxHeight = 270.dp).aspectRatio(1f))
        }
        Text("临时会话 · ${state.pairing?.token?.take(6)?.uppercase() ?: "准备中"}", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 17.dp))
        TextButton(onClick = refresh) { FarIcon(Glyph.REFRESH, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(8.dp)); Text("刷新二维码", fontSize = 12.sp) }
        Text("每次重新配对 · 不记住设备", color = Muted, fontSize = 11.sp)
        ConnectionDetails(state, diagnostics)
        Spacer(Modifier.weight(1f).heightIn(min = 30.dp))
        Hint("画面只在两台手机之间传送", "请确认两台手机在同一热点；刷新后旧码失效。", Glyph.SHIELD)
    }
}

@Composable private fun ConnectionDetails(state: SessionState, copy: () -> Unit) {
    if (state.connectionStatus.isNotBlank()) Text(state.connectionStatus, color = Muted, fontSize = 11.sp,
        lineHeight = 19.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
    state.connectionIssue?.let { Text(it, color = Sage, fontSize = 12.sp, lineHeight = 21.sp,
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)) }
    TextButton(onClick = copy) { Text("复制连接诊断", fontSize = 12.sp) }
}

private fun copyConnectionDiagnostics(context: Context, session: SessionManager) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("遥映连接诊断", session.connectionDiagnostics()))
    session.message("连接诊断已复制")
}

@Composable private fun ColumnScope.ScannerScreen(session: SessionManager) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val life by lifecycle.currentStateFlow.collectAsState()
    val context = LocalContext.current
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("PAIR & CONNECT", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 20.dp))
        Text("对准拍摄端的二维码", fontSize = 24.sp, modifier = Modifier.padding(top = 16.dp))
        Text("找到另一台手机上的配对页面", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        Box(Modifier.padding(top = 30.dp).fillMaxWidth().height(320.dp).clip(RoundedCornerShape(22.dp)).background(Ink), contentAlignment = Alignment.Center) {
            if (life.isAtLeast(Lifecycle.State.RESUMED)) {
                var previewInfo by remember { mutableStateOf<QrScanner.PreviewInfo?>(null) }
                val scanner = remember { QrScanner(context, { session.scanned(it) }, session::message, { previewInfo = it }) }
                DisposableEffect(scanner) { onDispose { scanner.close() } }
                AndroidView(factory = { VideoTexture(it, scanner::start, scanner::stop).apply { cameraSensorOrientation = 0 } },
                    update = { view ->
                        previewInfo?.let { info ->
                            view.imageWidth = info.width; view.imageHeight = info.height
                            view.cameraSensorOrientation = info.sensorOrientation
                        }
                        view.updateTransform()
                    }, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.size(210.dp).border(1.dp, Accent.copy(alpha = .8f), RoundedCornerShape(18.dp)))
            Text("将二维码放入框内 · 自动识别", fontSize = 12.sp, color = Paper, modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp))
        }
        Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
        Hint("两台手机需要连接同一热点", "扫码后自动建立连接，无需互联网。", Glyph.WIFI)
    }
}

@Composable private fun ColumnScope.LiveScreen(state: SessionState, session: SessionManager, full: Boolean, extraRotation: Int,
    toggleFull: () -> Unit, rotate: () -> Unit, quality: () -> Unit, about: () -> Unit, end: () -> Unit, black: () -> Unit, record: () -> Unit) {
    val viewer = state.role == Role.VIEWER
    val context = LocalContext.current
    val connected = state.stage == Stage.CONNECTED
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val life by lifecycle.currentStateFlow.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }
    if (!full) AppHeader(if (viewer) "观看端" else "拍摄端", if (!connected) "重连中" else if (state.weakNetwork) "网络较弱" else "已连接",
        about = about, end = end, warning = !connected || state.weakNetwork)
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val previewHeight = (maxHeight * .48f).coerceIn(210.dp, 390.dp)
        Column(Modifier.fillMaxSize().then(if (viewer && !full) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            Box(Modifier.fillMaxWidth().then(if (full || !viewer) Modifier.weight(1f) else Modifier.height(previewHeight)).background(Color(0xFF111A14))) {
                var output by remember { mutableStateOf<Surface?>(null) }
                AndroidView(factory = { ctx ->
                    VideoTexture(ctx, { texture ->
                        if (viewer) { output = Surface(texture); session.viewerSurface(output) } else session.cameraPreview(texture)
                    }, {
                        if (viewer) { session.viewerSurface(null); output?.release(); output = null } else session.cameraPreview(null)
                    })
                }, update = { view ->
                    view.imageWidth = state.videoWidth; view.imageHeight = state.videoHeight
                    // Camera2 already delivers upright buffers to TextureView previews; only
                    // the viewer side needs the stream rotation applied to decoded video.
                    if (viewer) view.imageRotation = (state.rotation + extraRotation) % 360
                    else { view.cameraSensorOrientation = state.rotation; view.imageRotation = 0 }
                    view.updateTransform()
                    if (viewer) session.viewerSurface(if (life.isAtLeast(Lifecycle.State.RESUMED)) output else null)
                    else session.cameraPreview(if (life.isAtLeast(Lifecycle.State.RESUMED) && view.isAvailable) view.surfaceTexture else null)
                }, modifier = Modifier.fillMaxSize())
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if (connected && !state.videoStalled) Accent else RecordingRed, CircleShape))
                    Text(if (connected && !state.videoStalled) "LIVE  ${duration(now - state.connectedAt)}" else "等待画面", fontSize = 10.sp, color = Paper, modifier = Modifier.padding(start = 7.dp).weight(1f))
                    if (state.recording) Surface(color = Color(0xB84D2828), shape = RoundedCornerShape(8.dp)) {
                        Text("● REC  ${duration(now - state.recordingAt)}", color = Color(0xFFFFA69D), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    PreviewLabel("${state.lens?.name ?: "后置镜头"} · ${"%.1f".format(Locale.US, state.zoom)}×")
                    PreviewLabel("${if (state.qualityMode == "auto") "AUTO · " else ""}${state.quality}p")
                }
                if (!connected || state.videoStalled) Column(Modifier.fillMaxSize().background(Color(0xE61A241E)).padding(30.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    FarIcon(Glyph.WIFI, modifier = Modifier.size(30.dp), tint = Accent)
                    Text(if (!connected) (if (viewer) "连接中断，正在自动重连" else "观看端连接已断开") else "暂未收到新画面", color = Paper, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
                    Text(if (!connected) (if (viewer) "当前画面不可用，远程控制暂不可用\n重连后不会自动继续录像" else "拍摄仍在继续，等待观看端重新连接") else "请确认拍摄端相机仍在运行\n必要时唤醒拍摄手机", color = Color(0xFFA3AE99), fontSize = 12.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                }
                if (full) {
                    OutlinedButton(onClick = toggleFull, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 62.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Paper), border = BorderStroke(1.dp, Sage)) {
                        FarIcon(Glyph.FULLSCREEN, modifier = Modifier.size(16.dp)); Text(" 退出全屏", fontSize = 12.sp)
                    }
                    TextButton(onClick = end, modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 62.dp)) { Text("结束连接", color = Paper, fontSize = 12.sp) }
                }
            }
            if (!full && viewer) ViewerControls(state, session, rotate, quality, toggleFull, record) {
                val intent = state.lastClip?.let { Intent(Intent.ACTION_VIEW).setDataAndType(it, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    ?: Intent(Intent.ACTION_VIEW).setDataAndType(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "vnd.android.cursor.dir/video")
                runCatching { context.startActivity(intent) }.onFailure { session.message("未找到可打开录像的相册应用") }
            }
            if (!full && !viewer) Column(Modifier.padding(24.dp)) {
                Text(if (connected) "画面正在传送" else "保持拍摄，等待重连", fontSize = 20.sp)
                Text("把手机放好，在另一台手机上查看与调整。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 9.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 23.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Summary("后置镜头", state.lens?.name ?: "主摄"); Summary("传输画质", "${state.quality}p"); Summary("音频", "不传声音")
                }
                MainButton("黑屏继续拍摄", black, Glyph.MOON)
                Text("系统持续显示拍摄状态通知 · 真正熄屏请按电源键", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 14.dp))
            }
        }
    }
}

@Composable private fun ViewerControls(state: SessionState, session: SessionManager, rotate: () -> Unit, quality: () -> Unit, full: () -> Unit, record: () -> Unit, gallery: () -> Unit) {
    val enabled = state.stage == Stage.CONNECTED
    val lens = state.lens
    var zoom by remember(state.lensId) { mutableFloatStateOf(state.zoom) }
    var lastZoomSent by remember { mutableLongStateOf(0) }
    LaunchedEffect(state.zoom, state.pendingCommand) { if (!state.pendingCommand) zoom = state.zoom }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 17.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("后置镜头", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.lenses.forEach { l ->
                    val selected = l.id == state.lensId
                    Surface(onClick = { session.command("lens", l.id) }, enabled = enabled && !state.pendingCommand,
                        shape = CircleShape, color = if (selected) Ink else Paper, border = BorderStroke(1.dp, Soft)) {
                        Text(l.name, color = if (selected) Paper else Sage, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("连续变焦", color = Muted, fontSize = 12.sp)
            Text("${"%.1f".format(Locale.US, zoom)}×", color = Ink, fontSize = 13.sp)
        }
        val min = lens?.zoomMin ?: 1f; val max = lens?.zoomMax ?: 1f
        Slider(value = zoom.coerceIn(min, max), onValueChange = { value ->
            zoom = value
            if (System.currentTimeMillis() - lastZoomSent > 140) { lastZoomSent = System.currentTimeMillis(); session.command("zoom", value.toDouble()) }
        }, onValueChangeFinished = { session.command("zoom", zoom.toDouble()) }, valueRange = min..max.coerceAtLeast(min + .001f),
            enabled = enabled && max > min, modifier = Modifier.fillMaxWidth().height(36.dp),
            colors = SliderDefaults.colors(thumbColor = Sage, activeTrackColor = Sage, inactiveTrackColor = Soft))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${"%.1f".format(Locale.US, min)}×", color = Muted, fontSize = 10.sp)
            Text("${"%.1f".format(Locale.US, max)}× · 设备实际范围", color = Muted, fontSize = 10.sp)
        }
        HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 6.dp), color = Soft)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tool(Glyph.FLASH, if (state.torch) "补光开" else "补光关", enabled && lens?.torch == true && !state.pendingCommand, state.torch) { session.command("torch", !state.torch) }
            Tool(Glyph.ROTATE, "旋转", enabled, click = rotate)
            Tool(Glyph.QUALITY, if (state.qualityMode == "auto") "自动画质" else "${state.quality}p", enabled && !state.pendingCommand, click = quality)
            Tool(Glyph.FULLSCREEN, "全屏", true, click = full)
        }
        if (state.pendingCommand) Text("正在等待拍摄端确认操作…", color = Sage, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        if (state.saving) Text("正在保存录像到本机相册…", color = Sage, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        if (state.weakNetwork && state.qualityMode == "1080") Text("网络较弱，建议将画质切回自动", color = Color(0xFFAA7C40), fontSize = 11.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = record, enabled = state.recording || (enabled && !state.videoStalled), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (state.recording) Color(0xFF57332F) else Ink), modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Box(Modifier.size(14.dp).background(RecordingRed, if (state.recording) RoundedCornerShape(3.dp) else CircleShape).border(3.dp, Color.White.copy(alpha = .15f), CircleShape))
            Text(if (state.recording) { if (state.waitingForKeyframe) " 取消录像准备" else " 停止录像" } else " 开始录像", modifier = Modifier.padding(start = 8.dp), fontSize = 15.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("保存到本机相册 · 无声音", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = gallery) { FarIcon(Glyph.GALLERY, modifier = Modifier.size(15.dp), tint = Sage); Text(" 相册${if (state.savedClips > 0) " (${state.savedClips})" else ""}", color = Sage, fontSize = 11.sp) }
        }
    }
}

@Composable private fun RowScope.Tool(icon: Glyph, title: String, enabled: Boolean, active: Boolean = false, click: () -> Unit) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = click).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FarIcon(icon, modifier = Modifier.size(22.dp), tint = if (active) Ink else Sage.copy(alpha = if (enabled) 1f else .35f))
        Text(title, color = if (active) Ink else Sage.copy(alpha = if (enabled) 1f else .35f), fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
    }
}
@Composable private fun PreviewLabel(text: String) { Text(text, color = Paper, fontSize = 10.sp, modifier = Modifier.background(Ink.copy(alpha = .6f), RoundedCornerShape(5.dp)).padding(6.dp)) }
@Composable private fun Summary(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(label, color = Muted, fontSize = 11.sp); Text(value, color = Sage, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp)) } }
@Composable private fun MainButton(text: String, click: () -> Unit, icon: Glyph? = null, enabled: Boolean = true) {
    Button(onClick = click, enabled = enabled, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        if (icon != null) { FarIcon(icon, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(10.dp)) }; Text(text, fontSize = 14.sp)
    }
}
@Composable private fun Hint(title: String, text: String, icon: Glyph) {
    Row(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(14.dp)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        FarIcon(icon, modifier = Modifier.size(20.dp), tint = Sage)
        Column { Text(title, color = Sage, fontSize = 12.sp, fontWeight = FontWeight.Medium); Text(text, color = Muted, fontSize = 11.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}
private fun duration(milliseconds: Long): String { val total = (milliseconds.coerceAtLeast(0) / 1000); return "%02d:%02d".format(Locale.US, total / 60, total % 60) }

@Composable private fun QualityPanel(state: SessionState, choose: (String) -> Unit) {
    Text("低延迟优先。录像将保存接收到的画面。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
    listOf("auto" to "自动调整", "720" to "720p", "1080" to "1080p").forEach { (value, title) ->
        val supported = value == "auto" || value.toIntOrNull() in state.lens?.qualities.orEmpty()
        Surface(onClick = { choose(value) }, enabled = supported, color = if (state.qualityMode == value) Soft else Paper, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.padding(vertical = 14.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.qualityMode == value, onClick = null, enabled = supported)
                Column(Modifier.padding(start = 13.dp)) {
                    Text(title, fontSize = 15.sp, color = if (supported) Ink else Muted)
                    Text(if (!supported) "当前镜头不支持" else if (value == "auto") "720p 起步，连接稳定时尝试 1080p" else "手动锁定 · ${if (value == "720") "优先流畅" else "优先清晰"}", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
    Text("切换画质或镜头时，正在进行的录像会自动分段保存。手动锁定时不会自行改变分辨率。", color = Muted, fontSize = 11.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 17.dp))
}

@Composable private fun AboutPanel(state: SessionState, session: SessionManager, updates: UpdateManager) {
    val context = LocalContext.current
    val update by updates.state.collectAsStateWithLifecycle()
    fun open(intent: Intent) { runCatching { context.startActivity(intent) }.onFailure { session.message("无法打开：${it.message}") } }
    Row(Modifier.fillMaxWidth().padding(vertical = 23.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(46)
        Column(Modifier.padding(start = 13.dp).weight(1f)) { Text("遥映 Farview", fontSize = 17.sp, fontWeight = FontWeight.Medium); Text("一道延伸的视线。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
        Text("v${BuildConfig.VERSION_NAME}", color = Sage, fontSize = 13.sp)
    }
    HorizontalDivider(color = Soft)
    AboutRow(Glyph.SHIELD, "GitHub 开源项目", "github.com/caiheyu/Farview") { open(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManager.REPOSITORY))) }
    HorizontalDivider(color = Soft)
    AboutRow(Glyph.REFRESH, "检查更新", "手动检查 GitHub Release") { updates.check() }
    HorizontalDivider(color = Soft)
    if (state.hasSession || state.connectionLog.isNotEmpty()) AboutRow(Glyph.WIFI, "连接诊断", "复制本机网络与最近连接结果") {
        copyConnectionDiagnostics(context, session)
    }
    if (state.pendingRecordings > 0) AboutRow(Glyph.GALLERY, "恢复未保存的录像", "${state.pendingRecordings} 个文件保留在本机，点击重试") { session.recoverRecordings() }
    if (update.phase != UpdatePhase.IDLE) {
        Spacer(Modifier.height(14.dp))
        val title = when (update.phase) {
            UpdatePhase.CHECKING -> "正在检查新版本"; UpdatePhase.AVAILABLE -> "发现新版本 ${update.release?.versionName}"
            UpdatePhase.LATEST -> "当前已是最新版本"; UpdatePhase.UNPUBLISHED -> "尚无可用更新发布"
            UpdatePhase.INCOMPATIBLE -> "新版本不兼容当前系统"; UpdatePhase.FAILED -> "更新未能完成"
            UpdatePhase.DOWNLOADING -> "正在下载 ${(update.progress * 100).toInt()}%"; UpdatePhase.VERIFYING -> "正在校验安装包"
            UpdatePhase.READY -> "安装包已准备好"; else -> ""
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        if (update.phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) {
            if (update.phase == UpdatePhase.DOWNLOADING && update.progress > 0) LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth().padding(top = 17.dp))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 17.dp))
            TextButton(onClick = updates::cancel) { Text("取消") }
        }
        if (update.phase in listOf(UpdatePhase.AVAILABLE, UpdatePhase.READY)) {
            Text("${update.release?.publishedAt?.take(10)}\n${update.release?.notes}", color = Muted, fontSize = 12.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 14.dp))
            if (update.phase == UpdatePhase.AVAILABLE) MainButton("下载并校验", updates::download)
            else {
                val active = state.hasSession || state.recording || state.ending || state.saving
                if (active) Text("请先结束当前拍摄或观看连接，再安装更新。", color = Sage, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
                MainButton(if (active) "请先结束当前连接" else "安装更新", {
                    if (session.state.value.hasSession || session.state.value.recording || session.state.value.saving || session.state.value.ending) { session.message("请先结束当前连接并等待录像保存"); return@MainButton }
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                        session.message("允许此来源安装应用后，请返回并再次点击安装更新")
                    } else runCatching { open(updates.installIntent()) }.onFailure { session.message(it.message ?: "无法安装") }
                }, enabled = !active)
            }
        }
        val detail = when (update.phase) {
            UpdatePhase.FAILED -> update.error
            UpdatePhase.UNPUBLISHED -> "需要在正式 Release 中同时发布 farview.apk 和 update.json。"
            UpdatePhase.INCOMPATIBLE -> "新版本最低要求 Android API ${update.release?.minSdk}，当前应用仍可正常使用。"
            UpdatePhase.LATEST -> "已成功读取发布清单并比较版本号。"
            else -> ""
        }
        if (detail.isNotBlank()) Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 15.dp))
    }
    Spacer(Modifier.height(22.dp))
    Hint("拍摄、观看与录像无需互联网", "仅打开 GitHub、检查或下载更新时需要联网。", Glyph.WIFI)
    Text("本机：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nAndroid ${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}", color = Muted, fontSize = 10.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 18.dp))
}

@Composable private fun AboutRow(icon: Glyph, title: String, subtitle: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(Soft, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { FarIcon(icon, modifier = Modifier.size(19.dp), tint = Sage) }
        Column(Modifier.weight(1f).padding(start = 14.dp)) { Text(title, fontSize = 14.sp); Text(subtitle, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp)) }
        FarIcon(Glyph.ARROW, modifier = Modifier.size(16.dp), tint = Sage)
    }
}
