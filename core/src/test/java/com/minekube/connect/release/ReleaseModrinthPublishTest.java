/*
 * Copyright (c) 2019-2022 Minekube. https://minekube.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * @author Minekube
 * @link https://github.com/minekube/connect-java
 */

package com.minekube.connect.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The Modrinth publish step writes to a public listing, and its dangerous failure mode is silent:
 * with the event condition removed, every push to {@code main} would publish a development build to
 * that listing while the workflow run stayed green. Nothing in the run, the release or the
 * repository would look wrong, and the first report would come from a user who installed a build we
 * never released.
 *
 * <p>So the condition is asserted here rather than left to review. These tests pin the properties
 * that make the step safe; each of them, removed, is a defect that ships quietly.
 */
class ReleaseModrinthPublishTest {

    private static final String MODRINTH_STEP = "Publish to Modrinth";

    /**
     * The steps that already publish only on a real release. The Modrinth step must carry their
     * condition exactly - not an equivalent-looking one - so there is a single event gate in this
     * workflow rather than two that can drift apart.
     */
    private static final List<String> RELEASE_ONLY_STEPS = Arrays.asList(
            "Upload Release Artifacts",
            "Update Latest Release");

    private static final Path WORKFLOW_PATH =
            Paths.get("..", ".github", "workflows", "release.yml");
    private static final Path REPOSITORY_GIT_PATH = Paths.get("..", ".git");

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readBuildJobSteps() throws Exception {
        if (!Files.exists(WORKFLOW_PATH)) {
            if (Files.exists(REPOSITORY_GIT_PATH)) {
                throw new AssertionError(
                        WORKFLOW_PATH + " is missing from the repository checkout");
            } else {
                assumeTrue(false,
                        WORKFLOW_PATH + " is unavailable outside a repository checkout");
                return List.of();
            }
        }

        Map<String, Object> workflow;
        try (InputStream in = Files.newInputStream(WORKFLOW_PATH)) {
            workflow = new Yaml().load(in);
        }

        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        assertTrue(jobs != null && jobs.containsKey("build"), "build job is missing");

        Map<String, Object> build = (Map<String, Object>) jobs.get("build");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) build.get("steps");
        assertTrue(steps != null && !steps.isEmpty(), "build job has no steps");
        return steps;
    }

    private static int stepIndex(List<Map<String, Object>> steps, String name) {
        for (int i = 0; i < steps.size(); i++) {
            if (name.equals(steps.get(i).get("name"))) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Object> modrinthStep(List<Map<String, Object>> steps) {
        int at = stepIndex(steps, MODRINTH_STEP);
        assertTrue(at >= 0, "build job is missing the \"" + MODRINTH_STEP + "\" step");
        return steps.get(at);
    }

    private static String modrinthScript(List<Map<String, Object>> steps) {
        Object run = modrinthStep(steps).get("run");
        assertTrue(run instanceof String && !((String) run).isEmpty(),
                "\"" + MODRINTH_STEP + "\" must be a run step");
        return (String) run;
    }

    /**
     * The one that matters. A push to {@code main} runs this workflow to build the
     * {@code latest-prerelease} artifacts; without the event condition it would publish those
     * development builds to the public Modrinth listing on every merge.
     */
    @Test
    void modrinthPublishOnlyRunsForARealRelease() throws Exception {
        List<Map<String, Object>> steps = readBuildJobSteps();

        Object condition = modrinthStep(steps).get("if");
        assertTrue(condition instanceof String,
                "\"" + MODRINTH_STEP + "\" has no event condition; every push to main would "
                        + "publish a development build to the public Modrinth listing");

        for (String releaseOnly : RELEASE_ONLY_STEPS) {
            int at = stepIndex(steps, releaseOnly);
            assertTrue(at >= 0, "expected release-only step \"" + releaseOnly + "\"");
            assertEquals(steps.get(at).get("if"), condition,
                    "\"" + MODRINTH_STEP + "\" does not carry the same event condition as \""
                            + releaseOnly + "\"; the two gates can drift apart");
        }
    }

    /**
     * Ordering. Modrinth publishing is deliberately independent of the release upload - it takes
     * the jars off the runner - but a run whose release did not land is not a release, and should
     * not put versions on a public listing.
     */
    @Test
    void modrinthPublishRunsAfterTheReleaseIsVerified() throws Exception {
        List<Map<String, Object>> steps = readBuildJobSteps();

        int modrinthAt = stepIndex(steps, MODRINTH_STEP);
        int verifyAt = stepIndex(steps, "Verify published release assets");
        assertTrue(verifyAt >= 0, "build job is missing the release verification step");
        assertTrue(modrinthAt > verifyAt,
                "\"" + MODRINTH_STEP + "\" runs before the release is verified; a release that "
                        + "published nothing would still reach the public listing");
    }

    /**
     * The jars must come off the runner. Downloading them from the release instead would make
     * Modrinth publishing depend on the release having landed correctly - the very failure
     * {@code ReleaseAssetVerificationTest} guards - so a single broken release would corrupt both.
     */
    @Test
    void modrinthPublishUploadsThisRunsBuildOutput() throws Exception {
        String script = modrinthScript(readBuildJobSteps());

        List<String> jars = Arrays.asList(
                "spigot/build/libs/connect-spigot.jar",
                "velocity/build/libs/connect-velocity.jar",
                "bungee/build/libs/connect-bungee.jar");

        List<String> missing = new ArrayList<>();
        for (String jar : jars) {
            if (!script.contains(jar)) {
                missing.add(jar);
            }
        }
        assertTrue(missing.isEmpty(),
                "\"" + MODRINTH_STEP + "\" does not publish " + missing + " from the build output");

        assertTrue(!script.contains("releases/download/"),
                "\"" + MODRINTH_STEP + "\" fetches from the release; it must upload the jars this "
                        + "run built so a broken release cannot corrupt the listing too");
    }

    /**
     * A separate version per platform jar. Modrinth runs every validator whose loaders intersect
     * the declared loaders against every file in a version, so one version declaring velocity,
     * bungeecord and paper together is rejected outright.
     */
    @Test
    void modrinthPublishUploadsOneVersionPerPlatform() throws Exception {
        String script = modrinthScript(readBuildJobSteps());

        List<String> loaders = Arrays.asList("velocity", "bungeecord", "spigot", "paper", "bukkit");
        List<String> missing = new ArrayList<>();
        for (String loader : loaders) {
            if (!script.contains(loader)) {
                missing.add(loader);
            }
        }
        assertTrue(missing.isEmpty(), "\"" + MODRINTH_STEP + "\" declares no loader " + missing);
    }

    /**
     * The upload is confirmed against what Modrinth stored, not against its own success. Size would
     * not do: two different jars can share a size and cannot share a digest.
     */
    @Test
    void modrinthPublishVerifiesTheStoredFileByDigest() throws Exception {
        String script = modrinthScript(readBuildJobSteps());

        List<String> required = Arrays.asList(
                "sha1sum",           // digest of the jar this run built
                "sha512sum",         // both digests, not one
                ".hashes.sha1",      // digest Modrinth computed from the bytes it stored
                ".hashes.sha512");

        List<String> missing = new ArrayList<>();
        for (String want : required) {
            if (!script.contains(want)) {
                missing.add(want);
            }
        }
        assertTrue(missing.isEmpty(), "\"" + MODRINTH_STEP + "\" does not reference " + missing
                + "; the upload must be confirmed by digest against the stored version");
    }

    /**
     * Minecraft versions are resolved from Modrinth's tag list at publish time. A hard-coded list
     * silently stops matching searches the day Mojang ships a release - the plugin keeps working,
     * so nothing goes red; the listing just quietly stops being found.
     */
    @Test
    void modrinthPublishResolvesGameVersionsAtPublishTime() throws Exception {
        String script = modrinthScript(readBuildJobSteps());

        assertTrue(script.contains("tag/game_version"),
                "\"" + MODRINTH_STEP + "\" does not resolve Minecraft versions from Modrinth's tag "
                        + "list; a hard-coded list rots without ever failing");
        assertTrue(script.contains("plugin.yml"),
                "\"" + MODRINTH_STEP + "\" does not read its version floor from the shipped plugin "
                        + "descriptor; a restated floor can drift from the jar");
    }

    /**
     * The credential reaches the step as an environment variable. Interpolating
     * {@code ${{ secrets.* }}} into the script body expands it into the shell command itself, where
     * a trace or a crash dump can print it.
     */
    @Test
    @SuppressWarnings("unchecked")
    void modrinthTokenIsNotInterpolatedIntoTheScript() throws Exception {
        List<Map<String, Object>> steps = readBuildJobSteps();
        Map<String, Object> step = modrinthStep(steps);

        Object env = step.get("env");
        assertTrue(env instanceof Map, "\"" + MODRINTH_STEP + "\" declares no env block");
        Object token = ((Map<String, Object>) env).get("MODRINTH_TOKEN");
        assertTrue(token instanceof String && ((String) token).contains("secrets.MODRINTH_TOKEN"),
                "\"" + MODRINTH_STEP + "\" does not take MODRINTH_TOKEN from repository secrets");

        assertTrue(!modrinthScript(steps).contains("secrets."),
                "\"" + MODRINTH_STEP + "\" interpolates a secret into its script body");
    }
}
