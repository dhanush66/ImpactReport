package io.spmp.impact.testgen;

import java.util.ArrayList;
import java.util.List;

/** Accumulator for test-case ingestion. Mirrors {@code ExtractionBatch} but scoped to testgen. */
public class TestCaseBatch {

    public final List<TestCaseNode> testCases = new ArrayList<>();
    public final List<TestSuiteNode> suites = new ArrayList<>();
    public final List<InSuiteEdge> inSuite = new ArrayList<>();
    public final List<CoversEdge> covers = new ArrayList<>();

    /** Single test case row from a markdown table or xlsx sheet. */
    public record TestCaseNode(
        String id,           // e.g. "LBF-DIST-006"
        String title,
        String area,         // "UI" | "CFG" | "DIST" | "DB" | "ENV" | "SEC"
        String steps,
        String expected,
        String source        // source filename (e.g. "TestCases.md")
    ) {}

    /** A group of test cases (one per area in v1). */
    public record TestSuiteNode(String name) {}

    public record InSuiteEdge(String testCaseId, String suiteName) {}

    /**
     * Test case COVERS a graph node. {@code targetKey} is the natural key of the target
     * node (FQN for classes/methods, name for tables, id for task types, value for
     * message constants, url for rest endpoints). {@code targetKind} disambiguates.
     */
    public record CoversEdge(
        String testCaseId,
        String targetKind,   // "Method" | "Class" | "RestEndpoint" | "TaskType" | "DbTable" | "MessageConstant" | "Scheduler"
        String targetKey,
        double confidence    // 0.0..1.0 — auto-tagger's confidence (1.0 = exact match)
    ) {}

    public int size() { return testCases.size(); }
}
