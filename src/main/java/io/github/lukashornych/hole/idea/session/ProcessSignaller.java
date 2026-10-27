package io.github.lukashornych.hole.idea.session;

import com.intellij.execution.process.UnixProcessManager;
import com.pty4j.unix.PtyHelpers;

/**
 * Sends a Unix signal to a `hole start` process.
 *
 * <p>Hole's contract for stopping a sandbox from another program (Hole 2.6.0 README, "Stopping a sandbox from another
 * program") is a signal to the <b>process group</b> of `hole start`, the way a terminal's Ctrl-C does it. Signalling
 * only the `hole` process also tears the sandbox down, but during an image build or a container start the teardown
 * waits until that step has finished, which can take minutes; the group signal stops the running step (e.g.
 * `docker compose build`) too. `hole start` runs directly as the process of its pty, and pty4j starts it as the
 * leader of a new session and process group, so the group ID is its PID.</p>
 *
 * <p>Never the process tree (it would also hit Hole's teardown watchdog, which runs in its own session on purpose),
 * and never Ctrl-C (`0x03`) written to the pty (in raw mode it reaches the agent as a keystroke).</p>
 */
@FunctionalInterface
public interface ProcessSignaller {

    /** Interrupt; Hole tears the sandbox down and exits with 130. */
    int SIGINT = UnixProcessManager.SIGINT;
    /** Kill; used by Force Stop. Hole's detached watchdog, in its own session, survives and finishes the teardown. */
    int SIGKILL = UnixProcessManager.SIGKILL;

    /**
     * Signals the process group whose ID is the given PID, through pty4j's `killpg(2)`.
     * {@link UnixProcessManager#sendSignal(int, int)} cannot be used for this: it rejects a PID &lt;= 0 with an
     * {@link IllegalArgumentException} instead of passing a negative PID to `kill(2)`. There is deliberately no
     * fallback to the process alone: pty4j always makes `hole` a session and group leader, so a failing `killpg`
     * means the group is gone, and a process-only signal could then hit an unrelated process that reused the PID.
     */
    ProcessSignaller PROCESS_GROUP = (pid, signal) -> {
        if (pid <= 0 || pid > Integer.MAX_VALUE) {
            return false;
        }
        return PtyHelpers.getInstance().killpg((int) pid, signal) == 0;
    };

    /**
     * Sends {@code signal} to the process {@code pid} (by {@link #PROCESS_GROUP}: to its process group).
     *
     * @return {@code true} when the signal was delivered
     */
    boolean send(long pid, int signal);
}
