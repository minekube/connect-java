/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package com.minekube.connect.util;

import static com.minekube.connect.util.ReflectionUtils.castedStaticBooleanValue;
import static com.minekube.connect.util.ReflectionUtils.getBooleanValue;
import static com.minekube.connect.util.ReflectionUtils.getClassSilently;
import static com.minekube.connect.util.ReflectionUtils.getConstructor;
import static com.minekube.connect.util.ReflectionUtils.getField;
import static com.minekube.connect.util.ReflectionUtils.getFieldOfType;
import static com.minekube.connect.util.ReflectionUtils.getMethod;
import static com.minekube.connect.util.ReflectionUtils.getValue;
import static com.minekube.connect.util.ReflectionUtils.invoke;
import static com.minekube.connect.util.ReflectionUtils.makeAccessible;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.util.function.BooleanSupplier;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

@SuppressWarnings("PMD.SystemPrintln")
public class ClassNames {
    public static final Class<?> MINECRAFT_SERVER;
    public static final Class<?> SERVER_CONNECTION;
    public static final Class<?> HANDSHAKE_PACKET;
    public static final Class<?> LOGIN_START_PACKET;
    public static final Class<?> LOGIN_LISTENER;
    @Nullable public static final Class<?> CLIENT_INTENT;

    public static final Constructor<OfflinePlayer> CRAFT_OFFLINE_PLAYER_CONSTRUCTOR;
    @Nullable public static final Constructor<?> LOGIN_HANDLER_CONSTRUCTOR;
    @Nullable public static final Constructor<?> HANDSHAKE_PACKET_CONSTRUCTOR;

    public static final Field SOCKET_ADDRESS;
    public static final Field HANDSHAKE_HOST;
    public static final Field VELOCITY_LOGIN_MESSAGE_ID;
    public static final Field LOGIN_PROFILE;
    public static final Field PACKET_LISTENER;

    @Nullable public static final Field HANDSHAKE_PORT;
    @Nullable public static final Field HANDSHAKE_PROTOCOL;
    @Nullable public static final Field HANDSHAKE_INTENTION;

    @Nullable public static final Field PAPER_DISABLE_USERNAME_VALIDATION;
    @Nullable public static final BooleanSupplier PAPER_VELOCITY_SUPPORT;

    public static final Method GET_PROFILE_METHOD;
    public static final Method LOGIN_DISCONNECT;
    public static final Method NETWORK_EXCEPTION_CAUGHT;
    @Nullable public static final Method INIT_UUID;
    @Nullable public static final Method FIRE_LOGIN_EVENTS;
    @Nullable public static final Method FIRE_LOGIN_EVENTS_GAME_PROFILE;
    @Nullable public static final Method CALL_PLAYER_PRE_LOGIN_EVENTS;
    @Nullable public static final Method START_CLIENT_VERIFICATION;

    public static final Field BUNGEE;

    public static final boolean IS_FOLIA;
    public static final boolean IS_PRE_1_20_2;
    public static final boolean IS_POST_LOGIN_HANDLER;

