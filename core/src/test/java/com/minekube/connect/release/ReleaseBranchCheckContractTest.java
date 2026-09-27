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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Every release PR shows a red {@code Build Pull Request} check and it can never go green. That is
 * not a broken job and it is not a trigger-filter artefact: it is GitHub's documented approval gate
 * for pull requests that a workflow created with {@code GITHUB_TOKEN}.
 *
 * <pre>
 *   "When a pull request is created or updated by a workflow using `GITHUB_TOKEN`, `pull_request`
 *   events with the `opened`, `synchronize`, or `reopened` activity types create workflow runs that
 *   require approval. A user with write access to the repository can approve these runs from the
 *   pull request page. With the exception of `workflow_dispatch` and `repository_dispatch`, other
 *   `GITHUB_TOKEN`-triggered events do not create workflow runs at all."
 *   - GitHub docs, "Events that trigger workflows" -&gt; `pull_request`
 * </pre>
 *
 * <p>Observed on this repository (2026-09-27): release PR #167 (0.15.14) head {@code 622af57f}
 * carries two {@code GitHub Actions} check suites - {@code 98357508591} ({@code status=completed},
 * {@code conclusion=failure}, {@code latest_check_runs_count=0}: the approval-gated run
 * {@code 36324298904}, {@code event=pull_request}, {@code actor=github-actions[bot]}, 0 jobs) and
 * {@code 98357509913} ({@code success}, 2 check runs: {@code build (17)} and {@code build (21)}
 * from the run {@code release-please.yml} dispatched with {@code workflow_dispatch}). The same
 * shape is on 0.15.13 (34791964301), 0.15.12 (34790457463), 0.15.11 (33797944232), 0.15.10
 * (33603995522) and 0.15.7 (32509874033).
 *
 * <p>The one release-PR run of this workflow that ever executed is the interesting one: 0.15.7 run
 * {@code 32509901978} reports {@code run_attempt=2} and {@code triggering_actor=robinbraemer} - a
 * human with write access approved it from the pull request page, which is exactly the documented
 * remedy above. No release PR run executes without that; the gate holds, and the approved attempt
 * is what built {@code build (17)}/{@code build (21)} for the head that merged.
 *
 * <p>Why the trigger is not the cause, and why it must not be "fixed" by touching it:
 * {@code pullrequest.yml} declares no {@code paths:} filter, so it matches every pull request -
 * the release PR included, on purpose. That is why the gated run exists at all: a workflow whose
 * filter does not match gets no run whatsoever (a docs-only PR produces zero runs). Adding a
 * filter that skips the release PR's own files ({@code .release-please-manifest.json},
 * {@code CHANGELOG.md}) can therefore only convert an accepted red check into a missing one.
 *
 * <p>Disposition, and the reason this test exists: the only contexts a release PR can ever
 * satisfy are the native matrix check runs of the build {@code release-please.yml} dispatches -
 * {@code build (17)} and {@code build (21)}. The workflow-level context of the gated workflow
 * ("{@code Build Pull Request}") must never be added to {@code main}'s required status checks:
 * the gated run reports no check run at all, so a release PR would sit in "Expected - waiting for
 * status to be reported" while the automation retries its merge and finally reds the release job.
 * Nothing is mirrored into branch protection either (no synthetic check run, no legacy commit
 * status) - {@code ReleasePleaseCheckAuditTest} pins that a release PR keeps showing exactly the
 * native checks of the run the automation validated. Making the check real would require
 * release-please to open its PR with a GitHub App/PAT token instead of {@code GITHUB_TOKEN}
 * (GitHub docs, "Triggering a workflow from a workflow": an App installation token or PAT "also
 * lets `pull_request` workflows run automatically (without the approval prompt described above)
 * when the pull request is created or updated by automation"). That is a credential change, not a
 * workflow edit, so it is deliberately not done here. The acceptance note above the {@code on:}
 * block of {@code pullrequest.yml} carries the same disposition for a reader of the workflow.
 */
class ReleaseBranchCheckContractTest {

    private static final Path WORKFLOW_DIR = Paths.get("..", ".github", "workflows");
    private static final Path GATED_WORKFLOW = WORKFLOW_DIR.resolve("pullrequest.yml");
    private static final Path RELEASE_PLEASE_WORKFLOW = WORKFLOW_DIR.resolve("release-please.yml");
    private static final Path REPOSITORY_GIT_PATH = Paths.get("..", ".git");

    /** The message prefix every rejection carries, so a mutation test can assert the reason. */
    private static final String CONTRACT = "release-branch-check contract:";

    /** The gated workflow's own name: it is the check a release PR sees on the pull request page. */
    private static final String GATED_WORKFLOW_NAME = "Build Pull Request";
    private static final String GATED_WORKFLOW_FILE = "pullrequest.yml";

    /** The dispatchable build whose native checks are the only contexts a release PR can satisfy. */
    private static final String DISPATCHED_WORKFLOW_FILE = "pullrequest.yml";

    /** {@code release-please.yml} dispatches {@link #DISPATCHED_WORKFLOW_FILE} on the release branch. */
    private static final String VALIDATE_STEP = "Validate and merge release PR";

    /** Every workflow file in this repository, asserted before it is swept. */
    private static final Set<String> EXPECTED_WORKFLOW_FILES =
            Set.of("pullrequest.yml", "release-please.yml", "release-repair.yml", "release.yml");

    /** The only workflow a release PR reaches: the gated one. */
    private static final Set<String> WANT_PULL_REQUEST_WORKFLOWS = Set.of(GATED_WORKFLOW_FILE);

    /**
     * The file set release-please writes into its own PR. Observed on #165 and #167 ({@code
     * .release-please-manifest.json} + {@code CHANGELOG.md}, nothing else - this component's
     * version lives in the manifest, not in a version file), which is what the trigger analysis
     * depends on.
     */
    private static final List<String> RELEASE_PR_FILES =
            List.of(".release-please-manifest.json", "CHANGELOG.md");

    /**
     * The contexts a release PR can satisfy: the matrix check runs of the dispatched build, derived
     * from the workflow's own job name and matrix. Nothing else may be required, and nothing
     * synthetic may be created to make the gated workflow-level context satisfiable.
     */
    private static final Set<String> WANT_NATIVE_CHECK_CONTEXTS = Set.of("build (17)", "build (21)");

    /** Nothing is mirrored into branch protection: no synthetic check run, no legacy status. */
    private static final Set<String> WANT_MIRRORED_CONTEXTS = Set.of();

    // ---------------------------------------------------------------------------------------
    // The contract: every workflow fact the disposition depends on.
    // ---------------------------------------------------------------------------------------

    /** Every workflow fact the acceptance note above {@code pullrequest.yml}'s {@code on:} rests on. */
    private static final class Contract {
        /** The name of the workflow that reports the approval-gated check. */
        private String gatedWorkflowName;
        /**
         * The {@code paths} filter of the gated workflow's {@code pull_request} trigger, or
         * {@code null} when the workflow declares no filter at all (its current shape, and the
         * shape that keeps it matching the release PR on purpose).
         */
        private List<String> gatedTriggerPaths;
        /** The workflows a {@code pull_request} event reaches - the gated one, and nothing else. */
        private Set<String> pullRequestWorkflows;
        /**
         * The {@code token:} the release-please action step receives. Empty means it uses the
         * runner's {@code GITHUB_TOKEN}, i.e. the approval gate applies.
         */
        private String releasePleaseTokenInput;
        /** The branch-protection contexts the merge step creates itself: none. */
        private List<String> mirroredContexts;
        /** The check-run names the dispatched build reports: the only contexts a PR can satisfy. */
        private List<String> nativeCheckContexts;

        private Contract(
                String gatedWorkflowName,
                List<String> gatedTriggerPaths,
                Set<String> pullRequestWorkflows,
                String releasePleaseTokenInput,
                List<String> mirroredContexts,
                List<String> nativeCheckContexts) {
            this.gatedWorkflowName = gatedWorkflowName;
            this.gatedTriggerPaths = gatedTriggerPaths;
            this.pullRequestWorkflows = pullRequestWorkflows;
            this.releasePleaseTokenInput = releasePleaseTokenInput;
            this.mirroredContexts = mirroredContexts;
            this.nativeCheckContexts = nativeCheckContexts;
        }

        /** The same facts, for a mutation to weaken. */
        private Contract copy() {
            return new Contract(gatedWorkflowName, gatedTriggerPaths, pullRequestWorkflows,
                    releasePleaseTokenInput, mirroredContexts, nativeCheckContexts);
        }
    }

    /**
     * The live contract, read from the workflows themselves. The {@code on:} block is read from the
     * YAML node tree, where the key stays the literal {@code on} instead of the boolean a YAML 1.1
     * resolver would make of it.
     */
    private static Contract liveContract() throws Exception {
        Node gated = readWorkflow(GATED_WORKFLOW);
        Set<String> pullRequestWorkflows = new TreeSet<>();
        for (Path workflow : workflowFiles()) {
            if (triggersOn(readWorkflow(workflow), "pull_request")) {
                pullRequestWorkflows.add(workflow.getFileName().toString());
            }
        }
        return new Contract(
                scalar(gated, "name"),
                triggerPaths(gated, GATED_WORKFLOW_FILE, "pull_request"),
                pullRequestWorkflows,
                releasePleaseTokenInput(),
                mirroredContexts(),
                nativeCheckContexts(gated));
    }

    /**
     * Fails when any fact the documented disposition has changed. The messages name the
     * disposition, so a future reader re-decides it instead of deleting the guard.
     */
    private static String validate(Contract contract) {
        if (!GATED_WORKFLOW_NAME.equals(contract.gatedWorkflowName)) {
            return CONTRACT + " the workflow that reports the approval-gated check on a release PR "
                    + "is no longer named \"" + GATED_WORKFLOW_NAME + "\" (got \""
                    + contract.gatedWorkflowName + "\"); the acceptance note above the on: block of "
                    + GATED_WORKFLOW_FILE + " names it explicitly, so the disposition has to be "
                    + "re-decided before this test is updated";
        }

        if (contract.gatedTriggerPaths != null) {
            List<String> matched = new ArrayList<>();
            for (String file : RELEASE_PR_FILES) {
                if (matchesAny(contract.gatedTriggerPaths, file)) {
                    matched.add(file);
                }
            }
            if (matched.isEmpty()) {
                return CONTRACT + " " + GATED_WORKFLOW_FILE + " now filters pull_request by paths "
                        + "that no release PR matches (" + contract.gatedTriggerPaths + " matches "
                        + "none of " + RELEASE_PR_FILES + "): GitHub then creates no run for the "
                        + "release PR at all, so the accepted red check silently becomes a missing "
                        + "one and the note in " + GATED_WORKFLOW_FILE + " describes something that "
                        + "no longer happens";
            }
        }

        if (!WANT_PULL_REQUEST_WORKFLOWS.equals(contract.pullRequestWorkflows)) {
            return CONTRACT + " the workflows a pull_request event reaches are "
                    + contract.pullRequestWorkflows + ", want exactly " + WANT_PULL_REQUEST_WORKFLOWS
                    + ". " + GATED_WORKFLOW_FILE + " is the only workflow a release PR reaches: "
                    + "another one would add contexts a release PR reports (and has to be ruled on), "
                    + "and its absence would remove the accepted red check that "
                    + GATED_WORKFLOW_FILE + " documents";
        }

        if (!contract.releasePleaseTokenInput.isEmpty()) {
            return CONTRACT + " the release-please action now receives a token (\""
                    + contract.releasePleaseTokenInput + "\"), so its pull request is no longer "
                    + "created with GITHUB_TOKEN and the approval gate described in "
                    + GATED_WORKFLOW_FILE + " does not apply anymore. Check whether the run is "
                    + "green now, remove the acceptance note, and reconsider which contexts a "
                    + "release PR may be required to satisfy";
        }

        Set<String> mirrored = new TreeSet<>(contract.mirroredContexts);
        if (!WANT_MIRRORED_CONTEXTS.equals(mirrored)) {
            return CONTRACT + " " + RELEASE_PLEASE_WORKFLOW.getFileName() + " now mirrors "
                    + mirrored + " into branch protection, want none. A release PR is validated "
                    + "through the checks of the run the automation dispatched; a synthetic context "
                    + "duplicates them on the commit and would let the workflow-level context of "
                    + GATED_WORKFLOW_FILE + " (or anything else) be required as if it were "
                    + "satisfiable";
        }

        Set<String> native_ = new TreeSet<>(contract.nativeCheckContexts);
        if (!WANT_NATIVE_CHECK_CONTEXTS.equals(native_)) {
            return CONTRACT + " the dispatched build now reports " + native_ + " on a release PR, "
                    + "want exactly " + WANT_NATIVE_CHECK_CONTEXTS + ". These are the only contexts "
                    + "a release PR can satisfy; if the job name or the matrix changed, re-decide "
                    + "which contexts may be required on main and update the acceptance note in "
                    + GATED_WORKFLOW_FILE + " with it";
        }

        return null;
    }

    // ---------------------------------------------------------------------------------------
    // The guard and its facts.
    // ---------------------------------------------------------------------------------------

    /** The live workflows must still satisfy every fact the documented disposition depends on. */
    @Test
    void releaseBranchCheckContractMatchesLiveWorkflows() throws Exception {
        Contract contract = liveContract();

        assertNull(validate(contract),
                "the live workflows no longer back the acceptance note in " + GATED_WORKFLOW_FILE
                        + ": " + validate(contract));
        assertTrue(contract.releasePleaseTokenInput.isEmpty(),
                CONTRACT + " the release-please step must not pass a token, or the approval gate "
                        + "(and the note) is void (got \"" + contract.releasePleaseTokenInput + "\")");

        for (String required : EXPECTED_WORKFLOW_FILES) {
            assertTrue(workflowFiles().stream()
                            .map(path -> path.getFileName().toString())
                            .anyMatch(required::equals),
                    "the contract did not read " + required + "; a sweep that silently misses a "
                            + "workflow file cannot rule on the contexts a release PR reports");
        }
    }

    /**
     * Both halves the note rests on: the release PR reaches the gated workflow (which is why the
     * red check exists), and it reaches no other workflow (a workflow whose trigger does not match
     * produces no run at all - that is the control that makes the red check the approval gate
     * rather than a filter artefact).
     */
    @Test
    void releasePrsReachTheGatedWorkflowAndNoOtherWorkflow() throws Exception {
        Contract contract = liveContract();

        for (String file : RELEASE_PR_FILES) {
            assertTrue(contract.gatedTriggerPaths == null
                            || matchesAny(contract.gatedTriggerPaths, file),
                    GATED_WORKFLOW_FILE + " no longer triggers for " + file
                            + " (paths: " + contract.gatedTriggerPaths + "), so a release PR shows "
                            + "no run of this workflow - the accepted red check the note describes "
                            + "would not exist");
        }
        assertEquals(WANT_PULL_REQUEST_WORKFLOWS, contract.pullRequestWorkflows,
                "exactly one workflow may be reachable by a release PR, and it is the gated one");
    }

    /**
     * The connect-java form of "only {@code lint-test} is mirrored": nothing is. A release PR must
     * keep showing exactly the native checks of the run the automation dispatched, and the workflow
     * it validates with is not one the gate can block ({@code workflow_dispatch} is exempt).
     */
    @Test
    void nothingIsMirroredForAReleasePrAndTheDispatchedRunIsTheOnlyEvidence() throws Exception {
        String script = mergeStepScript();

        assertTrue(mirroredContexts().isEmpty(),
                RELEASE_PLEASE_WORKFLOW.getFileName() + " creates synthetic branch-protection "
                        + "contexts for a release PR; the note in " + GATED_WORKFLOW_FILE
                        + " assumes it does not");

        assertTrue(script.contains("gh workflow run " + DISPATCHED_WORKFLOW_FILE + " \\"),
                RELEASE_PLEASE_WORKFLOW.getFileName() + " no longer dispatches "
                        + DISPATCHED_WORKFLOW_FILE + " (the exempt event that produces the native "
                        + "checks a release PR is judged by)");
        assertTrue(script.contains("--event workflow_dispatch"),
                "the release PR build must be found by its workflow_dispatch event; a "
                        + "pull_request-triggered run of that workflow is the approval-gated one and "
                        + "never executes");
        assertTrue(script.contains("commits/$HEAD_SHA/check-runs?per_page=100")
                        && script.contains("startswith($run_url + \"/job/\")"),
                "the merge step must audit the dispatched run's own native checks on the exact "
                        + "head, not any other context on that commit");
        assertFalse(script.contains(GATED_WORKFLOW_NAME),
                "the merge step now waits for or requires the workflow-level context \""
                        + GATED_WORKFLOW_NAME + "\", which a release PR can never satisfy");
    }

    /**
     * The acceptance note is the disposition a reader of the workflow file sees, and comments are
     * invisible to the YAML contract above, so it is pinned on the raw text: the facts it has to
     * name, in the {@code on:} block it documents. Removing or hollowing it out reds this test
     * instead of quietly losing the reasoning.
     */
    @Test
    void theAcceptanceNoteInTheGatedWorkflowsOnBlockNamesTheDisposition() throws Exception {
        String text = workflowText(GATED_WORKFLOW);
        int onIndex = text.indexOf("\non:");
        assertTrue(onIndex > 0, GATED_WORKFLOW_FILE + " has no on: block");

        String note = text.substring(0, onIndex).toLowerCase(Locale.ROOT);
        for (String fact : List.of("github_token", "approval", "0 jobs", "required",
                GATED_WORKFLOW_NAME.toLowerCase(Locale.ROOT), "release-please", "workflow_dispatch")) {
            assertTrue(note.contains(fact),
                    "the acceptance note above " + GATED_WORKFLOW_FILE + "'s on: block no longer "
                            + "names \"" + fact + "\". The note is what tells a reader that the red "
                            + "check on every release PR is GitHub's approval gate for "
                            + "GITHUB_TOKEN pull requests, that this workflow matches release PRs on "
                            + "purpose, and that no context of it may be required on main. If the "
                            + "gate no longer applies (for example because release-please now opens "
                            + "its PR with an App/PAT token), re-decide the disposition and update "
                            + "this test with it - do not just delete the note");
        }
    }

    // ---------------------------------------------------------------------------------------
    // The guard has teeth: every weakening mutation has to be rejected, with a stated reason.
    // ---------------------------------------------------------------------------------------

    @Test
    void releaseBranchCheckContractRejectsMutations() throws Exception {
        Contract live = liveContract();

        Map<String, UnaryOperator<Contract>> mutations = new LinkedHashMap<>();
        mutations.put("gated-workflow-renamed", contract -> {
            Contract mutated = contract.copy();
            mutated.gatedWorkflowName = "CI";
            return mutated;
        });
        mutations.put("gated-workflow-loses-the-pull-request-trigger", contract -> {
            Contract mutated = contract.copy();
            mutated.pullRequestWorkflows = new TreeSet<>();
            return mutated;
        });
        mutations.put("another-workflow-now-triggers-on-pull-requests", contract -> {
            Contract mutated = contract.copy();
            mutated.pullRequestWorkflows = new TreeSet<>(
                    List.of(GATED_WORKFLOW_FILE, "release-please.yml"));
            return mutated;
        });
        mutations.put("paths-filter-skips-the-release-pr-files", contract -> {
            Contract mutated = contract.copy();
            mutated.gatedTriggerPaths = List.of("core/**", "api/**");
            return mutated;
        });
        mutations.put("release-please-token-added", contract -> {
            Contract mutated = contract.copy();
            mutated.releasePleaseTokenInput = "${{ secrets.RELEASE_PLEASE_TOKEN }}";
            return mutated;
        });
        mutations.put("synthetic-check-run-mirrored", contract -> {
            Contract mutated = contract.copy();
            mutated.mirroredContexts = List.of("build (17)");
            return mutated;
        });
        mutations.put("gated-context-mirrored", contract -> {
            Contract mutated = contract.copy();
            mutated.mirroredContexts = List.of(GATED_WORKFLOW_NAME);
            return mutated;
        });
        mutations.put("matrix-or-job-name-changed", contract -> {
            Contract mutated = contract.copy();
            mutated.nativeCheckContexts = List.of("build (17)", "build (21)", "build (25)");
            return mutated;
        });

        for (Map.Entry<String, UnaryOperator<Contract>> mutation : mutations.entrySet()) {
            String rejection = validate(mutation.getValue().apply(live));

            assertFalse(rejection == null,
                    "MUTATION_ACCEPTED|" + mutation.getKey()
                            + "|the contract let a weakened workflow through");
            assertTrue(rejection.startsWith(CONTRACT),
                    "MUTATION_WRONG_REASON|" + mutation.getKey() + "|" + rejection);
            System.out.println(
                    "MUTATION_REJECTED|" + mutation.getKey() + "|reason=" + rejection);
        }

        // The guard is not "no filter may ever be declared": a filter that still covers the release
        // PR files is a legitimate edit and must be accepted, otherwise this test would force a
        // re-decision on every perf tweak instead of on the facts that matter.
        Contract withMatchingFilter = live.copy();
        withMatchingFilter.gatedTriggerPaths = List.of("core/**", "CHANGELOG.md");
        assertNull(validate(withMatchingFilter),
                CONTRACT + " a paths filter that still matches " + RELEASE_PR_FILES
                        + " was rejected: " + validate(withMatchingFilter));
    }

    // ---------------------------------------------------------------------------------------
    // The path matcher itself, so the analysis above cannot pass on a wrong glob implementation.
    // ---------------------------------------------------------------------------------------

    @Test
    void releasePrPathMatcherFollowsGitHubGlobbing() {
        Map<String, Boolean> cases = new LinkedHashMap<>();
        cases.put("CHANGELOG.md|CHANGELOG.md", true);
        cases.put("CHANGELOG.md|CHANGELOG.txt", false);
        cases.put(".release-please-manifest.json|.release-please-manifest.json", true);
        cases.put("core/**|core/src/main/java/com/minekube/connect/Constants.java", true);
        cases.put("core/**|core/build.gradle.kts", true);
        cases.put("core/**|api/src/main/java/com/minekube/connect/api/Connect.java", false);
        // Only file paths are matched here: a trailing `/**` in GitHub's matcher needs the
        // directory's separator (`core/**` does not match the directory entry `core` itself), and
        // that edge cannot influence whether the release PR's own files match a filter.
        cases.put("**/*.md|CHANGELOG.md", true);
        cases.put("**/*.md|docs/CHANGELOG.md", true);
        cases.put("**/*.md|CHANGELOG.txt", false);
        cases.put("api/**|api/build.gradle.kts", true);
        cases.put(".github/workflows/pullrequest.yml|.github/workflows/pullrequest.yml", true);

        for (Map.Entry<String, Boolean> testCase : cases.entrySet()) {
            String[] parts = testCase.getKey().split("\\|", 2);
            assertEquals(testCase.getValue(), matches(parts[0], parts[1]),
                    CONTRACT + " pattern " + parts[0] + " against " + parts[1]);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Workflow access.
    // ---------------------------------------------------------------------------------------

    private static List<Path> workflowFiles() throws Exception {
        if (!Files.isDirectory(WORKFLOW_DIR)) {
            if (Files.exists(REPOSITORY_GIT_PATH)) {
                throw new AssertionError(WORKFLOW_DIR + " is missing from the repository checkout");
            }
            assumeTrue(false, WORKFLOW_DIR + " is unavailable outside a repository checkout");
            return List.of();
        }
        try (Stream<Path> files = Files.list(WORKFLOW_DIR)) {
            return files.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static String workflowText(Path path) throws Exception {
        if (!Files.exists(path)) {
            if (Files.exists(REPOSITORY_GIT_PATH)) {
                throw new AssertionError(path + " is missing from the repository checkout");
            }
            assumeTrue(false, path + " is unavailable outside a repository checkout");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Parses a workflow as a YAML node tree, so the {@code on:} key is read as the literal scalar
     * GitHub writes rather than the boolean a YAML 1.1 resolver would make of it.
     */
    private static Node readWorkflow(Path path) throws Exception {
        return new Yaml().compose(new StringReader(workflowText(path)));
    }

    private static Node mappingValue(Node node, String key) {
        if (!(node instanceof MappingNode)) {
            return null;
        }
        for (NodeTuple tuple : ((MappingNode) node).getValue()) {
            Node keyNode = tuple.getKeyNode();
            if (keyNode instanceof ScalarNode && key.equals(((ScalarNode) keyNode).getValue())) {
                return tuple.getValueNode();
            }
        }
        return null;
    }

    private static String scalarOf(Node node) {
        return node instanceof ScalarNode ? ((ScalarNode) node).getValue() : "";
    }

    private static String scalar(Node mapping, String key) {
        return scalarOf(mappingValue(mapping, key));
    }

    /** Whether the workflow declares the given event under its {@code on:} block. */
    private static boolean triggersOn(Node workflow, String event) {
        Node on = mappingValue(workflow, "on");
        return on != null && isKey(on, event);
    }

    private static boolean isKey(Node mapping, String key) {
        if (!(mapping instanceof MappingNode)) {
            return false;
        }
        for (NodeTuple tuple : ((MappingNode) mapping).getValue()) {
            if (scalarOf(tuple.getKeyNode()).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code paths} filter of a workflow's event trigger, or {@code null} when the trigger
     * declares none (then it matches every pull request).
     */
    private static List<String> triggerPaths(Node workflow, String workflowFile, String event) {
        Node on = mappingValue(workflow, "on");
        if (on == null) {
            throw new AssertionError(CONTRACT + " " + workflowFile + " declares no on: block");
        }
        Node eventNode = mappingValue(on, event);
        if (eventNode == null) {
            throw new AssertionError(CONTRACT + " " + workflowFile + " does not trigger on " + event
                    + "; the release PR analysis assumes it does");
        }
        Node paths = mappingValue(eventNode, "paths");
        if (paths == null) {
            return null;
        }
        List<String> declared = new ArrayList<>();
        if (paths instanceof SequenceNode) {
            for (Node item : ((SequenceNode) paths).getValue()) {
                declared.add(scalarOf(item));
            }
        }
        return declared;
    }

    /** The step with the given name, anywhere in the workflow. */
    private static Node stepNode(Node workflow, String name) {
        Node jobs = mappingValue(workflow, "jobs");
        if (!(jobs instanceof MappingNode)) {
            throw new AssertionError(CONTRACT + " the workflow declares no jobs");
        }
        for (NodeTuple job : ((MappingNode) jobs).getValue()) {
            Node steps = mappingValue(job.getValueNode(), "steps");
            if (!(steps instanceof SequenceNode)) {
                continue;
            }
            for (Node step : ((SequenceNode) steps).getValue()) {
                if (name.equals(scalar(step, "name"))) {
                    return step;
                }
            }
        }
        throw new AssertionError(CONTRACT + " no step named \"" + name + "\"");
    }

    /** The shell script of release-please's release-PR validation/merge step. */
    private static String mergeStepScript() throws Exception {
        return scalarOf(mappingValue(
                stepNode(readWorkflow(RELEASE_PLEASE_WORKFLOW), VALIDATE_STEP), "run"));
    }

    /** The {@code token:} the release-please action step receives (empty means {@code GITHUB_TOKEN}). */
    private static String releasePleaseTokenInput() throws Exception {
        Node workflow = readWorkflow(RELEASE_PLEASE_WORKFLOW);
        Node jobs = mappingValue(workflow, "jobs");
        Node job = mappingValue(jobs, "release-please");
        if (job == null) {
            throw new AssertionError(CONTRACT + " " + RELEASE_PLEASE_WORKFLOW.getFileName()
                    + " has no release-please job");
        }
        Node steps = mappingValue(job, "steps");
        if (steps instanceof SequenceNode) {
            for (Node step : ((SequenceNode) steps).getValue()) {
                String uses = scalar(step, "uses");
                if (!uses.contains("release-please-action")) {
                    continue;
                }
                Node with = mappingValue(step, "with");
                if (with == null) {
                    return "";
                }
                return scalar(with, "token");
            }
        }
        throw new AssertionError(CONTRACT + " " + RELEASE_PLEASE_WORKFLOW.getFileName()
                + " no longer uses release-please-action, so the release PR (and with it the "
                + "approval-gated run) no longer exists");
    }

    /**
     * The branch-protection contexts the merge step creates itself, read back out of its own
     * script: a {@code gh api} that sets {@code name=} or {@code context=} posts a check run or a
     * legacy commit status. None may appear - a release PR is judged by the native checks of the
     * dispatched run (see {@code ReleasePleaseCheckAuditTest}).
     */
    private static List<String> mirroredContexts() throws Exception {
        String script = mergeStepScript();
        Set<String> contexts = new TreeSet<>();
        for (String pattern : List.of("(?:-f|--field)\\s+name='([^']*)'",
                "(?:-f|--field)\\s+context='([^']*)'")) {
            java.util.regex.Matcher matcher = Pattern.compile(pattern).matcher(script);
            while (matcher.find()) {
                contexts.add(matcher.group(1));
            }
        }
        return new ArrayList<>(contexts);
    }

    /**
     * The check-run names the dispatched build reports on a release PR, derived from the workflow's
     * job name and its matrix - the only contexts a release PR can satisfy.
     */
    private static List<String> nativeCheckContexts(Node gatedWorkflow) {
        Node jobs = mappingValue(gatedWorkflow, "jobs");
        if (!(jobs instanceof MappingNode) || ((MappingNode) jobs).getValue().isEmpty()) {
            throw new AssertionError(CONTRACT + " " + GATED_WORKFLOW_FILE + " declares no jobs");
        }
        NodeTuple job = ((MappingNode) jobs).getValue().get(0);
        String jobName = scalarOf(job.getKeyNode());
        Node matrix = mappingValue(mappingValue(job.getValueNode(), "strategy"), "matrix");
        Node java = mappingValue(matrix, "java");
        if (!(java instanceof SequenceNode)) {
            throw new AssertionError(CONTRACT + " " + GATED_WORKFLOW_FILE + "'s \"" + jobName
                    + "\" job declares no java matrix, so the check runs a release PR reports (and "
                    + "may be required to satisfy) changed");
        }
        List<String> contexts = new ArrayList<>();
        for (Node value : ((SequenceNode) java).getValue()) {
            contexts.add(jobName + " (" + scalarOf(value) + ")");
        }
        return contexts;
    }

    // ---------------------------------------------------------------------------------------
    // GitHub's `paths` globbing, in the subset this repository uses.
    // ---------------------------------------------------------------------------------------

    private static boolean matchesAny(List<String> patterns, String file) {
        for (String pattern : patterns) {
            if (matches(pattern, file)) {
                return true;
            }
        }
        return false;
    }

    /** {@code **} spans path separators, {@code *} and {@code ?} stay inside one segment. */
    private static boolean matches(String pattern, String path) {
        return matchesSegments(pattern.split("/", -1), path.split("/", -1));
    }

    private static boolean matchesSegments(String[] pattern, String[] path) {
        if (pattern.length == 0) {
            return path.length == 0;
        }
        if ("**".equals(pattern[0])) {
            for (int i = 0; i <= path.length; i++) {
                if (matchesSegments(java.util.Arrays.copyOfRange(pattern, 1, pattern.length),
                        java.util.Arrays.copyOfRange(path, i, path.length))) {
                    return true;
                }
            }
            return false;
        }
        if (path.length == 0) {
            return false;
        }
        if (!matchesSegment(pattern[0], path[0])) {
            return false;
        }
        return matchesSegments(java.util.Arrays.copyOfRange(pattern, 1, pattern.length),
                java.util.Arrays.copyOfRange(path, 1, path.length));
    }

    private static boolean matchesSegment(String pattern, String segment) {
        if (pattern.isEmpty()) {
            return segment.isEmpty();
        }
        switch (pattern.charAt(0)) {
            case '*':
                // `*` never crosses a separator, so it can only consume the rest of this segment.
                for (int i = 0; i <= segment.length(); i++) {
                    if (matchesSegment(pattern.substring(1), segment.substring(i))) {
                        return true;
                    }
                }
                return false;
            case '?':
                return !segment.isEmpty()
                        && matchesSegment(pattern.substring(1), segment.substring(1));
            default:
                return !segment.isEmpty() && segment.charAt(0) == pattern.charAt(0)
                        && matchesSegment(pattern.substring(1), segment.substring(1));
        }
    }
}
