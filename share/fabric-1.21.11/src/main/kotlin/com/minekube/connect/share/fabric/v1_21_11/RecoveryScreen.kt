package com.minekube.connect.share.fabric.v1_21_11

import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.ui.AdaptiveShareLayout
import com.minekube.connect.share.fabric.ui.ShareUiMessage
import java.nio.file.Path
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import org.lwjgl.util.tinyfd.TinyFileDialogs

class RecoveryScreen(
    private val parent: Screen,
) : Screen(Component.translatable("connect_share.recovery.title")) {
    private val viewModel = ConnectShareClient.recoveryViewModel()
    private var fingerprint = 0
    private var passphraseValue = ""
    private var confirmationValue = ""

    override fun init() {
        val state = viewModel.state.value
        fingerprint = state.hashCode()
        val layout = AdaptiveShareLayout.form(width, height, 2)

        addRenderableWidget(
            centered(title.copy().withStyle(ChatFormatting.BOLD), layout.headerY),
        )
        addRenderableWidget(
            MultiLineTextWidget(
                layout.contentX,
                layout.subtitleY,
                Component.translatable("connect_share.recovery.description"),
                font,
            ).setMaxWidth(layout.contentWidth).setCentered(true),
        )

        addRenderableWidget(
            StringWidget(
                layout.contentX,
                layout.bodyTop,
                layout.contentWidth,
                11,
                Component.translatable("connect_share.recovery.secret"),
                font,
            ),
        )
        secretBox(
            value = passphraseValue,
            y = layout.bodyTop + 12,
            label = "connect_share.recovery.secret",
        ) { passphraseValue = it }

        addRenderableWidget(
            StringWidget(
                layout.contentX,
                layout.bodyTop + 38,
                layout.contentWidth,
                11,
                Component.translatable("connect_share.recovery.secret_confirm"),
                font,
            ),
        )
        secretBox(
            value = confirmationValue,
            y = layout.bodyTop + 50,
            label = "connect_share.recovery.secret_confirm",
        ) { confirmationValue = it }

        val status = state.safeMessage?.component()
            ?: state.summary?.let { summary ->
                Component.translatable(
                    "connect_share.recovery.summary",
                    summary.entryCount,
                    Component.translatable(
                        if (summary.includesPreferences) {
                            "connect_share.recovery.included"
                        } else {
                            "connect_share.recovery.not_included"
                        },
                    ),
                    Component.translatable(
                        if (summary.includesEndpointIdentity) {
                            "connect_share.recovery.included"
                        } else {
                            "connect_share.recovery.not_included"
                        },
                    ),
                )
            }
        status?.let {
            addRenderableWidget(
                MultiLineTextWidget(
                    layout.contentX,
                    layout.bodyTop + 78,
                    it.withStyle(
                        if (state.safeMessage == null) {
                            ChatFormatting.GREEN
                        } else {
                            ChatFormatting.YELLOW
                        },
                    ),
                    font,
                ).setMaxWidth(layout.contentWidth).setCentered(true),
            )
        }
        addRenderableWidget(
            MultiLineTextWidget(
                layout.contentX,
                layout.bodyTop + 106,
                Component.translatable("connect_share.recovery.warning")
                    .withStyle(ChatFormatting.GRAY),
                font,
            ).setMaxWidth(layout.contentWidth).setCentered(true),
        )

        if (state.importConfirmationRequired) {
            addRenderableWidget(
                Button.builder(
                    Component.translatable("connect_share.recovery.restore_confirm"),
                ) {
                    viewModel.confirmImport()
                }.bounds(
                    layout.contentX,
                    layout.footerTop,
                    layout.halfButtonWidth,
                    20,
                ).build(),
            ).active = !state.operationInProgress
            addRenderableWidget(
                Button.builder(CommonComponents.GUI_CANCEL) {
                    viewModel.cancelImport()
                }.bounds(
                    layout.contentX + layout.halfButtonWidth + 6,
                    layout.footerTop,
                    layout.halfButtonWidth,
                    20,
                ).build(),
            ).active = !state.operationInProgress
        } else {
            addRenderableWidget(
                Button.builder(
                    Component.translatable("connect_share.recovery.export"),
                ) {
                    chooseBackupDestination()?.let { target ->
                        val passphrase = passphraseValue.toCharArray()
                        val confirmation = confirmationValue.toCharArray()
                        clearInputs()
                        viewModel.export(target, passphrase, confirmation)
                    }
                }.bounds(
                    layout.contentX,
                    layout.footerTop,
                    layout.halfButtonWidth,
                    20,
                ).build(),
            ).active = !state.operationInProgress
            addRenderableWidget(
                Button.builder(
                    Component.translatable("connect_share.recovery.import"),
                ) {
                    chooseBackupSource()?.let { source ->
                        val passphrase = passphraseValue.toCharArray()
                        clearInputs()
                        viewModel.previewImport(source, passphrase)
                    }
                }.bounds(
                    layout.contentX + layout.halfButtonWidth + 6,
                    layout.footerTop,
                    layout.halfButtonWidth,
                    20,
                ).build(),
            ).active = !state.operationInProgress
        }

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

    override fun tick() {
        super.tick()
        if (viewModel.state.value.hashCode() != fingerprint) {
            rebuildWidgets()
        }
    }

    override fun onClose() {
        clearInputs()
        viewModel.close()
        minecraft.setScreen(parent)
    }

    private fun secretBox(
        value: String,
        y: Int,
        label: String,
        changed: (String) -> Unit,
    ) {
        val layout = AdaptiveShareLayout.form(width, height, 2)
        addRenderableWidget(
            EditBox(
                font,
                layout.contentX,
                y,
                layout.contentWidth,
                20,
                Component.translatable(label),
            ).also { box ->
                box.value = value
                box.setMaxLength(256)
                box.setResponder(changed)
                box.addFormatter { text, _ ->
                    FormattedCharSequence.forward(
                        "•".repeat(text.length),
                        Style.EMPTY,
                    )
                }
            },
        )
    }

    private fun clearInputs() {
        passphraseValue = ""
        confirmationValue = ""
    }

    private fun chooseBackupDestination(): Path? {
        val selected = TinyFileDialogs.tinyfd_saveFileDialog(
            Component.translatable("connect_share.recovery.export").string,
            "connect-share-friends.backup",
            null,
            "Connect Share backup",
        ) ?: return null
        return Path.of(selected)
    }

    private fun chooseBackupSource(): Path? {
        val selected = TinyFileDialogs.tinyfd_openFileDialog(
            Component.translatable("connect_share.recovery.import").string,
            null,
            null,
            "Connect Share backup",
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

private fun ShareUiMessage.component() =
    Component.translatable(translationKey, *arguments.toTypedArray())

