package com.minekube.connect.share.fabric.v1_20_1

import com.minekube.connect.share.fabric.ConnectShareClient
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class BlockedFriendsScreen(
    private val parent: Screen,
) : Screen(Component.translatable("connect_share.privacy.blocked_title")) {
    override fun init() {
        val friends = ConnectShareClient.friendsViewModel()
        addRenderableWidget(
            StringWidget(
                width / 2 - font.width(title) / 2,
                16,
                font.width(title),
                20,
                title,
                font,
            ),
        )
        friends.state.value.blocked.take(5).forEachIndexed { index, blocked ->
            val y = 48 + index * 26
            addRenderableWidget(
                StringWidget(
                    width / 2 - 155,
                    y,
                    202,
                    20,
                    Component.literal(blocked.displayName),
                    font,
                ),
            )
            addRenderableWidget(
                Button.builder(
                    Component.translatable("connect_share.privacy.unblock"),
                ) {
                    friends.unblock(blocked.peerId)
                    rebuildWidgets()
                }.bounds(width / 2 + 51, y, 104, 20).build(),
            )
        }
        if (friends.state.value.blocked.isEmpty()) {
            val empty = Component.translatable(
                "connect_share.privacy.blocked_empty",
            )
            addRenderableWidget(
                StringWidget(
                    width / 2 - font.width(empty) / 2,
                    70,
                    font.width(empty),
                    20,
                    empty,
                    font,
                ),
            )
        }
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(width / 2 - 75, height - 28, 150, 20).build(),
        )
    }

    override fun onClose() {
        minecraft!!.setScreen(parent)
    }
}
