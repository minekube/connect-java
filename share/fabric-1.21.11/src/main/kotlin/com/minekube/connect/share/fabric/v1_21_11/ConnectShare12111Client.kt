package com.minekube.connect.share.fabric.v1_21_11

import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.FabricLocalLoginAdmission
import com.minekube.connect.share.fabric.FabricLocalLoginAdmissionGate
import com.minekube.connect.share.fabric.FabricShareBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

class ConnectShare12111Client : ClientModInitializer {
    override fun onInitializeClient() {
        val client = Minecraft.getInstance()
        val dispatcher = client.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val installation = FabricShareBootstrap.create(
            scope = scope,
            dataDirectory = FabricLoader.getInstance().configDir
                .resolve("minekube-connect-share"),
            minecraftVersion = SharedConstants.getCurrentVersion().name(),
            worldAvailable = client.hasSingleplayerServer(),
            playerCount = {
                client.singleplayerServer?.playerList?.playerCount ?: 0
            },
            bridgeFactory = { admission, admissionScope ->
                Minecraft12111Bridge {
                    FabricLocalLoginAdmissionGate(
                        admission = FabricLocalLoginAdmission(admission),
                        scope = admissionScope,
                    )
                }
            },
            screens = { parent, active ->
                val parentScreen = parent as Screen
                client.execute {
                    client.setScreen(
                        if (active) {
                            ShareStatusScreen(parentScreen)
                        } else {
                            ShareSetupScreen(parentScreen)
                        },
                    )
                }
            },
        )
        ConnectShareClient.install(installation)

        ClientTickEvents.END_CLIENT_TICK.register { minecraft ->
            ConnectShareClient.integratedWorldChanged(
                minecraft.hasSingleplayerServer(),
                minecraft.singleplayerServer,
            )
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            ConnectShareClient.shutdown()
        }
    }
}
