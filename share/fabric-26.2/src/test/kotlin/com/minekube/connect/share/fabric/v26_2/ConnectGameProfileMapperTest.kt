package com.minekube.connect.share.fabric.v26_2

import com.minekube.connect.api.player.GameProfile as ConnectGameProfile
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectGameProfileMapperTest {
    @Test
    fun `preserves signed and unsigned profile properties`() {
        val id = UUID.randomUUID()
        val mapped = ConnectGameProfileMapper.toMinecraft(
            ConnectGameProfile(
                "Robin",
                id,
                listOf(
                    ConnectGameProfile.Property("textures", "skin", "signature"),
                    ConnectGameProfile.Property("badge", "value", ""),
                ),
            ),
        ).getOrNull()
        requireNotNull(mapped)

        assertEquals(id, mapped.id())
        assertEquals("Robin", mapped.name())
        val texture = mapped.properties()["textures"].single()
        val badge = mapped.properties()["badge"].single()
        assertTrue(texture.hasSignature())
        assertEquals("signature", texture.signature())
        assertFalse(badge.hasSignature())
    }
}
