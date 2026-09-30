package com.agentic.sdlc.deliverables;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.agentic.sdlc.common.ConflictException;
import com.agentic.sdlc.common.NotFoundException;
import com.agentic.sdlc.metrics.MetricsService;
import com.agentic.sdlc.metrics.RunMetrics;
import com.agentic.sdlc.state.ApprovalRequest;
import com.agentic.sdlc.state.ApprovalRequestRepository;
import com.agentic.sdlc.state.ApprovalStatus;
import com.agentic.sdlc.state.Artifact;
import com.agentic.sdlc.state.ArtifactRepository;
import com.agentic.sdlc.state.ArtifactStatus;
import com.agentic.sdlc.state.AttemptStatus;
import com.agentic.sdlc.state.AuditEvent;
import com.agentic.sdlc.state.AuditEventRepository;
import com.agentic.sdlc.state.StageAttempt;
import com.agentic.sdlc.state.StageAttemptRepository;
import com.agentic.sdlc.state.WorkflowRun;
import com.agentic.sdlc.state.WorkflowRunRepository;
import com.agentic.sdlc.workspace.BuildResult;
import com.agentic.sdlc.workspace.Workspace;
import com.agentic.sdlc.workspace.WorkspaceService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes every artifact of a run into {@code deliverables/<name>/}, one folder per agent, as reviewable
 * Markdown: requirements (user stories, acceptance criteria), design (document, diagrams, API contract),
 * development (commit history, change set, logging/audit/error handling), code review (every file, every issue
 * and its resolution), QA (test report, functional and unit coverage, coverage gaps, HTML report), release and
 * governance (audit trail, human decisions, metrics).
 *
 * <p>The QA figures come from a fresh {@code clean test} build of the run's code, not from the agents' claims.
 */
@Service
public class DeliverablesExporter {

    public record ExportResult(String directory, List<String> files, Map<String, Object> summary) {
    }

    private static final Pattern GATE_FAILURE = Pattern.compile("^Exit gate failed: (\\w+):");
    private static final Pattern POLICY_FAILURE = Pattern.compile("^Policy violation: (\\w+):");
    private static final Pattern LOG_CALL = Pattern.compile("\\blog(?:ger)?\\.(info|warn|error|debug)\\(");

    private final WorkflowRunRepository runs;
    private final StageAttemptRepository attempts;
    private final ApprovalRequestRepository approvals;
    private final ArtifactRepository artifacts;
    private final AuditEventRepository audit;
    private final WorkspaceService workspaces;
    private final MetricsService metrics;
    private final ObjectMapper json;
    private final Path root;

    public DeliverablesExporter(WorkflowRunRepository runs, StageAttemptRepository attempts,
            ApprovalRequestRepository approvals, ArtifactRepository artifacts, AuditEventRepository audit,
            WorkspaceService workspaces, MetricsService metrics, ObjectMapper json,
            com.agentic.sdlc.config.SdlcProperties properties) {
        this.runs = runs;
        this.attempts = attempts;
        this.approvals = approvals;
        this.artifacts = artifacts;
        this.audit = audit;
        this.workspaces = workspaces;
        this.metrics = metrics;
        this.json = json;
        this.root = Path.of(properties.deliverablesRoot()).toAbsolutePath().normalize();
    }

    public ExportResult export(String runId, String requestedName) {
        WorkflowRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("Unknown run " + runId));
        Workspace ws = workspaces.find(runId)
                .orElseThrow(() -> new ConflictException("Run " + runId + " has no workspace to export"));
        String name = safeName(requestedName != null ? requestedName
                : run.getRecording() != null ? run.getRecording() : run.getScenario().name().toLowerCase());
        Path dir = root.resolve(name);
        deleteRecursively(dir);

        Map<String, String> committed = committedArtifacts(runId);
        BuildResult build = ws.build(List.of("clean", "test"));
        List<QaReports.TestCase> tests = QaReports.junit(ws.root());
        QaReports.Coverage coverage = QaReports.jacoco(ws.root());
        List<String> written = new ArrayList<>();
        Summary summary = new Summary();

        writeRequirements(dir, committed, written, summary);
        writeDesign(dir, committed, written);
        writeDevelopment(dir, run, ws, committed, written, summary);
        writeCodeReview(dir, run, ws, committed, written, summary);
        writeQa(dir, ws, committed, build, tests, coverage, written, summary);
        writeRelease(dir, committed, written);
        writeGovernance(dir, run, written);
        writeIndex(dir, run, summary, written);

