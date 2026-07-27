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
 * published (0.6.0 and 0.7.0 shipped with an empty asset list).
 *
 * <p>A repair necessarily <em>executes old tagged source</em> - a Gradle build and whatever its
 * plugins resolve - that nobody reviewed as part of the repair. So the security question is not
 * whether that build is trustworthy but what it can reach, and the answer cannot be "we scoped the
 * token to individual steps": within one job a build step can append to {@code $GITHUB_PATH} or
 * {@code $GITHUB_ENV} and have a later token-bearing step in that same job execute a tool it
 * planted. Step-level {@code env:} is not a sandbox. The job is the boundary.
 *
 * <p>Hence the split these tests pin: a {@code build} job with {@code contents: read} that runs the
 * tag, and a {@code publish} job with {@code contents: write} that checks nothing out and runs none
 * of it. Without that split, a compromised historical build could reach a write-scoped token and
 * move the live {@code latest} release - the stable {@code releases/download/latest/*.jar} URLs
 * download sites hand out - silently downgrading every consumer. A comment claiming the boundary is
 * an argument; these are the checks that can fail.
 */
class ReleaseRepairCapabilityTest {

    private static final Path WORKFLOW_PATH =
            Paths.get("..", ".github", "workflows", "release-repair.yml");
    private static final Path REPOSITORY_GIT_PATH = Paths.get("..", ".git");

    /** The job that runs untrusted tagged source. It must hold no write capability. */
    private static final String BUILD_JOB = "build";

    /** The job that holds the only write capability. It must run no tagged source. */
    private static final String PUBLISH_JOB = "publish";

    /** The live pointer releases whose download URLs are published to users. */
    private static final List<String> POINTER_RELEASES =
            Arrays.asList("latest", "latest-prerelease");

    private static final String POINTER_REFUSAL = "Refuse to repair a pointer release";

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
        if (workflowText().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try (InputStream in = Files.newInputStream(WORKFLOW_PATH)) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> job(String name) throws Exception {
        Map<String, Object> workflow = workflow();
        if (workflow.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        assertTrue(jobs != null && jobs.containsKey(name),
                "the " + name + " job is missing; the untrusted build and the write capability "
                        + "must live in separate jobs");
        return (Map<String, Object>) jobs.get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(String jobName) throws Exception {
        Map<String, Object> job = job(jobName);
        if (job.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> steps = (List<Map<String, Object>>) job.get("steps");
        assertTrue(steps != null && !steps.isEmpty(), jobName + " job has no steps");
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
        assertTrue(at >= 0, "missing the \"" + name + "\" step");
        Object run = steps.get(at).get("run");
        assertTrue(run instanceof String && !((String) run).isEmpty(),
                "\"" + name + "\" must be a run step");
        return (String) run;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stepEnv(Map<String, Object> step) {
        Object env = step.get("env");
        return env instanceof Map ? (Map<String, Object>) env : Map.of();
    }

    private static String stepLabel(Map<String, Object> step) {
        Object name = step.get("name");
        return name != null ? String.valueOf(name) : String.valueOf(step.get("uses"));
    }

    /** Every `run` script in a job, concatenated - for whole-job content assertions. */
    private static String allScripts(List<Map<String, Object>> steps) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> step : steps) {
            Object run = step.get("run");
            if (run instanceof String) {
                sb.append((String) run).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * THE split. This is the check that fails if anybody ever "simplifies" the workflow back into a
     * single job, or grants the build job a write scope, or teaches the publish job to check
     * something out.
     */
    @Test
    @SuppressWarnings("unchecked")
    void repairSplitsUntrustedBuildFromTheWriteCapability() throws Exception {
        Map<String, Object> workflow = workflow();
        assumeTrue(!workflow.isEmpty());

        // Nothing may be inherited: an implicit repository default would hand both jobs whatever
        // scopes happen to be enabled org-wide.
        Object topLevel = workflow.get("permissions");
        assertTrue(topLevel instanceof Map && ((Map<String, Object>) topLevel).isEmpty(),
                "top-level permissions must be {} so neither job inherits anything; found "
                        + topLevel);

        Map<String, Object> build = job(BUILD_JOB);
        Map<String, Object> publish = job(PUBLISH_JOB);

        assertEquals(Map.of("contents", "read"), build.get("permissions"),
                "the build job runs untrusted tagged source and must hold contents:read only; "
                        + "it holds " + build.get("permissions"));
        assertEquals(Map.of("contents", "write"), publish.get("permissions"),
                "the publish job must hold contents:write and nothing else; it holds "
                        + publish.get("permissions"));

        // Belt and braces: no job may hold any write scope other than the publish job's contents.
        for (Object value : ((Map<String, Object>) build.get("permissions")).values()) {
            assertTrue(!"write".equals(value),
                    "the build job grants a write scope; tagged source could reach it");
        }

        Object needs = publish.get("needs");
        List<String> needsList = needs instanceof List
                ? (List<String>) needs : List.of(String.valueOf(needs));
        assertTrue(needsList.contains(BUILD_JOB),
                "the publish job must depend on the build job; it needs " + needs);

        // The publish job must never materialise the tag: no checkout, no toolchain, no Gradle.
        List<Map<String, Object>> publishSteps = steps(PUBLISH_JOB);
        for (Map<String, Object> step : publishSteps) {
            Object uses = step.get("uses");
            if (uses instanceof String) {
                String action = (String) uses;
                assertTrue(!action.startsWith("actions/checkout@"),
                        "the publish job checks out source; it holds the write token and must "
                                + "never materialise the tag");
                assertTrue(!action.startsWith("actions/setup-java@")
                                && !action.startsWith("gradle/"),
                        "the publish job sets up a build toolchain (" + action + "); it must run "
                                + "none of the tagged source");
            }
        }
        String publishScripts = allScripts(publishSteps);
        assertTrue(!publishScripts.contains("gradlew"),
                "the publish job invokes Gradle; the write capability must never run tag code");

        // And the write itself must live only there.
        assertTrue(!allScripts(steps(BUILD_JOB)).contains("gh release upload"),
                "the build job uploads release assets; the write must live in the publish job");
        assertTrue(publishScripts.contains("gh release upload"),
                "the publish job never uploads any asset");
    }

    /**
     * The {@code $GITHUB_PATH} vector specifically. Even inside the read-only build job, a
     * token-bearing step placed <em>after</em> the tagged build could be made to execute a tool the
     * build planted. Nothing in that job may carry a credential once tag code has run.
     */
    @Test
    void buildJobHoldsNoCredentialOnceTaggedCodeHasRun() throws Exception {
        List<Map<String, Object>> build = steps(BUILD_JOB);
        assumeTrue(!build.isEmpty());

        // A job-level token would be visible to the tagged build itself.
        Object jobEnv = job(BUILD_JOB).get("env");
        if (jobEnv instanceof Map) {
            assertTrue(!((Map<?, ?>) jobEnv).containsKey("GH_TOKEN"),
                    "the build job declares a job-level GH_TOKEN; the tagged build would see it");
        }

        int buildAt = stepIndex(build, "Build");
        assertTrue(buildAt >= 0, "the build job is missing the Build step");

        List<String> offenders = new ArrayList<>();
        for (int i = buildAt; i < build.size(); i++) {
            if (stepEnv(build.get(i)).containsKey("GH_TOKEN")) {
                offenders.add(stepLabel(build.get(i)));
            }
        }
        assertTrue(offenders.isEmpty(),
                "step(s) " + offenders + " carry GH_TOKEN at or after the tagged build; the build "
                        + "can plant a tool on $GITHUB_PATH that a later step in the same job "
                        + "executes, so no credential may follow it");
    }

    /**
     * The artifact crossing the split was produced by a job that ran untrusted source, so its file
     * <em>names</em> are untrusted input: they become the names of public release assets. Only what
     * a connect release is supposed to carry may pass.
     */
    @Test
    void publishJobValidatesTheHandoverArtifact() throws Exception {
        List<Map<String, Object>> publish = steps(PUBLISH_JOB);
        assumeTrue(!publish.isEmpty());

        String validate = stepScript(publish, "Validate the downloaded assets");
        assertTrue(validate.contains("^connect-(spigot|velocity|bungee)"),
                "the publish job does not constrain which asset names may be published");
        assertTrue(validate.contains("exit 1"), "the asset-name validation cannot fail the run");

        int validateAt = stepIndex(publish, "Validate the downloaded assets");
        int uploadAt = stepIndex(publish, "Upload the missing assets");
        assertTrue(uploadAt >= 0, "the publish job is missing the upload step");
        assertTrue(validateAt < uploadAt,
                "the handover artifact is validated after it is uploaded; that is not validation");
    }

    /**
     * The registry half of the boundary. The permission checks above only prove the file does not
     * <em>name</em> a registry scope; this proves it does not reach one another way - a registry
     * login action, a docker/gradle publish, or a credential smuggled in through a secret other
     * than the job's own GITHUB_TOKEN.
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
     * a stable URL and downgrade everyone using it, so both jobs refuse the names outright - the
     * publish job most of all, because it is the one holding the write token.
     */
    @Test
    void repairCannotWriteAPointerRelease() throws Exception {
        String text = workflowText();
        assumeTrue(!text.isEmpty());

        for (String jobName : List.of(BUILD_JOB, PUBLISH_JOB)) {
            List<Map<String, Object>> jobSteps = steps(jobName);
            String refusal = stepScript(jobSteps, POINTER_REFUSAL);
            for (String pointer : POINTER_RELEASES) {
                assertTrue(refusal.contains(pointer),
                        "the " + jobName + " job's pointer refusal does not reject \"" + pointer
                                + "\"");
            }
            assertTrue(refusal.contains("exit 1"),
                    "the " + jobName + " job's pointer refusal does not fail the run");
            assertEquals(0, stepIndex(jobSteps, POINTER_REFUSAL),
                    "the pointer refusal must be the first step of the " + jobName + " job");
        }

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
        List<Map<String, Object>> build = steps(BUILD_JOB);
        assumeTrue(!build.isEmpty());

        String guard = stepScript(build, "Refuse to repair a complete release");
        assertTrue(guard.contains("/releases/tags/"),
                "the completeness guard must read the published release, not local state");
        assertTrue(guard.contains("connect-(spigot|velocity|bungee)"),
                "the completeness guard must classify plugin jars by name; a release carrying "
                        + "only LICENSE has a positive asset count and is still a hole");
        assertTrue(guard.contains("exit 1"), "the completeness guard does not fail the run");

        int guardAt = stepIndex(build, "Refuse to repair a complete release");
        int buildAt = stepIndex(build, "Build");
        assertTrue(buildAt >= 0, "the build job is missing the Build step");
        assertTrue(guardAt < buildAt,
                "the completeness guard runs after the build; it must refuse before rebuilding");

        // Guard 2, in the publish job: an ABSENT name uploads without --clobber, so GitHub's own
        // rejection is the atomic conditional; --clobber may only reach a name already broken.
        String upload = stepScript(steps(PUBLISH_JOB), "Upload the missing assets");
        assertTrue(upload.contains("--clobber"), "the upload does not use --clobber at all");
        assertTrue(upload.contains("ABSENT_LIST") && upload.contains("BROKEN_LIST"),
                "the upload does not separate absent names from broken ones; --clobber would "
                        + "then be able to reach an asset a concurrent writer just published");
        assertTrue(Pattern.compile("gh release upload \"\\$RELEASE_TAG\" \"\\$\\{ABSENT_LIST\\[@\\]}\""
                                + "(?!.*--clobber)").matcher(upload).find(),
                "absent assets are uploaded with --clobber; that upload must be unconditional-"
                        + "free so GitHub rejects a name published concurrently");
        assertTrue(upload.contains("state == \"uploaded\"") && upload.contains("size > 0"),
                "the upload does not classify already-good assets out of the clobber set");
    }

    /**
     * A repair must build the tag on the toolchain that tag pinned. A newer runner-default JDK that
     * fails to compile old tagged source condemns a perfectly buildable release - a false red is as
     * wrong as a false green, and here it would be recorded as "unrecoverable".
     */
    @Test
    @SuppressWarnings("unchecked")
    void repairBuildsAtTheTagsOwnPinnedToolchain() throws Exception {
        List<Map<String, Object>> build = steps(BUILD_JOB);
        assumeTrue(!build.isEmpty());

        String resolve = stepScript(build, "Resolve the tag's pinned Java toolchain");
        assertTrue(resolve.contains(".github/workflows/release.yml"),
                "the toolchain must be read from the tag's own release workflow");
        assertTrue(resolve.contains("java-version"), "the toolchain step reads no Java version");
        assertTrue(resolve.contains("exit 1"),
                "the toolchain step falls back to a default instead of failing; guessing a JDK "
                        + "produces a false result about whether the tag builds");

        int at = stepIndex(build, "Set up the tag's JDK");
        assertTrue(at >= 0, "the build job is missing the JDK setup step");
        Map<String, Object> with = (Map<String, Object>) build.get(at).get("with");
        assertTrue(with != null, "the JDK setup step has no inputs");
        assertEquals("${{ steps.toolchain.outputs.java-version }}",
                String.valueOf(with.get("java-version")),
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
        List<Map<String, Object>> publish = steps(PUBLISH_JOB);
        assumeTrue(!publish.isEmpty());

        String verify = stepScript(publish, "Verify published release assets");

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
                "the repair verification reads local build output; those files exist even when "
                        + "the upload never happened");

        // Wrong-artifact-type is its own failure mode and the more dangerous one, because the
        // release looks populated. A positive asset count must not be satisfiable by LICENSE, a
        // checksum manifest, an SBOM or a source archive: only a real plugin jar counts.
        assertTrue(verify.contains("^connect-(spigot|velocity|bungee).*\\\\.jar$"),
                "the landed check does not require a real plugin jar by name; an asset such as "
                        + "source.tar.gz could satisfy it while no build landed");
        assertTrue(Pattern.compile("BUILD_COUNT\"\\s*-eq\\s*0").matcher(verify).find(),
                "the repair verification does not fail on a zero build-artifact count");

        // Unconditional: a guard that can be gated off is not a guard.
        assertTrue(publish.get(stepIndex(publish, "Verify published release assets")).get("if") == null,
                "the repair verification is conditional; it must always run");

        assertTrue(stepIndex(publish, "Verify published release assets")
                        > stepIndex(publish, "Upload the missing assets"),
                "the repair verification runs before the upload; it would see the old release");
    }

    /**
     * "The workflow only names $RELEASE_TAG, so it cannot move a pointer" is an argument about the
     * source. This is the check on the landed fact: record both pointer releases before anything is
     * written and assert they are unchanged afterwards, so a move shows up as a red run rather than
     * as a server owner's surprise downgrade.
     */
    @Test
    void repairProvesNoPointerReleaseMoved() throws Exception {
        List<Map<String, Object>> publish = steps(PUBLISH_JOB);
        assumeTrue(!publish.isEmpty());

        String before = stepScript(publish, "Record the live pointer releases");
        String after = stepScript(publish, "Verify no pointer release moved");
        for (String pointer : POINTER_RELEASES) {
            assertTrue(before.contains(pointer) && after.contains(pointer),
                    "the pointer-immutability check does not cover \"" + pointer + "\"");
        }

        assertTrue(before.contains("gh api") && after.contains("gh api"),
                "the pointer-immutability check must read the releases from the API");
        assertTrue(after.contains("diff"), "the pointer check never compares before against after");
        assertTrue(after.contains("exit 1"), "the pointer check cannot fail the run");

        int recordAt = stepIndex(publish, "Record the live pointer releases");
        int uploadAt = stepIndex(publish, "Upload the missing assets");
        int checkAt = stepIndex(publish, "Verify no pointer release moved");
        assertTrue(recordAt < uploadAt,
                "the pointer baseline is recorded after the upload; it must predate every write "
                        + "this run performs");
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

        List<Map<String, Object>> build = steps(BUILD_JOB);
        Map<String, Object> checkout = null;
        for (Map<String, Object> step : build) {
            Object uses = step.get("uses");
            if (uses instanceof String && ((String) uses).startsWith("actions/checkout@")) {
                checkout = step;
                break;
            }
        }
        assertTrue(checkout != null, "the build job never checks out the repository");
        Map<String, Object> with = (Map<String, Object>) checkout.get("with");
        assertTrue(with != null, "the checkout step has no inputs");
        assertEquals("${{ inputs.release_tag }}", String.valueOf(with.get("ref")),
                "the checkout must build the requested tag, not the dispatch ref");
        assertEquals(Boolean.FALSE, with.get("persist-credentials"),
                "the checkout persists credentials into .git/config, handing the tagged Gradle "
                        + "build a usable token");

        // Provenance: the checked-out HEAD must be proven to be the tag, not a branch of that name.
        String provenance = stepScript(build, "Confirm the checkout is the tagged commit");
        assertTrue(provenance.contains("refs/tags/") && provenance.contains("exit 1"),
                "the repair does not prove it built the tagged commit");
    }
}
