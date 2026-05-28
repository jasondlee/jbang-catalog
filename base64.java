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

    @Argument(required = true, description = "The file to process")
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

        try {
            byte[] bytes = Files.readAllBytes(Path.of(fileName));

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
            Files.write(Paths.get(outputFile), bytes);
        } catch (IOException e) {
            System.err.println("Unable to write to file: " + e.getMessage());
        }
    }
}
