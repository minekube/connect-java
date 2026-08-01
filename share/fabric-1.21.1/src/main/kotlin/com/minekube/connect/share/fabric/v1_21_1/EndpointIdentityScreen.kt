package com.minekube.connect.share.fabric.v1_21_1

import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.identity.CredentialSource
import java.nio.file.Path
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
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
        fingerprint = state.copy(importDraft = state.importDraft.copy(token = "")).hashCode()
        addRenderableWidget(centered(title, 18))

        val identity = state.identity
        addRenderableWidget(
            centered(
                Component.translatable(
                    "connect_share.identity.current",
                    identity?.endpoint ?: "…",
                ),
                38,
            ),
        )
        addRenderableWidget(
            centered(
                Component.translatable(
                    "connect_share.identity.sources",
                    identity?.endpointSource?.displayName() ?: "…",
                    identity?.tokenSource?.displayName() ?: "…",
                ),
                52,
            ),
        )

        addRenderableWidget(
            centered(Component.translatable("connect_share.identity.endpoint"), 72),
        )
        endpointBox = EditBox(
            font,
            width / 2 - 100,
            84,
            200,
            20,
            Component.translatable("connect_share.identity.endpoint"),
        ).also { box ->
            box.value = state.importDraft.endpoint
            box.setResponder(viewModel::setImportEndpoint)
            box.setEditable(state.importDraft.endpointEditable)
            addRenderableWidget(box)
        }

        addRenderableWidget(
            centered(Component.translatable("connect_share.identity.token"), 110),
        )
        tokenBox = EditBox(
            font,
            width / 2 - 100,
            122,
            200,
            20,
            Component.translatable("connect_share.identity.token"),
        ).also { box ->
            box.value = state.importDraft.token
            box.setResponder(viewModel::setImportToken)
            box.setFormatter { text, _ ->
                FormattedCharSequence.forward("•".repeat(text.length), Style.EMPTY)
            }
            box.setEditable(state.importDraft.tokenEditable)
            addRenderableWidget(box)
        }

        val save = addRenderableWidget(
            Button.builder(Component.translatable("connect_share.identity.save")) {
                viewModel.importIdentity()
            }.bounds(width / 2 - 155, 150, 150, 20).build(),
        )
        val choose = addRenderableWidget(
            Button.builder(Component.translatable("connect_share.identity.choose_file")) {
                chooseTokenFile()?.let(viewModel::importTokenFile)
            }.bounds(width / 2 + 5, 150, 150, 20).build(),
        )
        save.active = state.importDraft.endpointEditable &&
            state.importDraft.tokenEditable &&
            !state.operationInProgress
        choose.active = state.importDraft.endpointEditable &&
            state.importDraft.tokenEditable &&
            !state.operationInProgress

        val reset = addRenderableWidget(
            Button.builder(Component.translatable("connect_share.identity.reset")) {
                confirmReset()
            }.bounds(width / 2 - 100, 178, 200, 20).build(),
        )
        reset.active = state.importDraft.endpointEditable &&
            state.importDraft.tokenEditable &&
            !state.operationInProgress

        state.safeMessage?.let { safeMessage ->
            addRenderableWidget(centered(Component.literal(safeMessage), 204))
        }
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build(),
        )
    }

    override fun tick() {
        super.tick()
        val state = viewModel.state.value
        val next = state.copy(importDraft = state.importDraft.copy(token = "")).hashCode()
        if (next != fingerprint || state.importDraft.token.isEmpty() && tokenBox?.value?.isNotEmpty() == true) {
            rebuildWidgets()
        }
    }

    override fun onClose() {
        viewModel.setImportToken("")
        minecraft?.setScreen(parent)
    }

    private fun confirmReset() {
        minecraft?.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) {
                        viewModel.resetIdentity()
                    }
                    minecraft?.setScreen(this)
                },
                Component.translatable("connect_share.identity.reset_confirm.title"),
                Component.translatable("connect_share.identity.reset_confirm.message"),
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
        return StringWidget(width / 2 - textWidth / 2, y, textWidth, 9, message, font)
    }
}

private fun CredentialSource.displayName(): String =
    name.lowercase().replaceFirstChar(Char::titlecase)
