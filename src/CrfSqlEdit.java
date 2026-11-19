import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.RexObjectList;
import com.clipsoft.clipreport.base.datas.DataSet;
import com.clipsoft.clipreport.base.datas.DataSetItemNormal;
import com.clipsoft.clipreport.base.datas.DataAccessMethodSQL;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;

/**
 * Headless SQL editor for ClipReport v5 .crf report templates.
 *
 * Uses the ClipReport engine's own public API
 * (com.clipsoft.clipreport.base.Rexpert4) to load a .crf into the object model,
 * read or change the SQL text of its datasets, and produce a new .crf. The engine
 * handles the binary REX file format and the field-level SQL encryption, so no
 * reverse engineering is involved. The required ClipReport engine jars are NOT
 * bundled -- supply your own from a licensed ClipReport v5 installation.
 *
 *   list <in.crf>
 *       Print every dataset and its current (decrypted) SQL length / preview.
 *
 *   dump <in.crf> <outDir>
 *       Write each SQL dataset's query to <outDir>/<dataset>.sql
 *       (plus <dataset>.pre.sql / <dataset>.post.sql when non-empty), UTF-8.
 *
 *   patch <in.crf> <out.crf> <dataset>=<sqlFile> [<dataset2>=<sqlFile2> ...]
 *       Replace the query of each named dataset and change NOTHING else: the output
 *       is the input file with only those datasets' stored SQL records swapped
 *       (the records themselves are produced by the engine). The result is read back
 *       and must differ from the input in exactly those query strings.
 *
 *   diff <a.crf> <b.crf>
 *       Load both reports and list every value in which the two models differ.
 *       Exit code 0 when they are the same, 1 when they differ.
 *
 *   set  <in.crf> <out.crf> <dataset>=<sqlFile> [<dataset2>=<sqlFile2> ...]
 *       Replace SQL by letting the engine rewrite the whole report. Also takes
 *       <dataset>.pre=<file> / <dataset>.post=<file> for Pre/Post SQL. The engine's
 *       writer does not write back everything it read, so prefer patch.
 *
 * The input file is never modified in place; writes always go to <out.crf>.
 * Datasets that are not SQL-based (stored procedure / CSV / XML / JSON / SAP)
 * are left untouched.
 *
 * Engine jars: ClipReport 4 (ClipReport4.0-*.jar) and 5 (ClipReport5.0*.jar) ship the same
 * package names, so the first on the classpath answers. list, dump and diff work with either.
 * patch needs the version 4 writer: it stores SQL encrypted, as the Designer does, so its records
 * can be found in and swapped into a Designer-saved file. The version 5 writer stores SQL as plain
 * text, which matches nothing in such a file; patch then stops and says so. The wrappers
 * (crf-sql.ps1, crf-sql.sh) put the version 4 jar first.
 */
