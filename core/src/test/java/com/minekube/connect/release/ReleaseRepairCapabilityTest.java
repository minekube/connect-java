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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code release-repair.yml} rebuilds an old tag and fills in the assets its original release never
 * published (0.6.0 and 0.7.0 shipped with an empty asset list). Rebuilding an old tag is exactly the
 * operation that can do the most damage if it is allowed to publish anywhere but that one release:
 * re-running {@code release.yml} at an old tag would drag the live {@code latest} release - the
 * stable {@code releases/download/latest/*.jar} URLs download sites hand out - backwards and
 * silently downgrade every consumer.
 *
 * <p>These tests pin the capability boundary that makes that unrepresentable rather than merely
 * discouraged: {@code contents: write} and nothing else, no registry scope, no secret beyond the
 * job's own {@code GITHUB_TOKEN}, and no release named other than the one being repaired. A comment
 * saying so is an argument; this is the check that can fail.
 */
class ReleaseRepairCapabilityTest {

    private static final Path WORKFLOW_PATH =
            Paths.get("..", ".github", "workflows", "release-repair.yml");
    private static final Path REPOSITORY_GIT_PATH = Paths.get("..", ".git");

    /** The live pointer releases whose download URLs are published to users. */
    private static final List<String> POINTER_RELEASES =
            Arrays.asList("latest", "latest-prerelease");

    private static String workflowText() throws Exception {
        if (!Files.exists(WORKFLOW_PATH)) {
            if (Files.exists(REPOSITORY_GIT_PATH)) {
                throw new AssertionError(
                        WORKFLOW_PATH + " is missing from the repository checkout");
            }
            assumeTrue(false, WORKFLOW_PATH + " is unavailable outside a repository checkout");
            return "";
        }
        return new String(Files.readAllBytes(WORKFLOW_PATH), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> workflow() throws Exception {
        String text = workflowText();
        if (text.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try (InputStream in = Files.newInputStream(WORKFLOW_PATH)) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> repairJob() throws Exception {
        Map<String, Object> workflow = workflow();
        if (workflow.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        assertTrue(jobs != null && jobs.containsKey("repair"), "repair job is missing");
        return (Map<String, Object>) jobs.get("repair");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps() throws Exception {
        Map<String, Object> job = repairJob();
        if (job.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> steps = (List<Map<String, Object>>) job.get("steps");
        assertTrue(steps != null && !steps.isEmpty(), "repair job has no steps");
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

    private static String stepScript(List<Map<String, Object>> steps, String name) {
        int at = stepIndex(steps, name);
        assertTrue(at >= 0, "repair job is missing the \"" + name + "\" step");
        Object run = steps.get(at).get("run");
        assertTrue(run instanceof String && !((String) run).isEmpty(),
                "\"" + name + "\" must be a run step");
        return (String) run;
    }

    /**
     * The boundary itself. A repair that could also push to a package registry would be able to
     * republish an old build as something users pull by default - the exact silent downgrade this
     * workflow exists to avoid. Absent capability beats a guarded one.
     */
    @Test
    @SuppressWarnings("unchecked")
    void repairGrantsContentsWriteAndNothingElse() throws Exception {
        Map<String, Object> workflow = workflow();
        assumeTrue(!workflow.isEmpty());

        Object permissions = workflow.get("permissions");
        assertTrue(permissions instanceof Map,
                "release-repair.yml must declare an explicit permissions block; inheriting the "
                        + "repository default would hand the repair whatever scopes happen to be "
                        + "enabled");

        Map<String, Object> granted = (Map<String, Object>) permissions;
        assertEquals(Map.of("contents", "write"), granted,
                "release-repair.yml must grant contents:write and nothing else; it grants "
                        + granted);

        // A job-level block would silently widen the workflow-level grant.
        Object jobPermissions = repairJob().get("permissions");
        assertTrue(jobPermissions == null || Map.of("contents", "write").equals(jobPermissions),
                "the repair job widens the workflow permissions to " + jobPermissions);
    }

    /**
     * The scope check above only proves the file does not <em>name</em> a registry permission. This
     * proves it does not reach one another way: a registry login action, a docker/gradle publish, or
     * a credential smuggled in through a secret other than the job's own GITHUB_TOKEN.
     */
    @Test
    void repairNeverPublishesToAPackageRegistry() throws Exception {
        String text = workflowText();
        assumeTrue(!text.isEmpty());
        String lower = text.toLowerCase(Locale.ROOT);

        List<String> registrySurfaces = Arrays.asList(
                "packages: write",      // the GitHub Packages scope
                "id-token:",            // OIDC federation into an external registry
                "ghcr.io",              // GitHub container registry
                "docker/login-action",  // registry login
                "docker push",
                "gradle-publish",
                "maven-publish",
                "publishtomavenlocal",
                "publishallpublications",
                "./gradlew publish",
                "npm publish",
                "sonatype",
                "nexus");

        List<String> found = new ArrayList<>();
        for (String surface : registrySurfaces) {
            if (lower.contains(surface)) {
                found.add(surface);
            }
        }
        assertTrue(found.isEmpty(), "release-repair.yml references registry publishing surface "
                + found + "; the repair must be unable to push a build anywhere but this "
                + "release's own assets");

        // Any secret beyond GITHUB_TOKEN is a credential this workflow must not hold. If a repair
        // genuinely needed one, that is a decision to escalate, not to quietly add here.
        Matcher secrets = Pattern.compile("secrets\\.([A-Za-z0-9_]+)").matcher(text);
        List<String> extra = new ArrayList<>();
        while (secrets.find()) {
            if (!"GITHUB_TOKEN".equals(secrets.group(1))) {
                extra.add(secrets.group(1));
            }
        }
        assertTrue(extra.isEmpty(),
                "release-repair.yml consumes secret(s) " + extra + " beyond GITHUB_TOKEN");
    }

    /**
     * {@code latest} and {@code latest-prerelease} are not versions, they are the live pointers
     * whose download URLs the release notes advertise. Repairing one would republish an old build at
     * a stable URL and downgrade everyone using it, so the workflow must refuse the names outright
     * and must never name any release but the one being repaired.
     */
    @Test
    void repairCannotWriteAPointerRelease() throws Exception {
        String text = workflowText();
        assumeTrue(!text.isEmpty());
        List<Map<String, Object>> steps = steps();

        String refusal = stepScript(steps, "Refuse to repair a pointer release");
        for (String pointer : POINTER_RELEASES) {
            assertTrue(refusal.contains(pointer),
                    "the pointer refusal does not reject \"" + pointer + "\"");
        }
        assertTrue(refusal.contains("exit 1"), "the pointer refusal does not fail the run");

        // It has to run before anything is built or uploaded, otherwise it refuses too late.
        assertEquals(0, stepIndex(steps, "Refuse to repair a pointer release"),
                "the pointer refusal must be the first step");

        // release.yml writes `tag_name: latest`. Nothing in a repair may.
        assertTrue(!Pattern.compile("(?m)tag_name:\\s*latest").matcher(text).find(),
                "release-repair.yml writes a pointer release via tag_name");

        // Every asset upload must name the tag under repair and no other release.
        Matcher uploads = Pattern.compile("gh release upload (\\S+)").matcher(text);
        int uploadCount = 0;
        while (uploads.find()) {
            uploadCount++;
            assertEquals("\"$RELEASE_TAG\"", uploads.group(1),
                    "release-repair.yml uploads to a release other than the tag under repair");
        }
        assertTrue(uploadCount > 0, "release-repair.yml never uploads any asset");
    }

    /**
     * Guard 1. Repair fills holes: a release that already publishes a plugin jar is complete, and
     * {@code --clobber} over it would replace exactly the bytes server owners already run. The check
     * has to precede the build, or a needless rebuild happens before it can say no.
     */
    @Test
    void repairRefusesToClobberACompleteRelease() throws Exception {
        List<Map<String, Object>> steps = steps();
        assumeTrue(!steps.isEmpty());

        String guard = stepScript(steps, "Refuse to repair a complete release");
        assertTrue(guard.contains("/releases/tags/"),
                "the completeness guard must read the published release, not local state");
        assertTrue(guard.contains("connect-(spigot|velocity|bungee)"),
                "the completeness guard must classify plugin jars by name; a release carrying "
                        + "only LICENSE has a positive asset count and is still a hole");
        assertTrue(guard.contains("exit 1"), "the completeness guard does not fail the run");

        int guardAt = stepIndex(steps, "Refuse to repair a complete release");
        int buildAt = stepIndex(steps, "Build");
        assertTrue(buildAt >= 0, "repair job is missing the Build step");
        assertTrue(guardAt < buildAt,
                "the completeness guard runs after the build; it must refuse before rebuilding");

        // Guard 2: --clobber must apply only to holes, never to a published good asset.
        String upload = stepScript(steps, "Upload the missing assets");
        assertTrue(upload.contains("--clobber"), "the upload does not use --clobber");
        assertTrue(upload.contains("state == \"uploaded\"") && upload.contains("size > 0"),
                "the upload does not exclude already-good assets from --clobber");
        assertTrue(upload.contains("INITIAL_RELEASE_JSON"),
                "the upload does not retain the initial release snapshot");
        assertTrue(upload.contains("initial_asset_count"),
                "the upload does not distinguish initially absent assets");
        assertTrue(upload.contains("[ \"$initial_asset_count\" -eq 0 ]"),
                "the upload misclassifies assets that appeared during the repair");
        assertTrue(upload.contains("ABSENT_LIST=()") && upload.contains("BROKEN_LIST=()"),
                "the upload does not separate absent assets from broken assets");
        int rereadAt = upload.indexOf("RELEASE_JSON=$(gh api");
        int decisionAt = upload.indexOf("for f in \"${FILES[@]}\"");
        assertTrue(rereadAt >= 0 && decisionAt > rereadAt,
                "the upload does not re-read the release immediately before deciding what to "
                        + "clobber");
        assertTrue(upload.contains("PUBLISHED_DURING_REPAIR"),
                "the upload does not report a newly published good asset that it skips");
        int absentUploadAt = upload.indexOf(
                "gh release upload \"$RELEASE_TAG\" \"${ABSENT_LIST[@]}\" --repo \"$GITHUB_REPOSITORY\"");
        int brokenUploadAt = upload.indexOf(
                "gh release upload \"$RELEASE_TAG\" \"${BROKEN_LIST[@]}\" --clobber --repo \"$GITHUB_REPOSITORY\"");
        assertTrue(absentUploadAt >= 0 && brokenUploadAt > absentUploadAt,
                "the upload does not use the atomic no-clobber path for absent assets");
    }

    /**
     * A repair must build the tag on the toolchain that tag pinned. A newer runner-default JDK that
     * fails to compile old tagged source condemns a perfectly buildable release - a false red is as
     * wrong as a false green, and here it would be recorded as "unrecoverable".
     */
    @Test
    void repairBuildsAtTheTagsOwnPinnedToolchain() throws Exception {
        List<Map<String, Object>> steps = steps();
        assumeTrue(!steps.isEmpty());

        String resolve = stepScript(steps, "Resolve the tag's pinned Java toolchain");
        assertTrue(resolve.contains(".github/workflows/release.yml"),
                "the toolchain must be read from the tag's own release workflow");
        assertTrue(resolve.contains("java-version"), "the toolchain step reads no Java version");
        assertTrue(resolve.contains("exit 1"),
                "the toolchain step falls back to a default instead of failing; guessing a JDK "
                        + "produces a false result about whether the tag builds");

        int at = stepIndex(steps, "Set up the tag's JDK");
        assertTrue(at >= 0, "repair job is missing the JDK setup step");
        @SuppressWarnings("unchecked")
        Map<String, Object> with = (Map<String, Object>) steps.get(at).get("with");
        assertTrue(with != null, "the JDK setup step has no inputs");
        assertEquals("${{ steps.toolchain.outputs.java-version }}", String.valueOf(with.get("java-version")),
                "the JDK is hard-coded instead of taken from the tag's pin");

        // The tag's checkout supplies its own Gradle wrapper; a pinned Gradle here would override it.
        assertTrue(!workflowText().contains("gradle-version:"),
                "release-repair.yml pins a Gradle version, overriding the tag's own wrapper");
    }

    /**
     * The whole point of the R4 mechanism: never trust the run, read the artifact. A green upload
     * step that published nothing looks exactly like a successful repair, so the guard goes back to
     * the API and additionally proves a jar is served, not merely listed.
     */
    @Test
    void repairVerifiesTheLandedRelease() throws Exception {
        List<Map<String, Object>> steps = steps();
        assumeTrue(!steps.isEmpty());

        String verify = stepScript(steps, "Verify published release assets");

        List<String> required = Arrays.asList(
                "gh api",              // reads the API, not the local build output
                "/releases/tags/",     // re-reads the published release by tag
                "\"uploaded\"",        // only fully-uploaded assets count
                "releases/download/"); // proves a jar is actually served, not just listed
        List<String> missing = new ArrayList<>();
        for (String want : required) {
            if (!verify.contains(want)) {
                missing.add(want);
            }
        }
        assertTrue(missing.isEmpty(), "the repair verification does not reference " + missing
                + "; it must assert on the published release, not on local build output");

        assertTrue(!verify.contains("build/libs") && !verify.contains("build/release"),
                "the repair verification reads local build output; those jars exist even when "
                        + "the upload never happened");

        // Wrong-artifact-type is its own failure mode and the more dangerous one, because the
        // release looks populated. LICENSE alone must not satisfy the guard.
        assertTrue(verify.contains("^connect-(spigot|velocity|bungee).*\\\\.jar$"),
                "the repair verification does not require a plugin jar asset by name");
        assertTrue(verify.contains("size > 0"),
                "the repair verification counts zero-byte plugin assets");
        assertTrue(Pattern.compile("BUILD_COUNT\"\\s*-eq\\s*0").matcher(verify).find(),
                "the repair verification does not fail on a zero build-artifact count");

        // Unconditional: a guard that can be gated off is not a guard.
        assertTrue(steps.get(stepIndex(steps, "Verify published release assets")).get("if") == null,
                "the repair verification is conditional; it must always run");

        int verifyAt = stepIndex(steps, "Verify published release assets");
        int uploadAt = stepIndex(steps, "Upload the missing assets");
        assertTrue(verifyAt > uploadAt,
                "the repair verification runs before the upload; it would see the old release");
    }

    @Test
    @SuppressWarnings("unchecked")
    void repairKeepsTheWriteTokenOutOfTaggedBuildLogic() throws Exception {
        Map<String, Object> job = repairJob();
        assumeTrue(!job.isEmpty());

        Map<String, Object> jobEnv = (Map<String, Object>) job.get("env");
        assertTrue(jobEnv == null || !jobEnv.containsKey("GH_TOKEN"),
                "the write token is exposed to every step in the repair job");

        List<Map<String, Object>> steps = steps();
        Map<String, Object> build = steps.get(stepIndex(steps, "Build"));
        Map<String, Object> buildEnv = (Map<String, Object>) build.get("env");
        assertTrue(buildEnv == null || !buildEnv.containsKey("GH_TOKEN"),
                "the tagged build can access the write-scoped GitHub token");

        Map<String, Object> checkout = null;
        for (Map<String, Object> step : steps) {
            Object uses = step.get("uses");
            if (uses instanceof String && ((String) uses).startsWith("actions/checkout@")) {
                checkout = step;
                break;
            }
        }
        assertTrue(checkout != null, "release-repair.yml never checks out the repository");
        Map<String, Object> checkoutWith = (Map<String, Object>) checkout.get("with");
        assertEquals(Boolean.FALSE, checkoutWith.get("persist-credentials"),
                "the checkout leaves the write token in the tagged build's git config");

        List<String> apiSteps = Arrays.asList(
                "Refuse to repair a complete release",
                "Record the live pointer releases",
                "Upload the missing assets",
                "Verify published release assets",
                "Verify no pointer release moved");
        for (String name : apiSteps) {
            Map<String, Object> env =
                    (Map<String, Object>) steps.get(stepIndex(steps, name)).get("env");
            assertEquals("${{ secrets.GITHUB_TOKEN }}", env.get("GH_TOKEN"),
                    "the API step " + name + " does not receive the scoped GitHub token");
        }
    }

    /**
     * "The workflow only names $RELEASE_TAG, so it cannot move a pointer" is an argument about the
     * source. This is the check on the landed fact: record both pointer releases before the build
     * and assert they are unchanged afterwards, so a move shows up as a red run rather than as a
     * server owner's surprise downgrade.
     */
    @Test
    void repairProvesNoPointerReleaseMoved() throws Exception {
        List<Map<String, Object>> steps = steps();
        assumeTrue(!steps.isEmpty());

        String before = stepScript(steps, "Record the live pointer releases");
        String after = stepScript(steps, "Verify no pointer release moved");
        for (String pointer : POINTER_RELEASES) {
            assertTrue(before.contains(pointer) && after.contains(pointer),
                    "the pointer-immutability check does not cover \"" + pointer + "\"");
        }

        assertTrue(before.contains("gh api") && after.contains("gh api"),
                "the pointer-immutability check must read the releases from the API");
        assertTrue(after.contains("diff"), "the pointer check never compares before against after");
        assertTrue(after.contains("exit 1"), "the pointer check cannot fail the run");

        int recordAt = stepIndex(steps, "Record the live pointer releases");
        int buildAt = stepIndex(steps, "Build");
        int uploadAt = stepIndex(steps, "Upload the missing assets");
        int checkAt = stepIndex(steps, "Verify no pointer release moved");
        assertTrue(recordAt < buildAt,
                "the pointer state is recorded after the build; the baseline must predate any "
                        + "work this run does");
        assertTrue(checkAt > uploadAt,
                "the pointer check runs before the upload and would never observe a move");
    }

    /**
     * Dispatch shape. Tags older than 0.7.1 have no {@code workflow_dispatch} trigger at all, and
     * GitHub compiles a dispatched run from the workflow file at the dispatched ref - so the repair
     * only works when it is dispatched from the default branch and checks the tag out itself.
     */
    @Test
    @SuppressWarnings("unchecked")
    void repairIsDispatchedByTagAndChecksThatTagOut() throws Exception {
        Map<String, Object> workflow = workflow();
        assumeTrue(!workflow.isEmpty());

        // snakeyaml resolves the bare `on:` key as YAML 1.1 boolean true.
        Object triggers = workflow.containsKey("on") ? workflow.get("on") : workflow.get(true);
        assertTrue(triggers instanceof Map, "release-repair.yml declares no triggers");
        Map<String, Object> on = (Map<String, Object>) triggers;

        assertEquals(1, on.size(),
                "release-repair.yml must be manual-dispatch only; it also triggers on " + on.keySet());
        assertTrue(on.containsKey("workflow_dispatch"),
                "release-repair.yml is not a workflow_dispatch workflow");

        Map<String, Object> dispatch = (Map<String, Object>) on.get("workflow_dispatch");
        Map<String, Object> inputs = (Map<String, Object>) dispatch.get("inputs");
        assertTrue(inputs != null && inputs.containsKey("release_tag"),
                "release-repair.yml takes no release_tag input");
        assertEquals(Boolean.TRUE, ((Map<String, Object>) inputs.get("release_tag")).get("required"),
                "release_tag must be required; an empty tag would repair the dispatch ref");

        List<Map<String, Object>> steps = steps();
        Map<String, Object> checkout = null;
        for (Map<String, Object> step : steps) {
            Object uses = step.get("uses");
            if (uses instanceof String && ((String) uses).startsWith("actions/checkout@")) {
                checkout = step;
                break;
            }
        }
        assertTrue(checkout != null, "release-repair.yml never checks out the repository");
        Map<String, Object> with = (Map<String, Object>) checkout.get("with");
        assertTrue(with != null, "the checkout step has no inputs");
        assertEquals("${{ inputs.release_tag }}", String.valueOf(with.get("ref")),
                "the checkout must build the requested tag, not the dispatch ref");

        // Provenance: the checked-out HEAD must be proven to be the tag, not a branch of that name.
        String provenance = stepScript(steps, "Confirm the checkout is the tagged commit");
        assertTrue(provenance.contains("refs/tags/") && provenance.contains("exit 1"),
                "the repair does not prove it built the tagged commit");
    }
}
