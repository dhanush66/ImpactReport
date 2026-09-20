package io.spmp.impact.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class DiffModels {
    private DiffModels() {}

    /** A Java file touched by the diff. {@code hunkRanges} are 1-based, inclusive [start,end] line ranges in the HEAD revision. */
    public record FileChange(
        String oldPath,
        String newPath,
        String changeType,        // "ADD" | "MODIFY" | "DELETE" | "RENAME" | "COPY"
        List<int[]> hunkRanges
    ) {}

    /**
     * Result of mapping a hunk to its smallest enclosing AST symbol.
     *
     * <p>{@code startLine}/{@code endLine} are the ENCLOSING symbol's range — e.g. the
     * full method declaration spans lines 2299-2719. {@code hunkStartLine}/{@code hunkEndLine}
     * are the ACTUAL diff hunk lines that landed inside the symbol (e.g. 2487-2495). The
     * report uses the hunk range to point QA at the precise changed lines instead of
     * making them scan a 420-line method.
     */
    public record ChangedSymbol(
        String filePath,
        String fqn,
        Kind kind,
        int startLine,
        int endLine,
        ChangeNature nature,
        int hunkStartLine,
        int hunkEndLine
    ) {
        /** Back-compat constructor: defaults hunk range = symbol range (callers that don't track hunks). */
        public ChangedSymbol(String filePath, String fqn, Kind kind, int startLine, int endLine, ChangeNature nature) {
            this(filePath, fqn, kind, startLine, endLine, nature, startLine, endLine);
        }
        public enum Kind { METHOD, CONSTRUCTOR, CLASS, INTERFACE }
        public enum ChangeNature { BODY, SIGNATURE, ADDED, DELETED }
    }

    /**
     * Merge {@link ChangedSymbol} entries that share (filePath, fqn, kind) — e.g. two separate
     * patch hunks landing inside the same method each resolve to their own ChangedSymbol for
     * that method (HunkToSymbolResolver processes hunks independently), and record equality
     * (which also compares hunkStartLine/hunkEndLine) doesn't catch that as a duplicate.
     *
     * <p>Both the CLI pipeline ({@code AnalyzeCmd}) and the web pipeline
     * ({@code AnalyzeController}) build their own {@code ChangedSymbol} list from
     * {@code HunkToSymbolResolver} output, so this lives here as the one shared merge step both
     * call — rather than duplicated (and drifting) per caller.
     *
     * <p>Keeps the first entry's identity fields and widens {@code hunkStartLine}/
     * {@code hunkEndLine} to the union of every merged hunk, keeping the most significant
     * {@link ChangedSymbol.ChangeNature} (DELETED > ADDED > SIGNATURE > BODY).
     */
    public static List<ChangedSymbol> mergeDuplicateSymbols(List<ChangedSymbol> in) {
        LinkedHashMap<String, ChangedSymbol> merged = new LinkedHashMap<>();
        for (ChangedSymbol cs : in) {
            String key = cs.filePath() + "|" + cs.fqn() + "|" + cs.kind();
            ChangedSymbol existing = merged.get(key);
            if (existing == null) {
                merged.put(key, cs);
            } else {
                int hStart = Math.min(existing.hunkStartLine(), cs.hunkStartLine());
                int hEnd = Math.max(existing.hunkEndLine(), cs.hunkEndLine());
                ChangedSymbol.ChangeNature nature = moreSignificantNature(existing.nature(), cs.nature());
                merged.put(key, new ChangedSymbol(existing.filePath(), existing.fqn(), existing.kind(),
                    existing.startLine(), existing.endLine(), nature, hStart, hEnd));
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static ChangedSymbol.ChangeNature moreSignificantNature(
            ChangedSymbol.ChangeNature a, ChangedSymbol.ChangeNature b) {
        return natureRank(b) > natureRank(a) ? b : a;
    }

    private static int natureRank(ChangedSymbol.ChangeNature n) {
        return switch (n) {
            case DELETED -> 3;
            case ADDED -> 2;
            case SIGNATURE -> 1;
            case BODY -> 0;
        };
    }
}
