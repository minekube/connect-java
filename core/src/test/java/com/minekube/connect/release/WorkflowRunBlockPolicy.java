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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The single implementation of a repository workflow's shell trust boundary: a {@code run:} block
 * must never contain a GitHub expression.
 *
 * <p>The runner expands {@code ${{ ... }}} in a {@code run:} body <em>textually</em>, before bash
 * ever parses the script. An expression that carries a value the repository does not control
 * ({@code steps.*.outputs.*}, {@code inputs.*}, a release object's {@code tag_name}) therefore does
 * not stay data: the value is spliced into the script and parsed as shell source. A {@code "} in it
 * ends the quoted literal, and everything after it
 * ({@code `; touch pwned; "`}, {@code $(id)}) runs as a command - in a release job that holds
 * {@code contents: write}. The same expansion silently mangles the value even when it does not
 * execute anything, so the step's own output is wrong in a way nothing reports.
 *
 * <p>Values reach a script as <em>data</em> through the step's {@code env:} block (the leaf
 * {@code ${{ ... }}} is evaluated by the runner, not written into the script) and are read by the
 * script as shell variables. {@code .agents/skills/release/SKILL.md} owns the contract; this class
 * owns the scan, so the invariant is one implementation for every workflow rather than a copy per
 * file.
 *
 * <p>Shared by {@code ReleasePleasePayloadTest} (the release-PR payload that motivated it) and
 * {@code ReleaseWorkflowShellBoundaryTest} (the repo-wide sweep and release.yml's tag resolution).
 */
final class WorkflowRunBlockPolicy {

    private WorkflowRunBlockPolicy() {
    }

    /**
     * Every line of every step's shell script in the workflow that interpolates an expression.
     *
     * @param workflow the parsed workflow document
     * @param policy the message prefix the calling test asserts on (so a failure names the contract
     *     the caller owns)
     * @param workflowLabel the workflow's file name, so a repo-wide sweep names the file instead of
     *     leaving the reader to guess which of them broke
     */
    @SuppressWarnings("unchecked")
    static List<String> violations(
            Map<String, Object> workflow, String policy, String workflowLabel) {
        List<String> violations = new ArrayList<>();

        Object jobs = workflow.get("jobs");
        if (!(jobs instanceof Map)) {
            violations.add(policy + ": " + workflowLabel + " declares no jobs");
            return violations;
        }

        for (Map.Entry<String, Object> job : ((Map<String, Object>) jobs).entrySet()) {
            if (!(job.getValue() instanceof Map)) {
                continue;
            }
            Object steps = ((Map<String, Object>) job.getValue()).get("steps");
            if (!(steps instanceof List)) {
                continue;
            }
            for (Object candidate : (List<Object>) steps) {
                if (!(candidate instanceof Map)) {
                    continue;
                }
                Map<String, Object> step = (Map<String, Object>) candidate;
                Object run = step.get("run");
                if (!(run instanceof String)) {
                    continue;
                }
                for (String line : ((String) run).split("\n")) {
                    if (line.contains("${{")) {
                        violations.add(policy + ": " + workflowLabel + " job \"" + job.getKey()
                                + "\" step \"" + step.get("name") + "\" interpolates an expression "
                                + "into its shell script, so a value containing a quote becomes "
                                + "shell code: " + line.trim());
                    }
                }
            }
        }

        return violations;
    }
}
