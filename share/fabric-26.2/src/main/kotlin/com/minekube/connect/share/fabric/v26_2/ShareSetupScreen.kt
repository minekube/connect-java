package com.minekube.connect.share.fabric.v26_2

import com.minekube.connect.share.ShareGameMode
import com.minekube.connect.share.fabric.ConnectShareClient
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class ShareSetupScreen(
    private val parent: Screen,
) : Screen(Component.translatable("connect_share.setup.title")) {
    private val viewModel = ConnectShareClient.viewModel()
    private var startButton: Button? = null

    override fun init() {
        val current = viewModel.state.value
        minecraft.singleplayerServer?.let { server ->
            viewModel.setGameMode(server.defaultGameType.toShareGameMode())
            viewModel.setAllowCheats(server.worldData.isAllowCommands)
        }

        addRenderableWidget(centered(title, 32))
        addRenderableWidget(
            centered(
                Component.translatable("connect_share.setup.description"),
                52,
            ),
        )
        addRenderableWidget(
            CycleButton.builder(
                { mode: ShareGameMode ->
                    Component.translatable("connect_share.game_mode.${mode.name.lowercase()}")
                },
                current.options.gameMode,
            ).withValues(ShareGameMode.entries)
                .create(
                    width / 2 - 155,
                    78,
                    150,
                    20,
                    Component.translatable("selectWorld.gameMode"),
                ) { _, mode -> viewModel.setGameMode(mode) },
        )
        addRenderableWidget(
            CycleButton.onOffBuilder(current.options.allowCheats)
                .create(
                    width / 2 + 5,
                    78,
                    150,
                    20,
                    Component.translatable("selectWorld.allowCommands"),
                ) { _, allowed -> viewModel.setAllowCheats(allowed) },
        )
        addRenderableWidget(
            CycleButton.builder(
                { guests: Int -> Component.literal(guests.toString()) },
                current.options.maxGuests,
            ).withValues((1..16).toList())
                .create(
                    width / 2 - 75,
                    110,
                    150,
                    20,
                    Component.translatable("connect_share.setup.max_guests"),
                ) { _, guests -> viewModel.setMaxGuests(guests) },
        )
        startButton = addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.setup.start"),
            ) {
                viewModel.start()
                minecraft.gui.setScreen(ShareStatusScreen(parent))
            }.bounds(width / 2 - 155, height - 28, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(width / 2 + 5, height - 28, 150, 20)
                .build(),
        )
        refresh()
    }

    override fun tick() {
        super.tick()
        refresh()
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private fun refresh() {
        startButton?.active = viewModel.state.value.startEnabled
    }

    private fun centered(message: Component, y: Int): StringWidget {
        val textWidth = font.width(message)
        return StringWidget(width / 2 - textWidth / 2, y, textWidth, 9, message, font)
    }
}

private fun net.minecraft.world.level.GameType.toShareGameMode(): ShareGameMode = when (this) {
    net.minecraft.world.level.GameType.SURVIVAL -> ShareGameMode.SURVIVAL
    net.minecraft.world.level.GameType.CREATIVE -> ShareGameMode.CREATIVE
    net.minecraft.world.level.GameType.ADVENTURE -> ShareGameMode.ADVENTURE
    net.minecraft.world.level.GameType.SPECTATOR -> ShareGameMode.SPECTATOR
}
