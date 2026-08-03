package com.minekube.connect.share.fabric.v1_20_1

import com.minekube.connect.share.ShareState
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.AdmissionPurpose
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.ui.AdaptiveShareLayout
import com.minekube.connect.share.fabric.ui.FormScreenLayout
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class ShareStatusScreen(
    private val parent: Screen,
) : Screen(Component.translatable("connect_share.status.title")) {
    private val viewModel = ConnectShareClient.viewModel()
    private var fingerprint: Int = 0
    private var linkCopied = false
    private var showConnectionDetails = false

    override fun init() {
        val state = viewModel.state.value
        fingerprint = state.hashCode()
        val layout = AdaptiveShareLayout.form(width, height, 3)
        val sharing = state.shareState as? ShareState.Sharing

        addRenderableWidget(
            centered(
                title.copy().withStyle(
                    if (sharing != null) {
                        ChatFormatting.GREEN
                    } else {
                        ChatFormatting.YELLOW
                    },
                ),
                layout.headerY,
            ),
        )
        addRenderableWidget(
            centeredWrapped(
                Component.translatable(statusKey(state.shareState))
                    .withStyle(ChatFormatting.GRAY),
                layout.subtitleY,
                layout.contentWidth,
            ),
        )

        var requestHeadingY = layout.bodyTop + 54
        if (sharing != null) {
            val invitation = viewModel.currentInvitation()
            addRenderableWidget(
                Button.builder(
                    Component.translatable(
                        if (linkCopied) {
                            "connect_share.status.friend_link_copied"
                        } else {
                            "connect_share.status.copy_friend_link"
                        },
                    ),
                ) {
                    invitation?.let {
                        minecraft!!.keyboardHandler.setClipboard(it)
                        linkCopied = true
                        rebuildWidgets()
                    }
                }.bounds(
                    layout.contentX,
                    layout.bodyTop,
                    layout.contentWidth,
                    20,
                ).build().apply { active = invitation != null },
            )
            addRenderableWidget(
                Button.builder(
                    Component.translatable(
                        if (showConnectionDetails) {
                            "connect_share.status.connection_details.hide"
                        } else {
                            "connect_share.status.connection_details.show"
                        },
                    ),
                ) {
                    showConnectionDetails = !showConnectionDetails
                    rebuildWidgets()
                }.bounds(
                    layout.contentX,
                    layout.bodyTop + 24,
                    layout.contentWidth,
                    20,
                ).build(),
            )
            if (showConnectionDetails) {
                addConnectionDetails(layout, sharing)
                requestHeadingY = layout.bodyTop + 102
            }
        }

        addRenderableWidget(
            centered(
                Component.translatable("connect_share.status.requests")
                    .withStyle(ChatFormatting.BOLD),
                requestHeadingY,
            ),
        )
        val pending = state.pendingAdmissions
        val rowsTop = requestHeadingY + 14
        val visibleRows = ((layout.footerTop - rowsTop) / 24).coerceIn(1, 3)
        pending.take(visibleRows).forEachIndexed { index, request ->
            val y = rowsTop + index * 24
            val buttonWidth = 54
            val labelWidth = layout.contentWidth - buttonWidth * 2 - 10
            val identity = request.identity
            val ingress = when (identity) {
                is AdmissionIdentity.Authenticated ->
                    identity.ingress
                is AdmissionIdentity.UnverifiedOffline ->
                    identity.ingress
            }
            val label = Component.translatable(
                if (request.purpose == AdmissionPurpose.FRIEND) {
                    "connect_share.status.friend_request"
                } else {
                    "connect_share.status.request"
                },
                identity.name,
                friendlyIngress(ingress),
            )
            addRenderableWidget(
                StringWidget(
                    layout.contentX,
                    y,
                    labelWidth,
                    20,
                    label,
                    font,
                ),
            )
            addRenderableWidget(
                Button.builder(
                    Component.translatable("connect_share.status.allow"),
                ) {
                    viewModel.allow(request.requestId)
                }.bounds(
                    layout.contentX + layout.contentWidth -
                        buttonWidth * 2 - 4,
                    y,
                    buttonWidth,
                    20,
                ).build(),
            )
            addRenderableWidget(
                Button.builder(
                    Component.translatable("connect_share.status.deny"),
                ) {
                    viewModel.deny(request.requestId)
                }.bounds(
                    layout.contentX + layout.contentWidth - buttonWidth,
                    y,
                    buttonWidth,
                    20,
                ).build(),
            )
        }
        if (pending.size > visibleRows) {
            addRenderableWidget(
                centered(
                    Component.translatable(
                        "connect_share.status.more",
                        pending.size - visibleRows,
                    ).withStyle(ChatFormatting.GRAY),
                    rowsTop + visibleRows * 24,
                ),
            )
        } else if (pending.isEmpty()) {
            addRenderableWidget(
                centered(
                    Component.translatable("connect_share.status.waiting")
                        .withStyle(ChatFormatting.GRAY),
                    rowsTop + 4,
                ),
            )
        }

        addFooter(layout)
    }

    private fun addConnectionDetails(
        layout: FormScreenLayout,
        sharing: ShareState.Sharing,
    ) {
        val address = sharing.address
        addRenderableWidget(
            Button.builder(
                Component.translatable(
                    if (address == null) {
                        "connect_share.status.address_unavailable"
                    } else {
                        "connect_share.status.copy_address"
                    },
                ),
            ) {
                address?.let(minecraft!!.keyboardHandler::setClipboard)
            }.bounds(
                layout.contentX,
                layout.bodyTop + 48,
                layout.contentWidth,
                20,
            ).build().apply { active = address != null },
        )
        addRenderableWidget(
            centeredWrapped(
                Component.translatable(
                    "connect_share.status.route_summary",
                    routeLabel(sharing),
                ).withStyle(ChatFormatting.GRAY),
                layout.bodyTop + 74,
                layout.contentWidth,
            ),
        )
    }

    private fun addFooter(layout: FormScreenLayout) {
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.privacy.title"),
            ) {
                minecraft!!.setScreen(SharePrivacyScreen(this))
            }.bounds(
                layout.contentX,
                layout.footerTop,
                layout.halfButtonWidth,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.identity.manage"),
            ) {
                minecraft!!.setScreen(EndpointIdentityScreen(this))
            }.bounds(
                layout.contentX + layout.halfButtonWidth + 6,
                layout.footerTop,
                layout.halfButtonWidth,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.status.stop"),
            ) {
                viewModel.stop()
                minecraft!!.setScreen(parent)
            }.bounds(
                layout.contentX,
                layout.footerTop + 24,
                layout.halfButtonWidth,
                20,
            ).build(),
        )
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
        val next = viewModel.state.value.hashCode()
        if (next != fingerprint) {
            rebuildWidgets()
        }
    }

    override fun onClose() {
        minecraft!!.setScreen(parent)
    }

    private fun routeLabel(sharing: ShareState.Sharing): Component =
        Component.translatable(
            when {
                sharing.internetDirectAvailable ->
                    "connect_share.status.route.internet"
                sharing.lanDirectAvailable && sharing.connectAvailable ->
                    "connect_share.status.route.lan_connect"
                sharing.lanDirectAvailable ->
                    "connect_share.status.route.lan"
                sharing.connectAvailable ->
                    "connect_share.status.route.connect"
                else -> "connect_share.status.route.starting"
            },
        )

    private fun friendlyIngress(ingress: Ingress): Component =
        Component.translatable(
            when (ingress) {
                Ingress.CONNECT -> "connect_share.ingress.online"
                Ingress.DIRECT_LAN -> "connect_share.ingress.nearby"
                Ingress.DIRECT_INTERNET -> "connect_share.ingress.direct"
            },
        )

    private fun statusKey(state: ShareState): String = when (state) {
        ShareState.Idle -> "connect_share.status.idle"
        ShareState.Starting -> "connect_share.status.starting"
        is ShareState.Sharing -> "connect_share.status.ready"
        ShareState.Stopping -> "connect_share.status.stopping"
        is ShareState.Failed -> "connect_share.status.failed"
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

    private fun centeredWrapped(
        message: Component,
        y: Int,
        maxWidth: Int,
    ): MultiLineTextWidget = MultiLineTextWidget(
        width / 2 - maxWidth / 2,
        y,
        message,
        font,
    ).setMaxWidth(maxWidth).setCentered(true)
}
