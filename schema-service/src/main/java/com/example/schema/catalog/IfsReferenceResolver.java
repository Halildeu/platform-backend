package com.example.schema.catalog;

import com.example.schema.model.ForeignKeyInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns IFS {@code REF=} column metadata into foreign keys (gitops#3631, slice 2).
 *
 * <p>The reporting account sees IFS views, never the {@code _TAB} tables that carry the
 * constraints, so {@code ALL_CONSTRAINTS} answers nothing for it. IFS wrote the same
 * fact into every view column's comment: {@code REF=Site} means "this column holds the
 * key of the Site logical unit"; {@code REF=AbsenceRegistration(company_id,emp_no)} means
 * the reference is composite and the named columns of THIS view carry the parent part of
 * the target's key. Measured live: 28,440 such columns across 8,296 of 10,885 views.
 *
 * <p>Resolution, all from the dictionary and nothing invented:
 * <ul>
 *   <li>target view = the LU name in UPPER_SNAKE ({@code CompanyFinance} → {@code COMPANY_FINANCE});
 *       a name that is not a view of this owner is left unresolved and counted;</li>
 *   <li>target key = the target view's key columns in column order, parent keys
 *       ({@code FLAGS} class {@code P}) first, then its own ({@code K}) — the way IFS
 *       composes a child LU's key;</li>
 *   <li>source columns = the parenthesised parent-key columns, then the referencing column;
 *       when the counts match it is a composite key, when the target has a single own key
 *       and no parent part it is a plain one, otherwise it is unresolved rather than guessed.</li>
 * </ul>
 *
 * <p>The result is a {@link ForeignKeyInfo} marked {@code isNotTrusted=true}: IFS does not
 * enforce it as a constraint in the dictionary the account can see, and the flag says so.
 */
public final class IfsReferenceResolver {

    /** One view column as the resolver needs it: name, dictionary order, parsed comment. */
    public record ColumnMeta(String name, int ordinal, IfsColumnComment comment) {}

    /** What resolution produced, with the counts a log line and a test want. */
    public record Result(List<ForeignKeyInfo> foreignKeys, int references, int resolved,
                         int unresolvedTarget, int unresolvedKeyShape) {}

    private static final Pattern REF_SHAPE = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*(?:\\(([^)]*)\\))?\\s*$");

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

    /**
     * @param owner       schema owner, used for both sides of every key
     * @param viewColumns view name → its columns in dictionary order
     */
    public static Result resolve(String owner, Map<String, List<ColumnMeta>> viewColumns) {
        Map<String, List<String>> keyColumnsByView = new LinkedHashMap<>();
        for (Map.Entry<String, List<ColumnMeta>> e : viewColumns.entrySet()) {
            keyColumnsByView.put(e.getKey(), keyColumns(e.getValue()));
        }

        List<ForeignKeyInfo> keys = new ArrayList<>();
        int references = 0;
        int unresolvedTarget = 0;
        int unresolvedShape = 0;
        for (Map.Entry<String, List<ColumnMeta>> e : viewColumns.entrySet()) {
            String view = e.getKey();
            for (ColumnMeta col : e.getValue()) {
                String ref = col.comment() == null ? null : col.comment().reference();
                if (ref == null) continue;
                references++;
                Matcher m = REF_SHAPE.matcher(ref);
                if (!m.matches()) { unresolvedShape++; continue; }
                String targetView = luToViewName(m.group(1));
                List<String> targetKey = keyColumnsByView.get(targetView);
                if (targetKey == null) { unresolvedTarget++; continue; }
                if (targetView.equals(view)) { unresolvedShape++; continue; } // self-reference through a parent-child LU pair is not a join here

                List<String> fromColumns = new ArrayList<>();
                if (m.group(2) != null && !m.group(2).isBlank()) {
                    for (String part : m.group(2).split(",")) {
                        String p = part.trim().toUpperCase(Locale.ROOT);
                        if (!p.isEmpty()) fromColumns.add(p);
                    }
                }
                fromColumns.add(col.name().toUpperCase(Locale.ROOT));

                List<String> toColumns;
                if (targetKey.size() == fromColumns.size()) {
                    toColumns = targetKey;
                } else if (fromColumns.size() == 1 && targetKey.size() == 1) {
                    toColumns = targetKey;
                } else {
                    unresolvedShape++;
                    continue;
                }
                keys.add(new ForeignKeyInfo(
                    "IFS_REF_" + view + "_" + col.name().toUpperCase(Locale.ROOT),
                    owner, view, List.copyOf(fromColumns),
                    owner, targetView, List.copyOf(toColumns),
                    false,
                    true,           // not enforced as a constraint the account can see
                    "NO ACTION", "NO ACTION"));
            }
        }
        return new Result(List.copyOf(keys), references, keys.size(), unresolvedTarget, unresolvedShape);
    }

    /** Parent keys first, then own keys, each in column order — IFS's composition of a child key. */
    static List<String> keyColumns(List<ColumnMeta> columns) {
        List<ColumnMeta> sorted = new ArrayList<>(columns);
        sorted.sort((a, b) -> Integer.compare(a.ordinal(), b.ordinal()));
        List<String> parent = new ArrayList<>();
        List<String> own = new ArrayList<>();
        for (ColumnMeta c : sorted) {
            if (c.comment() == null) continue;
            char k = c.comment().keyClass();
            if (k == 'P') parent.add(c.name().toUpperCase(Locale.ROOT));
            else if (k == 'K') own.add(c.name().toUpperCase(Locale.ROOT));
        }
        parent.addAll(own);
        return parent;
    }
}
