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

package com.minekube.connect.config;

import lombok.Getter;

/**
 * The configuration used by proxy platforms, currently Velocity and BungeeCord.
 */
@Getter
public final class ProxyConnectConfig extends ConnectConfig {
    private static final LoginReassertConfig DEFAULT_LOGIN_REASSERT = new LoginReassertConfig();

    /**
     * Whether Connect re-asserts its own pre-login decision after every other plugin has run.
     */
    private LoginReassertConfig loginReassert = new LoginReassertConfig();

    /**
     * Never {@code null}: a config file written before this option existed keeps the defaults.
     */
    public LoginReassertConfig getLoginReassert() {
        return loginReassert != null ? loginReassert : DEFAULT_LOGIN_REASSERT;
    }

    @Getter
    public static class LoginReassertConfig {
        /**
         * Default on. Turning it off restores the behaviour of leaving Connect's pre-login
         * decision to whichever plugin writes last, which is what an operator who deliberately
         * wants another plugin to override Connect needs.
         */
        private boolean enabled = true;
        /**
         * Default off. Also restores Connect's UUID and username, not just the skin properties.
         * Requires every login plugin on the proxy to key its own storage on the Mojang UUID -
         * see the prerequisite documented next to this option in {@code proxy-config.yml}.
         */
        private boolean restoreFullProfile = false;
    }
}
