package io.spmp.impact.diff;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HunkReconcilerTest {

    @Test
    void parseBStart_standard() {
        assertEquals(182, HunkReconciler.parseBStart("@@ -181,6 +182,32 @@"));
        assertEquals(1, HunkReconciler.parseBStart("@@ -0,0 +1,50 @@"));
        assertEquals(42, HunkReconciler.parseBStart("@@ -40,3 +42 @@"));
    }

    @Test
    void reconcileDetectsOffset() {
        // Simulate: patch says B-side starts at line 5, content is "alpha\nbeta\ngamma"
        // But actual source has those lines at position 8 (3-line shift)
        String patchText = """
                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                @@ -4,3 +5,5 @@ context
                 existing_line
                +alpha_unique_content_xyz
                +beta_unique_content_abc
                +gamma_unique_content_def
                 trailing_line
                """;

        String[] actualLines = {
            "line1",               // 1
            "line2",               // 2
            "line3",               // 3
            "line4",               // 4
            "something_inserted",  // 5
            "another_inserted",    // 6
            "yet_another",         // 7
            "existing_line",       // 8
            "alpha_unique_content_xyz",  // 9
            "beta_unique_content_abc",   // 10
            "gamma_unique_content_def",  // 11
            "trailing_line",       // 12
            "line13"               // 13
        };

        List<int[]> patchHunks = List.of(new int[]{5, 9}); // from patch: lines 5-9
        List<HunkReconciler.HunkContent> contents =
            HunkReconciler.extractHunkContents(patchText, "Foo.java");
        assertFalse(contents.isEmpty());

        List<int[]> result = HunkReconciler.reconcileInternal(
            patchText, "Foo.java", patchHunks, actualLines);

        // The content is at actual lines 8-12, so offset=+3
        // Original hunk [5,9] → adjusted [8,12]
        assertEquals(8, result.get(0)[0]);
        assertEquals(12, result.get(0)[1]);
    }

    @Test
    void reconcileNoOffsetReturnsSameRanges() {
        String patchText = """
                diff --git a/Bar.java b/Bar.java
                --- a/Bar.java
                +++ b/Bar.java
                @@ -1,3 +1,5 @@
                 first_line
                +added_unique_line_here
                +another_unique_addition
                 last_line
                """;

        String[] actualLines = {
            "first_line",              // 1
            "added_unique_line_here",  // 2
            "another_unique_addition", // 3
            "last_line"                // 4
        };

        List<int[]> patchHunks = List.of(new int[]{1, 4});
        List<int[]> result = HunkReconciler.reconcileInternal(
            patchText, "Bar.java", patchHunks, actualLines);

        assertEquals(1, result.get(0)[0]);
        assertEquals(4, result.get(0)[1]);
    }

    @Test
    void realPatch_13216_WFNotificationMacro() throws Exception {
        // Integration test: only runs if the actual files exist
        Path patchFile = Path.of("d:\\SPMP\\LoadBalancerV1\\ADMP\\13216\\9b3f4f5752...10d02b2e92.patch");
        Path sourceFile = Path.of("d:\\SPMP\\LoadBalancerV1\\adsm-ADMP_8050_OPEN_ISSUE_FIXES_BRANCH",
            "adsm-ADMP_8050_OPEN_ISSUE_FIXES_BRANCH\\source\\java_source\\server",
            "com\\adventnet\\sym\\adsm\\common\\server\\workflow\\WFNotificationMacro.java");

        if (!Files.exists(patchFile) || !Files.exists(sourceFile)) {
            System.out.println("[skip] real files not found");
            return;
        }

        byte[] sourceBytes = Files.readAllBytes(sourceFile);
        // The patch hunk @@ -181,6 +182,32 @@ has B-side starting at line 182
        List<int[]> patchHunks = List.of(new int[]{182, 213});
        List<int[]> result = HunkReconciler.reconcile(patchFile,
            "source/java_source/server/com/adventnet/sym/adsm/common/server/workflow/WFNotificationMacro.java",
            patchHunks, sourceBytes);

        // The actual code is shifted +9 lines, so expected: [191, 222]
        System.out.printf("Original: [%d, %d] → Reconciled: [%d, %d]%n",
            patchHunks.get(0)[0], patchHunks.get(0)[1],
            result.get(0)[0], result.get(0)[1]);

        // Verify it detected the shift (reconciled start should be > original start)
        assertTrue(result.get(0)[0] > patchHunks.get(0)[0],
            "Expected reconciled start > 182, got " + result.get(0)[0]);
    }
}
