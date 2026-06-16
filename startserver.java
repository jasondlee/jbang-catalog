/// usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS org.aesh:aesh:3.8
//DEPS dev.jbang:jash:RELEASE

import dev.jbang.jash.Jash;
import org.aesh.AeshRuntimeRunner;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@CommandDefinition(name = "startserver",
        description = "A script to manage starting a WildFly/EAP server",
        version = "1.0",
        generateHelp = true)
public class startserver implements Command<CommandInvocation> {
    @Argument(description = "The working directory of the server")
    private String serverDirArg;
    @Option(shortName = 'c', hasValue = false, description = "Clean and rebuild the server before starting")
    private boolean clean;
    @Option(shortName = 'L', name = "logging", hasValue = false, description = "Set logging level to DEBUG")
    private boolean debugLogging;
    @Option(shortName = 'O', name = "otel", aliases = {"opentelemetry"}, hasValue = false, description = "Enable OpenTelemetry")
    private boolean enableOtel;
    @Option(shortName = 'M', name = "micrometer", hasValue = false, description = "Enable Micrometer")
    private boolean enableMicrometer;
    @Option(shortName = 'm', name = "microprofile", hasValue = false, description = "Start the server using the standard-microprofile configuration")
    private boolean useMicroprofile;
    @Option(shortName = 'f', name = "full", hasValue = false, description = "Start the server using the standard-full configuration")
    private boolean useFull;
    @Option(name = "ha", hasValue = false, description = "Start the server using the standard-full-ha configuration")
    private boolean useHA;
    @Option(shortName = 's', name = "suspend", hasValue = false, description = "Enable suspend on start for debug")
    private boolean enableSuspend;
    @Option(shortName = 'n', name = "dry-run", hasValue = false, description = "Don't start the server")
    private boolean dryRun;
    @Option(shortName = 'S', name = "secman", hasValue = false, description = "Enable security manager")
    private boolean enableSecMan;
    @OptionList(name = "file", description = "Specify file with extra commands to run")
    private List<String> commandFile;

    private Path serverDir;
    private String configName = "standalone.xml";
    private final List<String> cliCommands = new ArrayList<>();
    private final List<String> possibleDirs = List.of("build", "dist");
    private final List<String> possiblePrefixes = List.of("wildfly", "jboss-eap");

    public static void main(String[] args) {
        AeshRuntimeRunner.builder()
                .command(startserver.class)
                .args(args)
                .execute();
    }

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        try {
            if (serverDirArg != null) {
                serverDir = Path.of(serverDirArg);
            }

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

            return CommandResult.SUCCESS;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }
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
            cliCommands.add("/subsystem=undertow:write-attribute(name=statistics-enabled,value=true)");
            cliCommands.add("/extension=org.wildfly.extension.micrometer:add");
            cliCommands.add("/subsystem=micrometer:add(endpoint=\"http://localhost:4318/v1/metrics\",step=\"1\")");
            cliCommands.add("/subsystem=micrometer/registry=prometheus:add(context=\"/prometheus\", security-enabled=\"false\")");
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

            cliCommands.add(0, "embed-server --server-config=" + configName);
            cliCommands.forEach(it -> System.out.println("    " + it));

            Path jbossCli = Path.of(serverDir + "/bin/jboss-cli.sh");
            try (Jash jash = Jash.start(jbossCli.toFile().getAbsolutePath())
                    .inputStream(cliCommands.stream())) {
                List<String> output = new ArrayList<>();
                jash.stream().peek(output::add);
                int exitCode = jash.getExitCode();
                if (exitCode != 0) {
                    System.out.println("output = " + output);
                    System.err.println("Failed to configure server");
                    System.exit(exitCode);
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
            var args = new ArrayList<String>();
            args.add("-c");
            args.add(configName);
            if (enableSecMan) {
                args.add("-secmgr");
            }
            try (Stream<String> jash = Jash.start(standalone.toFile().getAbsolutePath(), args.toArray(String[]::new))
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
