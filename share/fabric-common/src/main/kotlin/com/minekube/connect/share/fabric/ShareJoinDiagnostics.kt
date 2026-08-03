package com.minekube.connect.share.fabric

import java.time.Instant
import java.util.ArrayDeque

enum class JoinStage {
    COMPATIBILITY,
    FRIEND_CONTROL,
    APPROVAL,
    DIRECT,
    CONNECT_FALLBACK,
    MINECRAFT_LOGIN,
}

enum class JoinOutcome {
    STARTED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class JoinDiagnosticEvent(
    val at: Instant,
    val stage: JoinStage,
    val outcome: JoinOutcome,
)

class ShareJoinDiagnostics(
    private val now: () -> Instant = Instant::now,
) {
    private val events = ArrayDeque<JoinDiagnosticEvent>()

    @Synchronized
    fun record(stage: JoinStage, outcome: JoinOutcome) {
        while (events.size >= MAX_EVENTS) {
            events.removeFirst()
        }
        events.addLast(JoinDiagnosticEvent(now(), stage, outcome))
    }

    @Synchronized
    fun bundle(
        minecraftVersion: String,
        modVersion: String,
    ): String = buildString {
        appendLine("Connect Share diagnostic bundle")
        appendLine("Minecraft: ${minecraftVersion.safeField()}")
        appendLine("Connect Share: ${modVersion.safeField()}")
        appendLine("Generated: ${now()}")
        appendLine("Events (oldest first):")
        events.forEach { event ->
            appendLine("${event.at}: ${event.stage}: ${event.outcome}")
        }
        append("No addresses, names, invitations, tokens, or keys are included.")
    }

    private fun String.safeField(): String = filter {
        it.isLetterOrDigit() || it in ".+-_"
    }.take(MAX_FIELD_LENGTH).ifBlank { "unknown" }

    private companion object {
        const val MAX_EVENTS = 50
        const val MAX_FIELD_LENGTH = 64
    }
}
