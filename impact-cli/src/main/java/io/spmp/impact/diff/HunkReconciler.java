package io.spmp.impact.diff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reconciles patch hunk line numbers against the actual workspace source file.
 *
 * <p>When the workspace file is newer than the patch's B-side (post-patch version),
 * lines may have shifted. This reconciler parses the raw unified-diff text to extract
 * the expected B-side content of each hunk, then searches for that content in the
 * actual source to compute and apply the line offset.
 *
 * <p>If reconciliation cannot determine an offset (e.g., the anchor lines are not
 * found or ambiguous), the original patch-declared ranges are returned unchanged.
 */
public final class HunkReconciler {

    private HunkReconciler() {}

    /**
     * Reconcile hunk ranges for a single file in the patch.
     *
     * @param patchFile     path to the raw .patch file
     * @param filePath      the file's path within the repo (matches the b/... path in the diff)
     * @param patchHunks    original hunk ranges [startLine, endLine] from jgit (1-based, inclusive)
     * @param actualSource  the current workspace file content
     * @return adjusted hunk ranges; same list if no adjustment needed
     */
    public static List<int[]> reconcile(Path patchFile, String filePath,
                                        List<int[]> patchHunks, byte[] actualSource) {
        if (patchHunks.isEmpty() || actualSource == null || actualSource.length == 0) {
            return patchHunks;
        }
        try {
            String patchText = Files.readString(patchFile, StandardCharsets.UTF_8);
            String[] actualLines = new String(actualSource, StandardCharsets.UTF_8).split("\n", -1);
            return reconcileInternal(patchText, filePath, patchHunks, actualLines);
        } catch (IOException e) {
            // If we can't read the patch, return original ranges
            return patchHunks;
        }
    }

    static List<int[]> reconcileInternal(String patchText, String filePath,
                                         List<int[]> patchHunks, String[] actualLines) {
        // Find the file section in the patch text
        List<HunkContent> hunkContents = extractHunkContents(patchText, filePath);
        if (hunkContents.isEmpty()) return patchHunks;

        List<int[]> adjusted = new ArrayList<>(patchHunks.size());
        for (int i = 0; i < patchHunks.size(); i++) {
            int[] original = patchHunks.get(i);
            // Match patchHunk to HunkContent by B-start line (more robust than index)
            HunkContent matched = findMatchingContent(original, hunkContents);
            if (matched != null) {
                int[] reconciled = reconcileOneHunk(original, matched, actualLines);
                adjusted.add(reconciled);
            } else {
                adjusted.add(original);
            }
        }
        return adjusted;
    }

    /**
     * Find the HunkContent whose B-start line best matches the given patch hunk range.
     * The hunk's start line should fall within or just after the HunkContent's B-start.
     */
    private static HunkContent findMatchingContent(int[] hunk, List<HunkContent> contents) {
        // Exact match: hunk start == bStartLine
        for (HunkContent hc : contents) {
            if (hunk[0] == hc.bStartLine) return hc;
        }
        // Close match: hunk start is within the content's range
        for (HunkContent hc : contents) {
            int hcEnd = hc.bStartLine + hc.bSideLines.size() - 1;
            if (hunk[0] >= hc.bStartLine && hunk[0] <= hcEnd) return hc;
        }
        return null;
    }

