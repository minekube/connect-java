package com.minekube.connect.share

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildPinsTest {
    @Test
    fun wireProtocolStartsAtOne() {
        assertEquals(1, ShareBuild.WIRE_PROTOCOL)
        assertEquals("connect-share", ShareBuild.MOD_ID)
    }
}
