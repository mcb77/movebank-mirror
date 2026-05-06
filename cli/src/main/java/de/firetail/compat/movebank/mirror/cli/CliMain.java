package de.firetail.compat.movebank.mirror.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Mixin;

@Command(
        name = "movebank-mirror",
        mixinStandardHelpOptions = true,
        version = "movebank-mirror 0.0.1",
        description = "Mirrors Movebank study metadata and event data to a local file/folder structure.",
        subcommands = {
                MetadataCommand.class,
                EventDataCommand.class,
                SyncCommand.class,
                HelpCommand.class
        }
)
public class CliMain {

    /**
     * Global options live on the root command and are inherited (via
     * {@code ScopeType.INHERIT}) by every subcommand, so flags work either before
     * or after the subcommand name.
     */
    @Mixin GlobalOptions globals = new GlobalOptions();

    public static void main(String[] args) {
        int exit = new CommandLine(new CliMain())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionStrategy(new CommandLine.RunLast())
                .execute(args);
        System.exit(exit);
    }
}