    static {
        // Every failure below is latched into NmsDiagnostics before it propagates. Once a static
        // initializer throws, the JVM answers each later touch of this class with a
        // NoClassDefFoundError that names only the class, so without the latch the message that
        // names the drifted accessor is reachable only from the very first touch.
        try {
            // ahhhhhhh, this class should really be reworked at this point

            String[] versionSplit = Bukkit.getServer().getClass().getPackage().getName().split("\\.");
            // Paper, since 1.20.5, no longer relocates CraftBukkit classes
            // and NMS classes aren't relocated for a few versions now (both Spigot & Paper)
            if (versionSplit.length <= 3 && getClassSilently("net.minecraft.server.MinecraftServer") == null) {
                throw NmsDiagnostics.missingClass(
                        "MinecraftServer",
                        "net.minecraft.server.MinecraftServer");
            }
            // Makes it that we don't have to lookup both the new and the old
            // 'org.bukkit.craftbukkit. + version + CraftPlayer' will be .CraftPlayer on new
            // versions and .v1_8R3.CraftPlayer on older versions
            String version = versionSplit.length > 3 ? versionSplit[3] + '.' : "";
            String nmsPackage = "net.minecraft.server." + version;

            // SpigotSkinApplier
            Class<?> craftPlayerClass = getRequiredClass(
                    "CraftPlayer",
                    "org.bukkit.craftbukkit." + version + "entity.CraftPlayer");
            GET_PROFILE_METHOD = getMethod(craftPlayerClass, "getProfile");
            checkNotNull(GET_PROFILE_METHOD, "CraftPlayer#getProfile()");

            // SpigotInjector
            MINECRAFT_SERVER = getClassOrFallback(
                    "net.minecraft.server.MinecraftServer",
                    nmsPackage + "MinecraftServer"
            );

            // Paper 1.20.5+ uses Mojang mappings: ServerConnection -> ServerConnectionListener
            SERVER_CONNECTION = getRequiredClass(
                    "ServerConnection/ServerConnectionListener",
                    "net.minecraft.server.network.ServerConnectionListener",
                    "net.minecraft.server.network.ServerConnection",
                    nmsPackage + "ServerConnection");

            // WhitelistUtils
            Class<?> craftServerClass = getRequiredClass(
                    "CraftServer",
                    "org.bukkit.craftbukkit." + version + "CraftServer");
            Class<OfflinePlayer> craftOfflinePlayerClass = getRequiredClass(
                    "CraftOfflinePlayer",
                    "org.bukkit.craftbukkit." + version + "CraftOfflinePlayer");

            CRAFT_OFFLINE_PLAYER_CONSTRUCTOR = getConstructor(
                    craftOfflinePlayerClass, true, craftServerClass, GameProfile.class);

            // SpigotDataHandler
            // Paper 1.20.5+ uses Mojang mappings: NetworkManager -> Connection
            Class<?> networkManager = getRequiredClass(
                    "NetworkManager/Connection",
                    "net.minecraft.network.Connection",
                    "net.minecraft.network.NetworkManager",
                    nmsPackage + "NetworkManager");

            SOCKET_ADDRESS = getFieldOfType(networkManager, SocketAddress.class, false);

            // Paper 1.20.5+ uses Mojang mappings: PacketHandshakingInSetProtocol -> ClientIntentionPacket
            Class<?> handshakePacket = getRequiredClass(
                    "HandshakePacket",
                    "net.minecraft.network.protocol.handshake.ClientIntentionPacket",
                    "net.minecraft.network.protocol.handshake.PacketHandshakingInSetProtocol",
                    nmsPackage + "PacketHandshakingInSetProtocol");
            HANDSHAKE_PACKET = handshakePacket;

            HANDSHAKE_HOST = getFieldOfType(HANDSHAKE_PACKET, String.class);
            checkNotNull(HANDSHAKE_HOST, "HandshakePacket#<String field>");

            // Paper 1.20.5+ uses Mojang mappings: PacketLoginInStart -> ServerboundHelloPacket
            Class<?> loginStartPacket = getRequiredClass(
                    "LoginStartPacket",
                    "net.minecraft.network.protocol.login.ServerboundHelloPacket",
                    "net.minecraft.network.protocol.login.PacketLoginInStart",
                    nmsPackage + "PacketLoginInStart");
            LOGIN_START_PACKET = loginStartPacket;

            // Paper 1.20.5+ uses Mojang mappings: LoginListener -> ServerLoginPacketListenerImpl
            Class<?> loginListener = getRequiredClass(
                    "LoginListener/ServerLoginPacketListenerImpl",
                    "net.minecraft.server.network.ServerLoginPacketListenerImpl",
                    "net.minecraft.server.network.LoginListener",
                    nmsPackage + "LoginListener");
            LOGIN_LISTENER = loginListener;

            LOGIN_PROFILE = getFieldOfType(LOGIN_LISTENER, GameProfile.class);
            checkNotNull(LOGIN_PROFILE, "LoginListener#<GameProfile field>");

            LOGIN_DISCONNECT = getMethod(LOGIN_LISTENER, "disconnect", String.class);
            checkNotNull(LOGIN_DISCONNECT, "LoginListener#disconnect(String)");

            NETWORK_EXCEPTION_CAUGHT = getMethod(
                    networkManager,
                    "exceptionCaught",
                    ChannelHandlerContext.class, Throwable.class
            );

            VELOCITY_LOGIN_MESSAGE_ID = getField(LOGIN_LISTENER, "velocityLoginMessageId");

            // there are multiple no-arg void methods
            // Pre 1.20.2 uses initUUID so if it's null, we're on 1.20.2 or later
            INIT_UUID = getMethod(LOGIN_LISTENER, "initUUID");
            IS_PRE_1_20_2 = INIT_UUID != null;

            // somewhere during 1.20.4 md_5 moved PreLogin logic to CraftBukkit
            CALL_PLAYER_PRE_LOGIN_EVENTS = getMethod(
                    LOGIN_LISTENER,
                    "callPlayerPreLoginEvents",
                    GameProfile.class
            );
            IS_POST_LOGIN_HANDLER = CALL_PLAYER_PRE_LOGIN_EVENTS != null;


            if (IS_PRE_1_20_2) {
                Class<?> packetListenerClass = getRequiredClass(
                        "PacketListener",
                        "net.minecraft.network.PacketListener",
                        nmsPackage + "PacketListener");

                PACKET_LISTENER = getFieldOfType(networkManager, packetListenerClass);
                checkNotNull(PACKET_LISTENER, "NetworkManager#<PacketListener field>");
            } else {
                // We get the field by name on 1.20.2+ as there are now multiple fields of this type in network manager

                // PacketListener packetListener of NetworkManager
                // Try Mojang mappings first (Paper 1.20.5+), then fall back to obfuscated name
                Field packetListenerField = getField(networkManager, "packetListener");
                if (packetListenerField == null) {
                    packetListenerField = getField(networkManager, "q");
                }
                PACKET_LISTENER = packetListenerField;
                checkNotNull(PACKET_LISTENER,
                        "NetworkManager#packetListener", "NetworkManager#q");
                makeAccessible(PACKET_LISTENER);
            }

             if (IS_POST_LOGIN_HANDLER) {
                makeAccessible(CALL_PLAYER_PRE_LOGIN_EVENTS);

                // Try Mojang mappings first (Paper 1.20.5+), then fall back to obfuscated name
                Method startClientVerificationMethod = getMethod(LOGIN_LISTENER, "startClientVerification", GameProfile.class);
                if (startClientVerificationMethod == null) {
                    startClientVerificationMethod = getMethod(LOGIN_LISTENER, "b", GameProfile.class);
                }
                START_CLIENT_VERIFICATION = startClientVerificationMethod;
                checkNotNull(START_CLIENT_VERIFICATION,
                        "ServerLoginPacketListenerImpl#startClientVerification(GameProfile)",
                        "LoginListener#b(GameProfile)");
                makeAccessible(START_CLIENT_VERIFICATION);

                LOGIN_HANDLER_CONSTRUCTOR = null;
                FIRE_LOGIN_EVENTS = null;
                FIRE_LOGIN_EVENTS_GAME_PROFILE = null;
            } else {
                // Paper 1.20.5+ uses Mojang mappings: LoginListener$LoginHandler -> ServerLoginPacketListenerImpl$LoginHandler
                Class<?> loginHandler = getRequiredClass(
                        "LoginHandler",
                        "net.minecraft.server.network.ServerLoginPacketListenerImpl$LoginHandler",
                        "net.minecraft.server.network.LoginListener$LoginHandler",
                        nmsPackage + "LoginListener$LoginHandler");
                LOGIN_HANDLER_CONSTRUCTOR =
                        getConstructor(loginHandler, true, LOGIN_LISTENER);
                checkNotNull(LOGIN_HANDLER_CONSTRUCTOR,
                        "LoginHandler#<init>(LoginListener)");

                FIRE_LOGIN_EVENTS = getMethod(loginHandler, "fireEvents");

                // LoginHandler().fireEvents(GameProfile)
                FIRE_LOGIN_EVENTS_GAME_PROFILE = getMethod(loginHandler, "fireEvents",
                        GameProfile.class);
                checkNotNull(FIRE_LOGIN_EVENTS, FIRE_LOGIN_EVENTS_GAME_PROFILE,
                        "LoginHandler#fireEvents()", "LoginHandler#fireEvents(GameProfile)");

                START_CLIENT_VERIFICATION = null;
            }

            PAPER_DISABLE_USERNAME_VALIDATION = getField(LOGIN_LISTENER,
                    "iKnowThisMayNotBeTheBestIdeaButPleaseDisableUsernameValidation");

            if (Constants.DEBUG_MODE) {
                System.out.println("Paper disable username validation field exists? " +
                        (PAPER_DISABLE_USERNAME_VALIDATION != null));
            }

            // ProxyUtils
            Class<?> spigotConfig = getRequiredClass("SpigotConfig", "org.spigotmc.SpigotConfig");

            BUNGEE = getField(spigotConfig, "bungee");
            checkNotNull(BUNGEE, "SpigotConfig#bungee");

            Class<?> paperConfigNew = getClassSilently(
                    "io.papermc.paper.configuration.GlobalConfiguration");
            if (paperConfigNew != null) {
                // 1.19 and later
                Method paperConfigGet = checkNotNull(getMethod(paperConfigNew, "get"),
                        "GlobalConfiguration#get()");
                Field paperConfigProxies = checkNotNull(getField(paperConfigNew, "proxies"),
                        "GlobalConfiguration#proxies");
                Field paperConfigVelocity = checkNotNull(
                        getField(paperConfigProxies.getType(), "velocity"),
                        "Proxies#velocity");
                Field paperVelocityEnabled = checkNotNull(
                        getField(paperConfigVelocity.getType(), "enabled"),
                        "Velocity#enabled");
                PAPER_VELOCITY_SUPPORT = () -> {
                    Object paperConfigInstance = invoke(null, paperConfigGet);
                    Object proxiesInstance = getValue(paperConfigInstance, paperConfigProxies);
                    Object velocityInstance = getValue(proxiesInstance, paperConfigVelocity);
                    return getBooleanValue(velocityInstance, paperVelocityEnabled);
                };
            } else {
                // Pre-1.19
                Class<?> paperConfig = getClassSilently(
                        "com.destroystokyo.paper.PaperConfig");

                if (paperConfig != null) {
                    Field velocitySupport = getField(paperConfig, "velocitySupport");
                    // velocitySupport field is null pre-1.13
                    PAPER_VELOCITY_SUPPORT = velocitySupport != null ?
                            () -> castedStaticBooleanValue(velocitySupport) : null;
                } else {
                    PAPER_VELOCITY_SUPPORT = null;
                }
            }

            IS_FOLIA = ReflectionUtils.getClassSilently(
                    "io.papermc.paper.threadedregions.RegionizedServer"
            ) != null;


            if (!IS_PRE_1_20_2) {
                // PacketHandshakingInSetProtocol is now a record
                // This means its fields are now private and final
                // We therefore must use reflection to obtain the constructor
                CLIENT_INTENT = getClassOrFallback(
                        "net.minecraft.network.protocol.handshake.ClientIntent",
                        nmsPackage + "ClientIntent"
                );
                checkNotNull(CLIENT_INTENT, "ClientIntent");

                HANDSHAKE_PACKET_CONSTRUCTOR = getConstructor(HANDSHAKE_PACKET, false, int.class,
                        String.class, int.class, CLIENT_INTENT);
                checkNotNull(HANDSHAKE_PACKET_CONSTRUCTOR,
                        "HandshakePacket#<init>(int, String, int, ClientIntent)");

                // Paper 1.20.5+ Mojang mappings expose real field names; older Spigot uses obfuscated a/b/c/d.
                // Try Mojang name first, then obfuscated.
                Field protocolField = getField(HANDSHAKE_PACKET, "protocolVersion");
                if (protocolField == null) {
                    protocolField = getField(HANDSHAKE_PACKET, "a");
                }
                checkNotNull(protocolField, "HandshakePacket#protocolVersion", "HandshakePacket#a");

                if (protocolField.getType().isPrimitive()) {
                    // Mojang on 1.20.5+ OR obfuscated 1.20.2-1.20.4: int field is the protocol version
                    HANDSHAKE_PROTOCOL = protocolField;
                    Field portField = getField(HANDSHAKE_PACKET, "port");
                    if (portField == null) {
                        portField = getField(HANDSHAKE_PACKET, "c");
                    }
                    HANDSHAKE_PORT = portField;
                    checkNotNull(HANDSHAKE_PORT, "HandshakePacket#port", "HandshakePacket#c");
                } else {
                    // Obfuscated 1.20.5: a is the stream_codec, everything is shifted
                    HANDSHAKE_PROTOCOL = getField(HANDSHAKE_PACKET, "b");
                    checkNotNull(HANDSHAKE_PROTOCOL, "HandshakePacket#b");
                    Field portField = getField(HANDSHAKE_PACKET, "port");
                    if (portField == null) {
                        portField = getField(HANDSHAKE_PACKET, "d");
                    }
                    HANDSHAKE_PORT = portField;
                    checkNotNull(HANDSHAKE_PORT, "HandshakePacket#port", "HandshakePacket#d");
                }

                makeAccessible(HANDSHAKE_PROTOCOL);

                makeAccessible(HANDSHAKE_PORT);

                // Try Mojang field name first, then fall back to type-based lookup (obfuscated)
                Field intentionField = getField(HANDSHAKE_PACKET, "intention");
                if (intentionField == null) {
                    intentionField = getFieldOfType(HANDSHAKE_PACKET, CLIENT_INTENT);
                }
                HANDSHAKE_INTENTION = intentionField;
                checkNotNull(HANDSHAKE_INTENTION, "HandshakePacket#intention",
                        "HandshakePacket#<ClientIntent field>");
                makeAccessible(HANDSHAKE_INTENTION);
            } else {
                CLIENT_INTENT = null;
                HANDSHAKE_PACKET_CONSTRUCTOR = null;
                HANDSHAKE_PORT = null;
                HANDSHAKE_PROTOCOL = null;
                HANDSHAKE_INTENTION = null;
            }
        } catch (RuntimeException | Error failure) {
            NmsDiagnostics.recordInitializationFailure(failure);
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> getRequiredClass(String accessor, String... candidates) {
        for (String candidate : candidates) {
            Class<?> clazz = getClassSilently(candidate);
            if (clazz != null) {
                if (Constants.DEBUG_MODE) {
                    System.out.println("Found class: " + clazz.getName());
                }
                return (Class<T>) clazz;
            }
        }
        throw NmsDiagnostics.missingClass(accessor, candidates);
    }

    private static Class<?> getClassOrFallback(String className, String fallbackName) {
        Class<?> clazz = getClassSilently(className);

        if (clazz != null) {
            if (Constants.DEBUG_MODE) {
                System.out.println("Found class (primary): " + clazz.getName());
            }
            return clazz;
        }

        clazz = getClassSilently(fallbackName);
        if (clazz == null) {
            throw NmsDiagnostics.missingClass(className, className, fallbackName);
        }
        if (Constants.DEBUG_MODE) {
            System.out.println("Found class (fallback): " + clazz.getName());
        }

        return clazz;
    }

    private static <T> T checkNotNull(
            @CheckForNull T toCheck,
            @CheckForNull String objectName,
            String... candidates) {
        if (toCheck == null) {
            String tried = candidates.length == 0
                    ? objectName
                    : objectName + ", " + String.join(", ", candidates);
            throw NmsDiagnostics.missingAccessor(objectName, "Tried: " + tried + ".");
        }
        return toCheck;
    }

    // Ensure one of two is not null
    private static <T> T checkNotNull(
            @CheckForNull T toCheck,
            @CheckForNull T toCheck2,
            @CheckForNull String objectName,
            String... candidates
    ) {
        T resolved = toCheck != null ? toCheck : toCheck2;
        if (resolved == null) {
            String tried = candidates.length == 0
                    ? objectName
                    : objectName + ", " + String.join(", ", candidates);
            throw NmsDiagnostics.missingAccessor(objectName, "Tried: " + tried + ".");
        }
        return resolved;
    }
}
