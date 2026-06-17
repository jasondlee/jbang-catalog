/// usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.aesh:aesh:3.8
//DEPS org.apache.maven:maven-model:3.9.16
//DEPS org.apache.maven:maven-model-builder:3.9.16
//DEPS guru.nidi:graphviz-java:0.18.1
//DEPS org.apache.maven.resolver:maven-resolver-supplier:1.9.27
//DEPS org.apache.maven:maven-resolver-provider:3.9.16
//JAVA 17+

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import guru.nidi.graphviz.engine.Format;
import guru.nidi.graphviz.engine.Graphviz;
import org.aesh.AeshRuntimeRunner;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelSource2;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.version.Version;

@CommandDefinition(name = "maven-dep-graph",
        description = "maven-dep-graph",
        version = "1.0.0",
        generateHelp = true)
public class MavenDepGraph implements Command<CommandInvocation> {
    private PomResolver pomResolver;
    @OptionList(shortName = 'a', aliases = {"artifact"}, required = true)
    private Set<String> artifacts;
    @OptionList(shortName = 'r', aliases = {"repository"})
    private List<String> repositories;
    @Option(shortName = 'o', aliases = {"outputFile"})
    private String outputFileName;
    private Set<String> validFormats = Set.of("png", "svg");
    private Map<String, List<String>> deps = new HashMap<>();

    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2";
    private static final Path DEFAULT_LOCAL_REPO = Path.of(System.getProperty("user.home"), ".m2", "repository");

