///usr/bin/env jbang "$0" "$@" ; exit $?
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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
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

    public static void main(String... args) {
        int exitCode = new CommandLine(new mvnsrch()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception { // your business logic goes here...
        if (className != null) {
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
        var url = BASE_URL + "a:" + artifactId + "&rows=" + rows;
        outputResults(sendRequest(url));
    }

    private void searchForGroupId() {
        var url = BASE_URL + "g:" + groupId + "&rows=" + rows;
        outputResults(sendRequest(url));
    }

    private void searchForClassName() {
        var url = BASE_URL + "c:" + className + "&rows=" + rows;
        outputResults(sendRequest(url));
    }

    private void searchForFullyQualifiedClassName() {
        var url = BASE_URL + "fc:" + (fqcn.replace("/", ".")) + "&rows=" + rows;
        outputResults(sendRequest(url));
    }

    private void outputResults(SearchResult searchResult) {
        var format = new SimpleDateFormat("yyyy-MM-dd hh:mm aa (zzz)");
        System.out.printf("%-80s%s\n", "Coordinates", "Last Updated");
        System.out.printf("%-80s%s\n", "===========", "============");
        List<Document> docs = searchResult.response().docs();
        docs.sort(Comparator.comparing(Document::id));
        docs.forEach(doc -> {
            System.out.printf("%-80s%s\n", doc.id(), format.format(new Date(doc.timestamp)));
        });
    }

    private SearchResult sendRequest(String url) {
        ObjectMapper mapper = new ObjectMapper();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
//                    System.out.println("Request failed with status code: " + response.statusCode());
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
