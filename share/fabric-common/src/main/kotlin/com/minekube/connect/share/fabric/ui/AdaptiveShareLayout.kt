package com.minekube.connect.share.fabric.ui

data class FriendsScreenLayout(
    val contentX: Int,
    val contentWidth: Int,
    val headerY: Int,
    val subtitleY: Int,
    val rowsTop: Int,
    val rowHeight: Int,
    val rowGap: Int,
    val visibleRows: Int,
    val rowsBottom: Int,
    val messageY: Int,
    val footerTop: Int,
    val footerBottom: Int,
) {
    val halfButtonWidth: Int = (contentWidth - BUTTON_GAP) / 2

    fun rowY(index: Int): Int = rowsTop + index * (rowHeight + rowGap)

    private companion object {
        const val BUTTON_GAP = 6
    }
}

data class FormScreenLayout(
    val contentX: Int,
    val contentWidth: Int,
    val headerY: Int,
    val subtitleY: Int,
    val bodyTop: Int,
    val availableBodyHeight: Int,
    val footerTop: Int,
    val footerBottom: Int,
) {
    val halfButtonWidth: Int = (contentWidth - BUTTON_GAP) / 2

    private companion object {
        const val BUTTON_GAP = 6
    }
}

data class ManageFriendFormLayout(
    val nameLabelY: Int,
    val nameInputY: Int,
    val notifyY: Int,
    val shareWorldsY: Int,
    val accessPolicyY: Int,
    val internetDirectY: Int,
)

object AdaptiveShareLayout {
    const val EDGE_MARGIN: Int = 12
    const val MAX_CONTENT_WIDTH: Int = 360
    const val BUTTON_HEIGHT: Int = 20
    const val BUTTON_GAP: Int = 6
    const val FOOTER_ROW_GAP: Int = 4

    fun friends(
        screenWidth: Int,
        screenHeight: Int,
    ): FriendsScreenLayout {
        val contentWidth = contentWidth(screenWidth)
        val contentX = (screenWidth - contentWidth) / 2
        val footerBottom = screenHeight - EDGE_MARGIN
        val footerTop = footerBottom - BUTTON_HEIGHT * 2 - FOOTER_ROW_GAP
        val messageY = footerTop - 16
        val rowsTop = 56
        val rowHeight = 24
        val rowGap = 4
        val visibleRows = (
            (messageY - rowsTop + rowGap) / (rowHeight + rowGap)
        ).coerceIn(1, 6)
        val rowsBottom = rowsTop + visibleRows * rowHeight +
            (visibleRows - 1) * rowGap
        return FriendsScreenLayout(
            contentX = contentX,
            contentWidth = contentWidth,
            headerY = 14,
            subtitleY = 32,
            rowsTop = rowsTop,
            rowHeight = rowHeight,
            rowGap = rowGap,
            visibleRows = visibleRows,
            rowsBottom = rowsBottom,
            messageY = messageY,
            footerTop = footerTop,
            footerBottom = footerBottom,
        )
    }

    fun form(
        screenWidth: Int,
        screenHeight: Int,
        fieldCount: Int,
    ): FormScreenLayout {
        val contentWidth = contentWidth(screenWidth)
        val contentX = (screenWidth - contentWidth) / 2
        val footerBottom = screenHeight - EDGE_MARGIN
        val footerTop = footerBottom - BUTTON_HEIGHT * 2 - FOOTER_ROW_GAP
        val bodyTop = if (fieldCount >= 5) 52 else 58
        return FormScreenLayout(
            contentX = contentX,
            contentWidth = contentWidth,
            headerY = 14,
            subtitleY = 32,
            bodyTop = bodyTop,
            availableBodyHeight = footerTop - bodyTop,
            footerTop = footerTop,
            footerBottom = footerBottom,
        )
    }

    fun manageFriendForm(bodyTop: Int): ManageFriendFormLayout =
        ManageFriendFormLayout(
            nameLabelY = bodyTop,
            nameInputY = bodyTop + 12,
            notifyY = bodyTop + 34,
            shareWorldsY = bodyTop + 56,
            accessPolicyY = bodyTop + 78,
            internetDirectY = bodyTop + 100,
        )

    private fun contentWidth(screenWidth: Int): Int =
        (screenWidth - EDGE_MARGIN * 2)
            .coerceAtLeast(1)
            .coerceAtMost(MAX_CONTENT_WIDTH)
}
