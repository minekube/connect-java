package com.minekube.connect.share.friend

import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.direct.DirectSessionAttributes
import com.minekube.connect.tunnel.p2p.DirectP2pRoute
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference

data class FriendControlContext(
    val ingress: Ingress,
    val directPeerId: String?,
)

fun interface FriendControlServer {
    fun handle(
        context: FriendControlContext,
        request: FriendControlRequest,
    ): CompletionStage<FriendControlResponse>
}

class FriendControlChannelHandler(
    private val server: FriendControlServer,
) : ChannelInboundHandlerAdapter() {
    private val buffered = ByteArrayOutputStream()
    private val response =
        AtomicReference<CompletionStage<FriendControlResponse>?>(null)
    private var controlHandshake = false
    private var passedThrough = false

    override fun channelRead(
        context: ChannelHandlerContext,
        message: Any,
    ) {
        if (passedThrough || message !is ByteBuf) {
            context.fireChannelRead(message)
            return
        }
        try {
            val bytes = ByteArray(message.readableBytes())
            message.readBytes(bytes)
            buffered.write(bytes)
        } finally {
            ReferenceCountUtil.release(message)
        }
        if (buffered.size() > FriendControlWire.MAX_REQUEST_BYTES) {
            context.close()
            return
        }

        val accumulated = buffered.toByteArray()
        if (!controlHandshake) {
            when (
                val inspected =
                    FriendControlWire.inspectControlRequest(accumulated)
            ) {
                FriendControlDecode.Incomplete -> return
                FriendControlDecode.Invalid -> {
                    context.close()
                    return
                }

                is FriendControlDecode.Decoded -> {
                    if (!inspected.value) {
                        passThrough(context, accumulated)
                        return
                    }
                    controlHandshake = true
                }
            }
        }

        when (val decoded = FriendControlWire.decodeRequest(accumulated)) {
            FriendControlDecode.Incomplete -> Unit
            FriendControlDecode.Invalid -> context.close()
            is FriendControlDecode.Decoded -> {
                if (decoded.consumedBytes != accumulated.size) {
                    context.close()
                    return
                }
                beginRequest(context, decoded.value)
            }
        }
    }

    override fun channelInactive(context: ChannelHandlerContext) {
        response.getAndSet(null)?.toCompletableFuture()?.cancel(true)
        context.fireChannelInactive()
    }

    override fun exceptionCaught(
        context: ChannelHandlerContext,
        cause: Throwable,
    ) {
        context.close()
    }

    private fun beginRequest(
        context: ChannelHandlerContext,
        request: FriendControlRequest,
    ) {
        if (response.get() != null) {
            context.close()
            return
        }
        writeResponse(context, FriendControlResponse.Received)
        val pending = server.handle(context.controlContext(), request)
        if (!response.compareAndSet(null, pending)) {
            pending.toCompletableFuture().cancel(true)
            context.close()
            return
        }
        pending.whenComplete { answer, failure ->
            context.executor().execute {
                if (!context.channel().isOpen) {
                    return@execute
                }
                val safeAnswer = if (failure == null && answer != null) {
                    answer
                } else {
                    FriendControlResponse.Invalid
                }
                val bytes = FriendControlWire.encodeResponse(safeAnswer)
                context.writeAndFlush(Unpooled.wrappedBuffer(bytes))
                    .addListener(ChannelFutureListener.CLOSE)
            }
        }
    }

    private fun passThrough(
        context: ChannelHandlerContext,
        bytes: ByteArray,
    ) {
        passedThrough = true
        context.pipeline().remove(this)
        context.fireChannelRead(Unpooled.wrappedBuffer(bytes))
    }

    private fun writeResponse(
        context: ChannelHandlerContext,
        value: FriendControlResponse,
    ) {
        context.writeAndFlush(
            Unpooled.wrappedBuffer(
                FriendControlWire.encodeResponse(value),
            ),
        )
    }

    private fun ChannelHandlerContext.controlContext(): FriendControlContext {
        val direct = channel()
            .attr(DirectSessionAttributes.SESSION)
            .get()
        val ingress = when (direct?.route()) {
            DirectP2pRoute.LAN -> Ingress.DIRECT_LAN
            DirectP2pRoute.INTERNET -> Ingress.DIRECT_INTERNET
            null -> Ingress.CONNECT
        }
        return FriendControlContext(
            ingress = ingress,
            directPeerId = direct?.peerId(),
        )
    }
}
