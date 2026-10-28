package io.github.lukashornych.hole.idea.session;

import com.intellij.util.concurrency.AppExecutorUtil;
import io.github.lukashornych.hole.idea.hole.InstanceIdGenerator;
import io.github.lukashornych.hole.idea.hole.MetadataPoller;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * What a {@link SandboxSession} needs from the outside. Tests pass the real runner and signaller with a poller that
 * runs against the fake `hole` script.
 *
 * @param runner       spawns `hole start` in a pty
 * @param signaller    signals the `hole` process
 * @param metadata     metadata of running instances
 * @param instanceIds  generates instance IDs (for the retry after a duplicate ID)
 * @param clock        time source for the stopping timer
 * @param background   runs blocking work such as waiting for the end of the output
 * @param pastes       creates and deletes the paste directories of the image paste
 */
public record SessionServices(
    @NotNull HoleTerminalRunner runner,
    @NotNull ProcessSignaller signaller,
    @NotNull MetadataSource metadata,
    @NotNull Supplier<String> instanceIds,
    @NotNull Clock clock,
    @NotNull Executor background,
    @NotNull PasteDirectory pastes
) {

    public SessionServices {
        Objects.requireNonNull(runner, "runner");
        Objects.requireNonNull(signaller, "signaller");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(instanceIds, "instanceIds");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(pastes, "pastes");
    }

    /**
     * Returns the services used in the IDE.
     */
    @NotNull
    public static SessionServices platform() {
        final InstanceIdGenerator generator = new InstanceIdGenerator();
        return new SessionServices(
            new HoleTerminalRunner(),
            ProcessSignaller.PROCESS_GROUP,
            MetadataSource.of(MetadataPoller.getInstance()),
            generator::generate,
            Clock.systemUTC(),
            AppExecutorUtil.getAppExecutorService(),
            PasteDirectory.platform()
        );
    }
}
