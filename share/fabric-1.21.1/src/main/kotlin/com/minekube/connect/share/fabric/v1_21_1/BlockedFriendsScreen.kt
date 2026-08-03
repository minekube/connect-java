package com.minekube.connect.share.fabric.v1_21_1

import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.ui.AdaptiveShareLayout
import com.minekube.connect.share.fabric.ui.page
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class BlockedFriendsScreen(
    private val parent: Screen,
) : Screen(Component.translatable("connect_share.privacy.blocked_title")) {
    private var offset = 0

    override fun init() {
        val friends = ConnectShareClient.friendsViewModel()
        val layout = AdaptiveShareLayout.friends(width, height)
        val blocked = friends.state.value.blocked
        val page = blocked.page(offset, layout.visibleRows)
        offset = page.offset

        addRenderableWidget(
            centered(title.copy().withStyle(ChatFormatting.BOLD), layout.headerY),
        )
        addRenderableWidget(
            MultiLineTextWidget(
                layout.contentX,
                layout.subtitleY,
                Component.translatable(
                    "connect_share.privacy.blocked_description",
                ).withStyle(ChatFormatting.GRAY),
                font,
            ).setMaxWidth(layout.contentWidth).setCentered(true),
        )

        page.items.forEachIndexed { index, identity ->
            val y = layout.rowY(index)
            val buttonWidth = 88
            addRenderableWidget(
                StringWidget(
                    layout.contentX,
                    y,
                    layout.contentWidth - buttonWidth - 6,
                    20,
                    Component.literal(identity.displayName),
                    font,
                ),
            )
            addRenderableWidget(
                Button.builder(
                    Component.translatable("connect_share.privacy.unblock"),
                ) {
                    friends.unblock(identity.peerId)
                    rebuildWidgets()
                }.bounds(
                    layout.contentX + layout.contentWidth - buttonWidth,
                    y,
                    buttonWidth,
                    20,
                ).build(),
            )
        }

        if (blocked.isEmpty()) {
            addRenderableWidget(
                centered(
                    Component.translatable("connect_share.privacy.blocked_empty")
                        .withStyle(ChatFormatting.GRAY),
                    layout.rowsTop + 18,
                ),
            )
        } else {
            addRenderableWidget(
                centered(
                    Component.translatable(
                        "connect_share.friends.page",
                        page.pageNumber,
                        page.pageCount,
                    ).withStyle(ChatFormatting.DARK_GRAY),
                    layout.messageY,
                ),
            )
        }

        val pageButtonWidth = layout.halfButtonWidth
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.page.previous"),
            ) {
                offset = page.previousOffset ?: 0
                rebuildWidgets()
            }.bounds(
                layout.contentX,
                layout.footerTop,
                pageButtonWidth,
                20,
            ).build().apply { active = page.hasPrevious },
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.page.next"),
            ) {
                offset = page.nextOffset ?: page.offset
                rebuildWidgets()
            }.bounds(
                layout.contentX + pageButtonWidth + 6,
                layout.footerTop,
                pageButtonWidth,
                20,
            ).build().apply { active = page.hasNext },
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(
                    layout.contentX,
                    layout.footerTop + 24,
                    layout.contentWidth,
                    20,
                ).build(),
        )
    }

    override fun onClose() {
        minecraft!!.setScreen(parent)
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
