package io.spmp.impact.cmd;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterCSharp;
import org.treesitter.TreeSitterJavascript;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * L3 smoke test. Parses tiny snippets of JavaScript + C# using bonede tree-sitter
 * Java bindings and prints AST shape. Verifies the native libs load correctly on
 * the host platform before we commit to the full polyglot extractor build-out.
 *
 * <p>If this command prints {@code SMOKE OK} for both languages, the JNI integration
 * is working and L4/L6 (proper extractors) are safe to build. If it dies with
 * {@code UnsatisfiedLinkError} or similar, we need to either ship native libs
 * explicitly or fall back to subprocess-based parsers.
 */
@Command(name = "ts-smoke", description = "Internal: smoke-test tree-sitter JNI bindings (JavaScript + C#).")
public class TreeSitterSmokeCmd implements Callable<Integer> {

    @Override
    public Integer call() {
        int failures = 0;
        failures += smokeOne("JavaScript",
            new TreeSitterJavascript(),
            "function foo() { return 1; } const bar = () => 2;",
            new String[]{"function_declaration", "arrow_function", "variable_declaration"});
        failures += smokeOne("C#",
            new TreeSitterCSharp(),
            "public class Foo { public int Bar() => 42; }",
            new String[]{"class_declaration", "method_declaration"});
        return failures == 0 ? 0 : 1;
    }

    private static int smokeOne(String label, TSLanguage lang, String source, String[] expectTypes) {
        System.out.println("── " + label + " ─────────────────────────────");
        try {
            TSParser parser = new TSParser();
            parser.setLanguage(lang);
            TSTree tree = parser.parseString(null, source);
            TSNode root = tree.getRootNode();
            System.out.println("  root type:        " + root.getType());
            System.out.println("  root child count: " + root.getChildCount());
            for (String t : expectTypes) {
                int n = countByType(root, t);
                System.out.printf("  %-22s = %d%n", t, n);
            }
            System.out.println("  SMOKE OK");
            return 0;
        } catch (Throwable t) {
            System.err.println("  SMOKE FAILED: " + t.getClass().getName() + " : " + t.getMessage());
            t.printStackTrace(System.err);
            return 1;
        }
    }

    private static int countByType(TSNode node, String type) {
        int sum = type.equals(node.getType()) ? 1 : 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            sum += countByType(node.getChild(i), type);
        }
        return sum;
    }
}
