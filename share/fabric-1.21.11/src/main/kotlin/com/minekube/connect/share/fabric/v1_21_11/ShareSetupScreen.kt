package com.minekube.connect.share.fabric.v1_21_11

import com.minekube.connect.share.ShareGameMode
import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.ui.AdaptiveShareLayout
import com.minekube.connect.share.fabric.ui.FormScreenLayout
import com.minekube.connect.share.fabric.ui.ShareUiState
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class ShareSetupScreen(
    private val parent: Screen,
) : Screen(Component.translatable("connect_share.setup.title")) {
    private val viewModel = ConnectShareClient.viewModel()
    private var startButton: Button? = null
    private var defaultsLoaded = false
    private var optionsExpanded = false

    override fun init() {
        if (!defaultsLoaded) {
            minecraft.singleplayerServer?.let { server ->
                viewModel.setGameMode(server.defaultGameType.toShareGameMode())
                viewModel.setAllowCheats(server.worldData.isAllowCommands)
            }
            defaultsLoaded = true
        }
        val current = viewModel.state.value
        val layout = AdaptiveShareLayout.form(width, height, 4)

        addRenderableWidget(
            centered(title.copy().withStyle(ChatFormatting.BOLD), layout.headerY),
        )
        addRenderableWidget(
            MultiLineTextWidget(
                layout.contentX,
                layout.subtitleY,
                Component.translatable("connect_share.setup.description"),
                font,
            ).setMaxWidth(layout.contentWidth).setCentered(true),
        )
        addRenderableWidget(
            centered(
                Component.translatable("connect_share.setup.friends_only")
                    .withStyle(ChatFormatting.GREEN),
                layout.bodyTop,
            ),
        )

        if (optionsExpanded) {
            addOptions(layout, current)
        } else {
            addRenderableWidget(
                MultiLineTextWidget(
                    layout.contentX,
                    layout.bodyTop + 28,
                    Component.translatable("connect_share.setup.persistence")
                        .withStyle(ChatFormatting.GRAY),
                    font,
                ).setMaxWidth(layout.contentWidth).setCentered(true),
            )
        }

        startButton = addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.setup.start"),
            ) {
                viewModel.start()
                minecraft.setScreen(ShareStatusScreen(parent))
            }.bounds(
                layout.contentX,
                layout.footerTop,
                layout.contentWidth,
                20,
            ).build(),
        )

        val third = (layout.contentWidth - 12) / 3
        addRenderableWidget(
            Button.builder(
                Component.translatable(
                    if (optionsExpanded) {
                        "connect_share.setup.options.hide"
                    } else {
                        "connect_share.setup.options.show"
                    },
                ),
            ) {
                optionsExpanded = !optionsExpanded
                rebuildWidgets()
            }.bounds(
                layout.contentX,
                layout.footerTop + 24,
                third,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.privacy.title"),
            ) {
                minecraft.setScreen(SharePrivacyScreen(this))
            }.bounds(
                layout.contentX + third + 6,
                layout.footerTop + 24,
                third,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(
                    layout.contentX + (third + 6) * 2,
                    layout.footerTop + 24,
                    third,
                    20,
                ).build(),
        )
        refresh()
    }

    private fun addOptions(
        layout: FormScreenLayout,
        current: ShareUiState,
    ) {
        addRenderableWidget(
            CycleButton.builder(
                { mode: ShareGameMode ->
                    Component.translatable(
                        "connect_share.game_mode.${mode.name.lowercase()}",
                    )
                },
                current.options.gameMode,
            ).withValues(ShareGameMode.entries)
                .create(
                    layout.contentX,
                    layout.bodyTop + 24,
                    layout.halfButtonWidth,
                    20,
                    Component.translatable("selectWorld.gameMode"),
                ) { _, mode -> viewModel.setGameMode(mode) },
        )
        addRenderableWidget(
            CycleButton.onOffBuilder(current.options.allowCheats)
                .create(
                    layout.contentX + layout.halfButtonWidth + 6,
                    layout.bodyTop + 24,
                    layout.halfButtonWidth,
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
                    layout.contentX,
                    layout.bodyTop + 48,
                    layout.contentWidth,
                    20,
                    Component.translatable("connect_share.setup.max_guests"),
                ) { _, guests -> viewModel.setMaxGuests(guests) },
        )
        addRenderableWidget(
            Checkbox.builder(
                Component.translatable("connect_share.setup.internet"),
                font,
            ).pos(layout.contentX, layout.bodyTop + 74)
                .selected(current.options.allowInternetDirect)
                .onValueChange { _, allowed ->
                    viewModel.setAllowInternetDirect(allowed)
                }
                .tooltip(
                    Tooltip.create(
                        Component.translatable(
                            "connect_share.setup.internet.tooltip",
                        ),
                    ),
                )
                .build(),
        )
    }

    override fun tick() {
        super.tick()
        refresh()
    }

    override fun onClose() {
        minecraft.setScreen(parent)
    }

    private fun refresh() {
        startButton?.active = viewModel.state.value.startEnabled
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

private fun net.minecraft.world.level.GameType.toShareGameMode(): ShareGameMode = when (this) {
    net.minecraft.world.level.GameType.SURVIVAL -> ShareGameMode.SURVIVAL
    net.minecraft.world.level.GameType.CREATIVE -> ShareGameMode.CREATIVE
    net.minecraft.world.level.GameType.ADVENTURE -> ShareGameMode.ADVENTURE
    net.minecraft.world.level.GameType.SPECTATOR -> ShareGameMode.SPECTATOR
}
