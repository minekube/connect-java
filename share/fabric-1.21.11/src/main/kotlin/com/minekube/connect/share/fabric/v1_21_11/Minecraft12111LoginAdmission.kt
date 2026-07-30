package com.minekube.connect.share.fabric.v1_21_11

import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.fabric.FabricLocalLoginAdmissionGate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference

object Minecraft12111LoginAdmission {
    private val installed = AtomicReference<FabricLocalLoginAdmissionGate?>()

    fun install(gate: FabricLocalLoginAdmissionGate): AutoCloseable {
        check(installed.compareAndSet(null, gate)) {
            "A Minecraft login admission gate is already installed"
        }
        return AutoCloseable {
            if (installed.compareAndSet(gate, null)) {
                gate.stop()
            }
        }
    }

    @JvmStatic
    fun request(
        name: String,
        uuid: UUID,
        connectionId: String,
        minecraftAuthenticated: Boolean,
    ): CompletionStage<AdmissionAnswer> {
        val gate = installed.get()
        if (gate == null) {
            return CompletableFuture.completedFuture(AdmissionAnswer.STOPPED)
        }
        return gate.request(
            name = name,
            uuid = uuid,
            connectionId = connectionId,
            minecraftAuthenticated = minecraftAuthenticated,
        )
    }
}
