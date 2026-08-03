package com.minekube.connect.share.fabric.v1_20_1

import net.minecraft.client.gui.components.Checkbox
import net.minecraft.network.chat.Component

internal class ObservableCheckbox(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component,
    selected: Boolean,
    private val changed: (Boolean) -> Unit = {},
) : Checkbox(x, y, width, height, message, selected) {
    override fun onPress() {
        super.onPress()
        changed(selected())
    }
}
