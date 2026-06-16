/// usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.aesh:aesh:3.8
//DEPS dev.tamboui:tamboui-toolkit:0.3.0
//DEPS dev.tamboui:tamboui-jline3-backend:0.3.0

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.inline.InlineDisplay;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.BackendFactory;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.gauge.Gauge;
import dev.tamboui.widgets.paragraph.Paragraph;
import org.aesh.AeshRuntimeRunner;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

@CommandDefinition(name = "modulecheck",
        description = "Identify potentially unnecessary JARs in JBoss/WildFly module definitions")
public class modulecheck implements Command<CommandInvocation> {
    private static final Style RED = Style.EMPTY.fg(Color.RED);
    private static final Style GREEN = Style.EMPTY.fg(Color.GREEN);

    private static final String RESULTS_DIR = "./modulecheck-results";
    private static final int BANNER_WIDTH = 70;

    @Option(name = "wildfly-dir",
            required = true,
            description = "Path to the WildFly installation to modify and test")
    private String wildflyDir;

    @OptionList(name = "module-dir",
            defaultValue = "modules",
            description = "Module directory relative to --wildfly-dir (repeatable; default: modules)")
    private List<String> moduleDirs;

    @Option(shortName = 's',
            name = "script",
            required = true,
            description = "Script to run to test each artifact")
    private String script;

    @Option(shortName = 'v',
            name = "verbose",
            hasValue = false,
            description = "Show script output as it runs")
    private boolean verbose;

    private Path wildflyPath;
    private Path scriptPath;
    private List<Path> resolvedModuleDirs;
    private Path resultsPath;

    private volatile Path currentBackup;
    private volatile Path currentModule;

    private Backend backend;

    private int totalModulesWithEntries;
    private int totalModulesSkipped;
    private int totalEntriesTested;
    private List<String> necessary = new ArrayList<>();
    private List<String> unnecessary = new ArrayList<>();

    // --- public methods ---

    public static void main(String[] args) throws Exception {
        try {
            AeshRuntimeRunner.builder()
                    .command(modulecheck.class)
                    .args(args)
                    .execute();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        try {
            return run();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }
    }

    // --- private methods ---

    private CommandResult run() throws Exception {
        backend = BackendFactory.create();

        validateInputs();
        resolveModuleDirs();
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));

        long startTime = System.currentTimeMillis();
        initializeResultsDir();
        printBanner();

        List<Path> moduleFiles = findModuleFiles();
        processModules(moduleFiles);

        long totalDuration = (System.currentTimeMillis() - startTime) / 1000;
        printSummary(moduleFiles.size(), totalDuration);
        writeSummaryFile(moduleFiles.size(), totalDuration);

