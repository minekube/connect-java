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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the shell trust boundary of {@code release.yml}'s "Resolve release tag" step, and makes the
 * no-expression-in-a-{@code run:}-block policy <em>repo-wide</em> instead of per workflow file.
 *
 * <p>The runner expands {@code ${{ ... }}} inside a {@code run:} body as text before bash parses it,
 * so a step that reads a value the repository does not control that way is building shell
 * <em>source</em> out of it. The step under test interpolated two of them:
 *
 * <pre>
 *   TAG="${{ inputs.release_tag }}"
 *   TAG="${{ github.event.release.tag_name }}"
 * </pre>
 *
 * <p>Double quotes stop an apostrophe from closing the literal (the defect that stalled the
 * release-please auto-merge step in {@code release-please.yml}, pinned by
 * {@code ReleasePleasePayloadTest}), but they do not make the value data: a {@code "} in the value
 * ends the literal and the rest of it is parsed as shell code - in a job that declares
 * {@code contents: write} and hosts the publishing steps. Both values are attacker-shaped rather
 * than merely user-shaped: {@code inputs.release_tag} is caller-supplied on
 * {@code workflow_dispatch}, and {@code github.event.release.tag_name} is a tag name, which git
 * allows to contain {@code "}, {@code $} and backticks. This test therefore compares the fixed step
 * against the historical one it replaced: same resolution order, same
 * {@code steps.release-tag.outputs.tag} bytes for both trigger paths, and a value containing shell
 * metacharacters that stays a value.
 *
 * <p>Repo-wide twin: {@code ReleasePleasePayloadTest} delegates its run-block scan to
 * {@link WorkflowRunBlockPolicy}, which is the one implementation both classes assert on.
 */
class ReleaseWorkflowShellBoundaryTest {

    private static final Path WORKFLOW_DIR = Paths.get("..", ".github", "workflows");
    private static final Path RELEASE_WORKFLOW = WORKFLOW_DIR.resolve("release.yml");
    private static final Path REPOSITORY_GIT_PATH = Paths.get("..", ".git");

    private static final String POLICY = "release-workflow-shell-policy";
    private static final String RESOLVE_STEP = "Resolve release tag";

    /**
     * The value the step has to carry as data. A dispatch input wins over a release event's own tag
     * name, which is the precedence the two interpolated reads provided; the runner resolves this
     * leaf expression, so the script only ever sees the resulting string.
     */
    static final String TAG_ENV_VALUE =
            "${{ inputs.release_tag || github.event.release.tag_name }}";

    private static final String TAG_ENV_LINE = "RELEASE_TAG: " + TAG_ENV_VALUE;
    private static final String TAG_LINE = "TAG=\"$RELEASE_TAG\"";
    private static final String REF_FALLBACK_LINE = "TAG=\"${GITHUB_REF_NAME}\"";
    private static final String OUTPUT_LINE = "echo \"tag=$TAG\" >> \"$GITHUB_OUTPUT\"";

    /** The historical read of the dispatch input: the value is spliced into the script. */
    private static final String HISTORICAL_INPUT_LINE = "TAG=\"${{ inputs.release_tag }}\"";

    /** The historical read of a release object's tag name: the same, for the release-event path. */
    private static final String HISTORICAL_RELEASE_LINE = "TAG=\"${{ github.event.release.tag_name }}\"";

    // ---------------------------------------------------------------------------------------
    // Policy: no workflow builds a shell script out of an expression.
    // ---------------------------------------------------------------------------------------

    /**
     * The sweep the card asked for: the invariant is about the class of defect, not about one file,
     * so it is asserted over every workflow in the repository. A file that the scan stops seeing
     * (a rename, a glob that stopped matching, a workflow moved to a subdirectory) would otherwise
     * turn this test into a green no-op, so the expected file set is asserted first.
     */
    @Test
    void noWorkflowBuildsItsShellOutOfAnExpression() throws Exception {
        List<Path> workflows = workflowFiles();
        List<String> names =
                workflows.stream().map(path -> path.getFileName().toString()).collect(Collectors.toList());
        for (String required : List.of(
                "pullrequest.yml", "release-please.yml", "release-repair.yml", "release.yml")) {
            assertTrue(names.contains(required),
                    "the repo-wide policy did not read " + required + " (saw " + names + "); a sweep "
                            + "that silently misses a file passes every other assertion here");
        }

        List<String> violations = new ArrayList<>();
        for (Path workflow : workflows) {
            violations.addAll(shellPolicyViolations(
                    parse(Files.readString(workflow, StandardCharsets.UTF_8)),
                    workflow.getFileName().toString()));
        }

        assertTrue(violations.isEmpty(),
                "a workflow run: block is built out of a GitHub expression, so a value containing a "
                        + "quote becomes shell code:\n" + String.join("\n", violations));
    }

    /**
     * The RED this fix started from, kept as a guard that the policy can still see the defect it
     * exists for: rebuilding the historical step from the current workflow must be caught, by name,
     * and the scan must point at the historical lines rather than at some other step.
     */
    @Test
    void historicalTagInterpolationIsVisibleToThePolicy() throws Exception {
        String historical = historicalWorkflowText();

        assertTrue(historical.contains(HISTORICAL_INPUT_LINE),
                "the rebuilt historical step lost the interpolated dispatch input");
        assertTrue(historical.contains(HISTORICAL_RELEASE_LINE),
                "the rebuilt historical step lost the interpolated release tag name");

        List<String> violations = shellPolicyViolations(parse(historical), "release.yml");

        assertFalse(violations.isEmpty(),
                "the repo-wide policy accepted the historical interpolated tag reads - it cannot see "
                        + "the defect it exists for");
        assertEquals(2, violations.size(),
                "the policy should have caught exactly the two historical tag reads:\n"
                        + String.join("\n", violations));
        List<String> caught = List.of(violations.get(0), violations.get(1));
        assertTrue(caught.stream().anyMatch(line -> line.contains("inputs.release_tag")),
                "the policy did not name the interpolated dispatch input:\n" + String.join("\n", caught));
        assertTrue(caught.stream().anyMatch(line -> line.contains("github.event.release.tag_name")),
                "the policy did not name the interpolated release tag name:\n"
                        + String.join("\n", caught));

        violations = resolveStepViolations(parse(historical), "release.yml");
        assertFalse(violations.isEmpty(),
                "the resolve-step contract accepted the interpolated tag reads");
    }

    /**
     * The step keeps its name, its id and its event gate - {@code release.yml}'s later steps read
     * {@code steps.release-tag.outputs.tag}, and the asset-verification, Hangar and Modrinth tests
     * pin this workflow's step names, so a rename here breaks the release in three places at once.
     * The tag must reach the script as data.
     */
    @Test
    void resolveReleaseTagCarriesTheTagInItsEnv() throws Exception {
        Map<String, Object> step = resolveStep(parse(workflowText()));

        assertEquals("release-tag", String.valueOf(step.get("id")),
                "\"" + RESOLVE_STEP + "\" lost its id; every later step reads "
                        + "steps.release-tag.outputs.tag");

        String gate = String.valueOf(step.get("if"));
        assertTrue(gate.contains("github.event_name == 'release'")
                        && gate.contains("github.event_name == 'workflow_dispatch'"),
                "\"" + RESOLVE_STEP + "\" no longer runs for the two events that publish a release: "
                        + gate);

        Map<String, Object> env = stepEnv(step);
        assertEquals(TAG_ENV_VALUE, env.get("RELEASE_TAG"),
                "\"" + RESOLVE_STEP + "\" must carry the tag as data in its RELEASE_TAG env, from "
                        + TAG_ENV_VALUE + " (found: " + env.get("RELEASE_TAG") + ")");

        String script = String.valueOf(step.get("run"));
        assertFalse(script.contains("${{"),
                "\"" + RESOLVE_STEP + "\" interpolates an expression into its shell script");
        for (String required : List.of(TAG_LINE, REF_FALLBACK_LINE, OUTPUT_LINE)) {
            assertTrue(script.contains(required),
                    "\"" + RESOLVE_STEP + "\" no longer contains " + required);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Behaviour: the step's own shell, executed, against a real runner context.
    // ---------------------------------------------------------------------------------------

    /**
     * The fix has to be a refactor, not a behaviour change: for the dispatch path (with and without
     * an input), the release-event path, and a ref-name fallback, the fixed step must write exactly
     * the {@code tag=} line the historical step wrote - byte for byte - because that value selects
     * the release that is published, verified, and propagated to Hangar and Modrinth.
     */
    @Test
    void resolveStepStillWritesTheSameTagAsTheHistoricalStep() throws Exception {
        String fixed = workflowText();
        String historical = historicalWorkflowText();

        Map<String, Context> paths = new LinkedHashMap<>();
        paths.put("dispatch-with-input", new Context("0.15.14", "", "0.15.14"));
        paths.put("dispatch-without-input", new Context("", "", "main"));
        paths.put("release-event", new Context("", "0.15.14", "0.15.14"));
        paths.put("release-event-with-build-metadata", new Context("", "0.15.14+build.7",
                "0.15.14+build.7"));

        for (Map.Entry<String, Context> path : paths.entrySet()) {
            Context context = path.getValue();
            String expected = context.expectedTag();

            ResolveHarness fixedRun = runResolveStep(fixed, context);
            ResolveHarness historicalRun = runResolveStep(historical, context);

            assertEquals(0, fixedRun.exitCode,
                    "\"" + RESOLVE_STEP + "\" broke on " + path.getKey() + ":\n" + fixedRun.output);
            assertFalse(fixedRun.executedValueAsCode(),
                    "the tag value executed as shell code on " + path.getKey() + ":\n"
                            + fixedRun.output);
            assertEquals(expected, fixedRun.tag,
                    "the tag " + path.getKey() + " reports changed; it selects the release that is "
                            + "published and propagated");
            assertEquals(historicalRun.tag, fixedRun.tag,
                    "the fixed step resolved " + path.getKey() + " differently from the step it "
                            + "replaced (historical: " + historicalRun.tag + ")");
        }
    }

    /**
     * The defect this hardening exists for. A tag name is a git ref, and git allows {@code "},
     * {@code $} and backticks in one; {@code inputs.release_tag} is caller-supplied on
     * {@code workflow_dispatch}. Interpolated into the script they are parsed as shell source - the
     * historical step runs the injected command and reports a truncated tag. Reaching the script as
     * data, they are just a string, and the step's output stays byte-identical to the value it was
     * given.
     */
    @Test
    void shellMetacharactersInTheTagStayData() throws Exception {
        // Payload -> does the historical step run it as a command, or only corrupt the tag?
        Map<String, Boolean> payloads = new LinkedHashMap<>();
        // Closes the quoted literal, then runs a command in the release job.
        payloads.put("0.15.14\"; touch \"$CANARY\"; : \"", true);
        // Command substitution and a backtick, which a quoted literal still evaluates.
        payloads.put("0.15.14-$(touch \"$CANARY\")-`touch \"$CANARY\"`", true);
        // A parameter expansion and a backslash: no command runs, the tag is quietly wrong.
        payloads.put("0.15.14-${CANARY}-c:\\path\\to\\jar", false);

        for (Map.Entry<String, Boolean> payload : payloads.entrySet()) {
            for (Context context : List.of(
                    new Context(payload.getKey(), "", payload.getKey()),
                    new Context("", payload.getKey(), payload.getKey()))) {
                ResolveHarness fixedRun = runResolveStep(workflowText(), context);

                assertEquals(0, fixedRun.exitCode,
                        "the step broke on a value it must treat as data:\n" + fixedRun.output);
                assertFalse(fixedRun.executedValueAsCode(),
                        "the tag value executed as shell code:\n" + fixedRun.output);
                assertEquals(payload.getKey(), fixedRun.tag,
                        "the tag was not written to GITHUB_OUTPUT byte for byte");

                ResolveHarness historicalRun = runResolveStep(historicalWorkflowText(), context);

                assertNotEquals(payload.getKey(), historicalRun.tag,
                        "the historical step still reported the exact tag, so it never parsed the "
                                + "value as shell code");
                assertEquals(0, historicalRun.exitCode,
                        "the historical step went red; the defect is that it reports a wrong tag "
                                + "with a green step:\n" + historicalRun.output);
                assertEquals(payload.getValue(), historicalRun.executedValueAsCode(),
                        "the historical step's handling of " + payload.getKey() + " changed:\n"
                                + historicalRun.output);
            }
        }
    }

    /**
     * Weakening mutations of the fixed step must each be rejected by the policy, so a later edit
     * cannot quietly reopen the boundary - or swap the resolution order - without a red test.
     */
    @Test
    void weakeningMutationsOfTheResolveStepAreRejected() throws Exception {
        String fixed = workflowText();

        Map<String, String> mutations = new LinkedHashMap<>();
        mutations.put("shell-interpolated-dispatch-input",
                replace(fixed, TAG_LINE, "TAG=\"${{ inputs.release_tag }}\""));
        mutations.put("shell-interpolated-release-tag-name",
                replace(fixed, TAG_LINE, "TAG=\"${{ github.event.release.tag_name }}\""));
        mutations.put("tag-env-removed", removeLine(fixed,
                "(?m)^\\s*RELEASE_TAG: \\$\\{\\{ inputs\\.release_tag \\|\\| "
                        + "github\\.event\\.release\\.tag_name \\}\\}\\s*$\\n"));
        mutations.put("precedence-swapped",
                replace(fixed, TAG_ENV_LINE, "RELEASE_TAG: ${{ github.event.release.tag_name "
                        + "|| inputs.release_tag }}"));
        mutations.put("ref-name-fallback-removed", removeLine(fixed,
                "(?m)^\\s*if \\[ -z \"\\$TAG\" \\]; then\\n"
                        + "\\s*TAG=\"\\$\\{GITHUB_REF_NAME\\}\"\\n\\s*fi\\n"));
        mutations.put("unquoted-env-read",
                replace(fixed, TAG_LINE, "TAG=$RELEASE_TAG"));
        mutations.put("output-write-removed",
                replace(fixed, OUTPUT_LINE, "echo \"Release tag: $TAG\""));
        mutations.put("env-read-echoed-inert",
                replace(fixed, TAG_LINE, "echo " + TAG_LINE));
        mutations.put("tag-env-from-a-step-output-instead-of-the-input",
                replace(fixed, TAG_ENV_LINE,
                        "RELEASE_TAG: ${{ steps.version.outputs.version }}"));

        for (Map.Entry<String, String> mutation : mutations.entrySet()) {
            List<String> violations = resolveStepViolations(parse(mutation.getValue()), "release.yml");

            assertFalse(violations.isEmpty(),
                    "MUTATION_ACCEPTED|" + mutation.getKey() + "|the policy let a weakened "
                            + "resolve step through");
            assertTrue(violations.get(0).startsWith(POLICY),
                    "MUTATION_WRONG_REASON|" + mutation.getKey() + "|" + violations.get(0));
            System.out.println(
                    "MUTATION_REJECTED|" + mutation.getKey() + "|reason=" + violations.get(0));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Harness: executes the step's own run block with a stubbed runner context.
    // ---------------------------------------------------------------------------------------

    /** What a harness run resolved: exit status, output, the written tag, and any executed code. */
    private static final class ResolveHarness {
        private final int exitCode;
        private final String output;
        private final String tag;
        private final boolean canary;

        private ResolveHarness(int exitCode, String output, String tag, boolean canary) {
            this.exitCode = exitCode;
            this.output = output;
            this.tag = tag;
            this.canary = canary;
        }

        /** Did the tag value stop being data and run as a command? */
        private boolean executedValueAsCode() {
            return canary;
        }
    }

    /** The runner context a path supplies, named the way the workflow expressions name them. */
    private static final class Context {
        private final String dispatchTag;
        private final String releaseTagName;
        private final String refName;

        private Context(String dispatchTag, String releaseTagName, String refName) {
            this.dispatchTag = dispatchTag;
            this.releaseTagName = releaseTagName;
            this.refName = refName;
        }

        /** inputs.release_tag wins, then the release's own tag name, then the dispatched ref. */
        private String expectedTag() {
            for (String candidate : List.of(dispatchTag, releaseTagName, refName)) {
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
            return "";
        }
    }

    /**
     * Runs the step's own {@code run:} block with the step's own env entries, rendered the way the
     * runner does - the textual substitution that made the defect possible. Every env entry the step
     * declares is exported from a file, so a value containing quotes, {@code $} or backticks reaches
     * the shell the way GitHub passes it. {@code $CANARY} points at a file the harness watches: if
     * the script runs something out of the tag value, the file appears.
     */
    private static ResolveHarness runResolveStep(String workflowText, Context context)
            throws Exception {
        Map<String, Object> step = resolveStep(parse(workflowText));
        Path dir = Files.createTempDirectory("release-tag-shell-boundary");
        try {
            Path githubOutput = dir.resolve("github-output");
            Files.write(githubOutput, new byte[0]);
            Path canary = dir.resolve("canary");

            List<String> harness = new ArrayList<>();
            int index = 0;
            harness.add(exported(dir, index++, "GITHUB_OUTPUT", githubOutput.toString()));
            harness.add(exported(dir, index++, "GITHUB_REF_NAME", context.refName));
            harness.add(exported(dir, index++, "CANARY", canary.toString()));
            harness.add(exported(dir, index++, "GITHUB_REPOSITORY", "minekube/connect-java"));

            for (Map.Entry<String, Object> entry : stepEnv(step).entrySet()) {
                harness.add(exported(dir, index++, entry.getKey(),
                        renderExpressions(String.valueOf(entry.getValue()), context)));
            }

            harness.add("");
            harness.add(renderExpressions(String.valueOf(step.get("run")), context));
            harness.add("");
            harness.add("echo RESOLVE_STEP_COMPLETED");

            Path file = dir.resolve("resolve-release-tag.sh");
            Files.write(file, String.join("\n", harness).getBytes(StandardCharsets.UTF_8));

            Process process = new ProcessBuilder("bash", "-e", file.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            int exitCode = process.waitFor();

            String tag = null;
            if (Files.exists(githubOutput)) {
                for (String line : Files.readAllLines(githubOutput, StandardCharsets.UTF_8)) {
                    if (line.startsWith("tag=")) {
                        tag = line.substring("tag=".length());
                    }
                }
            }

            return new ResolveHarness(exitCode, output, tag, Files.exists(canary));
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * Exports one value the way the runner does - through the environment, never spliced into the
     * script. The harness has to obey the rule it asserts: interpolating a payload here would run it
     * inside the harness itself, before the step under test ever sees it (which is exactly what the
     * first draft of this harness did).
     */
    private static String exported(Path dir, int index, String name, String value)
            throws Exception {
        Path file = dir.resolve("harness-env-" + index);
        Files.write(file, value.getBytes(StandardCharsets.UTF_8));
        return "export " + name + "=\"$(cat \"" + file + "\")\"";
    }

    /**
     * Renders GitHub's template expressions the way the runner does - as a plain text substitution
     * into the script, which is exactly what made the defect possible. An unknown expression is a
     * harness bug, not a policy failure, so it is reported as such.
     */
    private static String renderExpressions(String text, Context context) {
        String rendered = text;
        // The "||" forms first: the leaf expressions below are substrings of them.
        rendered = rendered.replace("${{ inputs.release_tag || github.event.release.tag_name }}",
                context.expectedTag());
        rendered = rendered.replace("${{ github.event.release.tag_name || inputs.release_tag }}",
                context.expectedTag());
        rendered = rendered.replace("${{ inputs.release_tag }}", context.dispatchTag);
        rendered = rendered.replace("${{ github.event.release.tag_name }}", context.releaseTagName);
        rendered = rendered.replace("${{ steps.version.outputs.version }}", "0.15.14");

        Matcher leftover = Pattern.compile("\\$\\{\\{[^}]*}}").matcher(rendered);
        if (leftover.find()) {
            throw new AssertionError("the harness cannot render " + leftover.group()
                    + " - teach renderExpressions about it");
        }
        return rendered;
    }

    // ---------------------------------------------------------------------------------------
    // Policy implementation.
    // ---------------------------------------------------------------------------------------

    /** The repo-wide invariant, from the one implementation both release tests share. */
    private static List<String> shellPolicyViolations(
            Map<String, Object> workflow, String workflowLabel) {
        return WorkflowRunBlockPolicy.violations(workflow, POLICY, workflowLabel);
    }

    /**
     * The resolve step's own contract on top of the repo-wide one: the tag is carried in the step's
     * env, the script reads that env, and the resolution order (input, then the release's tag name,
     * then the ref this run was dispatched on) is the one the historical step had.
     */
    private static List<String> resolveStepViolations(
            Map<String, Object> workflow, String workflowLabel) {
        List<String> violations =
                new ArrayList<>(shellPolicyViolations(workflow, workflowLabel));

        Map<String, Object> step = findStep(workflow, RESOLVE_STEP);
        if (step == null) {
            violations.add(POLICY + ": " + workflowLabel + " is missing its \"" + RESOLVE_STEP
                    + "\" step");
            return violations;
        }

        Object tag = stepEnv(step).get("RELEASE_TAG");
        if (!TAG_ENV_VALUE.equals(tag)) {
            violations.add(POLICY + ": " + workflowLabel + " \"" + RESOLVE_STEP
                    + "\" must carry the tag as data in its RELEASE_TAG env from " + TAG_ENV_VALUE
                    + " (found: " + tag + ")");
        }

        String commands = commandLines(String.valueOf(step.get("run")));
        if (!commands.contains(TAG_LINE)) {
            violations.add(POLICY + ": " + workflowLabel + " \"" + RESOLVE_STEP
                    + "\" must read the tag out of its environment with " + TAG_LINE);
        }
        if (!commands.contains(REF_FALLBACK_LINE)) {
            violations.add(POLICY + ": " + workflowLabel + " \"" + RESOLVE_STEP
                    + "\" must fall back to the runner's ref name with " + REF_FALLBACK_LINE);
        }
        if (!commands.contains(OUTPUT_LINE)) {
            violations.add(POLICY + ": " + workflowLabel + " \"" + RESOLVE_STEP
                    + "\" must publish the resolved tag as " + OUTPUT_LINE);
        }
        return violations;
    }

    /**
     * The script's executable lines: a command that only appears in a comment or as an argument to
     * {@code echo} does not run, so it must not satisfy the policy.
     */
    private static String commandLines(String script) {
        StringBuilder commands = new StringBuilder();
        for (String line : stripComments(script).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("echo ")) {
                continue;
            }
            commands.append(trimmed).append('\n');
        }
        return commands.toString();
    }

    /**
     * Removes shell comments the way bash reads them: an unquoted {@code #} starting a word begins
     * a comment, so a command hidden in a comment - including one appended to a real command, like
     * {@code if # gh pr merge ...} - never runs and must not satisfy the policy.
     */
    private static String stripComments(String script) {
        StringBuilder stripped = new StringBuilder();
        for (String line : script.split("\n", -1)) {
            boolean single = false;
            boolean doubled = false;
            boolean escaped = false;
            boolean wordStart = true;
            int cut = -1;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (escaped) {
                    escaped = false;
                    wordStart = false;
                    continue;
                }
                if (c == '\\' && !single) {
                    escaped = true;
                    wordStart = false;
                    continue;
                }
                if (c == '\'' && !doubled) {
                    single = !single;
                    wordStart = false;
                    continue;
                }
                if (c == '"' && !single) {
                    doubled = !doubled;
                    wordStart = false;
                    continue;
                }
                if (c == '#' && !single && !doubled && wordStart) {
                    cut = i;
                    break;
                }
                wordStart = c == ' ' || c == '\t' || c == '(' || c == ';' || c == '|' || c == '&';
            }
            stripped.append(cut >= 0 ? line.substring(0, cut) : line).append('\n');
        }
        return stripped.toString();
    }

    // ---------------------------------------------------------------------------------------
    // Workflow access.
    // ---------------------------------------------------------------------------------------

    private static List<Path> workflowFiles() throws Exception {
        if (!Files.isDirectory(WORKFLOW_DIR)) {
            if (Files.exists(REPOSITORY_GIT_PATH)) {
                throw new AssertionError(
                        WORKFLOW_DIR + " is missing from the repository checkout");
            }
            assumeTrue(false, WORKFLOW_DIR + " is unavailable outside a repository checkout");
            return List.of();
        }
        try (Stream<Path> entries = Files.list(WORKFLOW_DIR)) {
            List<Path> workflows = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml")
                            || path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .collect(Collectors.toList());
            assertTrue(workflows.size() > 1,
                    "the repo-wide policy found " + workflows.size() + " workflow file(s) in "
                            + WORKFLOW_DIR);
            return workflows;
        }
    }

    private static String workflowText() throws Exception {
        if (!Files.exists(RELEASE_WORKFLOW)) {
            if (Files.exists(REPOSITORY_GIT_PATH)) {
                throw new AssertionError(
                        RELEASE_WORKFLOW + " is missing from the repository checkout");
            }
            assumeTrue(false, RELEASE_WORKFLOW + " is unavailable outside a repository checkout");
        }
        return Files.readString(RELEASE_WORKFLOW, StandardCharsets.UTF_8);
    }

    /**
     * The step this fix replaced, rebuilt from the current workflow: the two interpolated reads come
     * back and the env entry that carries the tag as data goes away. Rebuilding it from the current
     * text - instead of pasting a copy - is what makes the historical-form assertions fail the day
     * the fix is reverted in a different shape.
     */
    private static String historicalWorkflowText() throws Exception {
        String historical = removeLine(workflowText(),
                "(?m)^\\s*RELEASE_TAG: \\$\\{\\{ inputs\\.release_tag \\|\\| "
                        + "github\\.event\\.release\\.tag_name \\}\\}\\s*$\\n");
        return replace(historical, TAG_LINE,
                HISTORICAL_INPUT_LINE + "\n          if [ -z \"$TAG\" ]; then\n            "
                        + HISTORICAL_RELEASE_LINE + "\n          fi");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String text) {
        return (Map<String, Object>) new Yaml().load(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findStep(Map<String, Object> workflow, String name) {
        Object jobs = workflow.get("jobs");
        if (!(jobs instanceof Map)) {
            return null;
        }
        for (Object job : ((Map<String, Object>) jobs).values()) {
            if (!(job instanceof Map)) {
                continue;
            }
            Object steps = ((Map<String, Object>) job).get("steps");
            if (!(steps instanceof List)) {
                continue;
            }
            for (Object candidate : (List<Object>) steps) {
                if (candidate instanceof Map && name.equals(((Map<String, Object>) candidate).get("name"))) {
                    return (Map<String, Object>) candidate;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> resolveStep(Map<String, Object> workflow) {
        Map<String, Object> step = findStep(workflow, RESOLVE_STEP);
        if (step == null) {
            throw new AssertionError(
                    "release.yml is missing its \"" + RESOLVE_STEP + "\" step");
        }
        return step;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stepEnv(Map<String, Object> step) {
        Object env = step.get("env");
        return env instanceof Map ? (Map<String, Object>) env : Map.of();
    }

    // ---------------------------------------------------------------------------------------
    // Text helpers for the historical form and the weakening mutations.
    // ---------------------------------------------------------------------------------------

    private static String replace(String text, String from, String to) {
        int index = text.indexOf(from);
        assertTrue(index >= 0, "the mutation's anchor is not in the workflow: " + from);
        return text.substring(0, index) + to + text.substring(index + from.length());
    }

    private static String removeLine(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        assertTrue(matcher.find(), "the mutation's line pattern does not match: " + pattern);
        return text.substring(0, matcher.start()) + text.substring(matcher.end());
    }

    private static void deleteRecursively(Path root) {
        try {
            List<Path> paths = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.forEach(paths::add);
            }
            paths.sort(Comparator.reverseOrder());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
            // best effort: the harness directory is throwaway
        }
    }
}
