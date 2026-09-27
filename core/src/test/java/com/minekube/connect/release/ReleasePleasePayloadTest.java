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

import java.io.InputStream;
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
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the payload trust boundary of release-please's "Validate and merge release PR" step.
 *
 * <p>{@code steps.rp.outputs.pr} is GitHub's JSON for the generated release PR <em>including its
 * body</em>, and that body repeats the merged commit subjects verbatim. A step that builds its
 * command text out of that payload lets any apostrophe in a subject ({@code don't}, {@code
 * Operator's}) close the quoted shell literal, at which point the rest of the payload is parsed as
 * shell code: the step dies with {@code syntax error near unexpected token `('}, the release PR is
 * never merged and no tag or asset appears - while the {@code ci} run on the same merge commit is
 * green, so nothing tells the author. The payload therefore has to reach the shell as
 * <em>data</em> (an env entry read with {@code jq}), never as script text.
 */
class ReleasePleasePayloadTest {

    private static final Path WORKFLOW_PATH =
            Paths.get("..", ".github", "workflows", "release-please.yml");
    private static final Path REPOSITORY_GIT_PATH = Paths.get("..", ".git");

    private static final String MERGE_STEP = "Validate and merge release PR";
    private static final String POLICY = "release-please-payload-policy";
    private static final String PAYLOAD_EXPRESSION = "${{ steps.rp.outputs.pr }}";

    /** The one line that turns the payload into a number without ever becoming shell text. */
    private static final String PARSE_LINE = "PR_NUMBER=$(printf '%s' \"$RP_PR\" | jq -r '.number')";

    /** The historical form: the payload interpolated into a single-quoted shell literal. */
    private static final String HISTORICAL_PARSE_LINE =
            "PR_NUMBER=$(jq -r '.number' <<< '" + PAYLOAD_EXPRESSION + "')";

    private static final String REPOSITORY = "minekube/connect-java";
    private static final String RELEASE_BRANCH =
            "release-please--branches--main--components--connect-java";
    private static final String HEAD_SHA = "016d4b1a1c2d3e4f5061728394a5b6c7d8e9f0a1";
    private static final String RUN_ID = "98765";
    private static final String RUN_URL =
            "https://github.com/minekube/connect-java/actions/runs/" + RUN_ID;
    private static final String TAG = "0.15.14";

    /**
     * The shape GitHub recorded for the stalled gate release (run 36320821502, job 108624180274),
     * re-worded for this repository: the release PR body repeats the merged subjects, apostrophes
     * included.
     */
    private static final String APOSTROPHE_PAYLOAD = "{\"headBranchName\":\"" + RELEASE_BRANCH
            + "\",\"baseBranchName\":\"main\",\"number\":1202,"
            + "\"title\":\"chore(main): release 0.15.14\","
            + "\"body\":\":robot: I have created a release *beep* *boop*\\n---\\n\\n"
            + "### Bug Fixes\\n\\n* **release:** don't let a merged commit's apostrophe stall the "
            + "release-please auto-merge step "
            + "([#166](https://github.com/minekube/connect-java/pull/166)) "
            + "([016d4b1](https://github.com/minekube/connect-java/commit/016d4b1))\\n\","
            + "\"files\":[],\"labels\":[\"autorelease: pending\"]}";

    /** The same payload with every apostrophe removed: the shape that always worked. */
    private static final String QUOTE_FREE_PAYLOAD =
            APOSTROPHE_PAYLOAD.replace("don't", "do not").replace("commit's", "commit");

    // ---------------------------------------------------------------------------------------
    // Policy: no expression text inside a shell script, payload carried as data.
    // ---------------------------------------------------------------------------------------

    /**
     * A workflow step that interpolates any expression into its {@code run} script is building
     * shell text out of data the repository does not control. For the release PR payload that is
     * the reported defect; for every other expression it is the same class of failure waiting for
     * a value that happens to contain a quote.
     */
    @Test
    void releasePleaseShellNeverInterpolatesExpressions() throws Exception {
        List<String> violations = policyViolations(parse(workflowText()));

        assertTrue(violations.isEmpty(),
                "release-please/SKILL.md's payload trust boundary is violated:\n"
                        + String.join("\n", violations));
    }

    /**
     * The step must carry the payload in its own env and parse it as JSON, and it must keep the
     * synchronous merge plus rerun dispatch described in {@code .agents/skills/release/SKILL.md}.
     */
    @Test
    void mergeStepCarriesTheReleasePrPayloadAsData() throws Exception {
        String script = String.valueOf(mergeStep(parse(workflowText())).get("run"));

        assertFalse(script.contains("${{"),
                "\"" + MERGE_STEP + "\" interpolates an expression into its shell script");
        assertTrue(script.contains(PARSE_LINE),
                "\"" + MERGE_STEP + "\" does not read its payload out of the environment as JSON");
        assertTrue(script.contains("--repo \"$GITHUB_REPOSITORY\""),
                "\"" + MERGE_STEP + "\" builds its gh invocations from ${{ github.repository }} "
                        + "instead of the runner's $GITHUB_REPOSITORY");
    }

    // ---------------------------------------------------------------------------------------
    // Behaviour: the step's own shell, executed with a stub gh, against a real-shaped payload.
    // ---------------------------------------------------------------------------------------

    /**
     * The reported failure, end to end: the release PR body repeats a merged subject that contains
     * apostrophes, and the step must still merge exactly the validated head and re-dispatch itself.
     */
    @Test
    void apostropheInTheReleasePrBodyStillMergesTheValidatedHead() throws Exception {
        Harness harness = runMergeStepHarness(parse(workflowText()), APOSTROPHE_PAYLOAD);

        assertEquals(0, harness.exitCode,
                "\"" + MERGE_STEP + "\" did not survive an apostrophe in the release PR body:\n"
                        + harness.output);
        assertFalse(harness.output.toLowerCase().contains("syntax error")
                        || harness.output.contains("jq: error"),
                "the step's shell was built out of the payload:\n" + harness.output);
        assertTrue(harness.mergedHead(1202, HEAD_SHA),
                "the step did not merge PR 1202 at " + HEAD_SHA + " (the payload's own number) - "
                        + "recorded gh calls:\n" + String.join("\n", harness.ghCalls));
        assertTrue(harness.dispatched("pullrequest.yml"),
                "the release PR build was never dispatched:\n"
                        + String.join("\n", harness.ghCalls));
        assertTrue(harness.dispatched("release-please.yml"),
                "\"" + MERGE_STEP + "\" never re-dispatched itself, so the tag is never cut:\n"
                        + String.join("\n", harness.ghCalls));
    }

    /** The same step with a quote-free body - the case that always passed, which is the silence. */
    @Test
    void quoteFreeReleasePrBodyStillMerges() throws Exception {
        Harness harness = runMergeStepHarness(parse(workflowText()), QUOTE_FREE_PAYLOAD);

        assertEquals(0, harness.exitCode,
                "\"" + MERGE_STEP + "\" broke on a quote-free payload:\n" + harness.output);
        assertTrue(harness.mergedHead(1202, HEAD_SHA),
                "the step did not merge the release PR:\n"
                        + String.join("\n", harness.ghCalls));
    }

    /**
     * Rebuilds the historical single-quoted form from the current workflow and asserts the harness
     * catches it - the RED this fix started from, and the guard that the harness is still able to
     * see the defect it exists for.
     */
    @Test
    void historicallyInterpolatedPayloadIsCaught() throws Exception {
        String historical = removeLine(
                replace(workflowText(), PARSE_LINE, HISTORICAL_PARSE_LINE),
                "(?m)^\\s*RP_PR: \\$\\{\\{ steps\\.rp\\.outputs\\.pr \\}\\}\\s*$\\n");

        assertFalse(policyViolations(parse(historical)).isEmpty(),
                "the policy test accepted the historical single-quoted payload");

        Harness harness = runMergeStepHarness(parse(historical), APOSTROPHE_PAYLOAD);

        assertNotEquals(0, harness.exitCode,
                "the harness executed the historical step successfully; it cannot see the defect:\n"
                        + harness.output);
        assertTrue(harness.ghCalls.isEmpty(),
                "the shell reached gh before the payload's apostrophe broke the command:\n"
                        + String.join("\n", harness.ghCalls));
        assertTrue(payloadLeakedIntoTheCommand(harness.output),
                "the historical step failed without the payload leaking into its command:\n"
                        + harness.output);
    }

    /**
     * Weakening mutations of the fixed step must each be rejected by the policy, so a later edit
     * cannot quietly reopen the boundary.
     */
    @Test
    void weakeningMutationsOfTheMergeStepAreRejected() throws Exception {
        Map<String, String> mutations = new LinkedHashMap<>();
        mutations.put("shell-interpolated-payload", replace(workflowText(), PARSE_LINE,
                "PR_NUMBER=$(printf '%s' \"" + PAYLOAD_EXPRESSION + "\" | jq -r '.number')"));
        mutations.put("single-quoted-payload-expression",
                replace(workflowText(), PARSE_LINE, HISTORICAL_PARSE_LINE));
        mutations.put("payload-env-removed", removeLine(workflowText(),
                "(?m)^\\s*RP_PR: \\$\\{\\{ steps\\.rp\\.outputs\\.pr \\}\\}\\s*$\\n"));
        mutations.put("payload-not-parsed-as-json",
                replace(workflowText(), PARSE_LINE, "PR_NUMBER=$(echo \"$RP_PR\")"));
        mutations.put("payload-echoed-inert",
                replace(workflowText(), PARSE_LINE, "echo " + PARSE_LINE));
        mutations.put("unquoted-payload",
                replace(workflowText(), PARSE_LINE, "PR_NUMBER=$(printf '%s' $RP_PR | jq -r '.number')"));
        mutations.put("repository-expression-interpolated",
                replace(workflowText(), "--repo \"$GITHUB_REPOSITORY\"",
                        "--repo \"${{ github.repository }}\""));
        mutations.put("async-merge", workflowText().replace("--merge", "--auto"));
        mutations.put("merge-in-comment-decoy", replace(workflowText(),
                "gh pr merge \"$PR_NUMBER\" \\", "# gh pr merge \"$PR_NUMBER\" \\"));
        mutations.put("match-head-commit-removed", removeLine(workflowText(),
                "(?m)^\\s*--match-head-commit \"\\$HEAD_SHA\" \\\\\\s*$\\n"));
        mutations.put("rerun-dispatch-removed", replace(workflowText(),
                "gh workflow run release-please.yml \\", "# gh workflow run release-please.yml \\"));

        for (Map.Entry<String, String> mutation : mutations.entrySet()) {
            Map<String, Object> workflow = parse(mutation.getValue());
            List<String> violations = policyViolations(workflow);

            assertFalse(violations.isEmpty(),
                    "MUTATION_ACCEPTED|" + mutation.getKey() + "|the policy test let a weakened "
                            + "merge step through");
            assertTrue(violations.get(0).startsWith(POLICY),
                    "MUTATION_WRONG_REASON|" + mutation.getKey() + "|" + violations.get(0));
            System.out.println("MUTATION_REJECTED|" + mutation.getKey() + "|reason=" + POLICY);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Harness: executes the step's own shell with a stub gh and a stubbed runner context.
    // ---------------------------------------------------------------------------------------

    /** What a harness run did: exit status, output, and every gh invocation it recorded. */
    private static final class Harness {
        private final int exitCode;
        private final String output;
        private final List<String> ghCalls;

        private Harness(int exitCode, String output, List<String> ghCalls) {
            this.exitCode = exitCode;
            this.output = output;
            this.ghCalls = ghCalls;
        }

        private boolean mergedHead(int prNumber, String headSha) {
            String expected = "pr merge " + prNumber + " --repo " + REPOSITORY
                    + " --match-head-commit " + headSha + " --merge";
            return ghCalls.stream().anyMatch(call -> call.startsWith(expected));
        }

        private boolean dispatched(String workflowFile) {
            return ghCalls.stream()
                    .anyMatch(call -> call.startsWith("workflow run " + workflowFile + " "));
        }
    }

    private static Harness runMergeStepHarness(Map<String, Object> workflow, String payload)
            throws Exception {
        Map<String, Object> step = mergeStep(workflow);
        Map<String, Object> env = stepEnv(step);
        String script = renderExpressions(String.valueOf(step.get("run")), payload);

        Path dir = Files.createTempDirectory("release-please-payload-harness");
        try {
            Path stub = dir.resolve("gh");
            Files.write(stub, GH_STUB.getBytes(StandardCharsets.UTF_8));
            stub.toFile().setExecutable(true);
            Path calls = dir.resolve("gh-calls.log");
            Files.write(calls, new byte[0]);

            List<String> harness = new ArrayList<>(List.of(
                    "set -uo pipefail",
                    "export PATH=\"" + dir + ":$PATH\"",
                    "export GITHUB_REPOSITORY=\"" + REPOSITORY + "\"",
                    "export GITHUB_REF_NAME=\"main\"",
                    "export GH_STUB_LOG=\"" + calls + "\"",
                    "export STUB_HEAD_REF=\"" + RELEASE_BRANCH + "\"",
                    "export STUB_HEAD_SHA=\"" + HEAD_SHA + "\"",
                    "export STUB_RUN_ID=\"" + RUN_ID + "\"",
                    "export STUB_RUN_URL=\"" + RUN_URL + "\"",
                    "export STUB_RUN_STATUS=\"completed\"",
                    "export STUB_RUN_CONCLUSION=\"success\"",
                    "export STUB_PR_STATE=\"MERGED\"",
                    "",
                    "# The step backs off between polls; the harness must not wait for it.",
                    "sleep() { :; }",
                    ""));

            // Every env entry the step itself declares is exported with its expression rendered, so
            // a workflow that stopped passing the payload at all is executed as it would really run.
            int index = 0;
            for (Map.Entry<String, Object> entry : env.entrySet()) {
                Path value = dir.resolve("env-" + index++);
                Files.write(value, renderExpressions(String.valueOf(entry.getValue()), payload)
                        .getBytes(StandardCharsets.UTF_8));
                harness.add("export " + entry.getKey() + "=\"$(cat \"" + value + "\")\"");
            }

            harness.add("");
            harness.add(script);
            harness.add("");
            harness.add("echo \"MERGE_STEP_COMPLETED\"");

            Path harnessFile = dir.resolve("merge-step.sh");
            Files.write(harnessFile, String.join("\n", harness).getBytes(StandardCharsets.UTF_8));

            ProcessBuilder builder = new ProcessBuilder("bash", "-e", harnessFile.toString());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);

            return new Harness(process.waitFor(), output,
                    Files.readAllLines(calls, StandardCharsets.UTF_8));
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * Renders GitHub's template expressions the way the runner does - as a plain text substitution
     * into the script, which is exactly what made the defect possible. An unknown expression is a
     * harness bug, not a policy failure, so it is reported as such.
     */
    private static String renderExpressions(String text, String payload) {
        Map<String, String> expressions = new LinkedHashMap<>();
        expressions.put("steps.rp.outputs.pr", payload);
        expressions.put("secrets.GITHUB_TOKEN", "stub-token");
        expressions.put("github.repository", REPOSITORY);
        expressions.put("github.ref_name", "main");
        expressions.put("needs.release-please.outputs.tag_name", TAG);
        expressions.put("needs.release-please.outputs.release_created", "true");

        String rendered = text;
        for (Map.Entry<String, String> expression : expressions.entrySet()) {
            rendered = rendered.replace("${{ " + expression.getKey() + " }}", expression.getValue());
        }

        Matcher leftover = Pattern.compile("\\$\\{\\{[^}]*}}").matcher(rendered);
        if (leftover.find()) {
            throw new AssertionError("the harness cannot render " + leftover.group()
                    + " - teach renderExpressions about it");
        }
        return rendered;
    }

    /**
     * The historical single-quoted form leaks the payload into the command, so bash either refuses
     * to parse it ({@code syntax error near unexpected token `('}, the wording gate's run 36320821502
     * reported) or hands the leaked words to jq as file names. Either way the payload stopped being
     * data and the step dies before it merges anything.
     */
    private static boolean payloadLeakedIntoTheCommand(String output) {
        return Pattern.compile("(?i)syntax error|unexpected|jq: error|command not found")
                .matcher(output)
                .find();
    }

    /**
     * The stub answers exactly what the step asks for, and records every invocation so the calls
     * themselves can be asserted instead of the step's log lines.
     */
    private static final String GH_STUB = String.join("\n",
            "#!/usr/bin/env bash",
            "# Answers the release-please merge step's own gh calls. Records every invocation.",
            "set -uo pipefail",
            "printf '%s\\n' \"$*\" >> \"$GH_STUB_LOG\"",
            "",
            "value_after() {",
            "  local want=\"$1\"",
            "  shift",
            "  local prev=\"\"",
            "  local arg",
            "  for arg in \"$@\"; do",
            "    if [ \"$prev\" = \"$want\" ]; then",
            "      printf '%s' \"$arg\"",
            "      return 0",
            "    fi",
            "    prev=\"$arg\"",
            "  done",
            "  return 1",
            "}",
            "",
            "case \"${1:-} ${2:-}\" in",
            "  \"pr view\")",
            "    fields=\"$(value_after --json \"${@:3}\" || true)\"",
            "    case \"$fields\" in",
            "      \"headRefName,headRefOid\")",
            "        printf '{\"headRefName\":\"%s\",\"headRefOid\":\"%s\"}' \\",
            "          \"$STUB_HEAD_REF\" \"$STUB_HEAD_SHA\"",
            "        ;;",
            "      \"headRefOid\")",
            "        printf '%s' \"$STUB_HEAD_SHA\"",
            "        ;;",
            "      \"state,headRefOid\")",
            "        printf '{\"state\":\"%s\",\"headRefOid\":\"%s\"}' \\",
            "          \"$STUB_PR_STATE\" \"$STUB_HEAD_SHA\"",
            "        ;;",
            "      *)",
            "        printf 'gh stub: unsupported pr view --json %s\\n' \"$fields\" >&2",
            "        exit 1",
            "        ;;",
            "    esac",
            "    ;;",
            "  \"run list\")",
            "    printf '%s' \"$STUB_RUN_ID\"",
            "    ;;",
            "  \"run view\")",
            "    printf '{\"status\":\"%s\",\"conclusion\":\"%s\",\"url\":\"%s\"}' \\",
            "      \"$STUB_RUN_STATUS\" \"$STUB_RUN_CONCLUSION\" \"$STUB_RUN_URL\"",
            "    ;;",
            "  \"api \"*)",
            "    printf '{\"check_runs\":[{\"name\":\"build (17)\",\"status\":\"completed\","
                    + "\"conclusion\":\"success\",\"details_url\":\"%s/job/1\"}]}' \\",
            "      \"$STUB_RUN_URL\"",
            "    ;;",
            "  \"workflow run\")",
            "    printf 'stub: dispatched %s\\n' \"$*\"",
            "    ;;",
            "  \"pr merge\")",
            "    printf 'stub: merged\\n'",
            "    ;;",
            "  *)",
            "    printf 'gh stub: unsupported invocation: %s\\n' \"$*\" >&2",
            "    exit 1",
            "    ;;",
            "esac");

    // ---------------------------------------------------------------------------------------
    // Policy implementation.
    // ---------------------------------------------------------------------------------------

    private static List<String> policyViolations(Map<String, Object> workflow) {
        // The repo-wide form of this scan lives in WorkflowRunBlockPolicy, which
        // ReleaseWorkflowShellBoundaryTest asserts over every workflow file: one implementation,
        // so a workflow added later cannot be covered here and missed there.
        List<String> violations = new ArrayList<>(
                WorkflowRunBlockPolicy.violations(workflow, POLICY, "release-please.yml"));

        Map<String, Object> merge = findStep(workflow, MERGE_STEP);
        if (merge == null) {
            violations.add(POLICY + ": release-please is missing its \"" + MERGE_STEP + "\" step");
            return violations;
        }

        Map<String, Object> env = stepEnv(merge);
        Object payload = env.get("RP_PR");
        if (!PAYLOAD_EXPRESSION.equals(payload)) {
            violations.add(POLICY + ": \"" + MERGE_STEP + "\" must carry the release PR payload as "
                    + "data in its RP_PR env from " + PAYLOAD_EXPRESSION + " (found: " + payload
                    + ")");
        }

        String commands = commandLines(String.valueOf(merge.get("run")));
        if (!commands.contains(PARSE_LINE)) {
            violations.add(POLICY + ": \"" + MERGE_STEP + "\" must read the payload as JSON with "
                    + PARSE_LINE + " instead of building a command out of it");
        }
        if (!commands.contains("gh pr merge \"$PR_NUMBER\"")) {
            violations.add(POLICY + ": \"" + MERGE_STEP + "\" no longer merges the PR it validated");
        }
        if (!commands.contains("--match-head-commit \"$HEAD_SHA\"")) {
            violations.add(POLICY + ": \"" + MERGE_STEP + "\" no longer merges exactly the "
                    + "validated head");
        }
        if (!commands.contains("--merge")) {
            violations.add(POLICY + ": \"" + MERGE_STEP + "\" no longer merges the release PR "
                    + "synchronously");
        }
        if (commands.contains("--auto")) {
            violations.add(POLICY + ": \"" + MERGE_STEP + "\" defers the merge with --auto, so the "
                    + "rerun dispatch can land before the release PR was merged");
        }
        if (!commands.contains("gh workflow run release-please.yml")) {
            violations.add(POLICY + ": \"" + MERGE_STEP + "\" no longer re-dispatches "
                    + "release-please.yml, so the tag is never cut");
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

    private static String workflowText() throws Exception {
        if (!Files.exists(WORKFLOW_PATH)) {
            if (Files.exists(REPOSITORY_GIT_PATH)) {
                throw new AssertionError(
                        WORKFLOW_PATH + " is missing from the repository checkout");
            }
            assumeTrue(false, WORKFLOW_PATH + " is unavailable outside a repository checkout");
        }
        return Files.readString(WORKFLOW_PATH, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String text) {
        return (Map<String, Object>) new Yaml().load(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jobs(Map<String, Object> workflow) {
        return (Map<String, Object>) workflow.get("jobs");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(Object job) {
        return (List<Map<String, Object>>) ((Map<String, Object>) job).get("steps");
    }

    private static Map<String, Object> findStep(Map<String, Object> workflow, String name) {
        for (Map.Entry<String, Object> job : jobs(workflow).entrySet()) {
            for (Map<String, Object> step : steps(job.getValue())) {
                if (name.equals(step.get("name"))) {
                    return step;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> mergeStep(Map<String, Object> workflow) {
        Map<String, Object> step = findStep(workflow, MERGE_STEP);
        if (step == null) {
            throw new AssertionError("release-please is missing its \"" + MERGE_STEP + "\" step");
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
            try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
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
