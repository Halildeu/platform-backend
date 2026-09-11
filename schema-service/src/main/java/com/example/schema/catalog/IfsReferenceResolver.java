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
                         int duplicates, int resolvedViaLuIndex, int ambiguousTarget,
                         int resolvedViaKeyShape) {}

    /**
     * A view that declares itself as the LU's view in its own comment ({@code LU=Name^…^TABLE=X_TAB^}).
     * {@code base} is true when its TABLE entry is {@code <VIEW>_TAB} — the LU's primary view.
     */
    public record LuView(String view, boolean base, boolean filtered) {
        public LuView(String view, boolean base) { this(view, base, false); }
    }

    /** {@code Lu}, {@code Lu(a,b)}, {@code Lu/NOCHECK}, {@code Lu(a,b)/CUSTOM=(…)}. */
    record Reference(String logicalUnit, List<String> parentColumns, String tail) {}

    private static final Pattern REF_HEAD = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*(?:\\(([^)]*)\\))?\\s*$");

    private IfsReferenceResolver() {}

    /**
     * {@code AbsLegalBase} → {@code ABS_LEGAL_BASE}; digits stay attached ({@code Bill2Line} →
     * {@code BILL2_LINE}). A name with no lower-case letter is already the view name
     * ({@code ACCOUNTING_YEAR}, {@code ALL_LEDGER}): 346 live references write it that way and
     * the letter-by-letter split turned them into {@code A_C_C_…} (gitops#3643).
     */
    public static String luToViewName(String lu) {
        String trimmed = lu.trim();
        boolean anyLower = false;
        for (int i = 0; i < trimmed.length(); i++) if (Character.isLowerCase(trimmed.charAt(i))) { anyLower = true; break; }
        if (!anyLower) return trimmed.toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && trimmed.charAt(i - 1) != '_') out.append('_');
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }

    /** Where an LU name lands: a view, nothing, or several views none of which is the base. */
    record Target(String view, boolean viaIndex, boolean ambiguous, List<LuView> candidates) {
        static final Target NONE = new Target(null, false, false, List.of());
        static Target ambiguousAmong(List<LuView> known) {
            return new Target(null, true, true, List.copyOf(known));
        }
    }

    /**
     * The view an LU name stands for: the name rule first (5,212 of 5,214 LU-declaring views
     * agree with it), then the {@code LU=} index — one candidate, or the one whose TABLE entry
     * marks it as the base view; several non-base candidates are ambiguous, not guessed
     * (live: 381 references reach the index, 103 unambiguous, 278 ambiguous).
     */
    static Target targetView(String lu, Set<String> views, Map<String, List<LuView>> luIndex) {
        String byName = luToViewName(lu);
        if (views.contains(byName)) return new Target(byName, false, false, List.of());
        List<LuView> candidates = luIndex == null ? List.of() : luIndex.getOrDefault(lu, List.of());
        List<LuView> known = new ArrayList<>();
        for (LuView c : candidates) if (views.contains(c.view())) known.add(c);
        if (known.size() == 1) return new Target(known.getFirst().view(), true, false, List.of());
        List<LuView> base = new ArrayList<>();
        for (LuView c : known) if (c.base()) base.add(c);
        if (base.size() == 1) return new Target(base.getFirst().view(), true, false, List.of());
        return known.isEmpty() ? Target.NONE : Target.ambiguousAmong(known);
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
        return resolve(owner, viewColumns, Map.of());
    }

    /**
     * @param luIndex LU name → the views whose own comment declares that LU ({@code LU=…}),
     *                consulted only when the name rule finds no view
     */
    public static Result resolve(String owner, Map<String, List<ColumnMeta>> viewColumns,
                                 Map<String, List<LuView>> luIndex) {
        Map<String, List<KeyColumn>> keysByView = new LinkedHashMap<>();
        Map<String, List<String>> keyColumnsByView = new LinkedHashMap<>();
        for (Map.Entry<String, List<ColumnMeta>> e : viewColumns.entrySet()) {
            List<KeyColumn> keys = keyColumns(e.getValue());
            keysByView.put(e.getKey(), keys);
            keyColumnsByView.put(e.getKey(), keys.stream().map(KeyColumn::name).toList());
        }
        Set<String> views = keyColumnsByView.keySet();

        Map<String, ForeignKeyInfo> byIdentity = new LinkedHashMap<>();
        int references = 0;
        int unresolvedTarget = 0;
        int unresolvedShape = 0;
        int unresolvedSource = 0;
        int duplicates = 0;
        int viaIndex = 0;
        int ambiguous = 0;
        int viaShape = 0;
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
                String column = col.name().toUpperCase(Locale.ROOT);
                Target target = targetView(ref.logicalUnit(), views, luIndex);
                Attempt attempt;
                boolean byShape = false;
                if (target.ambiguous()) {
                    // Several views declare the LU and none is the base: the one whose key the
                    // reference's column shape fits is the target — live, 66 of 278 ambiguous
                    // references have exactly one such candidate (gitops#3651). Several or none:
                    // still ambiguous, not guessed.
                    // A candidate that is this very view (identity) still fits the key: it is
                    // counted as a fit so that it cannot make the other candidate look unique
                    // (Codex 01a08f06 #1); only a sole fit is taken, and a sole identity fit is
                    // the identity case, not a join. A filtered projection (a top-level WHERE in
                    // its definition) is never a candidate (#2): live, every one of the 66 sole
                    // key-shape fits was filtered while 75% of name-rule targets are not — a LOV
                    // that fits the key is still not the LU's view. Exactly one unfiltered fit
                    // resolves (live: 8 references), otherwise the reference stays ambiguous.
                    List<Attempt> fits = new ArrayList<>();
                    for (LuView candidate : target.candidates()) {
                        if (candidate.filtered()) continue;   // a LOV over the LU, never the LU's view
                        Attempt a = attempt(view, viewColumnNames, column, ref, candidate.view(),
                            keyColumnsByView.get(candidate.view()), keysByView.get(candidate.view()));
                        if (a.status() == Status.OK || a.status() == Status.IDENTITY) fits.add(a);
                    }
                    if (fits.size() != 1) { ambiguous++; continue; }
                    attempt = fits.getFirst();
                    if (attempt.status() == Status.IDENTITY) { unresolvedShape++; continue; }
                    byShape = true;
                } else {
                    if (target.view() == null) { unresolvedTarget++; continue; }
                    attempt = attempt(view, viewColumnNames, column, ref, target.view(),
                        keyColumnsByView.get(target.view()), keysByView.get(target.view()));
                    if (attempt.status() == Status.SOURCE) { unresolvedSource++; continue; }
                    if (attempt.status() == Status.SHAPE || attempt.status() == Status.IDENTITY) { unresolvedShape++; continue; }
                }

                ForeignKeyInfo fk = new ForeignKeyInfo(
                    "IFS_REF_" + view + "." + column,
                    owner, view, List.copyOf(attempt.fromOrdered()),
                    owner, attempt.targetView(), List.copyOf(attempt.targetKey()),
                    false,
                    true,           // not enforced as a constraint the account can see
                    "NO ACTION", "NO ACTION");
                String identity = view + "|" + attempt.fromOrdered() + "|" + attempt.targetView() + "|" + attempt.targetKey();
                if (byIdentity.putIfAbsent(identity, fk) != null) duplicates++;
                else if (byShape) viaShape++;
                else if (target.viaIndex()) viaIndex++;   // keys the LU= index produced, not merely targets it named
            }
        }
        return new Result(List.copyOf(byIdentity.values()), references, byIdentity.size(),
            unresolvedTarget, unresolvedShape, unresolvedSource, duplicates, viaIndex, ambiguous, viaShape);
    }

    /** {@code IDENTITY}: the key fits but maps every column onto itself — a fit for choosing a target, never a join. */
    enum Status { OK, IDENTITY, SOURCE, SHAPE }

    /** One reference tried against one target view: the pairs in target key order, or why not. */
    record Attempt(Status status, String targetView, List<String> targetKey, List<String> fromOrdered) {
        static Attempt fail(Status status) { return new Attempt(status, null, List.of(), List.of()); }
    }

    static Attempt attempt(String view, Set<String> viewColumnNames, String column, Reference ref,
                           String targetView, List<String> targetKey, List<KeyColumn> targetKeys) {
        if (targetKey == null || targetKey.isEmpty()) return Attempt.fail(Status.SHAPE);
        List<String> source = new ArrayList<>(ref.parentColumns());
        if (new HashSet<>(source).size() != source.size() || source.contains(column)) return Attempt.fail(Status.SOURCE);
        for (String s : source) if (!viewColumnNames.contains(s)) return Attempt.fail(Status.SOURCE);
        if (source.isEmpty() && targetKey.size() > 1) {
            // Implicit parent part: the other key columns of the target, by name, from this
            // view. The referencing column takes the target's own key: itself when it carries
            // that name, else the target's single K column; with no or several K columns the
            // choice would be a guess (Codex 01a08afc iter-2 #2), so it is counted instead.
            String own = ownKey(column, targetKeys);
            if (own == null) return Attempt.fail(Status.SHAPE);
            for (String k : targetKey) {
                if (k.equals(own)) continue;
                if (!viewColumnNames.contains(k)) return Attempt.fail(Status.SOURCE);
                source.add(k);
            }
        }
        source.add(column);
        if (source.size() != targetKey.size()) return Attempt.fail(Status.SHAPE);
        List<String> toColumns = pair(source, targetKey);
        if (toColumns == null) return Attempt.fail(Status.SHAPE);
        // Emit in target key order so the last pair is the target's own key.
        List<String> fromOrdered = new ArrayList<>(toColumns.size());
        for (String t : targetKey) fromOrdered.add(source.get(toColumns.indexOf(t)));
        if (targetView.equals(view) && fromOrdered.equals(targetKey)) return new Attempt(Status.IDENTITY, targetView, targetKey, fromOrdered);
        return new Attempt(Status.OK, targetView, targetKey, fromOrdered);
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

    /** A key column of a view with its {@code FLAGS} class: {@code 'K'} own, {@code 'P'} parent. */
    record KeyColumn(String name, char keyClass) {}

    /** The view's key columns ({@code P} or {@code K}) in column order. */
    static List<KeyColumn> keyColumns(List<ColumnMeta> columns) {
        List<ColumnMeta> sorted = new ArrayList<>(columns);
        sorted.sort((a, b) -> Integer.compare(a.ordinal(), b.ordinal()));
        List<KeyColumn> keys = new ArrayList<>();
        for (ColumnMeta c : sorted) {
            if (c.comment() == null) continue;
            char k = c.comment().keyClass();
            if (k == 'P' || k == 'K') keys.add(new KeyColumn(c.name().toUpperCase(Locale.ROOT), k));
        }
        return keys;
    }

    /** Names only, in column order. */
    static List<String> keyNames(List<ColumnMeta> columns) {
        return keyColumns(columns).stream().map(KeyColumn::name).toList();
    }

    /**
     * Which target key column a parenthesis-less referencing column stands for: the column
     * of the same name, a single-column key, or the target's one own ({@code K}) column.
     * Null when that is ambiguous (no K, or several).
     */
    static String ownKey(String column, List<KeyColumn> targetKeys) {
        for (KeyColumn k : targetKeys) if (k.name().equals(column)) return column;
        if (targetKeys.size() == 1) return targetKeys.getFirst().name();
        String own = null;
        for (KeyColumn k : targetKeys) {
            if (k.keyClass() != 'K') continue;
            if (own != null) return null;
            own = k.name();
        }
        return own;
    }
}
