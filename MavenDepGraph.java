///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.aesh:aesh:3.8
//DEPS org.apache.maven:maven-model:3.9.16
//DEPS guru.nidi:graphviz-java:0.18.1
//JAVA 17+

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

@CommandDefinition(name = "maven-dep-graph",
        description = "maven-dep-graph",
        version = "1.0.0",
        generateHelp = true)
public class MavenDepGraph implements Command<CommandInvocation> {
    @OptionList(shortName = 'a', aliases = {"artifact"}, required = true)
    private Set<String> artifacts;

    @Option(shortName = 'r', aliases = {"repository"}, defaultValue = "https://repo1.maven.org/maven2")
    private String repository;

    @Option(shortName = 'o', aliases = {"outputFile"})
    private String outputFileName;

    private Set<String> validFormats = Set.of("png", "svg");
    private Map<String, List<String>> deps = new HashMap<>();

    public static void main(String[] args) throws MalformedURLException {
        AeshRuntimeRunner.builder()
                .command(MavenDepGraph.class)
                .args(args)
                .execute();
    }

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        try {
            for (String artifact : artifacts) {
                processArtifact(gavToDependency(artifact));
            }

            outputGraph();

            return CommandResult.SUCCESS;
        } catch (IOException | XmlPullParserException e) {
            System.err.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
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

    private void processArtifact(Dependency parent) throws XmlPullParserException, IOException {
        String parentGav = depToGav(parent);
        if (!deps.containsKey(parentGav)) {
            try (InputStream inputStream = openStream(new URL(getUrlForDependency(parent)))) {
                Model model = new MavenXpp3Reader().read(inputStream);
                List<String> localDeps = deps.computeIfAbsent(parentGav, k -> new ArrayList<>());
                for (Dependency dep : model.getDependencies()) {
                    localDeps.add(depToGav(dep));
                    processArtifact(dep);
                }
            }
        }
    }

    private Dependency gavToDependency(String gav) {
        String[] parts = gav.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected groupId:artifactId:version");
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

    private String getUrlForDependency(Dependency dependency) {
        return String.format("%s/%s/%s/%s/%s-%s.pom",
                repository,
                dependency.getGroupId().replace('.', '/'),
                dependency.getArtifactId(),
                dependency.getVersion(),
                dependency.getArtifactId(),
                dependency.getVersion());
    }

    private InputStream openStream(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            connection.disconnect();
            throw new IllegalArgumentException("Expected 200 response, got " + responseCode + " for " + url);
        }
        return connection.getInputStream();
    }

    private Format getFormat(String option) {
        return switch (option) {
            case "png" -> Format.PNG;
            case "svg" -> Format.SVG;
            case "dot" -> Format.DOT;
            default -> throw new RuntimeException("Invalid format: " + option);
        };
    }
}
