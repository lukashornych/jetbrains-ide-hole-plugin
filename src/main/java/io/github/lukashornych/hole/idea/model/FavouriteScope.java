package io.github.lukashornych.hole.idea.model;

/**
 * Where a favourite is stored.
 */
public enum FavouriteScope {
    /** `.idea/hole.xml` of the current project. */
    PROJECT,
    /** IDE application settings, available in all projects. */
    GLOBAL
}
