package io.spmp.impact.cmd;

import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.graph.Schema;
import io.spmp.impact.web.auth.AppUser;
import io.spmp.impact.web.auth.AppUserService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * P9.6 — Manage {@code :AppUser} nodes from the CLI. Useful for offline admin
 * tasks (seed an additional admin, list users, revoke).
 *
 * <pre>
 * impact users list
 * impact users create --username alice --role DEV --password "..."
 * impact users delete --username bob
 * </pre>
 *
 * <p>For interactive flows we recommend leaving {@code --password} off so the
 * CLI prompts for it (avoiding shell history). The web {@code POST /auth/login}
 * is the in-app equivalent.
 */
@Command(name = "users",
    description = "Manage web :AppUser accounts (P9.6).",
    subcommands = { UsersCmd.ListCmd.class, UsersCmd.CreateCmd.class, UsersCmd.DeleteCmd.class, UsersCmd.SetPasswordCmd.class })
public class UsersCmd implements Runnable {
    @Override public void run() {
        System.out.println("Usage: impact users <list|create|delete|set-password> …  (try --help)");
    }

    @Command(name = "list", description = "List existing web users.")
    public static class ListCmd implements Callable<Integer> {
        @Mixin Neo4jOptions neo;
        @Override public Integer call() {
            try (Neo4jWriter w = new Neo4jWriter(neo.resolveUri(), neo.resolveUser(), neo.resolvePass())) {
                Schema.bootstrap(w);
                AppUserService svc = new AppUserService(w);
                List<AppUser> users = svc.list();
                if (users.isEmpty()) {
                    System.out.println("(no users — server bootstrap will seed an admin on first start)");
                    return 0;
                }
                System.out.printf("%-24s %-32s %s%n", "USERNAME", "ROLES", "CREATED_AT");
                System.out.println("────────────────────────────────────────────────────────────────────────");
                for (AppUser u : users) {
                    System.out.printf("%-24s %-32s %s%n",
                        u.username(),
                        String.join(",", u.roles()),
                        u.createdAt());
                }
                System.out.println();
                System.out.println(users.size() + " user(s).");
            }
            return 0;
        }
    }

    @Command(name = "create", description = "Create a new web user.")
    public static class CreateCmd implements Callable<Integer> {
        @Option(names = "--username", required = true, description = "Username (3-64 chars: a-z A-Z 0-9 . _ -).")
        String username;
        @Option(names = "--password",
            description = "Password (≥ 8 chars). Omit to be prompted interactively (recommended — keeps it out of shell history).",
            interactive = true, arity = "0..1")
        String password;
        @Option(names = "--role", required = true, split = ",",
            description = "Comma-separated role list: VIEWER, DEV, ADMIN. Repeatable.")
        List<String> roles;

        @Mixin Neo4jOptions neo;
        @Override public Integer call() {
            try (Neo4jWriter w = new Neo4jWriter(neo.resolveUri(), neo.resolveUser(), neo.resolvePass())) {
                Schema.bootstrap(w);
                AppUserService svc = new AppUserService(w);
                if (password == null || password.isEmpty()) {
                    System.err.println("[users] password is required (pass --password or let the CLI prompt).");
                    return 2;
                }
                AppUser created = svc.create(username, password, roles);
                System.out.println("[users] created: " + created.username()
                    + "  roles=" + created.roles());
            } catch (RuntimeException ex) {
                System.err.println("[users] " + ex.getMessage());
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "set-password", description = "Reset a web user's password (useful when the bootstrap one was lost).")
    public static class SetPasswordCmd implements Callable<Integer> {
        @Option(names = "--username", required = true, description = "Username whose password to reset.")
        String username;
        @Option(names = "--password",
            description = "New password. Omit to be prompted interactively.",
            interactive = true, arity = "0..1")
        String password;
        @Mixin Neo4jOptions neo;
        @Override public Integer call() {
            try (Neo4jWriter w = new Neo4jWriter(neo.resolveUri(), neo.resolveUser(), neo.resolvePass())) {
                if (password == null || password.isEmpty()) {
                    System.err.println("[users] password is required (pass --password or let the CLI prompt).");
                    return 2;
                }
                AppUserService svc = new AppUserService(w);
                boolean ok = svc.setPassword(username, password);
                System.out.println(ok
                    ? "[users] password updated for " + username
                    : "[users] no such user: " + username);
                return ok ? 0 : 1;
            } catch (RuntimeException ex) {
                System.err.println("[users] " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "delete", description = "Delete a web user.")
    public static class DeleteCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "Username to delete.")
        String username;
        @Mixin Neo4jOptions neo;
        @Override public Integer call() {
            try (Neo4jWriter w = new Neo4jWriter(neo.resolveUri(), neo.resolveUser(), neo.resolvePass())) {
                AppUserService svc = new AppUserService(w);
                boolean removed = svc.delete(username);
                System.out.println(removed
                    ? "[users] deleted: " + username
                    : "[users] no such user: " + username);
                return removed ? 0 : 1;
            }
        }
    }
}
