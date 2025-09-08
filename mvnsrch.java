/// usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS info.picocli:picocli:4.6.3
//DEPS com.fasterxml.jackson.core:jackson-core:2.20.0
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.0
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.20

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.naming.directory.SearchResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "mvnsrch",
        mixinStandardHelpOptions = true,
        version = "mvnsrch 0.1",
        description = "mvnsrch made with jbang")
class mvnsrch implements Callable<Integer> {
    // g: https://search.maven.org/solrsearch/select?q=g:com.google.inject&rows=20&wt=json
    // a: https://search.maven.org/solrsearch/select?q=a:guice&rows=20&wt=json
    // g+a: https://search.maven.org/solrsearch/select?q=g:com.google.inject+AND+a:guice&core=gav&rows=20&wt=json
    // c: https://search.maven.org/solrsearch/select?q=c:junit&rows=20&wt=json
    // fqcn: https://search.maven.org/solrsearch/select?q=fc:org.specs.runner.JUnit&rows=20&wt=json
    private static final String BASE_URL = "https://search.maven.org/solrsearch/select?wt=json&q=";

    @Option(names = {"-ga"}, description = "Group:Artifact")
    String groupArtifact;
    @Option(names = {"-c", "--classname"}, description = "Simple class name")
    String className;
    @Option(names = {"-f", "--fqcn"}, description = "Fully-qualified class name")
    String fqcn;
    @Option(names = {"-g", "--group"}, description = "Group ID")
    String groupId;
    @Option(names = {"-a", "--artifact"}, description = "Artifact ID")
    String artifactId;
    @Option(names = {"-r", "--rows"}, description = "Number of rows to return", defaultValue = "20")
    int rows;
    @Option(names = {"-s", "--sort"}, description = "Field to sort by: (a)rtifact, (g)group, (i)d, (v)ersion, (d)ate updated",
            defaultValue = "i")
    String sortField;
    @Option(names = {"-d", "--descending"}, description = "Sort results in descending order", defaultValue = "false")
    boolean ascending;

    public static void main(String... args) {
        int exitCode = new CommandLine(new mvnsrch()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception { // your business logic goes here...
        if (groupArtifact != null) {
            searchForGroupArtifact();
        }
        else if (className != null) {
            searchForClassName();
        } else if (fqcn != null) {
            searchForFullyQualifiedClassName();
        } else if (groupId != null) {
            searchForGroupId();
        } else if (artifactId != null) {
            searchForArtifactId();
        } else {
            System.out.println("No search criteria provided");
            return -1;
        }
        return 0;
    }

    private void searchForArtifactId() {
        if (artifactId == null) {
            throw new RuntimeException("artifactId must be specified");
        }
        var url = BASE_URL + "a:" + artifactId + "&rows=" + rows;
        outputResults(sendRequest(url));
    }

    private void searchForGroupArtifact() {
        if (groupArtifact == null) {
            throw new RuntimeException("groupArtifact must be specified");
        }

        String[] coords = groupArtifact.split(":");
        if (coords.length < 2) {
            throw new RuntimeException("Invalid group:artifact coordinates");
        }
        var url = BASE_URL +
                "g:" + coords[0] +
                "+AND+a:" + coords[1] +
                "&core=gav&rows=" + rows;
        outputResults(sendRequest(url));
    }

    private void searchForGroupId() {
        if (groupId == null) {
            throw new RuntimeException("groupId must be specified");
        }
        var url = BASE_URL + "g:" + groupId + "&rows=" + rows;
        outputResults(sendRequest(url));
    }

    private void searchForClassName() {
        if (className == null) {
            throw new RuntimeException("className must be specified");
        }
        var url = BASE_URL + "c:" + className + "&rows=" + rows;
        outputResults(sendRequest(url));
    }

    private void searchForFullyQualifiedClassName() {
        if (fqcn == null) {
            throw new RuntimeException("Fully-qualified classname must be specified");
        }
        var url = BASE_URL + "fc:" + (fqcn.replace("/", ".")) + "&rows=" + rows;
        outputResults(sendRequest(url));
    }

    private void outputResults(SearchResult searchResult) {
        List<Document> docs = searchResult.response().docs();
        
        var width = docs.stream().map(Document::id)
                .map(String::length)
                .max(Integer::compareTo)
                .orElseGet(() -> 80) + 2;
        var format = "%-" + width + "s%s\n";
        
        var df = new SimpleDateFormat("yyyy-MM-dd hh:mm aa (zzz)");
        System.out.printf(format, "Coordinates", "Last Updated");
        System.out.printf(format, "===========", "============");
        docs.sort(new DocComparator(sortField, ascending));
        docs.forEach(doc ->
                System.out.printf(format, doc.id(), df.format(new Date(doc.timestamp))));
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

    private static class DocComparator implements Comparator<Document> {
        private final String field;
        private final boolean ascending;

        public DocComparator(String field, boolean ascending) {
            this.field = field;
            this.ascending = ascending;
        }

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
    /*
    {
        "id": "org.specs:specs:1.2.3",
        "g": "org.specs",
        "a": "specs",
        "v": "1.2.3",
        "p": "jar",
        "timestamp": 1227569516000,
        "ec": [
          "-sources.jar",
          ".jar",
          "-tests.jar",
          ".pom"
        ],
        "tags": [
          "behaviour",
          "driven",
          "framework",
          "design",
          "specs"
        ]
      },
     */
}
