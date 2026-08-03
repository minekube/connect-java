package com.minekube.connect.share.forge.v1_20_1

import arrow.core.Either
import arrow.core.getOrElse

internal object ForgeLoginNegotiation {
    fun continueApprovedLogin(listener: Any, accept: Runnable) {
        Either.catch {
            val stateField = listener.javaClass.declaredFields.single { field ->
                field.type.enumConstants
                    ?.map { (it as Enum<*>).name }
                    ?.containsAll(REQUIRED_STATES) == true
            }
            val negotiating = stateField.type.enumConstants
                .single { (it as Enum<*>).name == NEGOTIATING }
            stateField.isAccessible = true
            stateField.set(listener, negotiating)
        }.getOrElse { failure ->
            throw IllegalStateException(
                "Connect Share could not continue Forge login negotiation",
                failure,
            )
        }
    }

    private const val NEGOTIATING = "NEGOTIATING"
    private val REQUIRED_STATES = listOf(
        NEGOTIATING,
        "READY_TO_ACCEPT",
    )
}
