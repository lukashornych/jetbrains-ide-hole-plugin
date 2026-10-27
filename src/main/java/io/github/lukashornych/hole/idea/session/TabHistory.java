package io.github.lukashornych.hole.idea.session;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Selection history of tabs, most recently selected last. When a tab closes, the previously selected one is selected
 * again, instead of the adjacent tab the content manager would pick. Not thread-safe; used on the EDT.
 *
 * @param <T> tab type
 */
public final class TabHistory<T> {

    private final List<T> order = new ArrayList<>();

    /**
     * Records that {@code tab} was selected.
     */
    public void selected(@NotNull T tab) {
        this.order.remove(tab);
        this.order.add(tab);
    }

    /**
     * Forgets a closed tab.
     */
    public void remove(@NotNull T tab) {
        this.order.remove(tab);
    }

    /**
     * Returns the most recently selected tab other than {@code tab}, or {@code null} when there is none.
     */
    @Nullable
    public T previous(@NotNull T tab) {
        for (int i = this.order.size() - 1; i >= 0; i--) {
            final T candidate = this.order.get(i);
            if (!candidate.equals(tab)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns the history, most recent last.
     */
    @NotNull
    public List<T> asList() {
        return List.copyOf(this.order);
    }
}
