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

object Versions {
    const val spigotVersion = "1.19.4-R0.1-SNAPSHOT"
    const val configUtilsVersion = "1.0-SNAPSHOT"
    const val guiceVersion = "6.0.0"
    const val nettyVersion = "4.2.3.Final"
    const val snakeyamlVersion = "1.28"
    const val cloudVersion = "1.5.0"
    const val adventureApiVersion = "4.10.0"
    const val adventurePlatformVersion = "4.0.0"
    // Compile-only (platform-provided). Must stay on a 5.x: Via 5.0 renamed
    // BukkitChannelInitializer#getOriginal() to original(), and SpigotInjector resolves both names
    // reflectively so Via 4.x servers keep working. See SpigotInjectorViaLegacyPathTest.
    const val viaVersionVersion = "5.11.0"
    const val gRPCVersion = "1.44.0"
    const val protocVersion = "3.19.4"
    const val bstatsVersion = "3.0.2"
    const val gsonVersion = "2.8.6"
    const val jvmLibp2pVersion = "1.3.5-RELEASE"
    const val kotlinStdlibVersion = "1.9.22"
    const val loomVersion = "1.17.17"
    const val fabricLoaderVersion = "0.19.3"
    const val fabricApi12111Version = "0.141.6+1.21.11"
    const val fabricApi1211Version = "0.116.15+1.21.1"
    const val fabricApi1201Version = "0.92.11+1.20.1"
    const val fabricApi262Version = "0.156.0+26.2"
    const val fabricLanguageKotlinVersion = "1.13.13+kotlin.2.4.10"
    const val kotlinVersion = "2.4.10"
    const val coroutinesVersion = "1.11.0"
    const val arrowVersion = "2.2.3"

    const val checkerQual = "3.19.0"
}
