package io.github.lukashornych.hole.idea.storage;

import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.model.FavouriteScope;
import io.github.lukashornych.hole.idea.model.Library;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Persisted form of a {@link Favourite}. Mutable with a no-arg constructor, as the XML serializer requires; the scope
 * is not stored, because it follows from the file the bean is stored in.
 */
@Data
@NoArgsConstructor
public class FavouriteBean {

    private String name = "";
    private String agent = SandboxConfig.DEFAULT_AGENT;
    private String profile = "";
    private String projectPath = SandboxConfig.DEFAULT_PROJECT_PATH;
    private boolean withDocker;
    private boolean unrestrictedNetwork;
    private boolean trustProject;
    private boolean dumpNetworkAccess;
    private boolean rebuild;
    private boolean updateAgents;
    private List<LibraryBean> libraries = new ArrayList<>();
    private String agentArgs = "";

    /**
     * Converts a favourite.
     */
    @NotNull
    public static FavouriteBean of(@NotNull Favourite favourite) {
        final SandboxConfig config = favourite.config();
        final FavouriteBean bean = new FavouriteBean();
        bean.name = favourite.name();
        bean.agent = config.agent();
        bean.profile = config.profile();
        bean.projectPath = config.projectPath();
        bean.withDocker = config.withDocker();
        bean.unrestrictedNetwork = config.unrestrictedNetwork();
        bean.trustProject = config.trustProject();
        bean.dumpNetworkAccess = config.dumpNetworkAccess();
        bean.rebuild = config.rebuild();
        bean.updateAgents = config.updateAgents();
        bean.libraries = new ArrayList<>(config.libraries().stream().map(LibraryBean::of).toList());
        bean.agentArgs = config.agentArgs();
        return bean;
    }

    /**
     * Converts back; {@code null} values of a hand-edited file become defaults.
     *
     * @param scope where the bean was stored
     */
    @NotNull
    public Favourite toFavourite(@NotNull FavouriteScope scope) {
        final List<Library> libs = this.libraries == null
            ? List.of()
            : this.libraries.stream().filter(Objects::nonNull).map(LibraryBean::toLibrary).toList();
        return new Favourite(Objects.requireNonNullElse(this.name, ""), scope, new SandboxConfig(
            Objects.requireNonNullElse(this.agent, ""), Objects.requireNonNullElse(this.profile, ""),
            Objects.requireNonNullElse(this.projectPath, ""),
            this.withDocker, this.unrestrictedNetwork, this.trustProject, this.dumpNetworkAccess, this.rebuild, this.updateAgents,
            libs, Objects.requireNonNullElse(this.agentArgs, "")
        ));
    }

    /**
     * Persisted form of a {@link Library}.
     */
    @Data
    @NoArgsConstructor
    public static class LibraryBean {
        private String path = "";
        private boolean readWrite;

        @NotNull
        static LibraryBean of(@NotNull Library library) {
            final LibraryBean bean = new LibraryBean();
            bean.path = library.path();
            bean.readWrite = library.readWrite();
            return bean;
        }

        @NotNull
        Library toLibrary() {
            return new Library(Objects.requireNonNullElse(this.path, ""), this.readWrite);
        }
    }
}
