/// usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.6.3
//JAVA 17+

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.concurrent.Callable;

@Command(name = "base64", mixinStandardHelpOptions = true, version = "base64 0.1",
        description = "base64 made with jbang")
class base64 implements Callable<Integer> {
    @CommandLine.Option(names = {"-d"}, description = "Decode file")
    boolean decode;

    @CommandLine.Option(names = {"-e"}, description = "Encode file")
    boolean encode;

    @CommandLine.Option(names = {"-o"}, description = "Output file", arity = "1")
    String outputFile;

    @Parameters(index = "0", description = "The file to process", arity = "1")
    String fileName;

    public static void main(String... args) {
        int exitCode = new CommandLine(new base64()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception { // your business logic goes here...
        if (encode && decode) {
            System.err.println("Can not encode and decode simultaneously.");
            System.exit(1);
        }

        byte[] bytes = Files.readAllBytes(Path.of(fileName));

        if (encode) {
            outputEncodedFile(Base64.getEncoder().encode(bytes));
        } else {
            outputEncodedFile(Base64.getDecoder().decode(bytes));
        }

        return 0;
    }

    private void outputEncodedFile(byte[] bytes) {
        try {
            Files.write(Paths.get(outputFile), bytes);
        } catch (IOException e) {
            System.err.println("Unable to write to file: " + e.getMessage());
        }
    }
}
