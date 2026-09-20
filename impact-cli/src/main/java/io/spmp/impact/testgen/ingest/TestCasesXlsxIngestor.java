package io.spmp.impact.testgen.ingest;

import io.spmp.impact.testgen.TestCaseBatch;
import io.spmp.impact.testgen.TestCaseBatch.InSuiteEdge;
import io.spmp.impact.testgen.TestCaseBatch.TestCaseNode;
import io.spmp.impact.testgen.TestCaseBatch.TestSuiteNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses test cases from a .xlsx workbook. Strategy:
 * <ol>
 *   <li>For each sheet, find a header row that contains both an "ID" / "Test ID" column
 *       and at least one of "Title" / "Steps" / "Expected".</li>
 *   <li>Read subsequent rows; treat each as a test case if the ID column matches
 *       {@code LBF-<AREA>-<NN>}.</li>
 *   <li>Be defensive: missing columns are filled with empty strings.</li>
 * </ol>
 *
 * <p>This avoids hard-coding column positions, since spreadsheets get edited and the
 * SPMP file's schema may drift between releases.
 */
public final class TestCasesXlsxIngestor {

    private static final Pattern TC_ID = Pattern.compile("^(LBF-([A-Z]+)-\\d+)\\b");

    private TestCasesXlsxIngestor() {}

    public static void ingest(Path xlsx, TestCaseBatch batch) throws IOException {
        String source = xlsx.getFileName().toString();
        Set<String> suites = new HashSet<>();
        int total = 0;

        try (InputStream in = Files.newInputStream(xlsx);
             Workbook wb = new XSSFWorkbook(in)) {
            DataFormatter fmt = new DataFormatter();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                int[] cols = findColumns(sheet, fmt);
                if (cols == null) continue;
                int idCol = cols[0], titleCol = cols[1], stepsCol = cols[2], expectedCol = cols[3];
                int headerRow = cols[4];

                int parsedInSheet = 0;
                for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String idRaw = cell(row, idCol, fmt);
                    Matcher m = TC_ID.matcher(stripParens(idRaw));
                    if (!m.find()) continue;
                    String id = m.group(1);
                    String area = m.group(2);

                    batch.testCases.add(new TestCaseNode(
                        id,
                        titleCol >= 0 ? cell(row, titleCol, fmt) : "",
                        area,
                        stepsCol >= 0 ? cell(row, stepsCol, fmt) : "",
                        expectedCol >= 0 ? cell(row, expectedCol, fmt) : "",
                        source
                    ));
                    batch.inSuite.add(new InSuiteEdge(id, area));
                    suites.add(area);
                    parsedInSheet++;
                }
                if (parsedInSheet > 0) {
                    System.out.printf("[TestCasesXlsxIngestor] %s/%s: %d rows%n",
                        source, sheet.getSheetName(), parsedInSheet);
                    total += parsedInSheet;
                }
            }
        }

        for (String suiteName : suites) batch.suites.add(new TestSuiteNode(suiteName));
        System.out.printf("[TestCasesXlsxIngestor] %s: parsed %d test cases across %d suites%n",
            source, total, suites.size());
    }

    /**
     * Return [idCol, titleCol, stepsCol, expectedCol, headerRowIndex] or null if no
     * suitable header row found in this sheet.
     */
    private static int[] findColumns(Sheet sheet, DataFormatter fmt) {
        for (int r = sheet.getFirstRowNum(); r <= Math.min(sheet.getLastRowNum(), 20); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int idCol = -1, titleCol = -1, stepsCol = -1, expectedCol = -1;
            for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
                String v = cell(row, c, fmt).toLowerCase();
                if (v.equals("id") || v.equals("test id") || v.equals("testcase id") || v.equals("test case id")) idCol = c;
                else if (v.equals("title") || v.contains("test case") || v.equals("name") || v.equals("scenario")) titleCol = c < 0 ? c : (titleCol < 0 ? c : titleCol);
                else if (v.equals("steps") || v.contains("step")) stepsCol = stepsCol < 0 ? c : stepsCol;
                else if (v.equals("expected") || v.contains("expected")) expectedCol = expectedCol < 0 ? c : expectedCol;
            }
            if (idCol >= 0 && (titleCol >= 0 || stepsCol >= 0 || expectedCol >= 0)) {
                return new int[]{idCol, titleCol, stepsCol, expectedCol, r};
            }
        }
        return null;
    }

    private static String cell(Row row, int c, DataFormatter fmt) {
        if (c < 0) return "";
        Cell cell = row.getCell(c);
        if (cell == null) return "";
        return fmt.formatCellValue(cell).trim();
    }

    private static String stripParens(String s) {
        if (s == null) return "";
        int p = s.indexOf('(');
        return p < 0 ? s.trim() : s.substring(0, p).trim();
    }
}
