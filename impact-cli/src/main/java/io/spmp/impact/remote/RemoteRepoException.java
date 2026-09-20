package io.spmp.impact.remote;

/**
 * Runtime exception carrying a CLI exit code. Thrown by the remote-repo layer when
 * a network / auth / shape problem makes proceeding unsafe.
 *
 * <p>Exit code contract (see plan §"Error handling"):
 * <ul>
 *   <li>{@code 10} — auth failed (401 / 403)</li>
 *   <li>{@code 11} — repo not found (404)</li>
 *   <li>{@code 12} — network / connection error</li>
 *   <li>{@code 13} — partial download; cache invalidated, retry suggested</li>
 *   <li>{@code 14} — endpoint not found, response shape wrong, or other API contract violation</li>
 * </ul>
 *
 * <p>Callers handle by printing {@code getMessage()} to stderr and
 * {@code System.exit(exitCode())}.
 */
public class RemoteRepoException extends RuntimeException {
    private final int exitCode;

    public RemoteRepoException(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public RemoteRepoException(int exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public int exitCode() { return exitCode; }
}
