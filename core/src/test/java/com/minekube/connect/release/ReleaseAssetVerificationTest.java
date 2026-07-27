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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The release workflow used to end at "upload the artifacts and hope": nothing ever re-read the
 * release that actually landed, so a release that published zero assets - or only LICENSE - looked
 * exactly like a healthy one. These tests pin the guard that closes that gap.
 *
 * <p>This is the same fail-loud guard proven written in geyserlite (minekube/geyserlite#136),
 * transplanted here and adapted to connect-java's build artifacts (the plugin jars).
 */
class ReleaseAssetVerificationTest {

    private static final String VERIFY_ASSETS_STEP = "Verify published release assets";

    /** Every step that publishes assets; the guard must run after all of them. */
    private static final List<String> UPLOAD_STEPS = Arrays.asList(
            "Upload Release Artifacts",
            "Update Latest Release",
            "Update Pre-Release");

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

    private static String verifyScript(List<Map<String, Object>> steps) {
        int at = stepIndex(steps, VERIFY_ASSETS_STEP);
        assertTrue(at >= 0, "build job is missing the \"" + VERIFY_ASSETS_STEP
                + "\" step; a release that publishes no asset would ship green again");
        Object run = steps.get(at).get("run");
        assertTrue(run instanceof String && !((String) run).isEmpty(),
                "\"" + VERIFY_ASSETS_STEP + "\" must be a run step that asserts on the "
                        + "published release");
        return (String) run;
    }

    /**
     * The core regression guard: the release job must fail when the published release carries no
     * downloadable artifact.
     */
    @Test
    void releaseVerifiesPublishedAssets() throws Exception {
        List<Map<String, Object>> steps = readBuildJobSteps();
        verifyScript(steps);

        // Unlike the upload steps, the guard is not gated on the event: it verifies whichever
        // release the run published, so it can never be skipped into silence.
        Object condition = steps.get(stepIndex(steps, VERIFY_ASSETS_STEP)).get("if");
        assertTrue(condition == null,
                "\"" + VERIFY_ASSETS_STEP + "\" is conditional (if: " + condition
                        + "); the guard must always run");
    }

    /**
     * The point of the guard. Asserting against the upload step's own output would rebuild the exact
     * "trust the run, not the artifact" defect one layer up, so the check has to go back to the
     * GitHub API and read what actually landed.
     */
    @Test
    void releaseVerificationReadsPublishedRelease() throws Exception {
        String script = verifyScript(readBuildJobSteps());

        List<String> required = Arrays.asList(
                "/releases/tags/",    // re-reads the published release by tag
                "\"uploaded\"",       // only fully-uploaded assets count
                "releases/download/", // proves a build is actually served, not just listed
                "gh api");            // reads the API, not the local build output

        List<String> missing = new ArrayList<>();
        for (String want : required) {
            if (!script.contains(want)) {
                missing.add(want);
            }
        }
        assertTrue(missing.isEmpty(), "\"" + VERIFY_ASSETS_STEP + "\" script does not reference "
                + missing + "; it must assert on the published release, not on local build output");

        // The guard must not be satisfied by inspecting the local build/libs directories:
        // those jars exist even when the upload never happened.
        assertTrue(!script.contains("build/libs"),
                "verification must read the published release, not local build output");
    }

    /**
     * Pins the second failure condition. A non-empty asset list is not proof of a usable release: a
     * release carrying only LICENSE, a checksum manifest, a signature bundle or an SBOM has a
     * positive asset count and still offers nothing anyone can run. So the guard must classify by
     * name/type rather than count.
     */
    @Test
    void releaseVerificationRequiresRealBuildArtifact() throws Exception {
        String script = verifyScript(readBuildJobSteps());

        // The metadata types that must NOT satisfy the guard on their own.
        List<String> excluded = Arrays.asList(
                "^checksums\\\\.txt$",        // checksum manifests
                "^SHA(256|512)SUMS$",         // checksum manifests
                "^LICENSE",                   // license metadata - connect-java uploads this
                "^README",                    // readme metadata
                "\\\\.sig$",                  // detached signatures
                "\\\\.sigstore\\\\.json$",    // signature bundles
                "\\\\.attest\\\\.spdx\\\\.json$", // SBOM attestations
                "\\\\.spdx\\\\.json$",        // SBOM metadata
                "\\\\.asc$",                  // armored signatures
                "\\\\.pem$",                  // certificate metadata
                "\\\\.sha256$",               // checksum sidecars
                "\\\\.h$",                    // C headers
                "\\\\.hpp$",                  // C++ headers
                "\\\\.md$",                   // markdown metadata
                "\\\\.txt$");                 // text metadata

        List<String> notExcluded = new ArrayList<>();
        for (String pattern : excluded) {
            if (!script.contains(pattern)) {
                notExcluded.add(pattern);
            }
        }
        assertTrue(notExcluded.isEmpty(), "build-artifact classifier does not exclude "
                + notExcluded + "; a release of pure metadata would pass the guard");

        // And it must actually gate on the classified count, not just compute it.
        assertTrue(Pattern.compile("BUILD_COUNT\"\\s*-eq\\s*0").matcher(script).find(),
                "guard does not fail on the classified zero build-artifact count");
    }

    /**
     * Pins the ordering. The guard is meaningless before the upload: run first, it would always see
     * an empty release and pass.
     */
    @Test
    void releaseVerificationRunsAfterUpload() throws Exception {
        List<Map<String, Object>> steps = readBuildJobSteps();

        int verifyAt = stepIndex(steps, VERIFY_ASSETS_STEP);
        assertTrue(verifyAt >= 0, "build job is missing the \"" + VERIFY_ASSETS_STEP + "\" step");

        for (String uploadStep : UPLOAD_STEPS) {
            int uploadAt = stepIndex(steps, uploadStep);
            assertTrue(uploadAt >= 0, "expected publishing step \"" + uploadStep
                    + "\" in the build job");
            assertTrue(verifyAt > uploadAt, "\"" + VERIFY_ASSETS_STEP + "\" runs before \""
                    + uploadStep + "\"; it would always see an empty release");
        }
    }

    /**
     * Both release targets must be verified. The release path publishes the version tag <em>and</em>
     * the stable {@code latest} release whose download URLs the release body advertises; the push
     * path publishes {@code latest-prerelease}. A guard that checked only one of them would leave
     * the others free to ship empty.
     */
    @Test
    void releaseVerificationCoversEveryPublishedTarget() throws Exception {
        String script = verifyScript(readBuildJobSteps());

        assertTrue(Pattern.compile("(?m)^\\s*TARGETS=\"\\$RELEASE_TAG latest\"\\s*$")
                        .matcher(script).find(),
                "guard does not verify the version-tag and latest release targets");
        assertTrue(Pattern.compile("(?m)^\\s*TARGETS=\"latest-prerelease\"\\s*$")
                        .matcher(script).find(),
                "guard does not verify the latest-prerelease release target");
        assertTrue(Pattern.compile("(?m)^\\s*for target in \\$TARGETS; do\\s*$")
                        .matcher(script).find(),
                "guard does not iterate over its assigned release targets");
    }
}
