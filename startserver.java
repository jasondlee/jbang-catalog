/// usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS info.picocli:picocli:4.6.3
//DEPS dev.jbang:jash:RELEASE

import dev.jbang.jash.Jash;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(name = "startserver",
        mixinStandardHelpOptions = true,
        version = "startserver 0.1",
        description = "A script to manage starting a WildFly/EAP server",
        helpCommand = true)
class startserver implements Callable<Integer> {
    @Parameters(index = "0", description = "The working directory of the server", arity = "0..1")
    Path serverDir;
    @CommandLine.Option(names = {"-c"}, description = "Clean and rebuild the server before starting", defaultValue = "false")
    boolean clean;
    @CommandLine.Option(names = {"-L", "--logging"}, description = "Set logging level to DEBUG", defaultValue = "false")
    boolean debugLogging;
    @CommandLine.Option(names = {"-O", "--otel", "--opentelemetry"}, description = "Enable OpenTelemetry", defaultValue = "false")
    boolean enableOtel;
    @CommandLine.Option(names = {"-M", "--micrometer"}, description = "Enable Micrometer", defaultValue = "false")
    boolean enableMicrometer;
    @CommandLine.Option(names = {"-m", "--microprofile"}, description = "Start the server using the standard-micropfile configuration", defaultValue = "false")
    boolean useMicroprofile;
    @CommandLine.Option(names = {"-f", "--full"}, description = "Start the server using the standard-full configuration", defaultValue = "false")
    boolean useFull;
    @CommandLine.Option(names = {"--ha"}, description = "Start the server using the standard-full-ha configuration", defaultValue = "false")
    boolean useHA;
    @CommandLine.Option(names = {"-s", "--suspend"}, description = "Enable suspend on start for debug", defaultValue = "false")
    boolean enableSuspend;
    @CommandLine.Option(names = {"-n", "--dry-run"}, description = "Don't start the server", defaultValue = "false")
    boolean dryRun;

    private String configName = "standalone.xml";
    private final List<String> cliCommands = new ArrayList<>();
    private final List<String> possibleDirs = List.of("build", "dist");
    private final List<String> possiblePrefixes = List.of("wildfly", "jboss-eap");

    public static void main(String... args) {
        int exitCode = new CommandLine(new startserver()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception { // your business logic goes here...
        if (serverDir == null) {
            findServerDir();
        }
        System.out.println("Using server directory: " + serverDir);
        if (clean) {
            cleanServer();
            extractServer();
        }

        configureServerDebug();
        configureLogging();
        configureOpenTelemetry();
        configureMicrometer();
        executeCliCommands();
        startServer();

        return 0;
    }

    private void cleanServer() throws IOException {
        System.out.println("Cleaning server directory: " + serverDir);
        Files.walk(serverDir)
                // Sort in reverse order to delete contents before the directory itself
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
//                        System.out.println("Deleted: " + path);
                    } catch (IOException e) {
                        System.err.println("Error deleting " + path + ": " + e.getMessage());
                    }
                });
    }

    private void findServerDir() throws IOException {
        for (String dir : possibleDirs) {
            for (String prefix : possiblePrefixes) {
                Path start = Path.of(dir + "/target");
                if (start.toFile().exists()) {
                    try (Stream<Path> files = Files.find(start, 1,
                            (path, attrs) -> path.getFileName().toString().startsWith(prefix))) {
                        Optional<Path> found = files.findFirst();
                        if (found.isPresent()) {
                            serverDir = found.get();
                            return;
                        }
                    }
                }
            }
        }

        if (serverDir == null) {
            throw new RuntimeException("Could not find server directory");
        }
    }

    private void extractServer() throws IOException {
        System.out.println("Extracting server");
        Path parent = serverDir.getParent();
        String zipFile = null;
        System.out.println("Looking for zip file in " + parent);
        for (String prefix : possiblePrefixes) {
            try (Stream<Path> files = Files.find(parent, 1,
                    (path, attrs) -> {
                        String fileName = path.getFileName().toString();
                        return fileName.startsWith(prefix) && fileName.endsWith(".zip");
                    })) {
                Optional<Path> found = files.findFirst();
                if (found.isPresent()) {
                    zipFile = found.get().toFile().getAbsolutePath();
                    break;
                }
            }
        }

        if (zipFile == null) {
            throw new RuntimeException("Could not find zip file");
        }
        try (Jash unzip = Jash.start("unzip", "-o", zipFile, "-d", parent.toFile().getAbsolutePath())) {
            if (unzip.getExitCode() != 0) {
                throw new RuntimeException("Failed to extract server");
            }
        }
    }

    private void configureServerDebug() throws IOException {
        Path configPath = Path.of(serverDir + "/bin/standalone.conf");
        String lines = Files.readAllLines(configPath)
                .stream()
                .filter(line -> !line.contains("agentlib"))
                .collect(Collectors.joining("\n"));
        lines += "JAVA_OPTS=\"$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,address=8787,server=y,suspend=" +
                (enableSuspend ? "y" : "n") + "\"";
        Files.write(configPath, lines.getBytes());
    }

    private void configureLogging() {
        if (debugLogging) {
            cliCommands.add("/subsystem=logging/console-handler=CONSOLE:write-attribute(name=level,value=DEBUG)");
            cliCommands.add("/subsystem=logging/root-logger=ROOT:write-attribute(name=level,value=DEBUG)");
        }
    }

    private void configureOpenTelemetry() {
        if (enableOtel) {
            cliCommands.add("/extension=org.wildfly.extension.opentelemetry:add()");
            cliCommands.add("/subsystem=opentelemetry:add()");
            cliCommands.add("/subsystem=opentelemetry:write-attribute(name=sampler-type,value=on)");
            cliCommands.add("/subsystem=opentelemetry:write-attribute(name=batch-delay,value=10)");
        }
    }

    private void configureMicrometer() {
        if (enableMicrometer) {
            cliCommands.add("/extension=org.wildfly.extension.micrometer:add");
            cliCommands.add("/subsystem=micrometer:add(endpoint=\"http://localhost:4318/v1/metrics\",step=\"1\")");
            cliCommands.add("/subsystem=undertow:write-attribute(name=statistics-enabled,value=true)");
        }
    }

    private void executeCliCommands() {

        if (useMicroprofile) {
            configName = "standalone-microprofile.xml";
        } else if (useFull) {
            configName = "standalone-full.xml";
        } else if (useHA) {
            configName = "standalone-full-ha.xml";
        }

        cliCommands.addFirst("embed-server --server-config=" + configName);

        Path jbossCli = Path.of(serverDir + "/bin/jboss-cli.sh");
        try (Jash jash = Jash.start(jbossCli.toFile().getAbsolutePath())
                .inputStream(cliCommands.stream())) {
            if (jash.getExitCode() != 0) {
                throw new RuntimeException("Failed to configure server");
            }
        }

    }

    private void startServer() {
        System.out.println("Dry run: " + dryRun);
        if (!dryRun) {
            System.out.println("Starting server using config: " + configName);
            Path standalone = Path.of(serverDir + "/bin/standalone.sh");
            try (Stream<String> jash = Jash.start(standalone.toFile().getAbsolutePath(),
                            "-c",
                            configName)
                    .stream()
            ) {
                jash.peek(System.out::println).count();
            }
        } else {
            System.out.println("Dry run, not starting server");
        }
    }
}
