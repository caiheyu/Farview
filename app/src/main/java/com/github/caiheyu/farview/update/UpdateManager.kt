package com.github.caiheyu.farview.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.github.caiheyu.farview.BuildConfig
import com.github.caiheyu.farview.network.toHex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

data class Release(val versionName: String, val versionCode: Long, val minSdk: Int, val sha256: String,
    val signerSha256: String, val publishedAt: String, val notes: String, val apkUrl: String)
enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, LATEST, UNPUBLISHED, INCOMPATIBLE, FAILED, DOWNLOADING, VERIFYING, READY }
data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val release: Release? = null,
    val progress: Float = 0f, val error: String = "", val file: File? = null)

class UpdateManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(3, TimeUnit.MINUTES).build()
    private val allowedHosts = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    fun check() {
        job?.cancel()
        mutableState.value = UpdateState(UpdatePhase.CHECKING)
        job = scope.launch {
            try {
                val releaseJson = response("https://api.github.com/repos/caiheyu/Farview/releases/latest").use { r ->
                    if (r.code == 404) { mutableState.value = UpdateState(UpdatePhase.UNPUBLISHED); return@launch }
                    check(r.isSuccessful) { "GitHub 请求失败（HTTP ${r.code}）" }
                    JSONObject(readLimited(r, 262144))
                }
                val assets = releaseJson.getJSONArray("assets")
                fun asset(name: String): String? = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == name }?.getString("browser_download_url")
                val manifestUrl = asset("update.json")
                val apkUrl = asset("farview.apk")
                if (manifestUrl == null || apkUrl == null) { mutableState.value = UpdateState(UpdatePhase.UNPUBLISHED); return@launch }
                val tag = releaseJson.getString("tag_name")
                val expectedPath = "/caiheyu/Farview/releases/download/$tag/"
                for (url in listOf(manifestUrl, apkUrl)) {
                    val parsed = java.net.URI(url)
                    require(parsed.scheme == "https" && parsed.host == "github.com" && parsed.path.startsWith(expectedPath)) { "发布附件地址无效" }
                }
                val j = response(manifestUrl).use { r ->
                    check(r.isSuccessful) { "读取更新清单失败（HTTP ${r.code}）" }
                    JSONObject(readLimited(r, 65536))
                }
                require(j.getInt("schemaVersion") == 1) { "更新清单格式不受支持" }
                val release = Release(j.getString("versionName"), j.getLong("versionCode"), j.getInt("minSdk"),
                    j.getString("apkSha256"), j.getString("signerCertificateSha256"), j.getString("publishedAt"), j.getString("releaseNotes"), apkUrl)
                require(release.versionCode in 1..Int.MAX_VALUE.toLong() && release.versionName.length in 1..80 &&
                    release.minSdk in 26..100 && release.notes.length <= 4000 && release.sha256.matches(Regex("[0-9a-f]{64}")) &&
                    release.signerSha256.matches(Regex("[0-9a-f]{64}"))) { "更新清单内容无效" }
                java.time.Instant.parse(release.publishedAt)
                ensureActive()
                mutableState.value = UpdateState(when {
                    release.versionCode <= BuildConfig.VERSION_CODE -> UpdatePhase.LATEST
                    release.minSdk > Build.VERSION.SDK_INT -> UpdatePhase.INCOMPATIBLE
                    else -> UpdatePhase.AVAILABLE
                }, release)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ensureActive(); mutableState.value = UpdateState(UpdatePhase.FAILED, error = e.message ?: "检查失败，请联网后重试") }
        }
    }

    fun download() {
        val release = state.value.release ?: return
        job?.cancel()
        mutableState.value = UpdateState(UpdatePhase.DOWNLOADING, release)
        job = scope.launch {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val part = File(dir, "farview-${java.util.UUID.randomUUID()}.part")
            try {
                response(release.apkUrl).use { r ->
                    check(r.isSuccessful) { "下载失败（HTTP ${r.code}）" }
                    val body = r.body ?: error("下载内容为空")
                    val length = body.contentLength()
                    require(length <= 150L * 1024 * 1024) { "安装包超过 150 MB" }
                    body.byteStream().use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(32768); var total = 0L
                        while (true) {
                            ensureActive(); val count = input.read(buffer); if (count < 0) break
                            total += count; check(total <= 150L * 1024 * 1024) { "安装包过大" }
                            output.write(buffer, 0, count)
                            mutableState.value = UpdateState(UpdatePhase.DOWNLOADING, release, if (length > 0) total.toFloat() / length else 0f)
                        }
                        check(total > 0) { "安装包为空" }; output.fd.sync()
                    } }
                }
                ensureActive(); mutableState.value = UpdateState(UpdatePhase.VERIFYING, release)
                verify(part, release)
                ensureActive()
                val final = File(dir, "farview.apk")
                if (final.exists()) check(final.delete())
                check(part.renameTo(final)) { "无法保存校验后的安装包" }
                mutableState.value = UpdateState(UpdatePhase.READY, release, 1f, file = final)
            } catch (e: CancellationException) { part.delete(); throw e }
            catch (e: Exception) { part.delete(); ensureActive(); mutableState.value = UpdateState(UpdatePhase.FAILED, release, error = e.message ?: "下载或校验失败") }
        }
    }

    private fun verify(file: File, release: Release) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32768); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        require(digest.digest().toHex() == release.sha256) { "安装包摘要校验失败，已拒绝安装" }
        val pm = context.packageManager
        @Suppress("DEPRECATION") val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION") val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("安装包无法解析")
        @Suppress("DEPRECATION") val installed = pm.getPackageInfo(context.packageName, flags)
        @Suppress("DEPRECATION") val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(archive.packageName == context.packageName && version == release.versionCode && archive.versionName == release.versionName &&
            (archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "安装包包名、版本或系统要求不匹配" }
        @Suppress("DEPRECATION") fun signers(info: android.content.pm.PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures).orEmpty()
                .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet()
        val theirs = signers(archive)
        require(theirs.size == 1 && theirs == signers(installed) && release.signerSha256 in theirs) { "签名证书与当前应用不一致，已拒绝安装" }
    }

    fun installIntent(): Intent {
        val s = state.value
        check(s.phase == UpdatePhase.READY && s.file?.isFile == true) { "请先下载并校验安装包" }
        return Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.files", s.file!!),
            "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fun cancel() { job?.cancel(); job = null; mutableState.value = state.value.copy(phase = if (state.value.release != null) UpdatePhase.AVAILABLE else UpdatePhase.IDLE, progress = 0f) }

    private suspend fun response(url: String): Response {
        var current = url
        repeat(5) { hop ->
            val request = Request.Builder().url(current).header("User-Agent", "Farview/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.github+json").build()
            require(request.url.scheme == "https" && request.url.host in allowedHosts && request.url.username.isEmpty() && request.url.password.isEmpty()) { "更新地址不受信任" }
            val call = client.newCall(request)
            val r = suspendCancellableCoroutine<Response> { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) { cont.resume(response) { _, value, _ -> value.close() } }
                })
            }
            if (r.code in listOf(301, 302, 303, 307, 308)) {
                val next = r.header("Location")?.let { r.request.url.resolve(it) }; r.close()
                require(hop < 4 && next != null) { "更新重定向无效或过多" }; current = next.toString()
            } else return r
        }
        error("更新重定向失败")
    }
    private suspend fun readLimited(response: Response, max: Int): String {
        val body = response.body ?: error("服务器返回空内容")
        require(body.contentLength() <= max) { "服务器响应过大" }
        val output = java.io.ByteArrayOutputStream()
        body.byteStream().use { input -> val buffer = ByteArray(8192); while (true) {
            currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break
            require(output.size() + n <= max) { "服务器响应过大" }; output.write(buffer, 0, n)
        } }
        return output.toString("UTF-8")
    }

    companion object { const val REPOSITORY = "https://github.com/caiheyu/Farview" }
}
