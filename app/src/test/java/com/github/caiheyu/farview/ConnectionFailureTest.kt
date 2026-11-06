package com.github.caiheyu.farview

import com.github.caiheyu.farview.network.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class ConnectionFailureTest {
    @Test fun networkTimeoutAndHandshakeTimeoutHaveDifferentAdvice() {
        assertTrue(connectionFailure(LocalConnectException(ConnectStep.NETWORK, SocketTimeoutException())).contains("连接拍摄端超时"))
        assertTrue(connectionFailure(LocalConnectException(ConnectStep.TLS, SocketTimeoutException())).contains("加密握手"))
    }

    @Test fun wrappedCertificateFailureRequiresFreshPairing() {
        val handshake = SSLHandshakeException("certificate_unknown").apply { initCause(CertificateException("pin mismatch")) }
        assertTrue(connectionFailure(LocalConnectException(ConnectStep.TLS, handshake)).contains("证书校验失败"))
        assertTrue(connectionFailureDetail(LocalConnectException(ConnectStep.TLS, handshake)).contains("CertificateException: pin mismatch"))
    }

    @Test fun permissionDenialPointsToNetworkPermissions() {
        assertTrue(connectionFailure(LocalConnectException(ConnectStep.NETWORK,
            SocketException("socket failed: EPERM (Operation not permitted)"))).contains("网络权限"))
    }

    @Test fun refusedConnectionPointsToTheCameraPairingPage() {
        assertTrue(connectionFailure(LocalConnectException(ConnectStep.NETWORK,
            ConnectException("connect failed: ECONNREFUSED (Connection refused)"))).contains("未接受连接"))
    }

    @Test fun unreachableNetworkDoesNotClaimThatTheCameraRefusedTheConnection() {
        assertTrue(connectionFailure(LocalConnectException(ConnectStep.NETWORK,
            ConnectException("connect failed: ENETUNREACH (Network is unreachable)"))).contains("无法到达"))
    }
}
