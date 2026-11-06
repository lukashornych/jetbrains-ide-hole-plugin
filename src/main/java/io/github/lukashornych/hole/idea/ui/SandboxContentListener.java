package io.github.lukashornych.hole.idea.ui;

import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import io.github.lukashornych.hole.idea.session.SandboxSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * Listens to the content manager of one sandbox tab, whichever it is. A split tool window has a nested content
 * manager per pane, and the platform offers no public way to enumerate them or to be told about new ones, so the
 * listener follows its content through the content's {@code "contentManager"} property (the platform's
 * {@code BaseContentCloseListener} pattern). Events of other contents are ignored.
 *
 * <p>Moving a tab between panes (split, unsplit, drag) is a remove from one manager and an add to another with
 * {@link Content#TEMPORARY_REMOVED_KEY} set; such removals are neither a close request nor the end of the tab, and
 * the selections they cause (also the neighbour the source pane selects) are not the user's. Used on the EDT only.</p>
 */
final class SandboxContentListener implements ContentManagerListener, PropertyChangeListener {

    /** {@code ContentImpl.PROP_CONTENT_MANAGER}, by name: {@code ContentImpl} is not public API. */
    static final String MANAGER_PROPERTY = "contentManager";

    private final Content content;
    private final SandboxSession session;
    private final HoleToolWindowView view;
    @Nullable
    private ContentManager manager;

    /**
     * Starts listening; call before the content is added to a manager.
     */
    SandboxContentListener(@NotNull Content content, @NotNull SandboxSession session, @NotNull HoleToolWindowView view) {
        this.content = content;
        this.session = session;
        this.view = view;
        content.addPropertyChangeListener(this);
        join(content.getManager());
    }

    /**
     * Stops listening to the content and its manager.
     */
    void detach() {
        this.content.removePropertyChangeListener(this);
        leave();
    }

    @Override
    public void propertyChange(@NotNull PropertyChangeEvent event) {
        if (MANAGER_PROPERTY.equals(event.getPropertyName())) {
            leave();
            join(event.getNewValue() instanceof ContentManager next ? next : null);
        }
    }

    @Override
    public void contentAdded(@NotNull ContentManagerEvent event) {
        if (event.getContent() == this.content) {
            this.view.tabAdded(this.session);
        }
    }

    @Override
    public void contentRemoveQuery(@NotNull ContentManagerEvent event) {
        if (event.getContent() == this.content && !moving()) {
            this.view.tabRemoveQuery(this.session, event);
        }
    }

    @Override
    public void contentRemoved(@NotNull ContentManagerEvent event) {
        if (event.getContent() != this.content) {
            return;
        }
        if (moving()) {
            // before the source pane selects a neighbour
            this.view.tabMoving(this.session);
        } else {
            // before the manager clears the content's manager property
            detach();
            this.view.tabRemoved(this.session);
        }
    }

    @Override
    public void selectionChanged(@NotNull ContentManagerEvent event) {
        if (event.getContent() == this.content && event.getOperation() == ContentManagerEvent.ContentOperation.add && !moving()) {
            this.view.tabSelected(this.session);
        }
    }

    private boolean moving() {
        return Content.TEMPORARY_REMOVED_KEY.get(this.content, false);
    }

    private void join(@Nullable ContentManager next) {
        if (next != null) {
            next.addContentManagerListener(this);
            this.manager = next;
        }
    }

    private void leave() {
        if (this.manager != null) {
            this.manager.removeContentManagerListener(this);
            this.manager = null;
        }
    }
}
