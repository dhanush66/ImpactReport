package io.spmp.impact.testgen.ingest;

import io.spmp.impact.testgen.TestCaseBatch;
import io.spmp.impact.testgen.TestCaseBatch.InSuiteEdge;
import io.spmp.impact.testgen.TestCaseBatch.TestCaseNode;
import io.spmp.impact.testgen.TestCaseBatch.TestSuiteNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses test cases out of a markdown file structured as one or more pipe-tables with
 * columns {@code | ID | Title | Steps | Expected |}.
 *
 * <p>Conforming format (matches the SPMP {@code TestCases.md}):
 * <pre>
 *   ## 1. UI test cases
 *
 *   | ID | Title | Steps | Expected |
 *   | --- | --- | --- | --- |
 *   | LBF-UI-001 | Renamed menu label | Log in as Admin … | Left rail shows … |
 * </pre>
 *
 * <p>The area code is parsed from each test ID: {@code LBF-<AREA>-<NN>}.
 */
public final class TestCasesMdIngestor {

    private static final Pattern TC_ID = Pattern.compile("^(LBF-([A-Z]+)-\\d+)\\b");

    private TestCasesMdIngestor() {}

    public static void ingest(Path mdFile, TestCaseBatch batch) throws IOException {
        List<String> lines = Files.readAllLines(mdFile);
        String source = mdFile.getFileName().toString();
        Set<String> suites = new HashSet<>();
        int rowsParsed = 0;

        for (String raw : lines) {
            String line = raw.trim();
            if (!line.startsWith("|")) continue;
            // skip table headers and divider rows
            if (line.contains("---")) continue;
            // Split on | with empty leading/trailing
            String[] cells = line.split("\\|", -1);
            if (cells.length < 5) continue;  // need at least ID, Title, Steps, Expected
            String id = cells[1].trim();
            // Sometimes the ID has a trailing reference like "(PDF#1)"
            String idCore = stripParens(id);
            Matcher m = TC_ID.matcher(idCore);
            if (!m.find()) continue;  // header rows or non-ID rows

            String pureId = m.group(1);
            String area = m.group(2);
            String title = cells[2].trim();
            String steps = cells[3].trim();
            String expected = cells[4].trim();

            batch.testCases.add(new TestCaseNode(pureId, title, area, steps, expected, source));
            batch.inSuite.add(new InSuiteEdge(pureId, area));
            suites.add(area);
            rowsParsed++;
        }

        for (String suiteName : suites) batch.suites.add(new TestSuiteNode(suiteName));
        System.out.printf("[TestCasesMdIngestor] %s: parsed %d test cases across %d suites%n",
            source, rowsParsed, suites.size());
    }

    private static String stripParens(String s) {
        int p = s.indexOf('(');
        return p < 0 ? s : s.substring(0, p).trim();
    }
}
