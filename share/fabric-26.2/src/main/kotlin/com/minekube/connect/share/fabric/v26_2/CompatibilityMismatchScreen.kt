package com.minekube.connect.share.fabric.v26_2

import com.minekube.connect.share.fabric.FriendJoinAttemptFailure
import com.minekube.connect.share.fabric.ui.AdaptiveShareLayout
import com.minekube.connect.share.fabric.ui.presentation
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
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
        val layout = AdaptiveShareLayout.form(width, height, 1)
        addRenderableWidget(
            centered(title.copy().withStyle(ChatFormatting.YELLOW), layout.headerY),
        )
        addRenderableWidget(
            MultiLineTextWidget(
                layout.contentX,
                layout.subtitleY,
                Component.translatable("connect_share.compatibility.description"),
                font,
            ).setMaxWidth(layout.contentWidth).setCentered(true),
        )

        val visibleDifferences = ((layout.availableBodyHeight - 24) / 15)
            .coerceIn(1, 5)
        failure.report.differences
            .take(visibleDifferences)
            .forEachIndexed { index, difference ->
                val line = difference.presentation()
                addRenderableWidget(
                    StringWidget(
                        layout.contentX,
                        layout.bodyTop + 12 + index * 15,
                        layout.contentWidth,
                        11,
                        Component.translatable(
                            line.translationKey,
                            *line.arguments.toTypedArray(),
                        ),
                        font,
                    ).setMaxWidth(layout.contentWidth),
                )
            }
        val hidden = failure.report.differences.size - visibleDifferences
        if (hidden > 0) {
            addRenderableWidget(
                centered(
                    Component.translatable(
                        "connect_share.compatibility.more",
                        hidden,
                    ).withStyle(ChatFormatting.GRAY),
                    layout.bodyTop + 12 + visibleDifferences * 15,
                ),
            )
        }

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
                    minecraft.keyboardHandler.setClipboard(pack.url)
                    packCopied = true
                    rebuildWidgets()
                }.bounds(
                    layout.contentX,
                    layout.footerTop,
                    layout.contentWidth,
                    20,
                ).build(),
            )
        }
        val actionY = layout.footerTop + 24
        if (failure.canTryAnyway) {
            addRenderableWidget(
                Button.builder(
                    Component.translatable(
                        "connect_share.compatibility.try_anyway",
                    ),
                ) {
                    minecraft.gui.setScreen(parent)
                    tryAnyway()
                }.bounds(
                    layout.contentX,
                    actionY,
                    layout.halfButtonWidth,
                    20,
                ).build(),
            )
        }
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(
                    if (failure.canTryAnyway) {
                        layout.contentX + layout.halfButtonWidth + 6
                    } else {
                        layout.contentX
                    },
                    actionY,
                    if (failure.canTryAnyway) {
                        layout.halfButtonWidth
                    } else {
                        layout.contentWidth
                    },
                    20,
                ).build(),
        )
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private fun centered(message: Component, y: Int): StringWidget {
        val textWidth = font.width(message)
        return StringWidget(
            width / 2 - textWidth / 2,
            y,
            textWidth,
            11,
            message,
            font,
        )
    }
}
