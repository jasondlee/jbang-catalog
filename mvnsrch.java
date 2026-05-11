/// usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS info.picocli:picocli:4.7.7
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
import java.util.concurrent.Callable;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "mvnsrch",
        mixinStandardHelpOptions = true,
        version = "mvnsrch 0.2",
        description = "Search Maven Central")
class mvnsrch implements Callable<Integer> {
    // https://central.sonatype.org/search/rest-api-guide/
    // g: https://search.maven.org/solrsearch/select?q=g:com.google.inject&rows=20&wt=json
    // a: https://search.maven.org/solrsearch/select?q=a:guice&rows=20&wt=json
    // g+a: https://search.maven.org/solrsearch/select?q=g:com.google.inject+AND+a:guice&core=gav&rows=20&wt=json
    // c: https://search.maven.org/solrsearch/select?q=c:junit&rows=20&wt=json
    // fqcn: https://search.maven.org/solrsearch/select?q=fc:org.specs.runner.JUnit&rows=20&wt=json
    private static final String BASE_URL = "https://search.maven.org/solrsearch/select?wt=json&core=gav&q=";

    @Option(names = {"-ga"}, description = "Group:Artifact")
    String groupArtifact;
    @Option(names = {"-c", "--classname"}, description = "Simple class name")
    String className;
    @Option(names = {"-f", "--fc", "--fqcn"}, description = "Fully-qualified class name")
    String fqcn;
    @Option(names = {"-g", "--group"}, description = "Group ID")
    String groupId;
    @Option(names = {"-a", "--artifact"}, description = "Artifact ID")
    String artifactId;
    @Option(names = {"-r", "--rows"}, description = "Number of rows to return", defaultValue = "20")
    int rows;
    @Option(names = {"-s", "--sort"}, description = "Field to sort by: \n\t(a)rtifact,\n\t(g)group,\n\t(i)d,\n\t(v)ersion,\n\t(d)ate updated",
            defaultValue = "i")
    String sortField;
    @Option(names = {"-d", "--descending"}, description = "Sort results in descending order", defaultValue = "true")
    boolean ascending;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    private List<String> parameters = new ArrayList<>();

    public static void main(String... args) {
        int exitCode = new CommandLine(new mvnsrch()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
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
            spec.commandLine().usage(System.err);
            return -1;
        }

        String url = BASE_URL + String.join("+AND+", parameters) + "&rows=" + rows;

        outputResults(sendRequest(url));

        return 0;
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
                .sorted(new DocComparator(sortField, ascending))
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

    private record DocComparator(String field, boolean ascending) implements Comparator<Document> {

        @Override
        public int compare(Document d1, Document d2) {
            return switch (field) {
                case "i" -> compare(d1.id(), d2.id(), ascending);
                case "g" -> compare(d1.groudId(), d2.groudId(), ascending);
                case "a" -> compare(d1.artifactId(), d2.artifactId(), ascending);
                case "v" -> compare(d1.version(), d2.version(), ascending);
                default -> Long.compare(d2.timestamp, d1.timestamp); // TODO
            };
        }

        private int compare(String one, String two, boolean ascending) {
            return (!ascending) ? one.compareTo(two) : two.compareTo(one);
        }
    }
}
