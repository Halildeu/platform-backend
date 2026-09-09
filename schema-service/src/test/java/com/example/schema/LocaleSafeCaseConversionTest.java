package com.example.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gitops#3603 — schema-service matches SQL identifiers by upper/lower-casing
 * them. A case conversion that follows the JVM default locale turns {@code i}
 * into U+0130 on {@code tr_TR}, which is outside {@code [A-Z]} and never equals
 * the catalogue's own spelling — every identifier containing an {@code i} then
 * silently stops matching (caught live in RelationshipDiscoveryService,
 * gitops#3594). This test is the machine-enforced ban on default-locale case
 * conversion in main sources: {@code toUpperCase()} / {@code toLowerCase()}
 * without an argument (any spacing, across lines), the method references
 * {@code ::toUpperCase} / {@code ::toLowerCase}, and the explicit
 * {@code Locale.getDefault()} argument. Comments and string/char/text-block
 * literals are stripped first so they neither hide nor fake a violation.
 * Natural-language text that must follow Turkish rules names a Turkish locale
 * explicitly (see AiChatService); that is allowed by construction.
 */
class LocaleSafeCaseConversionTest {

    private static final Pattern DEFAULT_LOCALE_CONVERSION = Pattern.compile(
            "\\.\\s*(toUpperCase|toLowerCase)\\s*\\(\\s*\\)"                                   // s.toUpperCase( )
            + "|::\\s*(toUpperCase|toLowerCase)\\b"                                             // String::toUpperCase
            + "|\\.\\s*(toUpperCase|toLowerCase)\\s*\\(\\s*(?:java\\s*\\.\\s*util\\s*\\.\\s*)?Locale\\s*\\.\\s*getDefault\\s*\\(\\s*\\)\\s*\\)"); // explicit default, plain or FQN

    @Test
    void noCaseConversionInMainSourcesFollowsTheDefaultLocale() throws IOException {
        Path root = Path.of("src/main/java");
        assertThat(root).isDirectory();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripCommentsAndLiterals(Files.readString(file));
                Matcher m = DEFAULT_LOCALE_CONVERSION.matcher(code);
                while (m.find()) {
                    offenders.add(root.relativize(file) + ":" + lineOf(code, m.start()) + "  " + m.group().strip());
                }
            }
        }
        assertThat(offenders)
                .as("default-locale toUpperCase/toLowerCase — name Locale.ROOT (or an explicit language) (gitops#3603)")
                .isEmpty();
    }

    @Test
    void scannerSeesThroughSpacingLinesAndMethodReferences() {
        String src = """
                class X {
                  String a(String s) { return s.toUpperCase( ); }
                  String b(String s) { return s
                      .toLowerCase(
                      ); }
                  Object c() { return java.util.stream.Stream.of("x").map(String::toUpperCase); }
                  String d(String s) { return s.toUpperCase(java.util.Locale.getDefault()); }
                  String e(String s) { return s.toUpperCase(Locale.getDefault()); }
                  String ok1(String s) { return s.toUpperCase(Locale.ROOT); }
                  String ok2(String s) { return s.toLowerCase(Locale.forLanguageTag("tr")); }
                }
                """;
        assertThat(findAll(stripCommentsAndLiterals(src)))
                .as("spacing, multi-line, method ref, FQN getDefault, plain getDefault")
                .hasSize(5);
    }

    @Test
    void escapedTripleQuoteInsideATextBlockDoesNotEndIt() {
        String src = "class Z {\n"
                + "  String block = \"\"\"\n"
                + "      has an escaped \\\"\"\" inside\n"
                + "      \"\"\";\n"
                + "  String real = block.toUpperCase();\n"
                + "}\n";
        List<String> hits = findAll(stripCommentsAndLiterals(src));
        assertThat(hits).as("the real call after the block is still seen").hasSize(1);
    }

    @Test
    void scannerIgnoresCommentsAndLiteralsButNotCodeAfterThem() {
        String src = """
                class Y {
                  // s.toUpperCase() in a line comment
                  /* s.toLowerCase() in a block
                     comment */
                  String lit = "s.toUpperCase()";
                  String block = \"\"\"
                      s.toLowerCase()
                      \"\"\";
                  char q = '"';
                  String url = "https://host/"; String real = lit.toUpperCase();
                }
                """;
        List<String> hits = findAll(stripCommentsAndLiterals(src));
        assertThat(hits).as("only the real call after the string literal").hasSize(1);
    }

    private static List<String> findAll(String code) {
        List<String> out = new ArrayList<>();
        Matcher m = DEFAULT_LOCALE_CONVERSION.matcher(code);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Replaces comments and literal contents with spaces (newlines kept so line
     * numbers stay right). Handles {@code //}, {@code /* *}{@code /}, string,
     * char and text-block literals with escapes.
     */
    static String stripCommentsAndLiterals(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (; i < end; i++) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                }
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                int end = textBlockEnd(s, i + 3);
                for (; i < end; i++) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n && s.charAt(i) != quote && s.charAt(i) != '\n') {
                    if (s.charAt(i) == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                    } else {
                        out.append(' ');
                        i++;
                    }
                }
                if (i < n) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Index just past the closing {@code """}; a backslash-escaped quote never closes the block. */
    private static int textBlockEnd(String s, int from) {
        int j = from;
        while (j < s.length()) {
            if (s.charAt(j) == '\\') {
                j += 2;
            } else if (s.startsWith("\"\"\"", j)) {
                return j + 3;
            } else {
                j++;
            }
        }
        return s.length();
    }
}
