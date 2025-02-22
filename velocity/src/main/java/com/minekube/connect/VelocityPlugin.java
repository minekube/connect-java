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

package com.minekube.connect;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.module.CommandModule;
import com.minekube.connect.module.ProxyCommonModule;
import com.minekube.connect.module.VelocityListenerModule;
import com.minekube.connect.module.VelocityPlatformModule;
import com.minekube.connect.module.WatcherModule;
import com.minekube.connect.util.ReflectionUtils;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import java.nio.file.Path;

public final class VelocityPlugin {
    private final ConnectPlatform platform;

    @Inject
    public VelocityPlugin(@DataDirectory Path dataDirectory, Injector guice) {
        ReflectionUtils.setPrefix("com.velocitypowered.proxy");

        long ctm = System.currentTimeMillis();
        Injector injector = guice.createChildInjector(
                new ProxyCommonModule(dataDirectory),
                new VelocityPlatformModule(guice)
        );

        platform = injector.getInstance(ConnectPlatform.class);

        long endCtm = System.currentTimeMillis();
        injector.getInstance(ConnectLogger.class)
                .translatedInfo("connect.core.finish", endCtm - ctm);
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        platform.enable(
                new CommandModule(),
                new VelocityListenerModule(),
//                new VelocityAddonModule(), - don't need proxy-side data injection
                new WatcherModule()
        );
    }
}
