package com.github.caiheyu.farview.network

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

enum class ConnectStep(val label: String) { NETWORK("连接拍摄端"), TLS("校验加密连接") }
class LocalConnectException(val step: ConnectStep, cause: Exception) : IOException(cause.message, cause)

fun connectionFailure(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    return when {
        causes.any { it is CertificateException } -> "拍摄端证书校验失败，请刷新二维码后重新扫码"
        causes.any { it.message?.contains("EACCES") == true || it.message?.contains("EPERM") == true } ->
            "系统拒绝了网络访问，请检查遥映的 Wi-Fi / 本地网络权限"
        error is LocalConnectException && error.step == ConnectStep.TLS || causes.any { it is SSLException } ->
            "加密握手未完成，请确认两端安装了相同版本，并查看拍摄端连接诊断"
        causes.any { it is SocketTimeoutException } -> "连接拍摄端超时，请保持拍摄端配对页打开并确认热点连接"
        causes.any { it is ConnectException && (it.message?.contains("ECONNREFUSED") == true ||
            it.message?.contains("Connection refused", ignoreCase = true) == true) } ->
            "拍摄端未接受连接，请刷新拍摄端二维码后重新扫码"
        else -> "当前网络无法到达拍摄端，正在尝试其他本地连接路径"
    }
}

/** Android often wraps the useful errno / KeyStore error inside several TLS exceptions. */
fun connectionFailureDetail(error: Throwable): String = generateSequence(error) { it.cause }.take(8)
    .map { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(180)}" }.distinct().joinToString(" / ").take(720)
