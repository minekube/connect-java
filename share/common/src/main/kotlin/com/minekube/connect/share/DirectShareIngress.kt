package com.minekube.connect.share

import java.net.SocketAddress

class DirectShareHandle(
    private val invitationProvider: () -> String,
    val lanAvailable: Boolean,
    val internetAvailable: Boolean,
    val close: suspend () -> Unit,
) {
    constructor(
        invitation: String,
        lanAvailable: Boolean,
        internetAvailable: Boolean,
        close: suspend () -> Unit,
    ) : this(
        invitationProvider = { invitation },
        lanAvailable = lanAvailable,
        internetAvailable = internetAvailable,
        close = close,
    )

    val invitation: String
        get() = invitationProvider()

    fun copy(
        invitation: String? = null,
        lanAvailable: Boolean = this.lanAvailable,
        internetAvailable: Boolean = this.internetAvailable,
        close: suspend () -> Unit = this.close,
    ) = DirectShareHandle(
        invitationProvider = invitation?.let { value -> { value } }
            ?: invitationProvider,
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
