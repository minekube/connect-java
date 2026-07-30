package com.minekube.connect.share

import java.net.SocketAddress

class DirectShareHandle(
    val invitation: String,
    val lanAvailable: Boolean,
    val internetAvailable: Boolean,
    val close: suspend () -> Unit,
) {
    fun copy(
        invitation: String = this.invitation,
        lanAvailable: Boolean = this.lanAvailable,
        internetAvailable: Boolean = this.internetAvailable,
        close: suspend () -> Unit = this.close,
    ) = DirectShareHandle(
        invitation = invitation,
        lanAvailable = lanAvailable,
        internetAvailable = internetAvailable,
        close = close,
    )

    override fun toString(): String =
        "DirectShareHandle(invitation=<redacted>, " +
            "lanAvailable=$lanAvailable, " +
            "internetAvailable=$internetAvailable)"
}

fun interface DirectShareIngress {
    suspend fun start(
        options: ShareOptions,
        target: SocketAddress,
        connectAddress: String?,
    ): DirectShareHandle
}
