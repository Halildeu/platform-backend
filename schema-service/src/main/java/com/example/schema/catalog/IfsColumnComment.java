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
 * were simply not read. The comment carries what the constraint would have said.
 * {@code FLAGS} position 1 is the key class: {@code K} = a key of this logical unit,
 * {@code P} = a parent key inherited from the parent logical unit, {@code A} = attribute.
 * A child LU is keyed by its parent's key plus its own, so the view's business key is
 * the union of its {@code K} and {@code P} columns and BOTH count as key columns here;
 * reading only one class would leave every child LU without its parent part, or every
 * LU without its own. {@code PROMPT} is the label a user sees in IFS; {@code REF} names
 * the logical unit a foreign reference points at.
 *
 * <p>A comment without {@code =} is free text from some other author and is kept as is:
 * no label, no key flag, nothing invented. {@code raw} is the comment exactly as the
 * dictionary stores it (only a null/blank comment becomes null); parsing trims for itself.
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
        Map<String, String> entries = new LinkedHashMap<>();
        for (String part : comment.trim().split("\\^")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim().toUpperCase(Locale.ROOT);
            String value = part.substring(eq + 1).trim();
            if (!key.isEmpty() && !entries.containsKey(key)) entries.put(key, value);
        }
        return new IfsColumnComment(comment, Map.copyOf(entries));
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

    /** The {@code FLAGS} key class ({@code 'K'} own key, {@code 'P'} parent key, {@code 'A'} attribute), or 0. */
    public char keyClass() {
        String flags = entries.get(FLAGS);
        return flags == null || flags.isEmpty() ? 0 : Character.toUpperCase(flags.charAt(0));
    }

    /** Part of the view's business key: its own key ({@code K}) or the inherited parent key ({@code P}). */
    public boolean keyColumn() {
        char k = keyClass();
        return k == 'K' || k == 'P';
    }

    /** The inherited part of the key ({@code P}): the columns a child LU shares with its parent. */
    public boolean parentKeyColumn() {
        return keyClass() == 'P';
    }

    /** The logical unit an IFS {@code REF=} points at, or null. */
    public String reference() {
        String ref = entries.get(REF);
        return ref == null || ref.isBlank() ? null : ref;
    }
}
