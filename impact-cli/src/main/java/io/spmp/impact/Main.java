package io.spmp.impact;

import io.spmp.impact.cmd.AnalyzeCmd;
import io.spmp.impact.cmd.IngestCmd;
import io.spmp.impact.cmd.QueryCmd;
import io.spmp.impact.cmd.ReposCmd;
import io.spmp.impact.cmd.SnapshotsCmd;
import io.spmp.impact.cmd.TestCasesCmd;
import io.spmp.impact.cmd.TreeSitterSmokeCmd;
import io.spmp.impact.cmd.UsersCmd;
import io.spmp.impact.cmd.WebCmd;
import io.spmp.impact.cmd.WipeCmd;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "impact",
    mixinStandardHelpOptions = true,
    version = "impact-cli 0.1.0",
    description = "Enterprise impact analysis over a Neo4j call graph.",
    subcommands = { IngestCmd.class, AnalyzeCmd.class, QueryCmd.class, WipeCmd.class, SnapshotsCmd.class, TestCasesCmd.class, TreeSitterSmokeCmd.class, ReposCmd.class, WebCmd.class, UsersCmd.class }
)
public class Main implements Runnable {

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        System.exit(exit);
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
