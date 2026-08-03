package com.minekube.connect.share

import java.net.SocketAddress

data class LocalShareTarget(
    val address: SocketAddress,
    val directAddress: SocketAddress = address,
    val close: suspend () -> Unit,
)

fun interface MinecraftShareBridge {
    suspend fun open(options: ShareOptions): LocalShareTarget
}
