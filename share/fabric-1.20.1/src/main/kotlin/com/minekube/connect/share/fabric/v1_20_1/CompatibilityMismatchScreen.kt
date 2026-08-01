package com.minekube.connect.share.fabric.v1_20_1

import com.minekube.connect.share.fabric.FriendJoinAttemptFailure
import com.minekube.connect.share.friend.CompatibilityDifference
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class CompatibilityMismatchScreen(
    private val parent: Screen,
    private val failure: FriendJoinAttemptFailure.Compatibility,
    private val tryAnyway: () -> Unit,
) : Screen(Component.translatable("connect_share.compatibility.title")) {
    private var packCopied = false

    override fun init() {
        addRenderableWidget(
            MultiLineTextWidget(
                width / 2 - 155,
                20,
                title,
                font,
            ).setMaxWidth(310).setCentered(true),
        )
        addRenderableWidget(
            MultiLineTextWidget(
                width / 2 - 155,
                48,
                Component.literal(failure.safeMessage),
                font,
            ).setMaxWidth(310).setCentered(true),
        )
        addRenderableWidget(
            MultiLineTextWidget(
                width / 2 - 155,
                78,
                Component.literal(details()),
                font,
            ).setMaxWidth(310),
        )
        failure.report.pack?.let { pack ->
            addRenderableWidget(
                Button.builder(
                    Component.translatable(
                        if (packCopied) {
                            "connect_share.compatibility.pack_copied"
                        } else {
                            "connect_share.compatibility.copy_pack"
                        },
                    ),
                ) {
                    minecraft!!.keyboardHandler.setClipboard(pack.url)
                    packCopied = true
                    rebuildWidgets()
                }.bounds(width / 2 - 155, height - 76, 310, 20).build(),
            )
        }
        if (failure.canTryAnyway) {
            addRenderableWidget(
                Button.builder(
                    Component.translatable(
                        "connect_share.compatibility.try_anyway",
                    ),
                ) {
                    minecraft!!.setScreen(parent)
                    tryAnyway()
                }.bounds(width / 2 - 155, height - 52, 150, 20).build(),
            )
        }
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(
                    if (failure.canTryAnyway) width / 2 + 5 else width / 2 - 75,
                    height - 52,
                    150,
                    20,
                ).build(),
        )
    }

    override fun onClose() {
        minecraft!!.setScreen(parent)
    }

    private fun details(): String = failure.report.differences
        .take(MAX_VISIBLE_DIFFERENCES)
        .joinToString("\n") { difference ->
            when (difference) {
                is CompatibilityDifference.MinecraftVersion ->
                    "Minecraft: you ${difference.local}, host ${difference.remote}"
                is CompatibilityDifference.Loader ->
                    "Loader: you ${difference.local.name.lowercase()}, " +
                        "host ${difference.remote.name.lowercase()}"
                is CompatibilityDifference.MissingLocal ->
                    "Install ${difference.modId} ${difference.remoteVersion}"
                is CompatibilityDifference.MissingRemote ->
                    "Host is missing ${difference.modId} ${difference.localVersion}"
                is CompatibilityDifference.ModVersion ->
                    "${difference.modId}: you ${difference.local}, " +
                        "host ${difference.remote}"
            }
        }

    private companion object {
        const val MAX_VISIBLE_DIFFERENCES = 5
    }
}
