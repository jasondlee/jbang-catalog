/// usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS org.aesh:aesh:3.8
//DEPS com.fasterxml.jackson.core:jackson-core:2.21.2
//DEPS com.fasterxml.jackson.core:jackson-databind:2.21.2
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.21

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aesh.AeshRuntimeRunner;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Option;

@CommandDefinition(name = "mvnsrch",
        description = "Search Maven Central",
        version = "1.1",
        generateHelp = true)
public class mvnsrch implements Command<CommandInvocation> {
    // https://central.sonatype.org/search/rest-api-guide/
    private static final String BASE_URL = "https://search.maven.org/solrsearch/select?wt=json&core=gav&q=";

    @Option(name = "ga", description = "Group:Artifact")
    String groupArtifact;
    @Option(shortName = 'c', name = "classname", description = "Simple class name")
    String className;
    @Option(shortName = 'f', name = "fc", aliases = {"fqcn"}, description = "Fully-qualified class name")
    String fqcn;
    @Option(shortName = 'g', name = "group", description = "Group ID")
    String groupId;
    @Option(shortName = 'a', name = "artifact", description = "Artifact ID")
    String artifactId;
    @Option(shortName = 'r', name = "rows", description = "Number of rows to return", defaultValue = "20")
    int rows;
    @Option(shortName = 's', name = "sort", description = "Field to sort by: (a)rtifact, (g)group, (i)d, (v)ersion, (d)ate updated",
            defaultValue = "i")
    String sortField;
    @Option(shortName = 'd', name = "descending", hasValue = false, description = "Sort results in descending order")
    boolean descending;

    private List<String> parameters = new ArrayList<>();

    public static void main(String... args) {
        AeshRuntimeRunner.builder()
                .command(mvnsrch.class)
                .args(args)
                .execute();
    }

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        if (groupArtifact != null) {
            String[] coords = groupArtifact.split(":");
            if (coords.length < 2) {
                throw new RuntimeException("Invalid group:artifact coordinates");
            }
            parameters.add("g:" + coords[0] + "+AND+a:" + coords[1]);
        } else {
            if (groupId != null) {
                parameters.add("g:" + groupId);
            }

            if (artifactId != null) {
                parameters.add("a:" + artifactId);
            }
        }

        if (className != null) {
            parameters.add("c:" + className);
        }

        if (fqcn != null) {
            parameters.add("fc:" + (fqcn.replace("/", ".")));
        }

        if (parameters.isEmpty()) {
            System.err.println("Error: At least one search parameter is required (--ga, -g, -a, -c, or -f)");
            return CommandResult.FAILURE;
        }

        String url = BASE_URL + String.join("+AND+", parameters) + "&rows=" + rows;

        outputResults(sendRequest(url));

        return CommandResult.SUCCESS;
    }

    private void outputResults(SearchResult searchResult) {
        List<Document> docs = searchResult.response().docs();

        Function<Document, String> formatDoc = d -> String.format("%s:%s:%s", d.groudId, d.artifactId, d.version);
        var width = docs.stream().map(formatDoc)
                .map(String::length)
                .max(Integer::compareTo)
                .orElseGet(() -> 80) + 2;
        var format = "%-" + width + "s%s\n";

        var df = new SimpleDateFormat("yyyy-MM-dd hh:mm aa (zzz)");
        System.out.printf(format, "Coordinates", "Last Updated");
        System.out.printf(format, "===========", "============");
        docs.stream()
                .sorted(new DocComparator(sortField, descending))
                .forEach(doc ->
                        System.out.printf(format, formatDoc.apply(doc), df.format(new Date(doc.timestamp))));
    }

    private SearchResult sendRequest(String url) {
        ObjectMapper mapper = new ObjectMapper();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new RuntimeException("Request failed with status code: " + response.statusCode());
                }
                return mapper.readValue(response.body(), SearchResult.class);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchResult(Response response) {

    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Response(int numFound, int start,
                            List<Document> docs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Document(String id,
                            @JsonProperty("g")
                            String groudId,
                            @JsonProperty("a")
                            String artifactId,
                            @JsonProperty("v")
                            String version,
                            @JsonProperty("p")
                            String packaging,
                            long timestamp,
                            List<String> tags) {
    }

    private record DocComparator(String field, boolean descending) implements Comparator<Document> {

        @Override
        public int compare(Document d1, Document d2) {
            return switch (field) {
                case "i" -> compare(d1.id(), d2.id(), descending);
                case "g" -> compare(d1.groudId(), d2.groudId(), descending);
                case "a" -> compare(d1.artifactId(), d2.artifactId(), descending);
                case "v" -> compare(d1.version(), d2.version(), descending);
                default -> Long.compare(d2.timestamp, d1.timestamp);
            };
        }

        private int compare(String one, String two, boolean descending) {
            return descending ? two.compareTo(one) : one.compareTo(two);
        }
    }
}