    /**
     * For a single hunk, find where its B-side content actually appears in the source.
     * Strategy: Use CONTEXT lines (unchanged by the patch) as position anchors since
     * they reliably exist in the workspace even after additional modifications.
     * Added lines may have been subsequently edited and won't match exactly.
     */
    private static int[] reconcileOneHunk(int[] original, HunkContent content, String[] actualLines) {
        if (content.bSideLines.isEmpty()) return original;

        // Strategy 1: Use TRAILING context lines as anchors (after the added block).
        // These are distinctive and exist in both the pre-patch and post-patch file.
        int offset = tryContextAnchors(content, actualLines, false); // trailing
        if (offset == Integer.MIN_VALUE) {
            // Strategy 2: Use LEADING context lines
            offset = tryContextAnchors(content, actualLines, true);
        }
        if (offset == Integer.MIN_VALUE) {
            // Strategy 3: Try added lines (works when workspace hasn't diverged)
            offset = tryAddedAnchors(content, actualLines);
        }
        if (offset == Integer.MIN_VALUE) return original;
        if (offset == 0) return original;

        int newStart = original[0] + offset;
        int newEnd = original[1] + offset;
        if (newStart < 1) newStart = 1;
        if (newEnd < newStart) newEnd = newStart;
        if (newEnd > actualLines.length) newEnd = actualLines.length;
        return new int[]{newStart, newEnd};
    }

    /**
     * Try to find offset using context lines (leading or trailing).
     * Returns the offset or Integer.MIN_VALUE if not found.
     */
    private static int tryContextAnchors(HunkContent content, String[] actualLines, boolean leading) {
        List<String> anchors = new ArrayList<>();
        List<Integer> anchorOffsets = new ArrayList<>();

        if (leading) {
            // Leading context: context lines at the start of the hunk (before added block)
            for (int i = 0; i < content.bSideLines.size(); i++) {
                if (content.addedLineIndices.contains(i)) break; // stop at first added line
                String line = content.bSideLines.get(i).trim();
                if (line.length() >= 8 && !line.equals("{") && !line.equals("}")) {
                    anchors.add(line);
                    anchorOffsets.add(i);
                    if (anchors.size() >= 2) break;
                }
            }
        } else {
            // Trailing context: context lines at the end of the hunk (after added block)
            for (int i = content.bSideLines.size() - 1; i >= 0; i--) {
                if (content.addedLineIndices.contains(i)) break; // stop at last added line
                String line = content.bSideLines.get(i).trim();
                if (line.length() >= 8 && !line.equals("{") && !line.equals("}")) {
                    anchors.add(0, line); // prepend to maintain order
                    anchorOffsets.add(0, i);
                    if (anchors.size() >= 2) break;
                }
            }
        }

        if (anchors.isEmpty()) return Integer.MIN_VALUE;
        return searchAnchors(anchors, anchorOffsets, content.bStartLine, actualLines);
    }

    /**
     * Try to find offset using added lines (works when content hasn't been further modified).
     */
    private static int tryAddedAnchors(HunkContent content, String[] actualLines) {
        List<String> anchors = new ArrayList<>();
        List<Integer> anchorOffsets = new ArrayList<>();

        for (int i = 0; i < content.bSideLines.size(); i++) {
            if (!content.addedLineIndices.contains(i)) continue;
            String line = content.bSideLines.get(i).trim();
            if (line.length() >= 20 && !line.equals("{") && !line.equals("}")) {
                anchors.add(line);
                anchorOffsets.add(i);
                if (anchors.size() >= 3) break;
            }
        }
        if (anchors.isEmpty()) return Integer.MIN_VALUE;
        return searchAnchors(anchors, anchorOffsets, content.bStartLine, actualLines);
    }

    /**
     * Search for anchor lines in the actual source and compute the offset.
     */
    private static int searchAnchors(List<String> anchors, List<Integer> anchorOffsets,
                                     int bStartLine, String[] actualLines) {
        int expectedPos = bStartLine - 1; // 0-based
        int searchRadius = Math.max(50, Math.abs(actualLines.length - expectedPos));
        String firstAnchor = anchors.get(0);
        int firstAnchorOffset = anchorOffsets.get(0);

        int bestMatch = -1;
        int bestDistance = Integer.MAX_VALUE;

        for (int i = Math.max(0, expectedPos - searchRadius);
             i < Math.min(actualLines.length, expectedPos + searchRadius); i++) {
            if (actualLines[i].trim().equals(firstAnchor)) {
                boolean allMatch = true;
                for (int a = 1; a < anchors.size(); a++) {
                    int checkIdx = i + (anchorOffsets.get(a) - firstAnchorOffset);
                    if (checkIdx < 0 || checkIdx >= actualLines.length
                        || !actualLines[checkIdx].trim().equals(anchors.get(a))) {
                        allMatch = false;
                        break;
                    }
                }
                if (allMatch) {
                    int distance = Math.abs(i - expectedPos);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestMatch = i;
                    }
                }
            }
        }

