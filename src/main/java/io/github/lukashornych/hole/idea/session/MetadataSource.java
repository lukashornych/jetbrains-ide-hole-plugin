package io.github.lukashornych.hole.idea.session;

import com.intellij.openapi.Disposable;
import io.github.lukashornych.hole.idea.hole.MetadataPoller;
import org.jetbrains.annotations.NotNull;

/**
 * Where sessions get their metadata from; implemented by {@link MetadataPoller}.
 */
public interface MetadataSource {

    /**
     * Follows one instance; see {@link MetadataPoller#subscribe(String, MetadataPoller.Listener)}.
     */
    @NotNull
    Disposable subscribe(@NotNull String instanceId, @NotNull MetadataPoller.Listener listener);

    /**
     * Asks for a poll as soon as possible; see {@link MetadataPoller#refreshNow()}.
     */
    void refreshNow();

    /**
     * Adapts the poller.
     */
    @NotNull
    static MetadataSource of(@NotNull MetadataPoller poller) {
        return new MetadataSource() {
            @NotNull
            @Override
            public Disposable subscribe(@NotNull String instanceId, @NotNull MetadataPoller.Listener listener) {
                return poller.subscribe(instanceId, listener);
            }

            @Override
            public void refreshNow() {
                poller.refreshNow();
            }
        };
    }
}
