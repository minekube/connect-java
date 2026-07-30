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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the safety boundary for publishing stable releases to Hangar. The listing must not silently
 * stop updating, publish a development build, attach a jar to the wrong platform, or accept an
 * upload without checking the bytes Hangar actually serves.
 */
class ReleaseHangarPublishTest {

    private static final String HANGAR_STEP = "Publish to Hangar";
    private static final List<String> RELEASE_ONLY_STEPS = Arrays.asList(
            "Upload Release Artifacts",
            "Update Latest Release");
    private static final Path WORKFLOW_PATH =
            Paths.get("..", ".github", "workflows", "release.yml");
    private static final Path REPOSITORY_GIT_PATH = Paths.get("..", ".git");
    private static final Path DESCRIPTION_PATH =
            Paths.get("..", ".github", "hangar-description.md");

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

    private static Map<String, Object> hangarStep(List<Map<String, Object>> steps) {
        int at = stepIndex(steps, HANGAR_STEP);
        assertTrue(at >= 0, "build job is missing the \"" + HANGAR_STEP + "\" step");
        return steps.get(at);
    }

    private static String hangarScript(List<Map<String, Object>> steps) {
        Object run = hangarStep(steps).get("run");
        assertTrue(run instanceof String && !((String) run).isEmpty(),
                "\"" + HANGAR_STEP + "\" must be a run step");
        return (String) run;
    }

    @Test
    void hangarPublishOnlyRunsForARealRelease() throws Exception {
        List<Map<String, Object>> steps = readBuildJobSteps();
        Object condition = hangarStep(steps).get("if");
        assertTrue(condition instanceof String,
                "\"" + HANGAR_STEP + "\" has no event condition; pushes would publish snapshots");

        for (String releaseOnly : RELEASE_ONLY_STEPS) {
            int at = stepIndex(steps, releaseOnly);
            assertTrue(at >= 0, "expected release-only step \"" + releaseOnly + "\"");
            assertEquals(steps.get(at).get("if"), condition,
                    "\"" + HANGAR_STEP + "\" does not share the canonical release event gate");
        }
    }

    @Test
    void hangarPublishRunsAfterTheGitHubReleaseIsVerified() throws Exception {
        List<Map<String, Object>> steps = readBuildJobSteps();
        int hangarAt = stepIndex(steps, HANGAR_STEP);
        int verifyAt = stepIndex(steps, "Verify published release assets");
        assertTrue(verifyAt >= 0, "build job is missing the release verification step");
        assertTrue(hangarAt > verifyAt,
                "\"" + HANGAR_STEP + "\" runs before the GitHub release is verified");
    }

    @Test
    void hangarPublishMapsEachBuildOutputToTheRightPlatform() throws Exception {
        String script = hangarScript(readBuildJobSteps());

        assertTrue(script.contains("spigot/build/libs/connect-spigot.jar")
                        && script.contains("'[\"PAPER\"]'"),
                "Spigot jar is not mapped exclusively to PAPER");
        assertTrue(script.contains("velocity/build/libs/connect-velocity.jar")
                        && script.contains("'[\"VELOCITY\"]'"),
                "Velocity jar is not mapped exclusively to VELOCITY");
        assertTrue(script.contains("bungee/build/libs/connect-bungee.jar")
                        && script.contains("'[\"WATERFALL\"]'"),
                "Bungee jar is not mapped exclusively to Hangar's WATERFALL platform");
        assertTrue(!script.contains("releases/download/"),
                "Hangar publishing must upload this run's build output, not release downloads");
    }

    @Test
    void hangarPublishResolvesPlatformVersionsAtPublishTime() throws Exception {
        String script = hangarScript(readBuildJobSteps());

        for (String platform : Arrays.asList("PAPER", "VELOCITY", "WATERFALL")) {
            assertTrue(script.contains("for platform in PAPER VELOCITY WATERFALL"),
                    "Hangar platform-version lookup does not include " + platform);
        }
        assertTrue(script.contains("platforms/$platform/versions"),
                "Hangar versions are not resolved dynamically from the platform endpoint");
        assertTrue(script.contains("spigot/src/main/resources/plugin.yml"),
                "Paper compatibility floor is restated instead of read from plugin.yml");
        assertTrue(script.contains("VELOCITY_FLOOR=\"3.0\""),
                "Hangar's historical Velocity 1.x identifiers are not excluded");
    }

    @Test
    void hangarPublishSyncsTheCheckedInResourcePage() throws Exception {
        String script = hangarScript(readBuildJobSteps());

        assertTrue(Files.isRegularFile(DESCRIPTION_PATH),
                "checked-in Hangar resource-page copy is missing");
        String description = Files.readString(DESCRIPTION_PATH);
        assertTrue(description.contains("Paper") && description.contains("Velocity")
                        && description.contains("BungeeCord"),
                "Hangar resource page does not identify every supported plugin platform");
        assertTrue(script.contains(".github/hangar-description.md")
                        && script.contains("pages/edit/$PROJECT"),
                "release workflow does not sync the checked-in resource page");
    }

    @Test
    @SuppressWarnings("unchecked")
    void hangarDescriptionComesFromTheWorkflowCommitWhenBackfillingAnOldTag() throws Exception {
        List<Map<String, Object>> steps = readBuildJobSteps();
        Map<String, Object> step = hangarStep(steps);

        Object env = step.get("env");
        assertTrue(env instanceof Map, "\"" + HANGAR_STEP + "\" declares no env block");
        Object workflowSha = ((Map<String, Object>) env).get("WORKFLOW_SHA");
        assertTrue(workflowSha instanceof String
                        && ((String) workflowSha).contains("github.workflow_sha"),
                "\"" + HANGAR_STEP + "\" does not identify the commit containing its workflow");

        String script = hangarScript(steps);
        assertTrue(script.contains("$GITHUB_REPOSITORY/$WORKFLOW_SHA/"
                        + ".github/hangar-description.md"),
                "Hangar description is not loaded from the exact workflow commit");
        assertTrue(script.contains("--rawfile content \"$TMP/hangar-description.md\""),
                "Hangar page sync still reads the checked-out release tag, where a backfill file "
                        + "may not exist");
    }

    @Test
    void hangarPublishVerifiesStoredDigestsAndJarMagic() throws Exception {
        String script = hangarScript(readBuildJobSteps());

        assertTrue(script.contains("sha256sum") && script.contains("sha256Hash"),
                "Hangar upload is not verified against Hangar's stored SHA-256");
        assertTrue(script.contains("504b0304"),
                "Hangar download is not probed for ZIP/JAR magic");
        assertTrue(script.contains("/download"),
                "Hangar download endpoint is not probed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void hangarTokenIsPassedOnlyThroughTheEnvironment() throws Exception {
        List<Map<String, Object>> steps = readBuildJobSteps();
        Map<String, Object> step = hangarStep(steps);

        Object env = step.get("env");
        assertTrue(env instanceof Map, "\"" + HANGAR_STEP + "\" declares no env block");
        Object token = ((Map<String, Object>) env).get("HANGAR_API_TOKEN");
        assertTrue(token instanceof String
                        && ((String) token).contains("secrets.HANGAR_API_TOKEN"),
                "\"" + HANGAR_STEP + "\" does not use the HANGAR_API_TOKEN repository secret");
        assertTrue(!hangarScript(steps).contains("secrets."),
                "\"" + HANGAR_STEP + "\" interpolates a secret into its script body");
    }
}
