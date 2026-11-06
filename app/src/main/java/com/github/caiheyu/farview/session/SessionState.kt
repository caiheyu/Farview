package com.github.caiheyu.farview.session

import android.net.Uri
import com.github.caiheyu.farview.camera.Lens
import com.github.caiheyu.farview.network.Pairing

enum class Role { CAMERA, VIEWER }
enum class Stage { HOME, SCAN, PREPARING, WAITING, CONNECTING, CONNECTED, RECONNECTING }
data class SessionState(
    val role: Role? = null, val stage: Stage = Stage.HOME, val pairing: Pairing? = null,
    val lenses: List<Lens> = emptyList(), val lensId: String = "", val zoom: Float = 1f,
    val torch: Boolean = false, val qualityMode: String = "auto", val quality: Int = 720,
    val rotation: Int = 90, val videoWidth: Int = 1280, val videoHeight: Int = 720,
    val connectedAt: Long = 0, val recording: Boolean = false, val waitingForKeyframe: Boolean = false,
    val recordingAt: Long = 0, val savedClips: Int = 0, val lastClip: Uri? = null,
    val pendingCommand: Boolean = false, val videoStalled: Boolean = true, val weakNetwork: Boolean = false,
    val error: String? = null, val reconnectAttempt: Int = 0, val ending: Boolean = false,
    val pendingRecordings: Int = 0, val saving: Boolean = false,
    val connectionStatus: String = "", val connectionIssue: String? = null, val connectionLog: List<String> = emptyList()
) {
    val lens get() = lenses.find { it.id == lensId }
    val hasSession get() = stage !in listOf(Stage.HOME, Stage.SCAN)
}
