/*
 * Copyright (c) 2021-2022 Minekube. https://minekube.com
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
 * @author Minekube
 * @link https://github.com/minekube/connect-java
 */

package com.minekube.connect.listener;

import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.PostOrder;
import java.lang.reflect.Method;

/**
 * Registers event handlers that must run strictly <i>after</i> every other plugin's handler.
 *
 * <p>Two layered levers, because neither covers the whole range of Velocity versions Connect
 * supports on its own:
 *
 * <ol>
 *   <li><b>Numeric priority.</b> Velocity maps {@link PostOrder#LAST} to
 *       {@code Short.MIN_VALUE + 1}, deliberately leaving exactly one slot below it.
 *       {@link #AFTER_LAST} takes that slot, so the handler runs after every {@code LAST}
 *       handler no matter which plugin loaded first. The
 *       {@code register(Object, Class, short, EventHandler)} overload that reaches it only
 *       exists on Velocity builds from 2024-09-16 onwards, which is why it is looked up
 *       reflectively - on a public interface method, so its absence is a plain
 *       {@link NoSuchMethodException} rather than anything that can break class loading.</li>
 *   <li><b>Load order.</b> On older builds the handler falls back to {@code PostOrder.LAST}.
 *       Velocity breaks ties between equal orders by registration order, which is plugin load
 *       order, which is a topological sort of the declared dependency graph - so the
 *       {@code optional} plugin dependencies declared in {@code velocity-plugin.json} put
 *       Connect after them. An optional dependency on a plugin that is not installed is a
 *       silent no-op.</li>
 * </ol>
 *
 * <p>This class knows nothing about any specific third-party plugin, and it never reflects
 * into Velocity internals: both levers are public API.
 */
final class VelocityLateEventRegistrar {
    /**
     * One slot below {@code PostOrder.LAST}, which Velocity maps to {@code Short.MIN_VALUE + 1}.
     * It is also the default of {@code Subscribe#priority()}, so a handler registered here still
     * runs after anything a plugin puts at {@code PostOrder.CUSTOM} without picking a priority.
     */
    static final short AFTER_LAST = Short.MIN_VALUE;

    private final EventManager eventManager;
    private final Object plugin;

    VelocityLateEventRegistrar(EventManager eventManager, Object plugin) {
        this.eventManager = eventManager;
        this.plugin = plugin;
    }

    /**
     * The {@code register(Object, Class, short, EventHandler)} overload of the running Velocity's
     * {@link EventManager}, or {@code null} on Velocity builds that predate it.
     */
    static Method shortRegisterMethod() {
        try {
            return EventManager.class.getMethod(
                    "register", Object.class, Class.class, short.class, EventHandler.class);
        } catch (ReflectiveOperationException | LinkageError absentOnOlderVelocity) {
            return null;
        }
    }

    /**
     * Registers {@code handler} so that it runs after every other handler of {@code eventType}.
     *
     * @return whether the numeric-priority lever was available; {@code false} means the handler
     *         was registered at {@link PostOrder#LAST} instead and relies on load order
     */
    <E> boolean registerAfterLast(Class<E> eventType, EventHandler<E> handler) {
        Method shortRegister = shortRegisterMethod();
        if (shortRegister != null) {
            try {
                shortRegister.invoke(eventManager, plugin, eventType, AFTER_LAST, handler);
                return true;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Fall through to the load-order lever below.
            }
        }
        eventManager.register(plugin, eventType, PostOrder.LAST, handler);
        return false;
    }
}
