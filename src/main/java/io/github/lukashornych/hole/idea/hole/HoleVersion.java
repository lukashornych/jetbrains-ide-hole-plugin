package io.github.lukashornych.hole.idea.hole;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version `hole version` reports, parsed from its output.
 *
 * <p>Hole prints `hole ` followed by its build identity (`version.Display()` in Hole):</p>
 * <ul>
 *     <li>a release: `hole 2.6.0`,</li>
 *     <li>a `go install` build: `hole 2.6.0 (go install)`, or a pseudo-version such as
 *     `hole 2.6.1-0.20261020101010-abcdef123456 (go install)` for an install from a branch,</li>
 *     <li>a development build from a checkout: `hole development`, optionally with the revision, e.g.
 *     `hole development (abc1234)` or `hole development (abc1234, dirty)`.</li>
 * </ul>
 *
 * <p>Any parenthesized annotation after the version (today only `(go install)`) is accepted, so a future build
 * annotation does not make a release unrecognizable. Only the numeric `major.minor.patch` part is compared. A pre-release or pseudo-version suffix is ignored, the way
 * Hole's own `version.GreaterThan` ignores it. A development build has no comparable number; it is treated as new
 * enough, because it is built from current sources by someone working on Hole.</p>
 *
 * @param major       major version, 0 for a development build
 * @param minor       minor version, 0 for a development build
 * @param patch       patch version, 0 for a development build
 * @param development a development build without a version number
 */
public record HoleVersion(int major, int minor, int patch, boolean development) {

    /** The first Hole release with `--instance-id`, `list --json`, `list --no-gc` and `settings get`. */
    public static final HoleVersion MINIMUM = new HoleVersion(2, 6, 0, false);

    /** Hole's `version.DevelopmentVersion` constant. */
    static final String DEVELOPMENT = "development";

    private static final Pattern RELEASE = Pattern.compile(
        "^hole\\s+v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+][0-9A-Za-z.+-]*)?(?:\\s+\\([^)]*\\))?\\s*$"
    );
    private static final Pattern DEVELOPMENT_BUILD = Pattern.compile("^hole\\s+" + DEVELOPMENT + "(?:\\s+\\([^)]*\\))?\\s*$");

    /**
     * Parses the stdout of `hole version`; the first non-blank line counts.
     *
     * @return the version, or empty when the output has none of the known shapes
     */
    @NotNull
    public static Optional<HoleVersion> parse(@NotNull String output) {
        final String line = output.lines().map(String::trim).filter(value -> !value.isEmpty()).findFirst().orElse("");
        final Matcher release = RELEASE.matcher(line);
        if (release.matches()) {
            try {
                return Optional.of(new HoleVersion(
                    Integer.parseInt(release.group(1)),
                    Integer.parseInt(release.group(2)),
                    Integer.parseInt(release.group(3)),
                    false
                ));
            } catch (NumberFormatException e) {
                // a component too large for an int is not a Hole version
                return Optional.empty();
            }
        }
        if (DEVELOPMENT_BUILD.matcher(line).matches()) {
            return Optional.of(new HoleVersion(0, 0, 0, true));
        }
        return Optional.empty();
    }

    /**
     * Returns {@code true} when this Hole has everything the plugin needs: a development build, or a version of at
     * least {@link #MINIMUM}.
     */
    public boolean isAtLeastMinimum() {
        if (this.development) {
            return true;
        }
        if (this.major != MINIMUM.major) {
            return this.major > MINIMUM.major;
        }
        if (this.minor != MINIMUM.minor) {
            return this.minor > MINIMUM.minor;
        }
        return this.patch >= MINIMUM.patch;
    }

    @Override
    public String toString() {
        return this.development ? DEVELOPMENT : this.major + "." + this.minor + "." + this.patch;
    }
}
