package com.example.schema.catalog;

import com.example.schema.model.ForeignKeyInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns IFS {@code REF=} column metadata into foreign keys (gitops#3631, slice 2).
 *
 * <p>The reporting account sees IFS views, never the {@code _TAB} tables that carry the
 * constraints, so {@code ALL_CONSTRAINTS} answers nothing for it. IFS wrote the same
 * fact into every view column's comment: {@code REF=Site} means "this column holds the
 * key of the Site logical unit"; {@code REF=AbsenceRegistration(company_id,emp_no)} means
 * the reference is composite and the named columns of THIS view carry the rest of the
 * target's key; a {@code /NOCHECK}, {@code /CASCADE} or {@code /CUSTOM…} tail says how
 * IFS enforces it and is not part of the name.
 *
 * <p>Every rule below was measured on the live IFSAPP dictionary (2026-09-10; 28,440
 * references on 10,885 views) rather than assumed:
 * <ul>
 *   <li>target view = the LU name in UPPER_SNAKE ({@code CompanyFinance} → {@code COMPANY_FINANCE});
 *       a name that is not a view of this owner is left unresolved and counted (1,436);</li>
 *   <li>target key = the target view's key columns ({@code FLAGS} class {@code P} or {@code K})
 *       in column order. 164 views interleave own and parent keys; no reference addressed
 *       one positionally, so column order is the order that matters;</li>
 *   <li>source columns = the parenthesised columns, then the referencing column. Each must
 *       exist in the source view (339 name one that does not) and the count must equal the
 *       target key's (514 do not); both are counted, never guessed. With no parentheses and
 *       a multi-column target key, the other key columns are taken from same-named source
 *       columns (644 references rely on that; 149 lack one and stay unresolved);</li>
 *   <li>pairing: a source column that carries a target key column's exact name pairs with
 *       it (21,701 of 21,727 such names sit at the same position anyway, 26 do not);
 *       the rest pair by position. The pairs are emitted in target key order, so the last
 *       pair is the target's own key;</li>
 *   <li>a self-reference is a join unless it maps every column onto itself
 *       (130 self-references, 4 of them identities).</li>
 * </ul>
 *
 * <p>The result is a {@link ForeignKeyInfo} named {@code IFS_REF_<VIEW>.<COLUMN>} (the dot
 * cannot occur in an unquoted Oracle identifier, so the name is unambiguous) and marked
 * {@code isNotTrusted=true}: IFS does not enforce it as a constraint in the dictionary the
 * account can see, and the flag says so. Two references that map the same columns onto the
 * same target yield one key.
 */
public final class IfsReferenceResolver {

    /** One view column as the resolver needs it: name, dictionary order, parsed comment. */
    public record ColumnMeta(String name, int ordinal, IfsColumnComment comment) {}

    /** What resolution produced, with the counts a log line and a test want. */
    public record Result(List<ForeignKeyInfo> foreignKeys, int references, int resolved,
                         int unresolvedTarget, int unresolvedKeyShape, int unresolvedSourceColumn,
                         int duplicates) {}

    /** {@code Lu}, {@code Lu(a,b)}, {@code Lu/NOCHECK}, {@code Lu(a,b)/CUSTOM=(…)}. */
    record Reference(String logicalUnit, List<String> parentColumns, String tail) {}

    private static final Pattern REF_HEAD = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*(?:\\(([^)]*)\\))?\\s*$");

    private IfsReferenceResolver() {}

    /** {@code AbsLegalBase} → {@code ABS_LEGAL_BASE}; digits stay attached ({@code Bill2Line} → {@code BILL2_LINE}). */
    public static String luToViewName(String lu) {
        String trimmed = lu.trim();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && trimmed.charAt(i - 1) != '_') out.append('_');
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }

    /** Parses a raw {@code REF=} value; null when it has no recognisable head. */
    static Reference parseReference(String raw) {
        if (raw == null) return null;
        String head = raw;
        String tail = "";
        int slash = raw.indexOf('/');
        if (slash >= 0) {
            head = raw.substring(0, slash);
            tail = raw.substring(slash + 1).trim();
        }
        Matcher m = REF_HEAD.matcher(head);
        if (!m.matches()) return null;
        List<String> parents = new ArrayList<>();
        if (m.group(2) != null) {
            for (String part : m.group(2).split(",")) {
                String p = part.trim().toUpperCase(Locale.ROOT);
                if (!p.isEmpty()) parents.add(p);
            }
        }
        return new Reference(m.group(1), List.copyOf(parents), tail);
    }

    /**
     * @param owner       schema owner, used for both sides of every key
     * @param viewColumns view name → its columns in dictionary order; only views (the reader
     *                    restricts the query to {@code ALL_VIEWS}), so every target here is one
     */
    public static Result resolve(String owner, Map<String, List<ColumnMeta>> viewColumns) {
        Map<String, List<String>> keyColumnsByView = new LinkedHashMap<>();
        for (Map.Entry<String, List<ColumnMeta>> e : viewColumns.entrySet()) {
            keyColumnsByView.put(e.getKey(), keyColumns(e.getValue()));
        }

        Map<String, ForeignKeyInfo> byIdentity = new LinkedHashMap<>();
        int references = 0;
        int unresolvedTarget = 0;
        int unresolvedShape = 0;
        int unresolvedSource = 0;
        int duplicates = 0;
        for (Map.Entry<String, List<ColumnMeta>> e : viewColumns.entrySet()) {
            String view = e.getKey();
            Set<String> viewColumnNames = new HashSet<>();
            for (ColumnMeta c : e.getValue()) viewColumnNames.add(c.name().toUpperCase(Locale.ROOT));

            for (ColumnMeta col : e.getValue()) {
                String raw = col.comment() == null ? null : col.comment().reference();
                if (raw == null) continue;
                references++;
                Reference ref = parseReference(raw);
                if (ref == null) { unresolvedShape++; continue; }
                String targetView = luToViewName(ref.logicalUnit());
                List<String> targetKey = keyColumnsByView.get(targetView);
                if (targetKey == null) { unresolvedTarget++; continue; }
                if (targetKey.isEmpty()) { unresolvedShape++; continue; }

                String column = col.name().toUpperCase(Locale.ROOT);
                List<String> source = new ArrayList<>(ref.parentColumns());
                if (new HashSet<>(source).size() != source.size() || source.contains(column)) { unresolvedSource++; continue; }
                boolean missing = false;
                for (String s : source) if (!viewColumnNames.contains(s)) { missing = true; break; }
                if (missing) { unresolvedSource++; continue; }
                if (source.isEmpty() && targetKey.size() > 1) {
                    // Implicit parent part: the other key columns of the target, by name, from this view.
                    String own = targetKey.contains(column) ? column : targetKey.get(targetKey.size() - 1);
                    for (String k : targetKey) {
                        if (k.equals(own)) continue;
                        if (!viewColumnNames.contains(k)) { missing = true; break; }
                        source.add(k);
                    }
                    if (missing) { unresolvedSource++; continue; }
                }
                source.add(column);
                if (source.size() != targetKey.size()) { unresolvedShape++; continue; }

                List<String> toColumns = pair(source, targetKey);
                if (toColumns == null) { unresolvedShape++; continue; }
                // Emit in target key order so the last pair is the target's own key.
                List<String> fromOrdered = new ArrayList<>(toColumns.size());
                for (String t : targetKey) fromOrdered.add(source.get(toColumns.indexOf(t)));
                if (targetView.equals(view) && fromOrdered.equals(targetKey)) { unresolvedShape++; continue; } // identity, not a join

                ForeignKeyInfo fk = new ForeignKeyInfo(
                    "IFS_REF_" + view + "." + column,
                    owner, view, List.copyOf(fromOrdered),
                    owner, targetView, List.copyOf(targetKey),
                    false,
                    true,           // not enforced as a constraint the account can see
                    "NO ACTION", "NO ACTION");
                String identity = view + "|" + fromOrdered + "|" + targetView + "|" + targetKey;
                if (byIdentity.putIfAbsent(identity, fk) != null) duplicates++;
            }
        }
        return new Result(List.copyOf(byIdentity.values()), references, byIdentity.size(),
            unresolvedTarget, unresolvedShape, unresolvedSource, duplicates);
    }

    /**
     * Pairs each source column with one target key column: exact names first, the rest by
     * position among what is left, in order. Returns the target column for each source
     * index, or null when a name clashes (two sources claiming one target).
     */
    static List<String> pair(List<String> source, List<String> targetKey) {
        String[] assigned = new String[source.size()];
        List<String> remainingTargets = new ArrayList<>(targetKey);
        for (int i = 0; i < source.size(); i++) {
            if (remainingTargets.remove(source.get(i))) assigned[i] = source.get(i);
        }
        int next = 0;
        for (int i = 0; i < source.size(); i++) {
            if (assigned[i] != null) continue;
            if (next >= remainingTargets.size()) return null;
            assigned[i] = remainingTargets.get(next++);
        }
        return List.of(assigned);
    }

    /** The view's key columns ({@code P} or {@code K}) in column order. */
    static List<String> keyColumns(List<ColumnMeta> columns) {
        List<ColumnMeta> sorted = new ArrayList<>(columns);
        sorted.sort((a, b) -> Integer.compare(a.ordinal(), b.ordinal()));
        List<String> keys = new ArrayList<>();
        for (ColumnMeta c : sorted) {
            if (c.comment() == null) continue;
            char k = c.comment().keyClass();
            if (k == 'P' || k == 'K') keys.add(c.name().toUpperCase(Locale.ROOT));
        }
        return keys;
    }
}
