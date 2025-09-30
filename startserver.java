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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.BiPredicate;
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
    @CommandLine.Option(names = {"--file"}, description = "Specify file with extra commands to run", arity = "0..*")
    List<String> commandFile;

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
        determineServerConfiguration();

        if (serverDir == null) {
            findServerDir();
        }

        if (clean) {
            cleanServer();
        }

        if (serverDir == null) {
            extractServer();
        }

        System.out.println("Using server directory: " + serverDir);

        configureServerDebug();
        configureLogging();
        configureOpenTelemetry();
        configureMicrometer();
        loadCommandFiles();
        executeCliCommands();
        startServer();

        return 0;
    }

    private void cleanServer() throws IOException {
        if (serverDir != null) {
        System.out.println("Cleaning server directory: " + serverDir);
        Files.walk(serverDir)
                // Sort in reverse order to delete contents before the directory itself
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        System.err.println("Error deleting " + path + ": " + e.getMessage());
                    }
                });
        serverDir = null;
        }
    }

    private void findServerDir() throws IOException {
        for (String dir : possibleDirs) {
            for (String prefix : possiblePrefixes) {
                Path start = Path.of(dir + "/target");
                if (start.toFile().exists()) {
                    try (Stream<Path> files = Files.find(start, 1,
                            (path, attrs) -> isServerDir(prefix, path))) {
                        Optional<Path> found = files.findFirst();
                        if (found.isPresent()) {
                            serverDir = found.get();
                            return;
                        }
                    }
                }
            }
        }
    }
    private static boolean isServerDir(String prefix, Path path) {
        return path.getFileName().toString().startsWith(prefix) &&
                path.toFile().isDirectory() &&
                Path.of(path + "/bin/standalone.sh").toFile().exists();
    }

    private void extractServer() throws IOException {
        System.out.println("Extracting server");
        for (String parent : possibleDirs) {
            for (String prefix : possiblePrefixes) {
                Path start = Path.of(parent + "/target");
                try (Stream<Path> files = Files.find(start, 1,
                        (path, attrs) -> isServerFileArchive(prefix, path))) {
                    Optional<Path> found = files.findFirst();
                    if (found.isPresent()) {
                        Path zipFile = found.get();
                        System.out.println("    Found: " + zipFile);
                        try (Jash unzip = Jash.start("unzip", "-o",
                                zipFile.toFile().getAbsolutePath(), "-d",
                                zipFile.getParent().toFile().getAbsolutePath())) {
                            if (unzip.getExitCode() != 0) {
                                throw new RuntimeException("Failed to extract server");
                            }
                        }
                        serverDir = Path.of(zipFile.toString().replace(".zip",""));
                        return;
                    }
                }
            }
        }

        throw new RuntimeException("Could not find zip file");
    }

    private static boolean isServerFileArchive(String prefix, Path path) {
        final String fileName = path.getFileName().toString();
        return fileName.startsWith(prefix) && fileName.endsWith(".zip");
    }

    private void configureServerDebug() throws IOException {
        Path configPath = Path.of(serverDir + "/bin/standalone.conf");
        String lines = Files.readAllLines(configPath)
                .stream()
                .filter(line -> !line.contains("agentlib"))
                .collect(Collectors.joining("\n"));
        lines += "\nJAVA_OPTS=\"$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,address=8787,server=y,suspend=" +
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

    private void loadCommandFiles() {
        if (commandFile != null && !commandFile.isEmpty()) {
            commandFile.forEach(fileName -> {
                try {
                    cliCommands.addAll(Files.readAllLines(Path.of(fileName)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
    private void executeCliCommands() {
        if (!cliCommands.isEmpty()) {
            System.out.println("Configuring the server...");

            System.out.println(cliCommands);

            cliCommands.add(0, "embed-server --server-config=" + configName);

            Path jbossCli = Path.of(serverDir + "/bin/jboss-cli.sh");
            try (Jash jash = Jash.start(jbossCli.toFile().getAbsolutePath())
                    .inputStream(cliCommands.stream())) {
                jash.stream().peek(System.out::println);
                if (jash.getExitCode() != 0) {
                    throw new RuntimeException("Failed to configure server");
                }
            }
        }
    }

    private void determineServerConfiguration() {
        if (useMicroprofile) {
            configName = "standalone-microprofile.xml";
        } else if (useFull) {
            configName = "standalone-full.xml";
        } else if (useHA) {
            configName = "standalone-full-ha.xml";
        }
    }

    private void startServer() {
        System.out.println("Dry run: " + dryRun);
        if (!dryRun) {
            System.out.println("Starting server using config: " + configName);
            Path standalone = Path.of(serverDir + "/bin/standalone.sh");
            try (Stream<String> jash = Jash.start(standalone.toFile().getAbsolutePath(), "-c", configName)
                    .stream()
                    .peek(System.out::println)
            ) {
                jash.count();
            }
        } else {
            System.out.println("Dry run, not starting server");
        }
    }
}
