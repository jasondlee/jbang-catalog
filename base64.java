/// usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.aesh:aesh:3.8
//JAVA 17+

import org.aesh.AeshRuntimeRunner;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

@CommandDefinition(name = "base64",
        description = "base64 made with jbang",
        version = "0.1",
        generateHelp = true)
public class base64 implements Command<CommandInvocation> {
    @Option(shortName = 'd', hasValue = false, description = "Decode file")
    boolean decode;

    @Option(shortName = 'e', hasValue = false, description = "Encode file")
    boolean encode;

    @Option(shortName = 'o', description = "Output file")
    String outputFile;

    @Option(shortName = 'i', hasValue = false, description = "Read from stdin")
    boolean readFromStdin;

    @Argument(description = "The file to process", required = false)
    String fileName;

    public static void main(String... args) {
        AeshRuntimeRunner.builder()
                .command(base64.class)
                .args(args)
                .execute();
    }

    @Override
    public CommandResult execute(CommandInvocation invocation) throws InterruptedException {
        if (encode && decode) {
            System.err.println("Can not encode and decode simultaneously.");
            return CommandResult.FAILURE;
        }

        if (!readFromStdin && fileName == null) {
            System.err.println("Either file name or stdin must be provided.");
            return CommandResult.FAILURE;
        }

        try {
            byte[] bytes;
            if (readFromStdin) {
                bytes = invocation.getStdin().readAllBytes();
                if (bytes.length == 0) {
                    System.err.println("No data provided on stdin.");
                    return CommandResult.FAILURE;
                }
            } else {
                bytes = Files.readAllBytes(Path.of(fileName));
            }

            if (bytes.length == 0) {
                System.err.println("No data to process.");
                return CommandResult.FAILURE;
            }

            if (encode) {
                outputEncodedFile(Base64.getEncoder().encode(bytes));
            } else {
                outputEncodedFile(Base64.getDecoder().decode(bytes));
            }

            return CommandResult.SUCCESS;
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            return CommandResult.FAILURE;
        }
    }

    private void outputEncodedFile(byte[] bytes) {
        try {
            if (outputFile == null) {
                System.out.write(bytes);
                System.out.println();
            } else {
                Files.write(Paths.get(outputFile), bytes);
            }
        } catch (IOException e) {
            System.err.println("Unable to write to file: " + e.getMessage());
        }
    }
}