        return CommandResult.SUCCESS;
    }

    private void validateInputs() throws IOException {
        wildflyPath = Path.of(wildflyDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(wildflyPath)) {
            throw new IOException("WildFly directory does not exist: " + wildflyDir);
        }

        scriptPath = Path.of(script).toAbsolutePath().normalize();
        if (!Files.exists(scriptPath)) {
            throw new IOException("The specified script does not exist: " + script);
        }
        if (!Files.isRegularFile(scriptPath) || !Files.isExecutable(scriptPath)) {
            throw new IOException("The script is not an executable file: " + scriptPath);
        }
    }

    private void resolveModuleDirs() throws IOException {
        if (moduleDirs == null || moduleDirs.isEmpty()) {
            moduleDirs = List.of("modules");
        }

        resolvedModuleDirs = new ArrayList<>();
        for (String md : moduleDirs) {
            Path modulePath = wildflyPath.resolve(md);
            if (!Files.isDirectory(modulePath)) {
                throw new IOException("Module directory does not exist: " + modulePath);
            }
            resolvedModuleDirs.add(modulePath);
        }
    }

    private void cleanup() {
        Path backup = currentBackup;
        Path module = currentModule;
        if (backup != null && Files.exists(backup) && module != null) {
            System.out.println();
            System.out.println("Restoring " + module + " from backup...");
            try {
                Files.copy(backup, module, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(backup);
                System.out.println("All module files have been restored.");
            } catch (IOException e) {
                System.err.println("Failed to restore backup: " + e.getMessage());
            }
            currentBackup = null;
            currentModule = null;
        }
    }

    private void initializeResultsDir() throws IOException {
        resultsPath = Path.of(RESULTS_DIR);
        if (Files.exists(resultsPath)) {
            try (Stream<Path> paths = Files.walk(resultsPath)) {
                paths.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
        Files.createDirectories(resultsPath.resolve("logs"));
        Files.writeString(resultsPath.resolve("unnecessary.txt"), "");
        Files.writeString(resultsPath.resolve("summary.txt"), "");
    }

    private void printBanner() {
        printSeparator("=");
        System.out.println("  Module Check — Unnecessary JAR Detection");
        printSeparator("=");
        System.out.println("  WildFly dir:   " + wildflyPath);
        String label = "  Module dir(s): ";
        for (Path dir : resolvedModuleDirs) {
            System.out.println(label + dir);
            label = "                 ";
        }
        System.out.println("  Test dir:      " + scriptPath);
        System.out.println("  Results:       " + RESULTS_DIR);
        System.out.println("  Started:       " +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy")));
        printSeparator("=");
        System.out.println();
    }

    private void printSeparator(String ch) {
        System.out.println(ch.repeat(BANNER_WIDTH));
    }

    private List<Path> findModuleFiles() throws IOException {
        List<Path> moduleFiles = new ArrayList<>();
        for (Path dir : resolvedModuleDirs) {
            try (var stream = Files.walk(dir)) {
                stream.filter(p -> p.getFileName().toString().equals("module.xml"))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .forEach(moduleFiles::add);
            }
        }
        return moduleFiles;
    }

    private void processModules(List<Path> moduleFiles) throws Exception {
        int totalModules = moduleFiles.size();
        for (int i = 0; i < totalModules; i++) {
            processModule(moduleFiles.get(i), i + 1, totalModules);
        }
    }

    private void processModule(Path moduleFile, int moduleIndex, int totalModules) throws Exception {
        List<Integer> lineNumbers = findUncommentedEntries(moduleFile);

        if (lineNumbers.isEmpty()) {
            totalModulesSkipped++;
            return;
        }

        totalModulesWithEntries++;
        String moduleName = extractModuleName(moduleFile);
        int entryCount = lineNumbers.size();

        var buffer = Buffer.empty(Rect.of(backend.size().width(), 1));
        renderParagraph(buffer.area(), buffer,
                Span.styled("[Module " + moduleIndex + "/" + totalModules + "] ", Style.EMPTY.fg(Color.CYAN)),
                Span.styled(moduleName, Style.EMPTY.addModifier(Modifier.BOLD)),
                Span.raw(" (" + entryCount + " entries)"));
        System.out.println(buffer.toAnsiStringTrimmed());

        testEntries(backend, moduleFile, moduleName, lineNumbers);
    }

    private List<Integer> findUncommentedEntries(Path file) throws IOException {
        List<Integer> entries = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        boolean inComment = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            if (inComment) {
                if (line.contains("-->")) {
                    inComment = false;
                }
                continue;
            }

            if (line.contains("<!--") && line.contains("-->")) {
                continue;
            }

            if (line.contains("<!--")) {
                inComment = true;
                continue;
            }

            if (line.contains("<resource-root ") || line.contains("<artifact ")) {
                entries.add(i + 1);
            }
        }
        return entries;
    }

    private String extractModuleName(Path file) throws IOException {
        Matcher m = Pattern.compile("module name=\"([^\"]*)\"").matcher(Files.readString(file));
        return m.find() ? m.group(1) : file.getParent().getFileName().toString();
    }

    private void testEntries(Backend backend, Path moduleFile, String moduleName, List<Integer> lineNumbers) throws Exception {
        final int entryCount = lineNumbers.size();
        totalEntriesTested++;
        List<String> localUnnecessary = new ArrayList<>();

        try (var display = InlineDisplay.withBackend(3, backend.size().width() / 2, backend)) {
            for (int i = 0; i < entryCount; i++) {
                var entryIndex = i;
                var lineNum = lineNumbers.get(i);
                final String entryDesc = extractEntryDescription(moduleFile, lineNum);
                display.render((area, buf) ->
                        renderArtifactTestStatus(area, buf, entryIndex + 1, entryCount, entryDesc)
                );

                currentModule = moduleFile;
                currentBackup = moduleFile.resolveSibling(moduleFile.getFileName() + ".modulecheck.bak");
                Files.copy(moduleFile, currentBackup, StandardCopyOption.REPLACE_EXISTING);

                removeArtifactEntry(moduleFile, lineNum);

                final boolean isSuccess =
                        (runScript(resultsPath.resolve(("logs/" + moduleName + "_" + entryDesc + ".log")
                                .replaceAll("[\\\\.:]", "-")
                        ))) == 0;

                if (verbose) {
                    logTestResults(display, entryDesc, isSuccess);
                }

                if (isSuccess) {
                    localUnnecessary.add(entryDesc);
                    Files.writeString(resultsPath.resolve("unnecessary.txt"),
                            moduleFile + ":" + lineNum + ": " + entryDesc + "\n",
                            StandardOpenOption.APPEND);
                } else {
                    necessary.add(entryDesc);
                }

                Files.copy(currentBackup, moduleFile, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(currentBackup);
                currentBackup = null;
                currentModule = null;
            }

            if (!localUnnecessary.isEmpty()) {
                unnecessary.addAll(localUnnecessary);
                var buf = Buffer.empty(Rect.of(backend.size().width(), localUnnecessary.size() + 1));

                var lines = new ArrayList<Line>();
                lines.add(Line.from("Finished testing " + moduleName + ". Unnecessary artifacts found:"));
                localUnnecessary.forEach(desc -> lines.add(Line.from(Span.styled("  - " + desc, RED))));
                lines.add(Line.from(""));
                var para = Paragraph.builder().text(Text.from(lines)).build();

                para.render(buf.area(), buf);
                display.println(buf.toAnsiString());
            }
        }
    }

    private String extractEntryDescription(Path file, int lineNum) throws IOException {
        String line = Files.readAllLines(file).get(lineNum - 1);
        Matcher m = null;

        if (line.contains("<resource-root")) {
            m = Pattern.compile("path=\"([^\"]*)\"").matcher(line);
        } else if (line.contains("<artifact")) {
            m = Pattern.compile("name=\"([^\"]*)\"").matcher(line);
        }

        if (m != null && m.find()) {
            return m.group(1);
        }

        return "(unknown)";
    }

    private void renderArtifactTestStatus(Rect area, Buffer buf, int entryIndex, int entryCount, String entryDesc) {
        var rows = Layout.vertical()
                .constraints(
                        Constraint.length(1),
                        Constraint.length(1),
                        Constraint.length(1))
                .split(area);

        renderParagraph(rows.get(0), buf,
                Span.raw("Testing "),
                Span.styled(entryDesc, Style.EMPTY.addModifier(Modifier.BOLD)));

        Gauge.builder()
                .ratio((double) entryIndex / entryCount)
                .gaugeStyle(GREEN)
                .build()
                .render(rows.get(1), buf);

        renderParagraph(rows.get(2), buf,
                Span.styled(String.format("%d/%d", entryIndex, entryCount), Style.EMPTY.fg(Color.YELLOW)),
                Span.raw(" artifacts"));
    }

    private void removeArtifactEntry(Path file, int lineNum) throws IOException {
        List<String> lines = Files.readAllLines(file);
        lines.remove(lineNum - 1);
        Files.write(file, lines);
    }

    private int runScript(Path logFile) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(scriptPath.toAbsolutePath().toString(),
                wildflyPath.toAbsolutePath().toString()));

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process process = pb.start();

        try (var in = process.getInputStream();
             var out = new FileOutputStream(logFile.toFile())) {
            if (verbose && false) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    System.out.write(buf, 0, n);
                    System.out.flush();
                }
            } else {
                in.transferTo(out);
            }
        }

        return process.waitFor();
    }

    private void logTestResults(InlineDisplay display, String entryDesc, boolean isSuccess) throws IOException {
        Style redBold = RED.addModifier(Modifier.BOLD);
        var buffer = Buffer.empty(Rect.of(backend.size().width(), 1));
        renderParagraph(buffer.area(), buffer,
                Span.raw("The test of '" + entryDesc + "' "),
                (isSuccess ? Span.styled("passed", redBold) : Span.styled("failed", GREEN)),
                Span.raw(", so the artifact is "),
                (isSuccess ? Span.styled("unnecessary", redBold) : Span.styled("necessary", GREEN)));
        display.println(buffer.toAnsiStringTrimmed());
    }

    private void renderParagraph(Rect rect, Buffer buffer, Span... spans) {
        Paragraph.builder()
                .text(Text.from(Line.from(spans)))
                .build()
                .render(rect, buffer);
    }

    private void printSummary(int totalModules, long totalDuration) throws IOException {
        System.out.println();
        printSeparator("=");
        System.out.println("                     MODULE CHECK SUMMARY");
        printSeparator("=");
        System.out.printf("  Total modules scanned:       %d%n", totalModules);
        System.out.printf("  Modules with entries:        %d%n", totalModulesWithEntries);
        System.out.printf("  Modules skipped (0 entries): %d%n", totalModulesSkipped);
        System.out.printf("  Total entries tested:        %d%n", totalEntriesTested);
        System.out.printf("    Needed (tests failed):     %d%n", necessary.size());
        System.out.printf("    Unnecessary (tests pass):  %d%n", unnecessary.size());
        System.out.printf("  Total time:                  %s%n", formatDuration(totalDuration));
        printSeparator("=");

        if (!unnecessary.isEmpty()) {
            System.out.println();
            System.out.println("Potentially unnecessary entries:");
            var buf = Buffer.empty(Rect.of(backend.size().width(), unnecessary.size() + 1));

            Paragraph.builder().text(
                            Text.from(
                                    unnecessary.stream().sorted()
                                            .map(entry -> Line.from(Span.styled("  - " + entry, RED)))
                                            .toList()
                            ))
                    .build()
                    .render(buf.area(), buf);
            System.out.print(buf.toAnsiStringTrimmed());
        }

        System.out.println();
        System.out.println("Full results: " + RESULTS_DIR + "/unnecessary.txt");
        System.out.println("Maven logs:   " + RESULTS_DIR + "/logs/");
    }

    private void writeSummaryFile(int totalModules, long totalDuration) throws IOException {
        var summary = new StringBuilder();
        summary.append("Module Check Summary — ").append(LocalDateTime.now()).append("\n");
        summary.append("WildFly dir:  ").append(wildflyPath).append("\n");
        for (Path dir : resolvedModuleDirs) {
            summary.append("Module dir:   ").append(dir).append("\n");
        }
        summary.append("Test dir:     ").append(scriptPath).append("\n\n");
        summary.append(String.format("Total modules scanned:       %d%n", totalModules));
        summary.append(String.format("Modules with entries:        %d%n", totalModulesWithEntries));
        summary.append(String.format("Modules skipped (0 entries): %d%n", totalModulesSkipped));
        summary.append(String.format("Total entries tested:        %d%n", totalEntriesTested));
        summary.append(String.format("  Needed (tests failed):     %d%n", necessary.size()));
        summary.append(String.format("  Unnecessary (tests pass):  %d%n", unnecessary.size()));
        summary.append(String.format("Total time:                  %s%n", formatDuration(totalDuration)));
        Files.writeString(resultsPath.resolve("summary.txt"), summary.toString());
    }

    private String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        }
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        }
        return String.format("%ds", secs);
    }
}
