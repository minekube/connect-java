package com.minekube.connect.share

import com.minekube.connect.share.identity.EndpointIdentity
import java.net.SocketAddress

data class ConnectShareHandle(
    val endpoint: String,
    val publicAddress: String,
    val close: suspend () -> Unit,
)

fun interface ConnectShareIngress {
    suspend fun start(
        identity: EndpointIdentity,
        target: SocketAddress,
    ): ConnectShareHandle
}
