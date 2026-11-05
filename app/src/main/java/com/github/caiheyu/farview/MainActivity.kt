package com.github.caiheyu.farview

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.github.caiheyu.farview.session.Role
import com.github.caiheyu.farview.ui.FarviewScreen
import com.github.caiheyu.farview.ui.theme.FarviewTheme

class MainActivity : ComponentActivity() {
    private val session get() = (application as FarviewApplication).session
    private var requestedRole: Role? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val role = requestedRole; requestedRole = null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && role != null) enterRole(role)
        else session.message("需要相机权限才能拍摄或扫码。可在系统设置中允许后重试。")
    }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.isNotEmpty() && granted.values.all { it }) session.toggleRecording() else session.message("保存录像到相册需要存储权限")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedRole = savedInstanceState?.getString("requestedRole")?.let { Role.valueOf(it) }
        setContent { FarviewTheme { FarviewScreen(session, ::chooseRole, ::record) } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        requestedRole?.let { outState.putString("requestedRole", it.name) }
        super.onSaveInstanceState(outState)
    }
    private fun chooseRole(role: Role) {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isEmpty()) enterRole(role) else { requestedRole = role; permissions.launch(needed.toTypedArray()) }
    }
    private fun enterRole(role: Role) { if (role == Role.CAMERA) session.chooseCamera() else session.chooseViewer() }
    private fun record() {
        if (Build.VERSION.SDK_INT <= 28 && !session.state.value.recording &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
             ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
            storagePermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        } else session.toggleRecording()
    }
}