        if (bestMatch < 0) return Integer.MIN_VALUE;

        int patchAnchorPos = (bStartLine - 1) + firstAnchorOffset;
        return bestMatch - patchAnchorPos;
    }

    /**
     * Parse the raw patch text and extract B-side line content for each hunk of the
     * given file.
     */
    static List<HunkContent> extractHunkContents(String patchText, String filePath) {
        List<HunkContent> results = new ArrayList<>();

        // Find the file section. Unified diffs use "--- a/path" and "+++ b/path" or
        // "diff --git a/path b/path" headers.
        String[] lines = patchText.split("\n", -1);
        int fileStart = findFileSection(lines, filePath);
        if (fileStart < 0) return results;

        // Walk hunks within this file section (until next "diff --git" or end)
        for (int i = fileStart; i < lines.length; i++) {
            if (i > fileStart && lines[i].startsWith("diff --git ")) break;

            if (lines[i].startsWith("@@ ")) {
                // Parse @@ -a,b +c,d @@ header
                int bStart = parseBStart(lines[i]);
                if (bStart < 0) continue;

                // Collect B-side lines: context (' ') and added ('+') lines
                List<String> bSideLines = new ArrayList<>();
                java.util.Set<Integer> addedIndices = new java.util.HashSet<>();
                for (int j = i + 1; j < lines.length; j++) {
                    if (lines[j].startsWith("@@ ") || lines[j].startsWith("diff --git ")) break;
                    if (lines[j].startsWith("+")) {
                        addedIndices.add(bSideLines.size());
                        bSideLines.add(lines[j].substring(1));
                    } else if (lines[j].startsWith(" ")) {
                        bSideLines.add(lines[j].substring(1));
                    }
                    // '-' lines are A-side only; skip them
                }
                results.add(new HunkContent(bStart, bSideLines, addedIndices));
            }
        }
        return results;
    }

    private static int findFileSection(String[] lines, String filePath) {
        // Normalize: strip leading slashes, handle both forward and back slashes
        String normalized = filePath.replace('\\', '/');
        // Try exact matches first: "+++ b/path" or "diff --git a/path b/path"
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("+++ ")) {
                String path = lines[i].substring(4).trim();
                if (path.startsWith("b/")) path = path.substring(2);
                if (path.equals(normalized)) return i + 1; // hunks start after +++
            }
        }
        // Fallback: match by filename suffix
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("+++ ")) {
                String path = lines[i].substring(4).trim();
                if (path.startsWith("b/")) path = path.substring(2);
                if (path.endsWith(normalized) || normalized.endsWith(path)) return i + 1;
            }
        }
        return -1;
    }

    /**
     * Parse the B-side start line from a @@ header.
     * Format: @@ -oldStart[,oldCount] +newStart[,newCount] @@
     */
    static int parseBStart(String headerLine) {
        int plusIdx = headerLine.indexOf('+', 3); // skip the leading "@@"
        if (plusIdx < 0) return -1;
        int spaceOrComma = headerLine.indexOf(' ', plusIdx);
        int comma = headerLine.indexOf(',', plusIdx);
        int end;
        if (comma > 0 && (spaceOrComma < 0 || comma < spaceOrComma)) {
            end = comma;
        } else if (spaceOrComma > 0) {
            end = spaceOrComma;
        } else {
            return -1;
        }
        try {
            return Integer.parseInt(headerLine.substring(plusIdx + 1, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Holds the B-side start line, content, and added-line indices extracted from a raw patch hunk. */
    record HunkContent(int bStartLine, List<String> bSideLines, java.util.Set<Integer> addedLineIndices) {}
}