public class CrfSqlEdit {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { usage(); System.exit(2); return; }
        String cmd = args[0];
        try {
            if ("list".equals(cmd)) {
                list(args[1]);
            } else if ("dump".equals(cmd)) {
                if (args.length < 3) { usage(); System.exit(2); return; }
                dump(args[1], args[2]);
            } else if ("set".equals(cmd)) {
                if (args.length < 4) { usage(); System.exit(2); return; }
                set(args[1], args[2], Arrays.copyOfRange(args, 3, args.length));
            } else if ("patch".equals(cmd)) {
                if (args.length < 4) { usage(); System.exit(2); return; }
                patch(args[1], args[2], Arrays.copyOfRange(args, 3, args.length));
            } else if ("diff".equals(cmd)) {
                if (args.length < 3) { usage(); System.exit(2); return; }
                diff(args[1], args[2]);
            } else {
                usage(); System.exit(2);
            }
        } catch (IllegalArgumentException e) {
            System.out.println("error: " + e.getMessage());
            System.exit(1);
        } catch (IllegalStateException e) {
            System.out.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    static void usage() {
        System.out.println("usage:\n"
                + "  CrfSqlEdit list  <in.crf>\n"
                + "  CrfSqlEdit dump  <in.crf> <outDir>\n"
                + "  CrfSqlEdit patch <in.crf> <out.crf> <dataset>=<sqlFile> [...]\n"
                + "       replace SQL and nothing else (recommended)\n"
                + "  CrfSqlEdit diff  <a.crf> <b.crf>\n"
                + "       list every value in which two reports differ\n"
                + "  CrfSqlEdit set   <in.crf> <out.crf> <dataset>=<sqlFile> [...]\n"
                + "       full engine rewrite (also <dataset>.pre=<file> / <dataset>.post=<file>)");
    }

    // ---- list ----
    static void list(String in) throws Exception {
        TheReportFile rf = Rexpert4.read(in);
        RexObjectList dl = rf.getGlobe().getGlobalObjectManager().getDataSetList();
        System.out.println("report=" + in + "  version=" + rf.getVersion() + "  datasets=" + dl.size());
        for (int i = 0; i < dl.size(); i++) {
            DataSet ds = (DataSet) dl.get(i);
            DataAccessMethodSQL s = sqlOf(ds);
            String q = s == null ? null : s.getQueryString();
            System.out.println(String.format("  [%d] %-16s type=%s access=%s sqlLen=%s",
                    i, ds.getName(), ds.getDataSetType(), accessOf(ds), q == null ? "n/a" : q.length()));
            if (q != null) System.out.println("        " + firstLine(q));
        }
    }

    // ---- dump ----
    static void dump(String in, String outDir) throws Exception {
        TheReportFile rf = Rexpert4.read(in);
        RexObjectList dl = rf.getGlobe().getGlobalObjectManager().getDataSetList();
        Path dir = Paths.get(outDir);
        Files.createDirectories(dir);
        int n = 0;
        for (int i = 0; i < dl.size(); i++) {
            DataSet ds = (DataSet) dl.get(i);
            DataAccessMethodSQL s = sqlOf(ds);
            if (s == null) continue;
            writeText(dir.resolve(ds.getName() + ".sql"), nz(s.getQueryString()));
            n++;
            if (notEmpty(s.getPreSQL()))  writeText(dir.resolve(ds.getName() + ".pre.sql"),  s.getPreSQL());
            if (notEmpty(s.getPostSQL())) writeText(dir.resolve(ds.getName() + ".post.sql"), s.getPostSQL());
            System.out.println("  dumped " + ds.getName() + " (" + nz(s.getQueryString()).length() + " chars)");
        }
        System.out.println("dumped " + n + " SQL dataset(s) to " + dir.toAbsolutePath());
    }

    // ---- set ----
    static void set(String in, String out, String[] specs) throws Exception {
        Map<String, String> q = new LinkedHashMap<String, String>();
        Map<String, String> pre = new LinkedHashMap<String, String>();
        Map<String, String> post = new LinkedHashMap<String, String>();
        for (String spec : specs) {
            int eq = spec.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("bad spec (need name=file): " + spec);
            String key = spec.substring(0, eq).trim();
            String file = spec.substring(eq + 1).trim();
            String text = readText(Paths.get(file));
            if (key.endsWith(".pre"))        pre.put(key.substring(0, key.length() - 4), text);
            else if (key.endsWith(".post"))  post.put(key.substring(0, key.length() - 5), text);
            else                              q.put(key, text);
        }

        TheReportFile rf = Rexpert4.read(in);
        RexObjectList dl = rf.getGlobe().getGlobalObjectManager().getDataSetList();
        Map<String, DataAccessMethodSQL> byName = new LinkedHashMap<String, DataAccessMethodSQL>();
        for (int i = 0; i < dl.size(); i++) {
            DataSet ds = (DataSet) dl.get(i);
            DataAccessMethodSQL s = sqlOf(ds);
            if (s != null) byName.put(ds.getName(), s);
        }

        for (String name : union(q.keySet(), pre.keySet(), post.keySet()))
            if (!byName.containsKey(name))
                throw new IllegalArgumentException("no SQL dataset named '" + name + "' (have: " + byName.keySet() + ")");

        for (Map.Entry<String, String> e : q.entrySet()) {
            DataAccessMethodSQL s = byName.get(e.getKey());
            int oldLen = nz(s.getQueryString()).length();
            s.setQueryString(e.getValue());
            System.out.println("  set " + e.getKey() + ".query  " + oldLen + " -> " + e.getValue().length() + " chars");
        }
        for (Map.Entry<String, String> e : pre.entrySet())  { byName.get(e.getKey()).setPreSQL(e.getValue());  System.out.println("  set " + e.getKey() + ".preSQL");  }
        for (Map.Entry<String, String> e : post.entrySet()) { byName.get(e.getKey()).setPostSQL(e.getValue()); System.out.println("  set " + e.getKey() + ".postSQL"); }

        Rexpert4.write(rf, out);
        System.out.println("wrote " + out + " (" + new File(out).length() + " bytes)");

        // verify by re-reading the written file
        TheReportFile chk = Rexpert4.read(out);
        RexObjectList cdl = chk.getGlobe().getGlobalObjectManager().getDataSetList();
        Map<String, String> after = new LinkedHashMap<String, String>();
        for (int i = 0; i < cdl.size(); i++) {
            DataSet ds = (DataSet) cdl.get(i);
            DataAccessMethodSQL s = sqlOf(ds);
            if (s != null) after.put(ds.getName(), nz(s.getQueryString()));
        }
        boolean ok = true;
        for (Map.Entry<String, String> e : q.entrySet()) {
            boolean match = e.getValue().equals(after.get(e.getKey()));
            ok &= match;
            System.out.println("  verify " + e.getKey() + ": " + (match ? "OK" : "MISMATCH"));
        }
        System.out.println(ok ? "[VERIFY PASS] all SQL changes round-tripped through write/read"
                              : "[VERIFY FAIL] a change did not persist");
        if (!ok) System.exit(1);
        System.out.println("WARNING: set lets the engine (" + engineJar() + ") rewrite the whole report, and the engine's"
                + " writer does not write back everything it read (seen on Designer-saved reports: table split-line"
                + " settings, line widths and the check/radio images are reset). To change SQL and nothing else use"
                + " patch; to see what this rewrite changed run: diff <in.crf> <out.crf>");
    }

    // ---- patch ----
    static void patch(String in, String out, String[] specs) throws Exception {
        if (new File(in).getCanonicalFile().equals(new File(out).getCanonicalFile()))
            throw new IllegalArgumentException("the output file must not be the input file (the input is never modified in place)");
        Map<String, String> q = new LinkedHashMap<String, String>();
        for (String spec : specs) {
            int eq = spec.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("bad spec (need name=file): " + spec);
            String key = spec.substring(0, eq).trim();
            if (key.endsWith(".pre") || key.endsWith(".post"))
                throw new IllegalArgumentException("patch changes query SQL only; for Pre/Post SQL use set (full rewrite): " + spec);
            q.put(key, readText(Paths.get(spec.substring(eq + 1).trim())));
        }

        byte[] original = Files.readAllBytes(Paths.get(in));
        // Scratch model: only used to let the engine produce the stored form of the new SQL.
        TheReportFile rf = Rexpert4.read(original);
        Map<String, DataAccessMethodSQL> byName = sqlByName(rf);
        for (String name : q.keySet())
            if (!byName.containsKey(name))
                throw new IllegalArgumentException("no SQL dataset named '" + name + "' (have: " + byName.keySet() + ")");

        Map<String, String> expected = new LinkedHashMap<String, String>();   // what each dataset must hold afterwards
        for (Map.Entry<String, DataAccessMethodSQL> e : byName.entrySet())
            expected.put(e.getKey(), nz(e.getValue().getQueryString()));

        byte[] baseline = Rexpert4.write(rf);   // the engine's serialisation with the SQL untouched
        // The records swapped in below come from the loaded engine's writer, so they must be stored the way the
        // Designer stores them. Without this check a plain-text writer surfaces only as the misleading
        // "changes in more places" error further down.
        for (String name : q.keySet())
            if (writesPlainText(baseline, nz(byName.get(name).getQueryString())))
                throw new IllegalStateException("cannot patch '" + name + "' with this engine (" + engineJar() + "):"
                        + " its writer stores SQL as plain text, unlike the Designer, so the stored SQL record cannot be"
                        + " matched in the input. Put the ClipReport 4 engine jar (ClipReport4.0-*.jar) before the"
                        + " version 5 jars on the classpath (crf-sql.ps1 and crf-sql.sh do this); list, dump and diff"
                        + " work with either engine");
        byte[] work = original;
        int changed = 0;
        for (Map.Entry<String, String> e : q.entrySet()) {
            String name = e.getKey();
            DataAccessMethodSQL s = byName.get(name);
            String oldSql = nz(s.getQueryString());
            String newSql = e.getValue();
            if (newSql.equals(oldSql)) { System.out.println("  " + name + ".query unchanged"); continue; }
            if (oldSql.length() == 0 || newSql.length() == 0)
                throw new IllegalArgumentException("patch cannot add or remove a query (dataset '" + name + "'); use set (full rewrite)");
            int bare = bareLineEndings(newSql);
            if (bare > 0 && bareLineEndings(oldSql) == 0)
                System.out.println("  NOTE " + name + ": the new SQL has " + bare + " line ending(s) that are not CRLF;"
                        + " the stored SQL has none (stored exactly as given)");
            if (newSql.length() % 4 == 0) {
                // Text that exactly fills its 8-byte blocks: the Designer stores one more block (the terminator),
                // the engine does not. One more character avoids the case, so the record is what the Designer writes.
                newSql = newSql + " ";
                System.out.println("  NOTE " + name + ": SQL length is a multiple of 4 characters; added one trailing space"
                        + " so the stored record has the layout the Designer itself writes");
                if (newSql.equals(oldSql)) { System.out.println("  " + name + ".query unchanged"); continue; }
            }
            s.setQueryString(newSql);
            byte[] rewritten = Rexpert4.write(rf);
            s.setQueryString(oldSql);
            int[] rec = locateRecord(baseline, rewritten, oldSql.length(), newSql.length());
            if (rec == null)
                throw new IllegalStateException("cannot patch '" + name + "': the engine's output changes in more places than"
                        + " this dataset's SQL record");
            expected.put(name, newSql);
            work = replaceRecord(work, name,
                    Arrays.copyOfRange(baseline, rec[0] + 4, rec[0] + 4 + rec[1]),
                    Arrays.copyOfRange(rewritten, rec[0], rec[0] + 4 + rec[2]),
                    oldSql.length() % 4 == 0, expected);
            changed++;
            System.out.println("  patch " + name + ".query  " + oldSql.length() + " -> " + newSql.length() + " chars");
        }

        if (changed == 0) {
            Files.write(Paths.get(out), original);
            System.out.println("no SQL changed; wrote a byte-identical copy to " + out);
            return;
        }

        // Verify the exact bytes about to be written: read them back and compare the whole model with the input's.
        TheReportFile check = Rexpert4.read(work);
        boolean ok = sqlEquals(check, expected);
        ModelDiff d = new ModelDiff();
        d.compare(Rexpert4.read(original), check);
        boolean onlySql = d.differences.size() == changed;
        for (String[] x : d.differences)
            onlySql &= "DataAccessMethodSQL".equals(x[1]) && "queryString".equals(x[2]);
        if (!ok || !onlySql) {
            if (!onlySql) d.print(20);
            System.out.println("[VERIFY FAIL] " + (ok ? "the patched report differs from the input in more than the requested SQL"
                                                      : "the patched report does not read back with the requested SQL")
                    + "; nothing written");
            System.exit(1);
        }
        Files.write(Paths.get(out), work);
        System.out.println("wrote " + out + " (" + work.length + " bytes, input " + original.length + ")");
        System.out.println("[VERIFY PASS] the engine reads back the new SQL, and the report differs from the input only in "
                + changed + " query string(s)");
    }

    // ---- diff ----
    static void diff(String a, String b) throws Exception {
        ModelDiff d = new ModelDiff();
        d.compare(Rexpert4.read(a), Rexpert4.read(b));
        d.print(40);
        if (!d.differences.isEmpty()) System.exit(1);
    }

    static Map<String, DataAccessMethodSQL> sqlByName(TheReportFile rf) {
        RexObjectList dl = rf.getGlobe().getGlobalObjectManager().getDataSetList();
        Map<String, DataAccessMethodSQL> byName = new LinkedHashMap<String, DataAccessMethodSQL>();
        for (int i = 0; i < dl.size(); i++) {
            DataSet ds = (DataSet) dl.get(i);
            DataAccessMethodSQL s = sqlOf(ds);
            if (s != null) byName.put(ds.getName(), s);
        }
        return byName;
    }

    static boolean sqlEquals(TheReportFile rf, Map<String, String> expected) {
        Map<String, DataAccessMethodSQL> have = sqlByName(rf);
        if (!have.keySet().equals(expected.keySet())) return false;
        for (Map.Entry<String, String> e : expected.entrySet())
            if (!e.getValue().equals(nz(have.get(e.getKey()).getQueryString()))) return false;
        return true;
    }

    /**
     * Finds the one stored SQL record in which two engine serialisations of the same report differ.
     * A record is a 4-byte little-endian length followed by that many bytes (the encrypted UTF-16LE
     * text, padded to the 8-byte cipher block). Returns {offset, oldLength, newLength}, or null when
     * the two serialisations differ anywhere outside that record.
     */
    static int[] locateRecord(byte[] a, byte[] b, int oldChars, int newChars) {
        int la = pad8(oldChars * 2), lb = pad8(newChars * 2);
        int n = Math.min(a.length, b.length), p = 0;
        while (p < n && a[p] == b[p]) p++;
        for (int at = Math.min(p, n - 4); at >= 0 && at > p - 4 - la; at--) {
            if (le32(a, at) != la || le32(b, at) != lb) continue;
            int ta = at + 4 + la, tb = at + 4 + lb;
            if (ta > a.length || tb > b.length || a.length - ta != b.length - tb) continue;
            if (regionEquals(a, ta, b, tb, a.length - ta)) return new int[]{at, la, lb};
        }
        return null;
    }

    /**
     * In the report's own bytes, replaces the stored record whose payload is {@code oldPayload} with
     * {@code newRecord} (length prefix + payload). Every other byte is copied through unchanged.
     */
    static byte[] replaceRecord(byte[] file, String name, byte[] oldPayload, byte[] newRecord,
                                boolean oldFillsItsBlocks, Map<String, String> expected) {
        byte[] result = null;
        int hits = 0;
        for (int i = indexOf(file, oldPayload, 4); i >= 0; i = indexOf(file, oldPayload, i + 1)) {
            int stored = le32(file, i - 4);
            // The Designer stores one more block (the terminator) when the text exactly fills its blocks.
            if (stored != oldPayload.length && !(oldFillsItsBlocks && stored == oldPayload.length + 8)) continue;
            if (i + stored > file.length) continue;
            hits++;
            byte[] candidate = new byte[file.length - 4 - stored + newRecord.length];
            System.arraycopy(file, 0, candidate, 0, i - 4);
            System.arraycopy(newRecord, 0, candidate, i - 4, newRecord.length);
            System.arraycopy(file, i + stored, candidate, i - 4 + newRecord.length, file.length - i - stored);
            if (result == null && readsAs(candidate, expected)) result = candidate;
        }
        if (result == null)
            throw new IllegalStateException(hits == 0
                    ? "cannot patch '" + name + "': its stored SQL record was not found in the input file"
                    : "cannot patch '" + name + "': replacing its stored SQL record did not give the expected SQL");
        return result;
    }

    /**
     * True when the start of the SQL appears as plain UTF-16LE text in an engine serialisation. The ClipReport 5
     * writer stores query strings this way; the ClipReport 4 writer and the Designer store them encrypted.
     */
    static boolean writesPlainText(byte[] serialised, String sql) {
        String probe = sql.trim();
        if (probe.length() < 16) return false;   // too short to tell a query from other text
        probe = probe.substring(0, Math.min(48, probe.length()));
        return indexOf(serialised, probe.getBytes(StandardCharsets.UTF_16LE), 0) >= 0;
    }

    /** File name of the jar the engine class was loaded from (version 4 and 5 jars share package names). */
    static String engineJar() {
        try {
            java.security.CodeSource cs = Rexpert4.class.getProtectionDomain().getCodeSource();
            return cs == null ? "unknown jar" : new File(cs.getLocation().toURI()).getName();
        } catch (Exception e) {
            return "unknown jar";
        }
    }

    static boolean readsAs(byte[] report, Map<String, String> expected) {
        try { return sqlEquals(Rexpert4.read(report), expected); }
        catch (Exception e) { return false; }
    }

    static int pad8(int n) { return (n + 7) / 8 * 8; }
    static int le32(byte[] x, int o) {
        return (x[o] & 0xff) | (x[o + 1] & 0xff) << 8 | (x[o + 2] & 0xff) << 16 | (x[o + 3] & 0xff) << 24;
    }
    static boolean regionEquals(byte[] a, int ao, byte[] b, int bo, int len) {
        for (int i = 0; i < len; i++) if (a[ao + i] != b[bo + i]) return false;
        return true;
    }
    static int indexOf(byte[] hay, byte[] needle, int from) {
        if (needle.length == 0) return -1;
        for (int i = Math.max(from, 0); i + needle.length <= hay.length; i++)
            if (hay[i] == needle[0] && regionEquals(hay, i, needle, 0, needle.length)) return i;
        return -1;
    }

    /**
     * Value-by-value comparison of two reports as the engine loaded them. Walks both object graphs in
     * parallel and records every position where a value differs: {path, owner class, field, A, B}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class ModelDiff {
        final List<String[]> differences = new ArrayList<String[]>();
        long objects = 0;
        private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        private final ArrayDeque<Object[]> work = new ArrayDeque<Object[]>();   // {a, b, path, owner, field}
        private final Map<Class, Field[]> fieldCache = new HashMap<Class, Field[]>();

        void compare(Object a, Object b) throws Exception {
            work.push(new Object[]{a, b, "report", "", ""});
            while (!work.isEmpty()) {
                Object[] w = work.pop();
                step(w[0], w[1], (String) w[2], (String) w[3], (String) w[4]);
            }
        }

        void print(int max) {
            for (int i = 0; i < differences.size() && i < max; i++) {
                String[] x = differences.get(i);
                System.out.println("DIFF " + x[1] + "." + x[2] + "  at " + x[0]);
                System.out.println("     A=" + x[3]);
                System.out.println("     B=" + x[4]);
            }
            if (differences.size() > max) System.out.println("  ... " + (differences.size() - max) + " more not shown");
            if (!differences.isEmpty()) {
                Map<String, Integer> by = new TreeMap<String, Integer>();
                for (String[] x : differences) {
                    String k = x[1] + "." + x[2];
                    Integer n = by.get(k);
                    by.put(k, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
                }
                System.out.println("by property:");
                for (Map.Entry<String, Integer> e : by.entrySet()) System.out.println("  x" + e.getValue() + "  " + e.getKey());
            }
            System.out.println("objects compared: " + objects);
            System.out.println("differences: " + differences.size());
        }

        private void add(String path, String owner, String field, Object a, Object b) {
            String sa, sb;
            if (a instanceof String && b instanceof String) {
                String x = (String) a, y = (String) b;
                int i = 0, m = Math.min(x.length(), y.length());
                while (i < m && x.charAt(i) == y.charAt(i)) i++;
                sa = "(" + x.length() + " chars) " + window(x, i);
                sb = "(" + y.length() + " chars) " + window(y, i);
            } else {
                sa = show(a);
                sb = show(b);
            }
            differences.add(new String[]{path, owner, field, sa, sb});
        }

        private static String window(String s, int at) {
            int from = Math.max(0, at - 40), to = Math.min(s.length(), at + 70);
            return esc((from > 0 ? "..." : "") + s.substring(from, to) + (to < s.length() ? "..." : ""));
        }
        private static String show(Object o) {
            if (o == null) return "null";
            if (o.getClass().isArray()) return o.getClass().getComponentType().getSimpleName() + "[" + Array.getLength(o) + "]";
            String s = esc(String.valueOf(o));
            return "(" + o.getClass().getSimpleName() + ") " + (s.length() > 140 ? s.substring(0, 140) + "..." : s);
        }
        private static String esc(String s) { return s.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t"); }

        private static boolean isScalar(Class c) {
            return c == String.class || Number.class.isAssignableFrom(c) || c == Boolean.class || c == Character.class
                    || Enum.class.isAssignableFrom(c) || c == Class.class;
        }
        private static boolean isJdk(Class c) {
            String n = c.getName();
            return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("sun.") || n.startsWith("com.sun.");
        }

        private void step(Object a, Object b, String path, String owner, String field) throws Exception {
            if (a == b) return;
            if (a == null || b == null) { add(path, owner, field, a, b); return; }
            Class c = a.getClass();
            if (c != b.getClass()) { add(path, owner, field, "class " + c.getName(), "class " + b.getClass().getName()); return; }
            if (a instanceof CharSequence && !(a instanceof String) || c.getName().startsWith("java.util.concurrent.atomic.")) {
                if (!a.toString().equals(b.toString())) add(path, owner, field, a.toString(), b.toString());
                return;
            }
            if (isScalar(c)) { if (!a.equals(b)) add(path, owner, field, a, b); return; }
            if (seen.put(a, Boolean.TRUE) != null) return;
            objects++;
            if (c.isArray()) {
                int la = Array.getLength(a), lb = Array.getLength(b), n = Math.min(la, lb);
                if (la != lb) add(path + ".length", owner, field, Integer.valueOf(la), Integer.valueOf(lb));
                if (c.getComponentType().isPrimitive()) {
                    for (int i = 0; i < n; i++)
                        if (!Array.get(a, i).equals(Array.get(b, i))) { add(path + "[" + i + "]", owner, field, Array.get(a, i), Array.get(b, i)); break; }
                } else {
                    for (int i = n - 1; i >= 0; i--) work.push(new Object[]{Array.get(a, i), Array.get(b, i), path + "[" + i + "]", owner, field});
                }
                return;
            }
            if (a instanceof java.awt.image.BufferedImage) {
                java.awt.image.BufferedImage x = (java.awt.image.BufferedImage) a, y = (java.awt.image.BufferedImage) b;
                boolean same = x.getWidth() == y.getWidth() && x.getHeight() == y.getHeight()
                        && Arrays.equals(x.getRGB(0, 0, x.getWidth(), x.getHeight(), null, 0, x.getWidth()),
                                         y.getRGB(0, 0, y.getWidth(), y.getHeight(), null, 0, y.getWidth()));
                if (!same) add(path, owner, field, "image " + x.getWidth() + "x" + x.getHeight(), "image " + y.getWidth() + "x" + y.getHeight() + " (pixels differ)");
                return;
            }
            if (a instanceof Map) {
                Map ma = (Map) a, mb = (Map) b;
                if (ma.size() != mb.size()) add(path + ".size()", owner, field, Integer.valueOf(ma.size()), Integer.valueOf(mb.size()));
                boolean scalarKeys = true;
                for (Object k : ma.keySet()) if (k != null && !isScalar(k.getClass())) { scalarKeys = false; break; }
                if (scalarKeys) {
                    for (Object o : ma.entrySet()) {
                        Map.Entry e = (Map.Entry) o;
                        String p = path + "{" + e.getKey() + "}";
                        if (!mb.containsKey(e.getKey())) add(p, owner, field, "present", "missing");
                        else work.push(new Object[]{e.getValue(), mb.get(e.getKey()), p, owner, field});
                    }
                    for (Object k : mb.keySet()) if (!ma.containsKey(k)) add(path + "{" + k + "}", owner, field, "missing", "present");
                } else if (a instanceof LinkedHashMap || a instanceof SortedMap) {
                    Iterator ia = ma.entrySet().iterator(), ib = mb.entrySet().iterator();
                    for (int i = 0; ia.hasNext() && ib.hasNext(); i++) {
                        Map.Entry ea = (Map.Entry) ia.next(), eb = (Map.Entry) ib.next();
                        work.push(new Object[]{ea.getValue(), eb.getValue(), path + ".value#" + i, owner, field});
                        work.push(new Object[]{ea.getKey(), eb.getKey(), path + ".key#" + i, owner, field});
                    }
                }
                if (!isJdk(c)) pushFields(a, b, c, path, false);
                return;
            }
            if (a instanceof Collection) {
                Collection ca = (Collection) a, cb = (Collection) b;
                if (ca.size() != cb.size()) add(path + ".size()", owner, field, Integer.valueOf(ca.size()), Integer.valueOf(cb.size()));
                if (a instanceof List || a instanceof LinkedHashSet || a instanceof SortedSet || a instanceof Queue) {
                    List<Object[]> items = new ArrayList<Object[]>();
                    Iterator ia = ca.iterator(), ib = cb.iterator();
                    for (int i = 0; ia.hasNext() && ib.hasNext(); i++) items.add(new Object[]{ia.next(), ib.next(), path + "[" + i + "]", owner, field});
                    for (int i = items.size() - 1; i >= 0; i--) work.push(items.get(i));
                } else {
                    boolean scalars = true;
                    for (Object o : ca) if (o != null && !isScalar(o.getClass())) { scalars = false; break; }
                    if (scalars && !new HashSet<Object>(ca).equals(new HashSet<Object>(cb))) add(path, owner, field, ca, cb);
                }
                if (!isJdk(c)) pushFields(a, b, c, path, false);
                return;
            }
            if (isJdk(c)) {
                // JDK value types (Color, Font, Date, ...) compare by equals; anything else is not looked into.
                if (c.getMethod("equals", Object.class).getDeclaringClass() != Object.class && !a.equals(b)) add(path, owner, field, a, b);
                return;
            }
            pushFields(a, b, c, path, true);
        }

        private void pushFields(Object a, Object b, Class c, String path, boolean includeJdkSupers) throws Exception {
            Field[] fs = fieldCache.get(c);
            if (fs == null) {
                List<Field> l = new ArrayList<Field>();
                for (Class k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                    if (isJdk(k) && !includeJdkSupers) continue;
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        try { f.setAccessible(true); l.add(f); } catch (RuntimeException inaccessible) { /* skip */ }
                    }
                }
                fs = l.toArray(new Field[l.size()]);
                fieldCache.put(c, fs);
            }
            for (int i = fs.length - 1; i >= 0; i--)
                work.push(new Object[]{fs[i].get(a), fs[i].get(b), path + "." + fs[i].getName(),
                        fs[i].getDeclaringClass().getSimpleName(), fs[i].getName()});
        }
    }

    // ---- helpers ----
    static DataAccessMethodSQL sqlOf(DataSet ds) {
        try {
            DataSetItemNormal it = ds.getDataSetItemNormal();
            return it == null ? null : it.getDataAccessMethodSQL();
        } catch (Throwable t) { return null; }
    }
    static String accessOf(DataSet ds) {
        try { DataSetItemNormal it = ds.getDataSetItemNormal(); return it == null ? "n/a" : String.valueOf(it.getDataAccessMethod()); }
        catch (Throwable t) { return "n/a"; }
    }
    static String nz(String s) { return s == null ? "" : s; }
    static boolean notEmpty(String s) { return s != null && s.length() > 0; }
    static String firstLine(String s) {
        s = s.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) + " ..." : s;
    }
    static void writeText(Path p, String text) throws IOException {
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }
    static String readText(Path p) throws IOException {
        String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        return s.length() > 0 && s.charAt(0) == '﻿' ? s.substring(1) : s;   // a byte order mark is not SQL
    }
    /** Line endings that are not CRLF: a lone LF or a lone CR. */
    static int bareLineEndings(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' && (i == 0 || s.charAt(i - 1) != '\r')) n++;
            else if (c == '\r' && (i + 1 == s.length() || s.charAt(i + 1) != '\n')) n++;
        }
        return n;
    }
    static java.util.Set<String> union(java.util.Set<String> a, java.util.Set<String> b, java.util.Set<String> c) {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<String>();
        s.addAll(a); s.addAll(b); s.addAll(c); return s;
    }
}
