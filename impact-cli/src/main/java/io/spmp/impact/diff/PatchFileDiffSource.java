package io.spmp.impact.diff;

import io.spmp.impact.model.DiffModels.FileChange;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.Patch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a unified-diff `.patch` file and emits the same {@link FileChange} records
 * that {@link JgitDiffSource} produces.
 *
 * <p>For impact analysis the patch file's HEAD-side line ranges are what matter —
 * we'll then read the actual HEAD file from the working tree at {@code <repo>/<newPath>}.
 *
 * <p>Assumes the patch was generated with {@code git diff} (uses {@code a/} and
 * {@code b/} prefixes which jgit strips automatically).
 */
public final class PatchFileDiffSource {

    private PatchFileDiffSource() {}

    public static List<FileChange> readJava(Path patchFile) throws IOException {
        return readFiltered(patchFile, p -> p.endsWith(".java"));
    }

    /**
     * Read every file in the patch — Java + polyglot + configs. Caller is responsible
     * for routing each {@link FileChange} to the right resolver based on extension.
     */
    public static List<FileChange> readAll(Path patchFile) throws IOException {
        return readFiltered(patchFile, p -> true);
    }

    private static List<FileChange> readFiltered(Path patchFile,
                                                 java.util.function.Predicate<String> accept) throws IOException {
        Patch patch = new Patch();
        try (InputStream in = Files.newInputStream(patchFile)) {
            patch.parse(in);
        }

        List<FileChange> out = new ArrayList<>();
        for (FileHeader fh : patch.getFiles()) {
            String oldPath = fh.getOldPath();
            String newPath = fh.getNewPath();
            String pathForFilter =
                (fh.getChangeType() == FileHeader.ChangeType.DELETE) ? oldPath : newPath;
            if (!accept.test(pathForFilter)) continue;

            List<int[]> hunks = new ArrayList<>();
            if (fh.getChangeType() != FileHeader.ChangeType.DELETE) {
                EditList edits = fh.toEditList();
                for (Edit e : edits) {
                    int startB = e.getBeginB() + 1;
                    int endB = Math.max(e.getEndB(), e.getBeginB() + 1);
                    hunks.add(new int[]{startB, endB});
                }
            }
            out.add(new FileChange(
                oldPath,
                newPath,
                fh.getChangeType().name(),
                hunks
            ));
        }
        return out;
    }
}
