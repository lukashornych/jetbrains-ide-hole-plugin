package io.github.lukashornych.hole.idea.model;

/**
 * Start flags of a running sandbox (`flags` object of `hole list --json`).
 */
public record InstanceFlags(
    boolean debug,
    boolean rebuild,
    boolean unrestricted,
    boolean dumpNetworkAccess,
    boolean withDocker
) {

    /** All flags off; used when Hole does not report the object. */
    public static final InstanceFlags NONE = new InstanceFlags(false, false, false, false, false);
}
