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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

        String conditionText = (String) condition;
        List<String> required = Arrays.asList(
                "always()",
                "github.event_name == 'release'",
                "github.event_name == 'workflow_dispatch'",
                "steps.verify_release_assets.outcome == 'success'");
        for (String fragment : required) {
            assertTrue(conditionText.contains(fragment),
                    "\"" + MODRINTH_STEP + "\" condition is missing \"" + fragment + "\"");
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

    /**
     * A version created a moment ago is not always readable yet. On 0.15.12 this read-back returned
     * HTTP 404 twice in a row for versions Modrinth had in fact stored - the release went red twice
     * on a tag that had published correctly, and only manual job reruns got it green. The read-back
     * therefore has to tolerate a lagging read.
     */
    @Test
    void modrinthReadBackRetriesUntilTheVersionIsVisible() throws Exception {
        Harness harness = runPublishHarness(readBuildJobSteps(), Map.of("STUB_404_READS", "2"));

        assertEquals(0, harness.exitCode,
                "a read-back that is not visible yet must not red the step:\n" + harness.output);
        assertTrue(harness.output.contains("OK: 0.15.12+velocity published as vid-1"),
                "the retried read-back never concluded the publish:\n" + harness.output);
        assertEquals(3, harness.readBackCalls,
                "expected the two lagging read-backs to be retried and the third to confirm");
        assertEquals(1, harness.createCalls,
                "the version was created once; only the read-back may be retried");
    }

    /**
     * The retry is a bound, not a wait. A version that never becomes visible still fails the step -
     * silently accepting an unconfirmed upload is the failure this step exists to prevent.
     */
    @Test
    void modrinthReadBackRetryIsBoundedAndFailsClosed() throws Exception {
        String script = modrinthScript(readBuildJobSteps());
        int bound = localIntConstant(script, "read_attempts");

        Harness harness = runPublishHarness(script, Map.of("STUB_404_READS", "99"));

        assertEquals(1, harness.exitCode,
                "a version that never becomes readable must still fail the step:\n"
                        + harness.output);
        assertEquals(bound, harness.readBackCalls,
                "the read-back is retried " + harness.readBackCalls + " times; its declared bound is "
                        + bound);
        assertTrue(harness.output.contains(
                        "Could not read version 0.15.12+velocity back from Modrinth (HTTP 404)"),
                "the failure does not name the version it could not confirm:\n" + harness.output);
    }

    /**
     * A refused read is not a lagging read. Retrying a 401/403 cannot change the answer and delays
     * the report of a missing scope, so it ends the step on the first read.
     */
    @Test
    void modrinthReadBackNeverRetriesARefusedRead() throws Exception {
        Harness harness = runPublishHarness(
                readBuildJobSteps(), Map.of("STUB_READ_CODE", "403"));

        assertEquals(1, harness.exitCode,
                "a refused read-back must fail the step:\n" + harness.output);
        assertEquals(1, harness.readBackCalls,
                "a refused read-back was retried; a token that cannot read cannot be waited out");
        assertTrue(harness.output.contains("was refused (HTTP 403)"),
                "the failure does not report the refusal:\n" + harness.output);
    }

    /**
     * The property that matters most about the retry: a digest mismatch is final. The bytes are
     * wrong, waiting cannot make them right, and the step must never turn a mismatch into a pass -
     * so it is not retried, not downgraded, and reported on the first read that sees it.
     */
    @Test
    void modrinthReadBackNeverRetriesADigestMismatch() throws Exception {
        Harness harness = runPublishHarness(readBuildJobSteps(), Map.of(
                "STUB_STORED_SHA1", "0000000000000000000000000000000000000000"));

        assertEquals(1, harness.exitCode,
                "a digest mismatch must fail the step:\n" + harness.output);
        assertEquals(1, harness.readBackCalls,
                "the step retried a version whose stored bytes do not match; a mismatch must be "
                        + "final, never retried and never skipped");
        assertTrue(harness.output.contains("Modrinth is serving different bytes"),
                "the mismatch is not reported as different bytes:\n" + harness.output);
    }

    /**
     * A duplicate version number is the listing saying it already holds this version - inventory,
     * not a failure, and not a create to retry (creating again cannot succeed, and re-uploading is
     * not a thing). Resolve the existing version and let the same read-back confirm the bytes.
     */
    @Test
    void modrinthDuplicateCreateIsConfirmedByReadBack() throws Exception {
        Harness harness = runPublishHarness(readBuildJobSteps(),
                Map.of("STUB_CREATE", "400", "STUB_EMPTY_INVENTORIES", "1"));

        assertEquals(0, harness.exitCode,
                "a duplicate create must be resolved by read-back, not red the step:\n"
                        + harness.output);
        assertEquals(1, harness.createCalls,
                "the create was retried; a duplicate version number cannot be created again");
        assertEquals(1, harness.readBackCalls,
                "a duplicate create was accepted without reading the version back");
        assertTrue(harness.output.contains("OK: 0.15.12+velocity published as vid-1"),
                "the duplicate was not confirmed against the stored bytes:\n" + harness.output);
    }

    /**
     * Pins the shape of the retry itself, so a future edit cannot quietly turn the bounded loop
     * into an unbounded one or widen it to answers that waiting cannot fix. The behaviour above is
     * executed against a stubbed API; these are the bounds that behaviour is bounded by.
     */
    @Test
    void modrinthReadBackRetryIsBoundedWithABackoff() throws Exception {
        String script = modrinthScript(readBuildJobSteps());

        int attempts = localIntConstant(script, "read_attempts");
        assertTrue(attempts >= 2 && attempts <= 10,
                "read-back attempt bound " + attempts + " is not a sane retry budget");

        int retrySeconds = localIntConstant(script, "read_retry_seconds");
        assertTrue(retrySeconds >= 1 && retrySeconds <= 30,
                "read-back retry delay " + retrySeconds + "s is not a sane backoff");

        assertTrue(Pattern.compile("(?m)^\\s*while :; do\\s*$").matcher(script).find(),
                "\"" + MODRINTH_STEP + "\" has no bounded read-back retry loop");
        assertTrue(script.contains("sleep \"$read_retry_seconds\""),
                "\"" + MODRINTH_STEP + "\" retries the read-back without backing off");
        assertTrue(Pattern.compile("\\[\\s*\"\\$code\"\\s*!=\\s*\"404\"\\s*\\]").matcher(script).find(),
                "\"" + MODRINTH_STEP + "\" is not restricted to retrying a not-yet-visible "
                        + "version (HTTP 404); a mismatch or a refusal would be waited out too");
    }

    /**
     * The workflow shell is only exercised by a release run, so a syntax error in it would be
     * discovered by a tag that has already been cut and announced. Parse it here instead.
     */
    @Test
    void modrinthPublishStepIsValidShell() throws Exception {
        String script = modrinthScript(readBuildJobSteps());
        Path file = Files.createTempFile("modrinth-publish-step", ".sh");
        try {
            Files.write(file, script.getBytes(StandardCharsets.UTF_8));

            Process process = new ProcessBuilder("bash", "-n", file.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);

            assertEquals(0, process.waitFor(),
                    "\"" + MODRINTH_STEP + "\" is not valid shell:\n" + output);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Harness: executes the step's own shell against a stubbed Modrinth API.
    // ---------------------------------------------------------------------------------------
    private static final String PUBLISH_FUNCTION = "publish_platform()";

    /** What a harness run did: exit status, output, and how often the API was actually called. */
    private static final class Harness {
        private final int exitCode;
        private final String output;
        private final int readBackCalls;
        private final int createCalls;

        private Harness(int exitCode, String output, int readBackCalls, int createCalls) {
            this.exitCode = exitCode;
            this.output = output;
            this.readBackCalls = readBackCalls;
            this.createCalls = createCalls;
        }
    }

    /**
     * Lifts one shell function out of the step so it can be executed instead of described. The
     * function ends at the first line that is nothing but a closing brace - the step indents every
     * nesting level, so only the function's own terminator is unindented.
     */
    private static String extractFunction(String script, String signature) {
        Matcher start = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(signature) + "\\s*\\{\\s*$").matcher(script);
        assertTrue(start.find(), "\"" + MODRINTH_STEP + "\" has no " + signature + " function");

        Matcher end = Pattern.compile("(?m)^\\}\\s*$").matcher(script);
        end.region(start.end(), script.length());
        assertTrue(end.find(), "the " + signature + " function in \"" + MODRINTH_STEP
                + "\" has no closing brace at a nesting boundary");

        return script.substring(start.start(), end.end());
    }

    private static int localIntConstant(String script, String name) {
        Matcher matcher = Pattern.compile(
                "(?m)^\\s*local " + Pattern.quote(name) + "=(\\d+)\\s*$").matcher(script);
        assertTrue(matcher.find(), "\"" + MODRINTH_STEP + "\" declares no `local " + name
                + "=...`; the read-back retry has no bound to hold it");
        return Integer.parseInt(matcher.group(1));
    }

    private static int outputField(String output, String name) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(name) + "=(\\d+)\\s*$")
                .matcher(output);
        assertTrue(matcher.find(), "the harness did not report " + name + ":\n" + output);
        return Integer.parseInt(matcher.group(1));
    }

    private static Harness runPublishHarness(List<Map<String, Object>> steps,
                                             Map<String, String> stub) throws Exception {
        return runPublishHarness(modrinthScript(steps), stub);
    }

    /**
     * Runs the step's {@code publish_platform} against a stubbed API whose responses are driven by
     * the {@code STUB_*} environment: exactly the answers Modrinth gave in production, including
     * the 404-on-a-created-version race that red-ened 0.15.12.
     */
    private static Harness runPublishHarness(String script, Map<String, String> stub)
            throws Exception {
        Path dir = Files.createTempDirectory("modrinth-publish-harness");
        try {
            Path jar = dir.resolve("connect-velocity.jar");
            Files.write(jar, "the bytes this build produced".getBytes(StandardCharsets.UTF_8));
            Path harnessFile = dir.resolve("harness.sh");

            String harness = String.join("\n",
                    "set -euo pipefail",
                    "MODRINTH_PROJECT_ID=PuSyuNRf",
                    "RELEASE_TAG=0.15.12",
                    "API=\"https://api.modrinth.com/v2\"",
                    "UA=\"minekube/connect-java harness\"",
                    "CHANGELOG=\"changelog\"",
                    "CHANNEL=release",
                    "GAME_VERSIONS='[\"1.21\"]'",
                    "STUB_FILENAME=\"$(basename \"$JAR\")\"",
                    "TMP=\"$(mktemp -d)\"",
                    "trap 'rm -rf \"$TMP\"' EXIT",
                    "WANT_SHA1=\"$(sha1sum \"$JAR\" | awk '{print $1}')\"",
                    "WANT_SHA512=\"$(sha512sum \"$JAR\" | awk '{print $1}')\"",
                    "",
                    "# The step calls api() in a command substitution, so a counter kept in a shell",
                    "# variable would be incremented in a subshell and lost. Count on disk.",
                    "count() { local f=\"$TMP/count-$1\";"
                            + " echo \"$(( $(cat \"$f\" 2>/dev/null || echo 0) + 1 ))\" > \"$f\"; }",
                    "count_of() { cat \"$TMP/count-$1\" 2>/dev/null || echo 0; }",
                    "",
                    "api() {",
                    "  local out=\"$1\"; shift",
                    "  local code=200 n url=\"\" arg",
                    "  for arg in \"$@\"; do",
                    "    case \"$arg\" in \"$API\"/*) url=\"$arg\" ;; esac",
                    "  done",
                    "  case \"$url\" in",
                    "    \"$API/version\")",
                    "      count create",
                    "      code=\"${STUB_CREATE:-200}\"",
                    "      if [ \"$code\" = \"200\" ]; then",
                    "        printf '{\"id\":\"vid-1\"}' > \"$out\"",
                    "      else",
                    "        printf '{\"error\":\"A version with this version number already exists\"}'"
                            + " > \"$out\"",
                    "      fi",
                    "      ;;",
                    "    \"$API/version/vid-1\")",
                    "      count readback",
                    "      n=\"$(count_of readback)\"",
                    "      if [ \"$n\" -le \"${STUB_404_READS:-0}\" ]; then",
                    "        printf '{\"error\":\"not found\"}' > \"$out\"; code=404",
                    "      elif [ \"${STUB_READ_CODE:-200}\" != \"200\" ]; then",
                    "        printf '{\"error\":\"refused\"}' > \"$out\"; code=\"${STUB_READ_CODE}\"",
                    "      else",
                    "        printf"
                            + " '{\"id\":\"vid-1\",\"files\":[{\"filename\":\"%s\",\"hashes\":"
                            + "{\"sha1\":\"%s\",\"sha512\":\"%s\"}}]}' \\",
                    "          \"$STUB_FILENAME\" \"${STUB_STORED_SHA1:-$WANT_SHA1}\""
                            + " \"${STUB_STORED_SHA512:-$WANT_SHA512}\" > \"$out\"",
                    "      fi",
                    "      ;;",
                    "    \"$API/project/$MODRINTH_PROJECT_ID/version\")",
                    "      count inventory",
                    "      if [ \"$(count_of inventory)\" -le \"${STUB_EMPTY_INVENTORIES:-99}\" ]; then",
                    "        printf '[]' > \"$out\"",
                    "      else",
                    "        printf '[{\"id\":\"vid-1\",\"version_number\":\"%s\"}]'"
                            + " \"$RELEASE_TAG+velocity\" > \"$out\"",
                    "      fi",
                    "      ;;",
                    "    *)",
                    "      printf '{}' > \"$out\"; code=500",
                    "      ;;",
                    "  esac",
                    "  printf '%s' \"$code\"",
                    "}",
                    "",
                    "# The step backs off between attempts; the harness should not wait for it.",
                    "sleep() { echo \"HARNESS_SLEEP $*\" >&2; }",
                    "",
                    "# The step's best-effort inventory, taken before it publishes anything.",
                    "HAVE_INVENTORY=0",
                    "INVENTORY_CODE=\"$(api \"$TMP/existing.json\""
                            + " \"$API/project/$MODRINTH_PROJECT_ID/version\")\"",
                    "if [ \"$INVENTORY_CODE\" = \"200\" ]; then HAVE_INVENTORY=1; fi",
                    "",
                    extractFunction(script, PUBLISH_FUNCTION),
                    "",
                    "rc=0",
                    "# A subshell, because the step reports failure with `exit 1` - in the workflow that",
                    "# ends the step, here it must only end this publish attempt.",
                    "( publish_platform velocity \"$JAR\" '[\"velocity\"]' \"Velocity\" ) || rc=$?",
                    "echo \"PUBLISH_EXIT=$rc\"",
                    "echo \"READBACK_CALLS=$(count_of readback)\"",
                    "echo \"CREATE_CALLS=$(count_of create)\"");

            Files.write(harnessFile, harness.getBytes(StandardCharsets.UTF_8));

            ProcessBuilder builder = new ProcessBuilder("bash", harnessFile.toString());
            builder.redirectErrorStream(true);
            builder.environment().put("JAR", jar.toString());
            stub.forEach(builder.environment()::put);

            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);

            return new Harness(outputField(output, "PUBLISH_EXIT"), output,
                    outputField(output, "READBACK_CALLS"), outputField(output, "CREATE_CALLS"));
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path root) {
        try {
            List<Path> paths = new ArrayList<>();
            try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
                walk.forEach(paths::add);
            }
            paths.sort(java.util.Comparator.reverseOrder());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
            // best effort: the harness directory is throwaway
        }
    }
}
