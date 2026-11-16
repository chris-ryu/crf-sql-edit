import com.clipsoft.clipreport.base.Rexpert4;
import com.clipsoft.clipreport.base.globe.TheReportFile;
import com.clipsoft.clipreport.base.globe.GlobalObjectManager;
import com.clipsoft.clipreport.base.RexObjectList;
import com.clipsoft.clipreport.base.datas.DataSet;
import com.clipsoft.clipreport.base.datas.DataSetItemNormal;
import com.clipsoft.clipreport.base.datas.DataAccessMethodSQL;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Headless SQL editor for ClipReport v5 .crf report templates.
 *
 * Uses the ClipReport engine's own public API
 * (com.clipsoft.clipreport.base.Rexpert4) to load a .crf into the object model,
 * change the SQL text of one or more datasets, and write it back out. The engine
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
 *   set  <in.crf> <out.crf> <dataset>=<sqlFile> [<dataset2>=<sqlFile2> ...]
 *       Replace the query of each named dataset from the given UTF-8 file,
 *       write a new .crf, then re-read it and verify each change persisted.
 *       Use <dataset>.pre=<file> / <dataset>.post=<file> for Pre/Post SQL.
 *
 * The input file is never modified in place; writes always go to <out.crf>.
 * Datasets that are not SQL-based (stored procedure / CSV / XML / JSON / SAP)
 * are left untouched.
 */
public class CrfSqlEdit {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { usage(); System.exit(2); return; }
        String cmd = args[0];
        if ("list".equals(cmd)) {
            list(args[1]);
        } else if ("dump".equals(cmd)) {
            if (args.length < 3) { usage(); System.exit(2); return; }
            dump(args[1], args[2]);
        } else if ("set".equals(cmd)) {
            if (args.length < 4) { usage(); System.exit(2); return; }
            set(args[1], args[2], java.util.Arrays.copyOfRange(args, 3, args.length));
        } else {
            usage(); System.exit(2);
        }
    }

    static void usage() {
        System.out.println("usage:\n"
                + "  CrfSqlEdit list <in.crf>\n"
                + "  CrfSqlEdit dump <in.crf> <outDir>\n"
                + "  CrfSqlEdit set  <in.crf> <out.crf> <dataset>=<sqlFile> [...]\n"
                + "       (also <dataset>.pre=<file> / <dataset>.post=<file>)");
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
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
    static java.util.Set<String> union(java.util.Set<String> a, java.util.Set<String> b, java.util.Set<String> c) {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<String>();
        s.addAll(a); s.addAll(b); s.addAll(c); return s;
    }
}
