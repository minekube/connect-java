package com.minekube.connect.share.forge.v1_20_1

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import net.minecraftforge.fml.util.thread.SidedThreadGroups

internal object ForgeGatewayThreadFactory : ThreadFactory {
    private val threadNumber = AtomicInteger()

    override fun newThread(task: Runnable): Thread =
        SidedThreadGroups.SERVER.newThread(task).apply {
            name = "Connect Share Forge gateway-${threadNumber.incrementAndGet()}"
            isDaemon = true
        }
}
