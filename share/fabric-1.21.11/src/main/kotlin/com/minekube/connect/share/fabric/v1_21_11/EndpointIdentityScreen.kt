package com.minekube.connect.share.fabric.v1_21_11

import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.ui.AdaptiveShareLayout
import com.minekube.connect.share.identity.CredentialSource
import java.nio.file.Path
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import org.lwjgl.util.tinyfd.TinyFileDialogs

class EndpointIdentityScreen(
    private val parent: Screen,
) : Screen(Component.translatable("connect_share.identity.title")) {
    private val viewModel = ConnectShareClient.viewModel()
    private var fingerprint = 0
    private var endpointBox: EditBox? = null
    private var tokenBox: EditBox? = null

    override fun init() {
        val state = viewModel.state.value
        fingerprint = state.copy(
            importDraft = state.importDraft.copy(token = ""),
        ).hashCode()
        val layout = AdaptiveShareLayout.form(width, height, 2)

        addRenderableWidget(
            centered(title.copy().withStyle(ChatFormatting.BOLD), layout.headerY),
        )
        addRenderableWidget(
            MultiLineTextWidget(
                layout.contentX,
                layout.subtitleY,
                Component.translatable("connect_share.identity.description"),
                font,
            ).setMaxWidth(layout.contentWidth).setCentered(true),
        )

        addRenderableWidget(
            StringWidget(
                layout.contentX,
                layout.bodyTop,
                layout.contentWidth,
                11,
                Component.translatable("connect_share.identity.endpoint"),
                font,
            ),
        )
        endpointBox = EditBox(
            font,
            layout.contentX,
            layout.bodyTop + 12,
            layout.contentWidth,
            20,
            Component.translatable("connect_share.identity.endpoint"),
        ).also { box ->
            box.value = state.importDraft.endpoint
            box.setHint(Component.translatable("connect_share.identity.endpoint_hint"))
            box.setResponder(viewModel::setImportEndpoint)
            box.setEditable(state.importDraft.endpointEditable)
            addRenderableWidget(box)
        }

        addRenderableWidget(
            StringWidget(
                layout.contentX,
                layout.bodyTop + 38,
                layout.contentWidth,
                11,
                Component.translatable("connect_share.identity.token"),
                font,
            ),
        )
        tokenBox = EditBox(
            font,
            layout.contentX,
            layout.bodyTop + 50,
            layout.contentWidth,
            20,
            Component.translatable("connect_share.identity.token"),
        ).also { box ->
            box.value = state.importDraft.token
            box.setHint(Component.translatable("connect_share.identity.token_hint"))
            box.setResponder(viewModel::setImportToken)
            box.addFormatter { text, _ ->
                FormattedCharSequence.forward(
                    "•".repeat(text.length),
                    Style.EMPTY,
                )
            }
            box.setEditable(state.importDraft.tokenEditable)
            addRenderableWidget(box)
        }

        val identity = state.identity
        addRenderableWidget(
            MultiLineTextWidget(
                layout.contentX,
                layout.bodyTop + 78,
                Component.translatable(
                    "connect_share.identity.sources",
                    identity?.endpointSource?.displayName() ?: "…",
                    identity?.tokenSource?.displayName() ?: "…",
                ).withStyle(ChatFormatting.GRAY),
                font,
            ).setMaxWidth(layout.contentWidth).setCentered(true),
        )
        state.safeMessage?.let { safeMessage ->
            addRenderableWidget(
                MultiLineTextWidget(
                    layout.contentX,
                    layout.bodyTop + 104,
                    Component.literal(safeMessage)
                        .withStyle(ChatFormatting.YELLOW),
                    font,
                ).setMaxWidth(layout.contentWidth).setCentered(true),
            )
        }

        val save = addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.identity.save"),
            ) {
                viewModel.importIdentity()
            }.bounds(
                layout.contentX,
                layout.footerTop,
                layout.halfButtonWidth,
                20,
            ).build(),
        )
        val choose = addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.identity.choose_file"),
            ) {
                chooseTokenFile()?.let(viewModel::importTokenFile)
            }.bounds(
                layout.contentX + layout.halfButtonWidth + 6,
                layout.footerTop,
                layout.halfButtonWidth,
                20,
            ).build(),
        )
        save.active = state.importDraft.endpointEditable &&
            state.importDraft.tokenEditable &&
            !state.operationInProgress
        choose.active = state.importDraft.endpointEditable &&
            state.importDraft.tokenEditable &&
            !state.operationInProgress

        val reset = addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.identity.reset"),
            ) {
                confirmReset()
            }.bounds(
                layout.contentX,
                layout.footerTop + 24,
                layout.halfButtonWidth,
                20,
            ).build(),
        )
        reset.active = state.importDraft.endpointEditable &&
            state.importDraft.tokenEditable &&
            !state.operationInProgress
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(
                    layout.contentX + layout.halfButtonWidth + 6,
                    layout.footerTop + 24,
                    layout.halfButtonWidth,
                    20,
                ).build(),
        )
    }

    override fun tick() {
        super.tick()
        val state = viewModel.state.value
        val next = state.copy(
            importDraft = state.importDraft.copy(token = ""),
        ).hashCode()
        if (
            next != fingerprint ||
            state.importDraft.token.isEmpty() &&
            tokenBox?.value?.isNotEmpty() == true
        ) {
            rebuildWidgets()
        }
    }

    override fun onClose() {
        viewModel.setImportToken("")
        minecraft.setScreen(parent)
    }

    private fun confirmReset() {
        minecraft.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) {
                        viewModel.resetIdentity()
                    }
                    minecraft.setScreen(this)
                },
                Component.translatable(
                    "connect_share.identity.reset_confirm.title",
                ),
                Component.translatable(
                    "connect_share.identity.reset_confirm.message",
                ),
            ),
        )
    }

    private fun chooseTokenFile(): Path? {
        val selected = TinyFileDialogs.tinyfd_openFileDialog(
            Component.translatable("connect_share.identity.choose_file").string,
            null,
            null,
            "token.json",
            false,
        ) ?: return null
        return Path.of(selected)
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

private fun CredentialSource.displayName(): Component =
    Component.translatable(
        "connect_share.identity.source.${name.lowercase()}",
    )
