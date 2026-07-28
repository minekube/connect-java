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

package com.minekube.connect.listener;

import com.minekube.connect.VelocityPlugin;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.platform.listener.ListenerRegistration;
import com.velocitypowered.api.event.EventManager;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class VelocityListenerRegistration implements ListenerRegistration<Object> {
    private final EventManager eventManager;
    private final VelocityPlugin plugin;
    private final ConnectLogger logger;

    @Override
    public void register(Object listener) {
        if (listener instanceof VelocityLateReassertListener) {
            // Cannot be an annotated listener: @Subscribe has no way to express "after
            // PostOrder.LAST" on the Velocity API this plugin compiles against.
            try {
                ((VelocityLateReassertListener) listener).register(eventManager, plugin);
            } catch (Throwable throwable) {
                logger.error(
                        "Could not register Connect's login re-assert handlers; "
                                + "Connect will behave as it did before the late floor existed",
                        throwable);
            }
            return;
        }
        eventManager.register(plugin, listener);
    }
}