        return new ExportResult(dir.toString(), written, summary.asMap());
    }

    // ------------------------------------------------------------------ 01 requirements

    private void writeRequirements(Path dir, Map<String, String> a, List<String> written, Summary summary) {
        JsonNode spec = parse(a.get("requirements_spec"));
        Map<String, String> storyOfCriterion = new LinkedHashMap<>();
        List<List<String>> stories = new ArrayList<>();
        for (JsonNode s : spec.path("userStories")) {
            List<String> acs = texts(s.path("acceptanceCriteria"));
            acs.forEach(ac -> storyOfCriterion.merge(ac, s.path("id").asString(""), (x, y) -> x + ", " + y));
            stories.add(List.of(s.path("id").asString(""), s.path("asA").asString(""), s.path("iWant").asString(""),
                    s.path("soThat").asString(""), String.join(", ", acs)));
        }
        List<List<String>> criteria = new ArrayList<>();
        for (JsonNode ac : spec.path("acceptanceCriteria")) {
            String id = ac.path("id").asString("");
            criteria.add(List.of(id, ac.path("criterion").asString(""), storyOfCriterion.getOrDefault(id, "—")));
        }
        summary.userStories = stories.size();
        summary.acceptanceCriteria = criteria.size();

        write(dir, "01-requirements/user-stories.md", new Md()
                .h1("User stories and acceptance criteria")
                .p("Produced by the Requirements agent and approved by a human at the requirements checkpoint.")
                .h2("User stories")
                .table(List.of("ID", "As a", "I want", "So that", "Acceptance criteria"), stories)
                .h2("Acceptance criteria")
                .table(List.of("ID", "Criterion", "User story"), criteria).toString(), written);

        List<List<String>> questions = new ArrayList<>();
        for (JsonNode q : spec.path("openQuestions")) {
            questions.add(List.of(q.path("question").asString(""), q.path("answer").asString(""),
                    q.path("assumption").asString("")));
        }
        write(dir, "01-requirements/requirements-spec.md", new Md()
                .h1("Requirements specification")
                .p(spec.path("summary").asString(""))
                .p("**Change type:** " + spec.path("changeType").asString("-"))
                .h2("Functional requirements").bullets(texts(spec.path("functional")))
                .h2("Non-functional requirements").bullets(texts(spec.path("nonFunctional")))
                .h2("Out of scope").bullets(texts(spec.path("outOfScope")))
                .h2("Open questions, answers and assumptions")
                .table(List.of("Question", "Human answer", "Assumption"), questions).toString(), written);
        write(dir, "01-requirements/requirements_spec.json", pretty(spec), written);
    }

    // ------------------------------------------------------------------ 02 design

    private void writeDesign(Path dir, Map<String, String> a, List<String> written) {
        write(dir, "02-design/design.md", Optional.ofNullable(a.get("design_doc")).orElse("_No design document._"),
                written);
        JsonNode contract = parse(a.get("api_contract"));
        List<List<String>> ops = new ArrayList<>();
        for (JsonNode op : contract) {
            List<String> responses = new ArrayList<>();
            op.path("responses").forEach(r -> responses.add(r.path("status").asString(r.path("status").toString())));
            List<String> errors = new ArrayList<>();
            op.path("errors").forEach(e -> errors.add(e.path("status").asString(e.path("status").toString()) + " "
                    + e.path("when").asString("")));
            ops.add(List.of(op.path("method").asString(""), op.path("path").asString(""),
                    compact(op.path("request")), String.join(", ", responses), String.join("; ", errors)));
        }
        write(dir, "02-design/api-contract.md", new Md().h1("API contract")
                .table(List.of("Method", "Path", "Request", "Success", "Errors"), ops)
                .h2("Full contract (JSON)").code("json", pretty(contract)).toString(), written);

        JsonNode plan = parse(a.get("task_plan"));
        List<List<String>> tasks = new ArrayList<>();
        for (JsonNode t : plan.path("tasks")) {
            tasks.add(List.of(t.path("id").asString(""), t.path("title").asString(""),
                    String.join(", ", texts(t.path("dependsOn"))), String.join(", ", texts(t.path("acceptanceCriteria")))));
        }
        List<String> risks = new ArrayList<>();
        plan.path("risks").forEach(r -> risks.add(r.path("risk").asString(r.toString()) + " — mitigation: "
                + r.path("mitigation").asString("")));
        write(dir, "02-design/task-plan.md", new Md().h1("Task plan")
                .p("Produced by the Planner agent; the validTaskPlan gate checked it is a dependency graph without cycles.")
                .table(List.of("Task", "Title", "Depends on", "Acceptance criteria"), tasks)
                .h2("Risks").bullets(risks).toString(), written);

        if (a.containsKey("impact_report")) {
            JsonNode impact = parse(a.get("impact_report"));
            List<List<String>> files = new ArrayList<>();
            impact.path("impactedFiles").forEach(f -> files.add(List.of(f.path("changeKind").asString(""),
                    f.path("path").asString(""), f.path("reason").asString(""))));
            List<String> apis = new ArrayList<>();
            impact.path("impactedApis").forEach(x -> apis.add(x.path("endpoint").asString("") + ": "
                    + x.path("change").asString("") + " (backward compatible: " + x.path("backwardCompatible") + ")"));
            write(dir, "02-design/impact-analysis.md", new Md().h1("Impact analysis (brownfield)")
                    .p("Produced by the Impact Analyst agent from the real repository; the impactFilesExist gate "
                            + "verified every file it names.")
                    .h2("Impacted files").table(List.of("Change", "File", "Reason"), files)
                    .h2("Impacted APIs").bullets(apis)
                    .h2("Data changes").bullets(anyTexts(impact.path("dataChanges")))
                    .h2("Risks").bullets(anyTexts(impact.path("risks"))).toString(), written);
        }
    }

    // ------------------------------------------------------------------ 03 development

    private void writeDevelopment(Path dir, WorkflowRun run, Workspace ws, Map<String, String> a, List<String> written,
            Summary summary) {
        List<String> commits = ws.commitLogSince(run.getBaselineCommit());
        summary.commits = commits.size();
        Md history = new Md().h1("Commit history")
                .p("Every accepted agent attempt is one commit on the run's branch, with the agent's Conventional "
                        + "Commit message and trailers naming the stage, attempt, agent and run. Reverts are rollbacks "
                        + "made when a human sent a stage back. Baseline: `" + run.getBaselineCommit() + "`.");
        commits.forEach(c -> history.code("text", c));
        write(dir, "03-development/commit-history.md", history.toString(), written);

        JsonNode changeSet = parse(a.get("change_set"));
        List<List<String>> files = new ArrayList<>();
        changeSet.path("files").forEach(f -> files.add(List.of(f.path("action").asString(""), f.path("path").asString(""),
                f.path("lines").toString())));
        write(dir, "03-development/change-set.md", new Md().h1("Implementation change set")
                .p(changeSet.path("summary").asString(""))
                .table(List.of("Action", "File", "Lines"), files)
                .h2("Diff").code("diff", changeSet.path("diff").asString("")).toString(), written);

        writeLoggingAuditErrors(dir, ws, written, summary);
        copyIfExists(ws.root().resolve("README.md"), dir.resolve("03-development/documentation/README.md"), written, dir);
        copyTree(ws.root().resolve("docs"), dir.resolve("03-development/documentation/docs"), written, dir);
    }

    private void writeLoggingAuditErrors(Path dir, Workspace ws, List<String> written, Summary summary) {
        List<List<String>> logging = new ArrayList<>();
        List<String> auditClasses = new ArrayList<>();
        List<List<String>> handlers = new ArrayList<>();
        Pattern handler = Pattern.compile("@ExceptionHandler\\(([^)]*)\\)");
        Pattern status = Pattern.compile("HttpStatus\\.([A-Z_]+)");
        for (String path : ws.listFiles()) {
            if (!path.startsWith("src/main/java/") || !path.endsWith(".java")) {
                continue;
            }
            String source = ws.read(path).orElse("");
            String file = path.substring(path.lastIndexOf('/') + 1);
            Map<String, Integer> levels = new LinkedHashMap<>(Map.of("info", 0, "warn", 0, "error", 0, "debug", 0));
            Matcher m = LOG_CALL.matcher(source);
            while (m.find()) {
                levels.merge(m.group(1), 1, Integer::sum);
            }
            boolean hasLogger = source.contains("LoggerFactory.getLogger") || source.contains("@Slf4j");
            if (hasLogger) {
                logging.add(List.of(file, levels.get("info").toString(), levels.get("warn").toString(),
                        levels.get("error").toString(), levels.get("debug").toString()));
            }
            if (source.contains("@Entity") && file.contains("Audit")) {
                auditClasses.add(file);
            }
            Matcher h = handler.matcher(source);
            while (h.find()) {
                Set<String> statuses = new LinkedHashSet<>();
                Matcher s = status.matcher(source.substring(h.end(), Math.min(source.length(), h.end() + 600)));
                if (s.find()) {
                    statuses.add(s.group(1));
                }
                handlers.add(List.of(file, h.group(1).replace(".class", ""), String.join(", ", statuses)));
            }
        }
        summary.classesWithLogging = logging.size();
        summary.auditEntities = auditClasses;
        write(dir, "03-development/logging-audit-error-handling.md", new Md()
                .h1("Error handling, logging and auditing")
                .p("Extracted from the released source code. The loggingAndAuditing gate required every controller "
                        + "and service to log through SLF4J and an audit entity to exist before implementation was accepted.")
                .h2("Logging (SLF4J calls per class)")
                .table(List.of("Class", "info", "warn", "error", "debug"), logging)
                .h2("Audit trail entities").bullets(auditClasses)
                .h2("Error handling (exception handlers)")
                .table(List.of("Class", "Handles", "HTTP status"), handlers).toString(), written);
    }

    // ------------------------------------------------------------------ 04 code review

    private void writeCodeReview(Path dir, WorkflowRun run, Workspace ws, Map<String, String> a, List<String> written,
            Summary summary) {
        JsonNode review = parse(a.get("review_report"));
        JsonNode security = parse(a.get("security_report"));
        List<ApprovalRequest> decisions = approvals.findByRunIdOrderByIdAsc(run.getId());

        List<String> changed = ws.changedFilesSince(run.getBaselineCommit()).stream()
                .filter(p -> p.startsWith("src/") || p.equals("build.gradle")).toList();
        Map<String, JsonNode> reviewed = new LinkedHashMap<>();
        review.path("filesReviewed").forEach(f -> reviewed.put(f.path("path").asString(""), f));
        List<List<String>> fileRows = new ArrayList<>();
        for (String path : changed) {
            JsonNode f = reviewed.get(path);
            fileRows.add(List.of(path, f == null ? "NOT REVIEWED" : f.path("verdict").asString(""),
                    f == null ? "" : f.path("note").asString("")));
        }
        long reviewedCount = changed.stream().filter(reviewed::containsKey).count();
        summary.filesChanged = changed.size();
        summary.filesReviewed = (int) reviewedCount;

        String reviewDecision = humanDecisionAt(decisions, "review");
        List<List<String>> findings = new ArrayList<>();
        review.path("findings").forEach(f -> findings.add(List.of(f.path("severity").asString(""),
                f.path("file").asString(""), f.path("summary").asString(""), f.path("recommendation").asString(""),
                reviewDecision != null ? reviewDecision : "Non-blocking; carried into the engineering summary as a follow-up")));

        String securityDecision = humanDecisionAt(decisions, "security_review");
        List<List<String>> secRows = new ArrayList<>();
        security.path("findings").forEach(f -> {
            String severity = f.path("severity").asString("").toUpperCase();
            String issue = f.path("issue").asString("");
            String disposition = issue.toLowerCase().contains("accepted risk") ? "Accepted risk (binding human decision)"
                    : (severity.equals("HIGH") || severity.equals("CRITICAL"))
                            ? Optional.ofNullable(securityDecision).orElse("Blocking: sent to a human")
                            : "Non-blocking; recorded as a follow-up";
            secRows.add(List.of(severity, f.path("file").asString(""), issue, f.path("recommendation").asString(""),
                    disposition));
        });

        List<List<String>> acRows = new ArrayList<>();
        review.path("acceptanceCriteria").forEach(ac -> acRows.add(List.of(ac.path("id").asString(""),
                ac.path("met").asBoolean(false) ? "met" : "NOT MET", ac.path("evidence").asString(""))));

        List<List<String>> issueLog = issueLog(run.getId(), decisions);
        summary.issuesFound = issueLog.size();
        summary.issuesResolved = (int) issueLog.stream().filter(r -> !r.get(4).startsWith("Unresolved")).count();

        write(dir, "04-code-review/code-review-report.md", new Md()
                .h1("Code review report")
                .p("**Reviewer verdict:** " + review.path("verdict").asString("-") + ". **Files reviewed:** "
                        + reviewedCount + " of " + changed.size() + " changed files (checked by the allFilesReviewed gate).")
                .h2("Every changed file").table(List.of("File", "Verdict", "Note"), fileRows)
                .h2("Acceptance criteria verified by the reviewer").table(List.of("Criterion", "Result", "Evidence"), acRows)
                .h2("Reviewer findings").table(List.of("Severity", "File", "Finding", "Recommendation", "Resolution"), findings)
                .h2("Security review findings")
                .p(security.path("summary").asString(""))
                .table(List.of("Severity", "File", "Issue", "Recommendation", "Disposition"), secRows)
                .h2("Issue log: every problem found during the run and how it was resolved")
                .p("Built from the orchestrator's attempt records and human decisions: each failed attempt is an issue a "
                        + "gate or policy found; the resolution is the attempt or decision that fixed it.")
                .table(List.of("Stage", "Attempt", "Found by", "Issue", "Resolution"), issueLog)
                .h2("Remaining risks").bullets(anyTexts(review.path("risks")))
                .h2("Trade-offs").bullets(anyTexts(review.path("tradeoffs"))).toString(), written);
    }

    private List<List<String>> issueLog(String runId, List<ApprovalRequest> decisions) {
        List<StageAttempt> all = attempts.findByRunIdOrderByIdAsc(runId);
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            StageAttempt attempt = all.get(i);
            if (attempt.getStatus() != AttemptStatus.FAILED && attempt.getStatus() != AttemptStatus.ABORTED) {
                continue;
            }
            String reason = Optional.ofNullable(attempt.getFailureReason()).orElse("");
            String foundBy = foundBy(reason, attempt.getStatus());
            String resolution = "Unresolved";
            for (int j = i + 1; j < all.size(); j++) {
                StageAttempt later = all.get(j);
                if (later.getNodeId().equals(attempt.getNodeId()) && later.getStatus() == AttemptStatus.SUCCEEDED) {
                    resolution = "Fixed in attempt " + later.getAttemptNo()
                            + (later.getCheckpointCommit() == null ? "" : " (commit " + later.getCheckpointCommit().substring(0, 10) + ")");
                    break;
                }
            }
            rows.add(List.of(attempt.getNodeId(), String.valueOf(attempt.getAttemptNo()), foundBy,
                    firstLines(reason, 300), resolution));
        }
        for (ApprovalRequest request : decisions) {
            if (request.getStatus() != ApprovalStatus.REJECTED) {
                continue;
            }
            String resolution = decisions.stream()
                    .filter(d -> d.getNodeId().equals(request.getNodeId()) && d.getId() > request.getId()
                            && d.getStatus() == ApprovalStatus.APPROVED)
                    .findFirst().map(d -> "Reworked by the agent; approved in approval #" + d.getId())
                    .orElse("Unresolved");
            rows.add(List.of(request.getNodeId(), "-", "Human (" + request.getDecidedBy() + ")",
                    firstLines(Optional.ofNullable(request.getComment()).orElse(""), 300), resolution));
        }
        return rows;
    }

    private static String foundBy(String reason, AttemptStatus status) {
        if (status == AttemptStatus.ABORTED) {
            return "Safe-stop / restart";
        }
        Matcher gate = GATE_FAILURE.matcher(reason);
        if (gate.find()) {
            return "Gate: " + gate.group(1);
        }
        Matcher policy = POLICY_FAILURE.matcher(reason);
        if (policy.find()) {
            return "Policy: " + policy.group(1);
        }
        return reason.startsWith("Agent error") ? "Agent error" : "Agent self-check";
    }

    private static String humanDecisionAt(List<ApprovalRequest> decisions, String nodeId) {
        return decisions.stream()
                .filter(d -> d.getNodeId().equals(nodeId) && d.getStatus() == ApprovalStatus.APPROVED)
                .reduce((first, second) -> second)
                .map(d -> "Decided by " + d.getDecidedBy() + (d.getComment() == null ? "" : ": " + d.getComment()))
                .orElse(null);
    }

    // ------------------------------------------------------------------ 05 QA

    private void writeQa(Path dir, Workspace ws, Map<String, String> a, BuildResult build, List<QaReports.TestCase> tests,
            QaReports.Coverage coverage, List<String> written, Summary summary) {
        long passed = tests.stream().filter(t -> t.status().equals("PASSED")).count();
        long failed = tests.stream().filter(t -> t.status().equals("FAILED")).count();
        summary.tests = tests.size();
        summary.testsFailed = (int) failed;

        Map<String, long[]> perClass = new LinkedHashMap<>();
        tests.forEach(t -> {
            long[] c = perClass.computeIfAbsent(t.simpleClass(), k -> new long[2]);
            c[0]++;
            if (t.status().equals("PASSED")) {
                c[1]++;
            }
        });
        List<List<String>> classRows = new ArrayList<>();
        perClass.forEach((cls, c) -> classRows.add(List.of(cls, String.valueOf(c[0]), String.valueOf(c[1]),
                String.valueOf(c[0] - c[1]))));
        List<List<String>> caseRows = new ArrayList<>();
        tests.forEach(t -> caseRows.add(List.of(t.simpleClass(), t.name(), t.status(), String.format("%.2fs", t.seconds()))));
        write(dir, "05-qa/test-report.md", new Md().h1("Test report")
                .p("Fresh `./gradlew clean test` of the released code, run by the exporter (build "
                        + (build.succeeded() ? "succeeded" : "FAILED") + " in " + build.durationMs() / 1000 + "s). "
                        + tests.size() + " tests: " + passed + " passed, " + failed + " failed.")
                .h2("By test class").table(List.of("Test class", "Tests", "Passed", "Failed"), classRows)
                .h2("All test cases").table(List.of("Class", "Test", "Result", "Time"), caseRows).toString(), written);

        writeFunctionalCoverage(dir, a, tests, written, summary);

        if (coverage == null) {
            write(dir, "05-qa/unit-coverage.md", "# Unit test coverage\n\nNo JaCoCo report was produced.\n", written);
            return;
        }
        summary.lineCoverage = coverage.lineRatio();
        summary.branchCoverage = coverage.branchRatio();
        List<List<String>> covRows = new ArrayList<>();
        coverage.classes().stream().sorted(Comparator.comparingDouble(QaReports.ClassCoverage::lineRatio))
                .forEach(c -> covRows.add(List.of(c.className().substring(c.className().lastIndexOf('.') + 1),
                        pct(c.lineRatio()), c.branchesCovered() + c.branchesMissed() == 0 ? "n/a" : pct(c.branchRatio()),
                        String.valueOf(c.linesMissed()))));
        write(dir, "05-qa/unit-coverage.md", new Md().h1("Unit test coverage (JaCoCo)")
                .p("**Target: 100%.** Achieved: **" + pct(coverage.lineRatio()) + " of lines** ("
                        + coverage.linesCovered() + "/" + (coverage.linesCovered() + coverage.linesMissed()) + ") and **"
                        + pct(coverage.branchRatio()) + " of branches** (" + coverage.branchesCovered() + "/"
                        + (coverage.branchesCovered() + coverage.branchesMissed()) + "). The coverage gate required at "
                        + "least 90% of lines; everything below 100% is itemised in coverage-gaps.md. The full HTML "
                        + "report is in jacoco-html/index.html.")
                .table(List.of("Class", "Line coverage", "Branch coverage", "Lines missed"), covRows).toString(), written);

        JsonNode testReport = parse(a.get("test_report"));
        List<List<String>> declared = new ArrayList<>();
        testReport.path("coverageGaps").forEach(g -> declared.add(List.of(g.path("target").asString(""),
                g.path("reason").asString(""))));
        List<List<String>> gapRows = new ArrayList<>();
        coverage.gaps().forEach(g -> gapRows.add(List.of(g.sourceFile(),
                g.missedLineRanges().isEmpty() ? "—" : String.join(", ", g.missedLineRanges()),
                g.partlyCoveredBranchLines().isEmpty() ? "—" : g.partlyCoveredBranchLines().toString())));
        boolean complete = coverage.linesMissed() == 0 && coverage.branchesMissed() == 0;
        write(dir, "05-qa/coverage-gaps.md", new Md().h1("Coverage gaps: where the 100% target was not achieved")
                .p(complete ? "**The 100% line and branch coverage target was achieved.**"
                        : "**The 100% target was not fully achieved.** " + coverage.linesMissed() + " line(s) and "
                                + coverage.branchesMissed() + " branch(es) are not covered. They are listed below by file "
                                + "and line number, followed by the reasons the QA agent gave.")
                .h2("Uncovered code (from the JaCoCo report)")
                .table(List.of("Source file", "Uncovered lines", "Lines with partly covered branches"), gapRows)
                .h2("Reasons given by the QA agent").table(List.of("Target", "Reason"), declared).toString(), written);

        copyTree(ws.root().resolve("build/reports/jacoco/test/html"), dir.resolve("05-qa/jacoco-html"), written, dir);
        copyIfExists(ws.root().resolve("build/reports/jacoco/test/jacocoTestReport.xml"),
                dir.resolve("05-qa/jacocoTestReport.xml"), written, dir);
    }

    private void writeFunctionalCoverage(Path dir, Map<String, String> a, List<QaReports.TestCase> tests,
            List<String> written, Summary summary) {
        JsonNode spec = parse(a.get("requirements_spec"));
        JsonNode testReport = parse(a.get("test_report"));
        Map<String, String> storyOf = new LinkedHashMap<>();
        spec.path("userStories").forEach(s -> texts(s.path("acceptanceCriteria"))
                .forEach(ac -> storyOf.merge(ac, s.path("id").asString(""), (x, y) -> x + ", " + y)));
        Map<String, List<String>> testsFor = new LinkedHashMap<>();
        testReport.path("testCases").forEach(tc -> {
            String name = tc.path("name").asString("");
            String method = name.substring(name.lastIndexOf('.') + 1).replaceAll("\\(.*", "").strip();
            texts(tc.path("covers")).forEach(ac -> testsFor.computeIfAbsent(ac, k -> new ArrayList<>()).add(method));
        });
        List<List<String>> rows = new ArrayList<>();
        int covered = 0;
        for (JsonNode ac : spec.path("acceptanceCriteria")) {
            String id = ac.path("id").asString("");
            List<String> methods = testsFor.getOrDefault(id, List.of());
            List<String> results = new ArrayList<>();
            boolean allPass = !methods.isEmpty();
            for (String method : methods) {
                List<QaReports.TestCase> runsOf = tests.stream().filter(t -> t.method().equals(method)).toList();
                String result = runsOf.isEmpty() ? "not run"
                        : runsOf.stream().allMatch(t -> t.status().equals("PASSED")) ? "passed" : "FAILED";
                allPass &= result.equals("passed");
                results.add(method + " (" + result + ")");
            }
            if (allPass) {
                covered++;
            }
            rows.add(List.of(id, ac.path("criterion").asString(""), storyOf.getOrDefault(id, "—"),
                    String.join("<br>", results), allPass ? "COVERED" : methods.isEmpty() ? "NOT COVERED" : "FAILING"));
        }
        int total = rows.size();
        summary.criteriaCovered = covered;
        write(dir, "05-qa/functional-coverage.md", new Md().h1("Functional test coverage")
                .p("Every acceptance criterion mapped to the tests that prove it, with each test's result from the fresh "
                        + "build. **Target: 100%. Achieved: " + covered + "/" + total + " criteria ("
                        + pct(total == 0 ? 0 : (double) covered / total) + ").** The acceptanceCriteriaCovered gate "
                        + "rejected the test stage until every criterion had at least one real test method.")
                .table(List.of("Criterion", "Description", "User story", "Tests (result)", "Status"), rows)
                .toString(), written);
    }

    // ------------------------------------------------------------------ 06 release and governance

    private void writeRelease(Path dir, Map<String, String> a, List<String> written) {
        write(dir, "06-release/release-notes.md", Optional.ofNullable(a.get("release_notes")).orElse("_None._"), written);
        write(dir, "06-release/engineering-summary.md", Optional.ofNullable(a.get("engineering_summary")).orElse("_None._"),
                written);
    }

    private void writeGovernance(Path dir, WorkflowRun run, List<String> written) {
        List<List<String>> events = new ArrayList<>();
        for (AuditEvent e : audit.findByRunIdOrderByIdAsc(run.getId())) {
            events.add(List.of(e.getTimestamp().toString(), e.getType(), Optional.ofNullable(e.getNodeId()).orElse("-"),
                    e.getActor(), firstLines(Optional.ofNullable(e.getMessage()).orElse(""), 240)));
        }
        write(dir, "governance/audit-trail.md", new Md().h1("Orchestrator audit trail")
                .p("Every transition, gate result, policy finding, rollback, checkpoint commit, approval and publish, "
                        + "in order (append-only).")
                .table(List.of("Time (UTC)", "Event", "Stage", "Actor", "Message"), events).toString(), written);

        List<List<String>> rows = new ArrayList<>();
        for (ApprovalRequest r : approvals.findByRunIdOrderByIdAsc(run.getId())) {
            rows.add(List.of("#" + r.getId(), r.getNodeId(), r.getReason().name(), r.getStatus().name(),
                    Optional.ofNullable(r.getDecidedBy()).orElse("-"), Optional.ofNullable(r.getComment()).orElse("")));
        }
        write(dir, "governance/human-decisions.md", new Md().h1("Human checkpoints and decisions")
                .p("Approvals are requested at the spec, design and release checkpoints, by policies (configuration, "
                        + "schema changes) and by gates that need a human (clarification, blocking security findings).")
                .table(List.of("Approval", "Stage", "Reason", "Decision", "By", "Comment"), rows).toString(), written);

        RunMetrics m = metrics.forRun(run.getId());
        List<List<String>> stageRows = new ArrayList<>();
        m.stages().forEach(s -> stageRows.add(List.of(s.nodeId(), s.status(), String.valueOf(s.attempts()),
                s.durationMs() == null ? "-" : s.durationMs() / 1000 + "s")));
        write(dir, "governance/metrics.md", new Md().h1("Reliability metrics")
                .table(List.of("Metric", "Value"), List.of(
                        List.of("Status", m.status()),
                        List.of("End-to-end time", m.endToEndMs() / 1000 + "s"),
                        List.of("Agent time / human wait", m.agentTimeMs() / 1000 + "s / " + m.humanWaitMs() / 1000 + "s"),
                        List.of("Attempts / failed / retries", m.attempts() + " / " + m.failedAttempts() + " / " + m.retries()),
                        List.of("Rollbacks", String.valueOf(m.rollbacks())),
                        List.of("Approvals requested / rejected", m.approvalsRequested() + " / " + m.approvalsRejected()),
                        List.of("Tokens", String.valueOf(m.tokensUsed())),
                        List.of("MTTR", m.mttrMs() == null ? "-" : m.mttrMs() / 1000 + "s")))
                .h2("By stage").table(List.of("Stage", "Status", "Attempts", "Duration"), stageRows).toString(), written);
    }

    private void writeIndex(Path dir, WorkflowRun run, Summary s, List<String> written) {
        Md index = new Md().h1("SDLC deliverables: " + run.getScenario().name().toLowerCase() + " scenario")
                .p("All artifacts produced by the agents for run `" + run.getId() + "` (status " + run.getStatus()
                        + "), exported by the orchestrator. Requirement:")
                .raw("> " + run.getRequirement().replace("\n", "\n> "))
                .h2("At a glance")
                .table(List.of("Artifact", "Result"), List.of(
                        List.of("User stories / acceptance criteria", s.userStories + " / " + s.acceptanceCriteria),
                        List.of("Commits on the run branch", String.valueOf(s.commits)),
                        List.of("Classes with logging / audit entities", s.classesWithLogging + " / " + s.auditEntities),
                        List.of("Files reviewed", s.filesReviewed + " of " + s.filesChanged + " changed"),
                        List.of("Issues found / resolved", s.issuesFound + " / " + s.issuesResolved),
                        List.of("Tests (failed)", s.tests + " (" + s.testsFailed + ")"),
                        List.of("Functional coverage", s.criteriaCovered + "/" + s.acceptanceCriteria + " criteria"),
                        List.of("Line / branch coverage", pct(s.lineCoverage) + " / " + pct(s.branchCoverage) + " (target 100%)")))
                .h2("Contents")
                .table(List.of("Agent", "Deliverables"), List.of(
                        List.of("Requirements", "[user-stories.md](01-requirements/user-stories.md), [requirements-spec.md](01-requirements/requirements-spec.md)"),
                        List.of("Design / planning", "[design.md](02-design/design.md) (with diagrams), [api-contract.md](02-design/api-contract.md), [task-plan.md](02-design/task-plan.md)"
                                + (written.contains("02-design/impact-analysis.md") ? ", [impact-analysis.md](02-design/impact-analysis.md)" : "")),
                        List.of("Development", "[commit-history.md](03-development/commit-history.md), [change-set.md](03-development/change-set.md), [logging-audit-error-handling.md](03-development/logging-audit-error-handling.md), [documentation/](03-development/documentation/)"),
                        List.of("Code review", "[code-review-report.md](04-code-review/code-review-report.md)"),
                        List.of("QA", "[test-report.md](05-qa/test-report.md), [functional-coverage.md](05-qa/functional-coverage.md), [unit-coverage.md](05-qa/unit-coverage.md), [coverage-gaps.md](05-qa/coverage-gaps.md), [jacoco-html/](05-qa/jacoco-html/index.html)"),
                        List.of("Release", "[release-notes.md](06-release/release-notes.md), [engineering-summary.md](06-release/engineering-summary.md)"),
                        List.of("Governance", "[audit-trail.md](governance/audit-trail.md), [human-decisions.md](governance/human-decisions.md), [metrics.md](governance/metrics.md)")));
        write(dir, "README.md", index.toString(), written);
    }

    // ------------------------------------------------------------------ helpers

    private static final class Summary {
        int userStories, acceptanceCriteria, commits, classesWithLogging, filesChanged, filesReviewed, issuesFound,
                issuesResolved, tests, testsFailed, criteriaCovered;
        List<String> auditEntities = List.of();
        double lineCoverage, branchCoverage;

        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userStories", userStories);
            m.put("acceptanceCriteria", acceptanceCriteria);
            m.put("criteriaCovered", criteriaCovered);
            m.put("commits", commits);
            m.put("classesWithLogging", classesWithLogging);
            m.put("auditEntities", auditEntities);
            m.put("filesReviewed", filesReviewed + "/" + filesChanged);
            m.put("issuesFoundResolved", issuesFound + "/" + issuesResolved);
            m.put("tests", tests);
            m.put("testsFailed", testsFailed);
            m.put("lineCoverage", pct(lineCoverage));
            m.put("branchCoverage", pct(branchCoverage));
            return m;
        }
    }

    private Map<String, String> committedArtifacts(String runId) {
        Map<String, String> latest = new LinkedHashMap<>();
        for (Artifact artifact : artifacts.findByRunIdOrderByIdAsc(runId)) {
            if (artifact.getStatus() == ArtifactStatus.COMMITTED) {
                latest.put(artifact.getName(), artifact.getContent());
            }
        }
        return latest;
    }

    private JsonNode parse(String content) {
        if (content == null || content.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(content);
        } catch (RuntimeException e) {
            return json.createObjectNode();
        }
    }

    private String pretty(JsonNode node) {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    private static String compact(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "—" : node.isString() ? node.asString() : node.toString();
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString(v.toString())));
        return values;
    }

    /** Array items as text: strings as-is, objects as "key: value; ..." so any agent output shape reads well. */
    private static List<String> anyTexts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> {
            if (v.isObject()) {
                List<String> parts = new ArrayList<>();
                v.properties().forEach(e -> parts.add(e.getKey() + ": " + e.getValue().asString(e.getValue().toString())));
                values.add(String.join("; ", parts));
            } else {
                values.add(v.asString(v.toString()));
            }
        });
        return values;
    }

    private static String firstLines(String text, int max) {
        String oneLine = text.strip().replaceAll("\\s*\\n\\s*", " / ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + " …";
    }

    private static String pct(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }

    private static String safeName(String name) {
        String cleaned = name.toLowerCase().replaceAll("[^a-z0-9._-]", "-");
        if (cleaned.isBlank() || cleaned.startsWith(".")) {
            throw new IllegalArgumentException("Invalid deliverables name: " + name);
        }
        return cleaned;
    }

    private static void write(Path dir, String relative, String content, List<String> written) {
        try {
            Path file = dir.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            written.add(relative);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyIfExists(Path source, Path target, List<String> written, Path dir) {
        if (!Files.isRegularFile(source)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            written.add(dir.relativize(target).toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyTree(Path source, Path target, List<String> written, Path dir) {
        if (!Files.isDirectory(source)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                copyIfExists(path, target.resolve(source.relativize(path).toString()), written, dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
