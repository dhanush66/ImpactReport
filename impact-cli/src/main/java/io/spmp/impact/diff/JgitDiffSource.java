package io.spmp.impact.diff;

import io.spmp.impact.model.DiffModels.FileChange;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.NullOutputStream;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a git diff between two revisions using jgit.
 *
 * <p>Returns one {@link FileChange} per touched Java file with its hunk line ranges
 * (1-based, inclusive) in the HEAD revision. Renames are detected. Deleted files are
 * included but their hunkRanges will be empty.
 */
public class JgitDiffSource implements AutoCloseable {

    private final Git git;
    private final Repository repo;

    public JgitDiffSource(Path repoDir) throws IOException {
        this.git = Git.open(repoDir.toFile());
        this.repo = git.getRepository();
    }

    public Repository repository() { return repo; }

    /** Resolve "branch", "tag", "sha", or "HEAD^" to its tree id. */
    public ObjectId resolveTree(String rev) throws IOException {
        ObjectId id = repo.resolve(rev + "^{tree}");
        if (id == null) {
            throw new IllegalArgumentException("Cannot resolve revision: " + rev);
        }
        return id;
    }

    /** Compute the set of Java-file changes between baseRev..headRev. */
    public List<FileChange> diffJava(String baseRev, String headRev) throws IOException {
        ObjectId baseTree = resolveTree(baseRev);
        ObjectId headTree = resolveTree(headRev);
        List<FileChange> out = new ArrayList<>();

        try (DiffFormatter fmt = new DiffFormatter(NullOutputStream.INSTANCE)) {
            fmt.setRepository(repo);
            fmt.setDetectRenames(true);

            List<DiffEntry> entries = fmt.scan(baseTree, headTree);
            for (DiffEntry de : entries) {
                String path = de.getChangeType() == DiffEntry.ChangeType.DELETE
                    ? de.getOldPath()
                    : de.getNewPath();
                if (!path.endsWith(".java")) continue;

                List<int[]> hunks = new ArrayList<>();
                if (de.getChangeType() != DiffEntry.ChangeType.DELETE) {
                    FileHeader header = fmt.toFileHeader(de);
                    EditList edits = header.toEditList();
                    for (Edit e : edits) {
                        // EditList is 0-based, end-exclusive on the B (head) side.
                        // Convert to 1-based inclusive line numbers.
                        int startB = e.getBeginB() + 1;
                        int endB   = Math.max(e.getEndB(), e.getBeginB() + 1);
                        hunks.add(new int[]{startB, endB});
                    }
                }
                out.add(new FileChange(
                    de.getOldPath(),
                    de.getNewPath(),
                    de.getChangeType().name(),
                    hunks
                ));
            }
        }
        return out;
    }

    /** Read the raw bytes of a file at a given tree. Returns null if the path is absent. */
    public byte[] readBlob(ObjectId treeId, String path) throws IOException {
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(treeId);
            tw.setRecursive(true);
            tw.setFilter(PathFilter.create(path));
            if (!tw.next()) return null;
            ObjectId blobId = tw.getObjectId(0);
            try (ObjectReader reader = repo.newObjectReader()) {
                return reader.open(blobId).getBytes();
            }
        }
    }

    @Override
    public void close() {
        git.close();
    }
}
