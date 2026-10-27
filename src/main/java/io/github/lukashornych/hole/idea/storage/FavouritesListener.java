package io.github.lukashornych.hole.idea.storage;

import com.intellij.util.messages.Topic;

/**
 * Told when favourites were saved ({@link GlobalFavouritesService#saveAll}), e.g. so the empty start page of the tool
 * window lists the new ones. Published on the application message bus, because favourites of <i>All projects</i>
 * change the start page of every open project; subscribers re-read the favourites of their own project.
 */
public interface FavouritesListener {

    /** Application-level topic. */
    Topic<FavouritesListener> TOPIC = new Topic<>("Hole favourites changed", FavouritesListener.class);

    /**
     * Project or global favourites were saved. Called on the thread that saved them (the EDT for the dialog).
     */
    void favouritesChanged();
}
