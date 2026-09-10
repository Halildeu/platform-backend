package com.example.schema.catalog;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The structured comment IFS Applications writes on every view column (gitops#3631).
 *
 * <p>IFS publishes its business data as views and keeps the column's own metadata in
 * the dictionary comment, as {@code KEY=VALUE} pairs separated by {@code ^}:
 *
 * <pre>FLAGS=PMI--^DATATYPE=STRING(20)/UPPERCASE^PROMPT=Company^REF=Company^</pre>
 *
 * <p>Measured on the live IFSAPP dictionary before this parser existed: 205,874
 * columns, zero primary keys and zero comments in the snapshot — the constraints live
 * on the {@code _TAB} base tables the reporting account cannot see, and the comments
 * were simply not read. The comment carries what the constraint would have said:
 * {@code FLAGS} position 1 is the key class ({@code P} = part of the primary key,
 * {@code K} = parent key, {@code A} = attribute), {@code PROMPT} is the label a user
 * sees in IFS, {@code REF} names the logical unit a foreign reference points at.
 *
 * <p>A comment without {@code =} is free text from some other author and is kept as is:
 * no label, no key flag, nothing invented.
 */
public record IfsColumnComment(String raw, Map<String, String> entries) {

    public static final String FLAGS = "FLAGS";
    public static final String PROMPT = "PROMPT";
    public static final String REF = "REF";
    public static final String DATATYPE = "DATATYPE";

    /** Parses a dictionary comment; never throws, never returns null. */
    public static IfsColumnComment parse(String comment) {
        if (comment == null || comment.isBlank()) {
            return new IfsColumnComment(null, Map.of());
        }
        String trimmed = comment.trim();
        Map<String, String> entries = new LinkedHashMap<>();
        for (String part : trimmed.split("\\^")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim().toUpperCase(Locale.ROOT);
            String value = part.substring(eq + 1).trim();
            if (!key.isEmpty() && !entries.containsKey(key)) entries.put(key, value);
        }
        return new IfsColumnComment(trimmed, Map.copyOf(entries));
    }

    /** True when the comment is IFS-structured (at least one {@code KEY=VALUE} pair). */
    public boolean structured() {
        return !entries.isEmpty();
    }

    /** The IFS prompt, or null — never the column name, never free text. */
    public String label() {
        String prompt = entries.get(PROMPT);
        return prompt == null || prompt.isBlank() ? null : prompt;
    }

    /** {@code FLAGS} first position {@code P}: this column is part of the view's primary key. */
    public boolean keyColumn() {
        String flags = entries.get(FLAGS);
        return flags != null && !flags.isEmpty() && flags.charAt(0) == 'P';
    }

    /** {@code FLAGS} first position {@code K}: a parent-key column (the referenced side of a relation). */
    public boolean parentKeyColumn() {
        String flags = entries.get(FLAGS);
        return flags != null && !flags.isEmpty() && flags.charAt(0) == 'K';
    }

    /** The logical unit an IFS {@code REF=} points at, or null. */
    public String reference() {
        String ref = entries.get(REF);
        return ref == null || ref.isBlank() ? null : ref;
    }
}