    public static void main(String[] args) throws MalformedURLException {
        AeshRuntimeRunner.builder()
                .command(MavenDepGraph.class)
                .args(args)
                .execute();
    }

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        try {
            Set<String> repos = new HashSet<>();

            if (repositories != null) {
                repos.addAll(repositories);
            }

            pomResolver = new PomResolver(DEFAULT_LOCAL_REPO, repos);


            for (String artifact : artifacts) {
                processArtifact(gavToDependency(artifact));
            }

            outputGraph();

            return CommandResult.SUCCESS;
        } catch (IOException | ModelBuildingException | UnresolvableModelException e) {
            System.err.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }
    }

    private void processArtifact(Dependency parent) throws IOException, ModelBuildingException, UnresolvableModelException {
        if ("test".equals(parent.getScope())) {
            return;
        }
        try {
            String resolvedVersion = pomResolver.resolveVersionRange(
                    parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
            parent.setVersion(resolvedVersion);
        } catch (VersionRangeResolutionException e) {
            System.err.println("WARNING: Unable to resolve version range for " + depToGav(parent));
            return;
        }
        String parentGav = depToGav(parent);
        if (!deps.containsKey(parentGav)) {
            try {
                Model model = pomResolver.resolve(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
                List<String> localDeps = deps.computeIfAbsent(parentGav, k -> new ArrayList<>());
                for (Dependency dep : model.getDependencies()) {
                    try {
                        String resolvedDepVersion = pomResolver.resolveVersionRange(
                                dep.getGroupId(), dep.getArtifactId(), dep.getVersion());
                        dep.setVersion(resolvedDepVersion);
                        localDeps.add(depToGav(dep));
                        processArtifact(dep);
                    } catch (VersionRangeResolutionException e) {
                        System.err.println("WARNING: Unable to resolve version range for " + depToGav(dep));
                    } catch (IOException | ModelBuildingException e) {
                        System.err.println("WARNING: Unable to build model for " + dep);
                    }
                }
            } catch (ModelBuildingException | UnresolvableModelException e) {
                System.err.println("WARNING: Unable to resolve model for " + parent);
            }
        }
    }

    private void outputGraph() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph MavenDepGraph {\n");
        sb.append("\t{\n");
        sb.append("\t\tnode [shape=\"box\",style=\"rounded\",fontname=\"Helvetica\",fontsize=\"14\"]\n");
        sb.append("\t\tedge [fontsize=\"10\",fontname=\"Helvetica\"]\n");
        deps.keySet().forEach(entry -> {
            sb.append(String.format("\t\t\"%s\" [label=\"%s\"]%n", entry, entry.replace(":", ":\\n")));
        });
        sb.append("\t}\n");

        deps.forEach((parent, children) -> {
            sb.append(String.format("\t \"%s\" -> {%s}%n", parent,
                    children.stream()
                            .map(d -> "\"" + d + "\"")
                            .collect(Collectors.joining(", "))));
        });
        sb.append("}\n");

        if (outputFileName != null) {
            Graphviz.fromString(sb.toString())
                    .render(getFormat(getFileExtension(outputFileName)))
                    .toFile(new File(outputFileName));
        } else {
            System.out.println(sb);
        }
    }

    private String getFileExtension(String outputFileName) {
        int index = outputFileName.lastIndexOf(".");
        return index == -1 ? "png" : outputFileName.substring(index + 1);
    }

    private Dependency gavToDependency(String gav) {
        String[] parts = gav.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected groupId:artifactId:version. Found " + gav);
        }

        Dependency dependency = new Dependency();
        dependency.setGroupId(parts[0]);
        dependency.setArtifactId(parts[1]);
        dependency.setVersion(parts[2]);

        return dependency;
    }

    private String depToGav(Dependency dependency) {
        return String.format("%s:%s:%s",
                dependency.getGroupId(),
                dependency.getArtifactId(),
                dependency.getVersion());
    }

    private Format getFormat(String option) {
        Format format = switch (option) {
            case "png" -> Format.PNG;
            case "svg" -> Format.SVG;
            case "dot" -> Format.DOT;
            default -> null;
        };
        if (format == null) {
            System.err.println("Invalid format: " + option);
            System.exit(-1);
        }
        return format;
    }

    private static class PomResolver {
        private static final Pattern VERSION_RANGE_PATTERN = Pattern.compile("[\\[\\](),]");
        private final Path localRepo;
        private final Set<String> remoteRepoUrls;
        private final ModelBuilder modelBuilder;
        private final RepositorySystem repoSystem;
        private final DefaultRepositorySystemSession repoSession;
        private final List<RemoteRepository> remoteRepositories;

        PomResolver(Path localRepo, Set<String> remoteRepoUrls) {
            this.localRepo = localRepo;
            this.remoteRepoUrls = new HashSet<>(remoteRepoUrls);
            this.modelBuilder = new DefaultModelBuilderFactory().newInstance();
            this.repoSystem = new RepositorySystemSupplier().get();
            this.repoSession = MavenRepositorySystemUtils.newSession();
            this.repoSession.setLocalRepositoryManager(repoSystem.newLocalRepositoryManager(repoSession, new LocalRepository(localRepo.toFile())));
            this.remoteRepositories =  remoteRepoUrls.stream()
                    .map(url -> new RemoteRepository.Builder(repoId(url), "default", url).build()).toList();
        }

        Model resolve(String groupId, String artifactId, String version) throws ModelBuildingException, UnresolvableModelException {
            try {
                version = resolveVersionRange(groupId, artifactId, version);
            } catch (VersionRangeResolutionException e) {
                throw new UnresolvableModelException(e.getMessage(), groupId, artifactId, version, e);
            }

            DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
            request.setModelSource(new FileModelSource(getLocalPom(localRepo, remoteRepoUrls, groupId, artifactId, version).toFile()));
            request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
            request.setProcessPlugins(false);
            request.setSystemProperties(System.getProperties());
            request.setModelResolver(new LocalRemoteModelResolver()); //localRepo, remoteRepoUrls, repoSystem, repoSession, remoteRepositories));

            ModelBuildingResult result = modelBuilder.build(request);
            return result.getEffectiveModel();
        }

        private void downloadFromRemotes(Set<String> remoteRepoUrls, String groupId, String artifactId, String version, Path targetPath) throws IOException {
            IOException lastFailure = null;
            for (String repoUrl : remoteRepoUrls) {
                String url = pomUrl(repoUrl, groupId, artifactId, version);
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                    int responseCode = connection.getResponseCode();
                    if (responseCode == 200) {
                        Files.createDirectories(targetPath.getParent());
                        try (InputStream in = connection.getInputStream()) {
                            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                        return;
                    }
                    lastFailure = new IOException("HTTP " + responseCode + " for " + url);
                } catch (IOException e) {
                    lastFailure = e;
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
            throw lastFailure != null ? lastFailure
                    : new IOException("No remote repositories configured for " + groupId + ":" + artifactId + ":" + version);
        }

        private String repoId(String url) {
            return (url.contains("repo1.maven.org") || url.contains("repo.maven.apache.org")) ?
                    "central" : "repo-" + Integer.toHexString(url.hashCode());
        }

        private Path getLocalPom(Path localRepo,
                                        Set<String> remoteRepoUrls,
                                        String groupId,
                                        String artifactId,
                                        String version) throws UnresolvableModelException {
            Path localPom = pomPath(localRepo, groupId, artifactId, version);
            if (!Files.exists(localPom)) {
                try {
                    downloadFromRemotes(remoteRepoUrls, groupId, artifactId, version, localPom);
                } catch (IOException e) {
                    throw new UnresolvableModelException(e.getMessage(), groupId, artifactId, version, e);
                }
            }
            return localPom;
        }

        private Path pomPath(Path repoBase, String groupId, String artifactId, String version) {
            return repoBase
                    .resolve(groupId.replace('.', '/'))
                    .resolve(artifactId)
                    .resolve(version)
                    .resolve(artifactId + "-" + version + ".pom");
        }

        private String pomUrl(String baseUrl, String groupId, String artifactId, String version) {
            return String.format("%s/%s/%s/%s/%s-%s.pom",
                    baseUrl,
                    groupId.replace('.', '/'),
                    artifactId,
                    version,
                    artifactId,
                    version);
        }

        private String resolveVersionRange(String groupId, String artifactId, String version) throws VersionRangeResolutionException {
            if (version == null || !VERSION_RANGE_PATTERN.matcher(version).find()) {
                return version;
            }
            VersionRangeRequest request = new VersionRangeRequest();
            request.setArtifact(new DefaultArtifact(groupId, artifactId, "pom", version));
            request.setRepositories(remoteRepositories);
            VersionRangeResult result = repoSystem.resolveVersionRange(repoSession, request);
            Version highest = result.getHighestVersion();
            if (highest == null) {
                throw new VersionRangeResolutionException(result, "No versions matched range " + version + " for " + groupId + ":" + artifactId);
            }
            return highest.toString();
        }

        private class LocalRemoteModelResolver implements ModelResolver {

            @Override
            public ModelSource2 resolveModel(String groupId, String artifactId, String version) throws UnresolvableModelException {
                try {
                    version = resolveVersionRange(groupId, artifactId, version);
                } catch (VersionRangeResolutionException e) {
                    throw new RuntimeException(e);
                }
                return new FileModelSource(getLocalPom(localRepo, remoteRepoUrls, groupId, artifactId, version).toFile());
            }

            @Override
            public ModelSource2 resolveModel(Parent parent) throws UnresolvableModelException {
                return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
            }

            @Override
            public ModelSource2 resolveModel(Dependency dependency) throws UnresolvableModelException {
                return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
            }

            @Override
            public void addRepository(Repository repository) {
                addRepository(repository, false);
            }

            @Override
            public void addRepository(Repository repository, boolean replace) {
                String url = repository.getUrl();
                if (url == null) {
                    return;
                }
                String normalized = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
                if (replace) {
                    remoteRepoUrls.remove(normalized);
                }
                remoteRepoUrls.add(normalized);
            }

            @Override
            public ModelResolver newCopy() {
                return new LocalRemoteModelResolver(); //localRepo, remoteRepoUrls, repoSystem, repoSession, remoteRepositories);
            }
        }
    }
}
