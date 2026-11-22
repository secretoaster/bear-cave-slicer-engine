package com.design218.slicetest;

// Bear Cave Slicer Checker (Bear Cave Labs): runs the real ElegooSlicer engine (bcslice) on the
// phone or tablet and reports whether it's fast enough to slice, how long it took and how much memory it used.
// Copyright (C) 2026 Design202, LLC (Bear Cave Labs). Built with Claude. GNU AGPL v3.

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    static final int PICK = 7;
    static final String[][] SAMPLES = {
            {"ElegooToleranceTest.stl", "Tolerance test (STL)"},
            {"Stanford_Bunny.3mf", "Stanford Bunny (3MF)"},
            {"Elegoo_stringhell.3mf", "Stringing test (3MF)"},
    };
    static final int BG = Color.rgb(11, 13, 16), PANEL = Color.rgb(21, 25, 31), TEXT = Color.rgb(232, 234, 237),
            DIM = Color.rgb(140, 148, 160), YELLOW = Color.rgb(242, 193, 78), GREEN = Color.rgb(61, 220, 132),
            RED = Color.rgb(255, 77, 61), GO1 = Color.rgb(166, 219, 255), GO2 = Color.rgb(116, 176, 255), INK = Color.rgb(7, 16, 28),
            LINE = Color.rgb(36, 42, 51);
    static final String SITE = "https://bearcavelabs.com/";

    TextView result, log, verdictText;
    LinearLayout verdict;
    Spinner procSp, filSp;
    List<String> procFiles = new ArrayList<>(), filFiles = new ArrayList<>();
    String machineFile;
    File lastGcode;
    volatile boolean busy, testing;
    final StringBuilder summary = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        try { unpackAssets(); } catch (Exception e) { toast("Setup failed: " + e.getMessage()); }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(24));
        root.setBackgroundColor(BG);

        // thin brand bar: opens bearcavelabs.com
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(0, dp(4), 0, dp(10));
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.bcl_logo);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView bt = text("BEAR CAVE SLICER CHECKER", 11, Color.rgb(201, 206, 214));
        bt.setTypeface(Typeface.DEFAULT_BOLD);
        bt.setLetterSpacing(0.18f);
        bt.setPadding(dp(8), 0, 0, 0);
        brand.addView(bt, new LinearLayout.LayoutParams(0, -2, 1));
        TextView by = text("Bear Cave Labs ↗", 10, DIM);
        brand.addView(by);
        brand.setOnClickListener(v -> open(SITE + "#checker"));
        root.addView(brand);
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, 1);
        rl.bottomMargin = dp(16);
        root.addView(rule, rl);

        TextView t = text("Can this phone slice?", 24, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(t);
        root.addView(text("The first phone slicer benchmark for 3D printing. It runs a real 3D printer slicer, the ElegooSlicer engine, right here on this phone or tablet and times it. No computer, no cloud, nothing sent anywhere.", 14, DIM));

        // device card
        TextView dev = text(deviceText(), 14, TEXT);
        dev.setPadding(dp(14), dp(12), dp(14), dp(12));
        dev.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(-1, -2);
        dp1.topMargin = dp(14);
        root.addView(dev, dp1);

        Button go = button("▶  Check this phone");
        go.setTextColor(INK);
        go.setBackground(round(new int[]{GO1, GO2}, 14));
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setOnClickListener(v -> quickTest());
        LinearLayout.LayoutParams gp = (LinearLayout.LayoutParams) go.getLayoutParams();
        gp.height = dp(60); gp.topMargin = dp(14);
        root.addView(go);
        root.addView(text("About a minute. Slices 3 sample models, then two of them twice more to check they come out the same every time.", 12, DIM));

        result = text("", 16, GO1);
        result.setPadding(dp(14), dp(12), dp(14), dp(12));
        result.setBackgroundColor(PANEL);
        result.setVisibility(View.GONE);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(14);
        root.addView(result, rp);

        // after the check: what this means, and Bear Cave Slicer
        verdict = new LinearLayout(this);
        verdict.setOrientation(LinearLayout.VERTICAL);
        verdict.setPadding(dp(14), dp(12), dp(14), dp(14));
        verdict.setBackground(round(new int[]{Color.rgb(15, 26, 38), Color.rgb(15, 26, 38)}, 14));
        verdict.setVisibility(View.GONE);
        verdictText = text("", 15, Color.rgb(215, 236, 255));
        verdict.addView(verdictText);
        Button get = button("Get Bear Cave Slicer");
        get.setTextColor(INK);
        get.setTypeface(Typeface.DEFAULT_BOLD);
        get.setBackground(round(new int[]{GO1, GO2}, 14));
        get.setOnClickListener(v -> open(SITE + "#slicer"));
        verdict.addView(get);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, -2);
        vp.topMargin = dp(12);
        root.addView(verdict, vp);

        Button share = button("Share my results");
        share.setOnClickListener(v -> shareResults());
        root.addView(share);

        Button check = button("Deep check");
        check.setOnClickListener(v -> consistencyCheck());
        root.addView(check);
        root.addView(text("About 5 minutes. Slices the Bunny and the stringing test 20 times each (10 on all cores, 10 on one) and saves a report to Downloads/Bear Cave/check.", 12, DIM));

        // advanced
        TextView advT = label("More ▸");
        root.addView(advT);
        LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(View.GONE);
        advT.setOnClickListener(v -> {
            boolean show = adv.getVisibility() != View.VISIBLE;
            adv.setVisibility(show ? View.VISIBLE : View.GONE);
            advT.setText(show ? "MORE ▾" : "MORE ▸");
        });
        root.addView(adv);
        adv.addView(label("Print settings"));
        procSp = spinner(adv, procFiles, "0.20mm Standard");
        adv.addView(label("Filament"));
        filSp = spinner(adv, filFiles, "ASA-CF");
        adv.addView(label("Slice one sample"));
        for (String[] sm : SAMPLES) {
            Button sb = button(sm[1]);
            sb.setOnClickListener(v -> slice(new File(getFilesDir(), "models/" + sm[0]), sm[0], false));
            adv.addView(sb);
        }
        Button pick = button("Pick my own .3mf or .stl…");
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, PICK);
        });
        adv.addView(pick);
        Button save = button("Save last G-code to Downloads");
        save.setOnClickListener(v -> saveToDownloads());
        adv.addView(save);
        log = text("", 11, DIM);
        log.setTypeface(Typeface.MONOSPACE);
        log.setPadding(0, dp(10), 0, 0);
        adv.addView(log);

        TextView foot = text("Made by Bear Cave Labs. Free and open source (GNU AGPL v3). The slicing engine is ElegooSlicer; this app is not made by or affiliated with Elegoo. © 2026 Design202, LLC (Bear Cave Labs)", 11, DIM);
        foot.setPadding(0, dp(20), 0, 0);
        root.addView(foot);
        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        String[][] ls = {{"Terms", SITE + "terms/#checker"}, {"Privacy", SITE + "privacy/"}, {"Help", SITE + "help/#checker"}, {"Source code", "https://github.com/secretoaster/bear-cave-slicer-engine"}};
        for (String[] l : ls) {
            TextView a = text(l[0], 12, GO1);
            a.setPadding(0, dp(10), dp(16), dp(6));
            a.setOnClickListener(v -> open(l[1]));
            links.addView(a);
        }
        root.addView(links);

        // tablets: keep the column readable instead of stretching edge to edge
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(BG);
        int maxW = dp(720);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        android.widget.FrameLayout.LayoutParams fp = new android.widget.FrameLayout.LayoutParams(Math.min(maxW, screenW), -2);
        fp.gravity = Gravity.CENTER_HORIZONTAL;
        frame.addView(root, fp);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(frame);
        setContentView(sv);
    }

    boolean isTablet() { return getResources().getConfiguration().smallestScreenWidthDp >= 600; }

    String deviceText() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        String chip = Build.VERSION.SDK_INT >= 31 ? (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL) : Build.HARDWARE;
        return String.format(Locale.US, "%s %s (%s)\nChip: %s · %d cores\nMemory: %.1f GB · Android %s",
                cap(Build.MANUFACTURER), Build.MODEL, isTablet() ? "tablet" : "phone", chip,
                Runtime.getRuntime().availableProcessors(), mi.totalMem / 1073741824.0, Build.VERSION.RELEASE);
    }

    static String cap(String s) { return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    // ---------- the one-button test ----------
    String lastReport = "";

    void quickTest() {
        if (busy || testing) { toast("Already running."); return; }
        testing = true;
        new Thread(() -> {
            ui(() -> result.setVisibility(View.VISIBLE));
            StringBuilder r = new StringBuilder();
            double bunnySec = 0, peak = 0;
            boolean allOk = true;
            String[] names = {"Tolerance test", "Stanford Bunny", "Stringing test"};
            for (int i = 0; i < SAMPLES.length; i++) {
                JSONObject j = runSlice(new File(getFilesDir(), "models/" + SAMPLES[i][0]), SAMPLES[i][0], true, 0, null);
                if (j == null || !j.optBoolean("ok")) { allOk = false; r.append("✕ ").append(names[i]).append(": failed\n"); continue; }
                r.append(String.format(Locale.US, "✓ %s: %.1f s · %.0f MB · prints in %s · %.1f g\n", names[i],
                        j.optDouble("seconds"), j.optDouble("peakMB"), j.optString("estimate"), j.optDouble("grams")));
                peak = Math.max(peak, j.optDouble("peakMB"));
                if (i == 1) bunnySec = j.optDouble("seconds");
            }
            // consistency: the Bunny and the stringing test twice more each, compared with the first run
            int same = 2, total = 2;
            double bunnyMin = bunnySec, bunnyMax = bunnySec;
            File again = new File(getExternalFilesDir(null), "again.gcode");
            String[][] repeat = {{"Stanford_Bunny.3mf", "Stanford Bunny", "Stanford_Bunny.gcode"},
                                 {"Elegoo_stringhell.3mf", "Stringing test", "Elegoo_stringhell.gcode"}};
            for (String[] rp : repeat) {
                String h0 = gcodeHash(new File(getExternalFilesDir(null), rp[2]));
                for (int k = 0; k < 2; k++) {
                    JSONObject j = runSlice(new File(getFilesDir(), "models/" + rp[0]), rp[1] + " · repeat " + (k + 1), true, 0, again);
                    total++;
                    if (j != null && j.optBoolean("ok") && gcodeHash(again).equals(h0)) same++;
                    if (j != null && j.optBoolean("ok") && rp[0].startsWith("Stanford")) {
                        bunnyMin = Math.min(bunnyMin, j.optDouble("seconds"));
                        bunnyMax = Math.max(bunnyMax, j.optDouble("seconds"));
                    }
                }
            }
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
            // rate on the fastest Bunny run: a phone that heats up slows itself down on later runs
            String speed = bunnyMin <= 0 ? "-" : bunnyMin < 10 ? "Fast" : bunnyMin < 30 ? "OK" : "Slow";
            r.append(String.format(Locale.US, "\nSpeed: %s (big model in %.1f s at best)\nMemory: used %.0f MB of %.1f GB\nSame result every run: %s (%d of %d)",
                    speed, bunnyMin, peak, mi.totalMem / 1073741824.0, same == total ? "yes ✓" : "NO ✕", same, total));
            if (bunnyMin > 0 && bunnyMax > bunnyMin * 1.6)
                r.append(String.format(Locale.US, "\nThe phone slowed down between runs (%.1f–%.1f s). It's likely warm; run again after it cools for a fair speed.", bunnyMin, bunnyMax));
            if (same != total) r.append("\nRun the Deep check below and share its report; it saves the different results.");
            final boolean good = allOk && same == total;
            lastReport = "Bear Cave Slicer Checker\n" + deviceText() + "\n\n" + r;
            final String txt = r.toString();
            final String sp = speed;
            final boolean finished = allOk;
            ui(() -> {
                result.setTextColor(good ? GREEN : YELLOW);
                result.setText(txt);
                verdictText.setText(!finished
                        ? "The slicer couldn't finish every model on this phone. Share your results so we can look into it. Bear Cave Slicer's live camera and printer controls still work on any phone."
                        : sp.equals("Fast") || sp.equals("OK")
                        ? "This phone can slice. " + (sp.equals("Fast") ? "It's fast enough for everyday prints. " : "Big models take a little longer, but it works. ")
                          + "Bear Cave Slicer slices on your phone, sends the print to your Elegoo Centauri Carbon 2, and shows it live on the printer's camera over your home Wi-Fi."
                        : "This phone is slow for slicing big models. Small prints still work, and Bear Cave Slicer's live camera and printer controls work on any phone.");
                verdict.setVisibility(View.VISIBLE);
            });
            testing = false;
        }).start();
    }

    void shareResults() {
        if (lastReport.isEmpty()) { toast("Run the test first."); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Bear Cave Slicer Checker results");
        i.putExtra(Intent.EXTRA_TEXT, lastReport + "\n\nChecked with Bear Cave Slicer Checker (free, from Bear Cave Labs): bearcavelabs.com");
        startActivity(Intent.createChooser(i, "Share results"));
    }

    // ---------- slicing ----------

    void runAll() {
        if (busy) return;
        summary.setLength(0);
        new Thread(() -> {
            for (String[] s : SAMPLES) {
                runSlice(new File(getFilesDir(), "models/" + s[0]), s[0], true);
            }
            ui(() -> result.setText(summary.toString().trim()));
        }).start();
    }

    void slice(File model, String name, boolean quiet) {
        if (busy) { toast("Already slicing."); return; }
        new Thread(() -> runSlice(model, name, quiet)).start();
    }

    void runSlice(File model, String name, boolean inSet) { runSlice(model, name, inSet, 0, null); }

    // threads: 0 = all cores. out: where the G-code goes (null = default).
    JSONObject runSlice(File model, String name, boolean inSet, int threads, File outOverride) {
        busy = true;
        ui(() -> getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        String base = name.replaceAll("\\.[^.]+$", "");
        File outDir = getExternalFilesDir(null);
        File gcode = outOverride != null ? outOverride : new File(outDir, base + ".gcode");
        File json = new File(outDir, base + ".json");
        json.delete();
        String exe = getApplicationInfo().nativeLibraryDir + "/libbcslice.so";
        String proc = new File(getFilesDir(), procFiles.get(procSp.getSelectedItemPosition())).getPath();
        String fil = new File(getFilesDir(), filFiles.get(filSp.getSelectedItemPosition())).getPath();
        ui(() -> { log.setText(""); result.setVisibility(View.VISIBLE); result.setTextColor(GO1); result.setText("Slicing " + name + "…"); });
        long t0 = System.currentTimeMillis();
        int code = -1;
        try {
            ProcessBuilder pb = new ProcessBuilder(exe, "--machine", new File(getFilesDir(), machineFile).getPath(),
                    "--process", proc, "--filament", fil, "--tmp", new File(getCacheDir(), "bcslice").getPath(),
                    "--resources", new File(getFilesDir(), "presets").getPath(),
                    "--threads", String.valueOf(threads),
                    "--json", json.getPath(), "-o", gcode.getPath(), model.getPath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                final String l = line;
                ui(() -> {
                    log.append(l + "\n");
                    if (l.startsWith("PROGRESS ")) result.setText(name + "\n" + l.substring(9) + "  (" + (System.currentTimeMillis() - t0) / 1000 + " s)");
                });
            }
            code = p.waitFor();
        } catch (Exception e) {
            final String m = e.toString();
            ui(() -> log.append("Could not run the engine: " + m + "\n"));
        }
        double wall = (System.currentTimeMillis() - t0) / 1000.0;
        String msg;
        boolean ok = false;
        JSONObject j = null;
        try {
            j = new JSONObject(readFile(json));
            ok = j.optBoolean("ok");
            if (ok) {
                lastGcode = gcode;
                msg = String.format(Locale.US, "✓ %s\nSliced in %.1f s · peak memory %.0f MB\nPrint: %s · %.1f g · G-code %.1f MB",
                        name, j.optDouble("seconds", wall), j.optDouble("peakMB"), j.optString("estimate", "?"),
                        j.optDouble("grams"), gcode.length() / 1048576.0);
            } else {
                msg = String.format(Locale.US, "✕ %s\n%s\n(peak memory %.0f MB, %.1f s)", name, j.optString("error"), j.optDouble("peakMB"), wall);
            }
        } catch (Exception e) {
            msg = String.format(Locale.US, "✕ %s\nThe engine stopped (exit code %d) after %.1f s. See the log below.", name, code, wall);
        }
        final String fm = msg;
        final boolean fok = ok;
        summary.append(fm).append("\n\n");
        ui(() -> {
            result.setTextColor(fok ? GREEN : RED);
            result.setText(fm);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        busy = false;
        return j;
    }

    // ---------- consistency check ----------
    // Slices the Bunny 10 times on all cores and 10 times on 1 core, compares
    // every G-code, and saves one copy of each different result (zipped) plus
    // a report to Downloads/Bear Cave/check.

    void consistencyCheck() {
        if (busy || testing) { toast("Already slicing."); return; }
        testing = true;
        new Thread(() -> {
            File tmp = new File(getExternalFilesDir(null), "check.gcode");
            StringBuilder rep = new StringBuilder();
            rep.append(String.format(Locale.US, "Bear Cave consistency check\n%s %s, %d cores, Android %s\nprint settings: %s\nfilament: %s\n\n",
                    Build.MANUFACTURER, Build.MODEL, Runtime.getRuntime().availableProcessors(), Build.VERSION.RELEASE,
                    procSp.getSelectedItem(), filSp.getSelectedItem()));
            String[][] models = {{"Stanford_Bunny.3mf", "Bunny", "bunny"}, {"Elegoo_stringhell.3mf", "Stringing", "stringing"}};
            int[] modes = {0, 1};
            int different = 0;
            for (String[] md : models) {
                File model = new File(getFilesDir(), "models/" + md[0]);
                java.util.LinkedHashMap<String, Integer> seen = new java.util.LinkedHashMap<>();
                for (int mode : modes) {
                    String label = mode == 0 ? "all cores" : "1 core";
                    java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= 10; i++) {
                        final int run = i;
                        JSONObject j = runSlice(model, md[1] + " · " + label + " · run " + i + " of 10", true, mode, tmp);
                        String h = j != null && j.optBoolean("ok") ? gcodeHash(tmp) : "failed";
                        String line = String.format(Locale.US, "%-9s %-9s run %2d  %s  %s  %.2f g  %.1f MB  %.1f s  %.0f MB",
                                md[1], label, i, h.substring(0, Math.min(8, h.length())), j == null ? "-" : j.optString("estimate"),
                                j == null ? 0 : j.optDouble("grams"), tmp.length() / 1048576.0, j == null ? 0 : j.optDouble("seconds"),
                                j == null ? 0 : j.optDouble("peakMB"));
                        rep.append(line).append("\n");
                        counts.merge(h, 1, Integer::sum);
                        if (!h.equals("failed") && !seen.containsKey(h)) {
                            seen.put(h, seen.size() + 1);
                            saveGz(tmp, "check/" + md[2] + "_" + (mode == 0 ? "all" : "1core") + "_" + h.substring(0, 8) + ".gcode.gz");
                        }
                    }
                    rep.append(String.format(Locale.US, "%s, %s: %d different result(s) in 10 runs\n\n", md[1], label, counts.size()));
                }
                if (seen.size() != 1) different++;
                rep.append(String.format(Locale.US, "%s: %s\n\n", md[1], seen.size() == 1 ? "all 20 runs identical" : seen.size() + " different G-codes (one copy of each saved)"));
            }
            rep.append(different == 0 ? "RESULT: every run identical ✓\n" : "RESULT: some runs differ ✕\n");
            final boolean allSame = different == 0;
            saveText(rep.toString(), "check/report.txt");
            lastReport = rep.toString();
            final String r = rep.toString();
            ui(() -> {
                result.setVisibility(View.VISIBLE);
                result.setTextColor(allSame ? GREEN : YELLOW);
                result.setText(r);
                setTitle("Bear Cave Slicer Checker");
                toast("Saved to Downloads/Bear Cave/check");
            });
            testing = false;
        }).start();
    }

    // MD5 of the G-code, skipping the "generated on <time>" line
    static String gcodeHash(File f) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)), 1 << 16)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("; generated by")) continue;
                md.update(line.getBytes("UTF-8"));
                md.update((byte) 10);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return "error"; }
    }

    Uri newDownload(String rel, String mime) {
        String dir = Environment.DIRECTORY_DOWNLOADS + "/Bear Cave" + (rel.contains("/") ? "/" + rel.substring(0, rel.lastIndexOf('/')) : "");
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, rel.substring(rel.lastIndexOf('/') + 1));
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir);
        return getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
    }

    void saveGz(File src, String rel) {
        try (InputStream in = new FileInputStream(src);
             OutputStream o = new java.util.zip.GZIPOutputStream(getContentResolver().openOutputStream(newDownload(rel, "application/gzip")))) {
            copy(in, o);
        } catch (Exception e) { toast("Couldn't save " + rel + ": " + e.getMessage()); }
    }

    void saveText(String text, String rel) {
        try (OutputStream o = getContentResolver().openOutputStream(newDownload(rel, "text/plain"))) {
            o.write(text.getBytes("UTF-8"));
        } catch (Exception e) { toast("Couldn't save the report: " + e.getMessage()); }
    }

    // ---------- files ----------

    void unpackAssets() throws Exception {
        File stamp = new File(getFilesDir(), "assets.stamp");
        String ver = String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime);
        boolean fresh = !stamp.exists() || !readFile(stamp).equals(ver);
        JSONObject idx = new JSONObject(readStream(getAssets().open("presets/index.json")));
        machineFile = "presets/" + idx.getJSONArray("machine").getJSONObject(0).getString("file");
        for (String k : new String[]{"process", "filament"}) {
            JSONArray a = idx.getJSONArray(k);
            for (int i = 0; i < a.length(); i++) {
                String f = "presets/" + a.getJSONObject(i).getString("file");
                (k.equals("process") ? procFiles : filFiles).add(f);
                if (fresh) copyAsset(f);
            }
        }
        if (fresh) {
            JSONArray info = idx.getJSONArray("info");
            for (int i = 0; i < info.length(); i++) copyAsset("presets/" + info.getJSONObject(i).getString("file"));
            copyAsset(machineFile);
            for (String[] s : SAMPLES) copyAsset("models/" + s[0]);
            try (FileOutputStream o = new FileOutputStream(stamp)) { o.write(ver.getBytes()); }
        }
    }

    void copyAsset(String path) throws Exception {
        File out = new File(getFilesDir(), path);
        out.getParentFile().mkdirs();
        try (InputStream in = getAssets().open(path); OutputStream o = new FileOutputStream(out)) { copy(in, o); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null) return;
        Uri u = data.getData();
        String name = "model.3mf";
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) { }
        String low = name.toLowerCase(Locale.US);
        if (!low.endsWith(".3mf") && !low.endsWith(".stl") && !low.endsWith(".obj")) { toast("Pick a .3mf, .stl or .obj file."); return; }
        File dst = new File(getCacheDir(), "picked/" + name.replaceAll("[\\\\/:*?\"<>|]", "_"));
        dst.getParentFile().mkdirs();
        try (InputStream in = getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(dst)) {
            copy(in, o);
        } catch (Exception e) { toast("Couldn't read that file: " + e.getMessage()); return; }
        slice(dst, name, false);
    }

    void saveToDownloads() {
        if (lastGcode == null || !lastGcode.exists()) { toast("Slice something first."); return; }
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, "phone_" + lastGcode.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/x.gcode");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Bear Cave");
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            try (InputStream in = new FileInputStream(lastGcode); OutputStream o = getContentResolver().openOutputStream(u)) { copy(in, o); }
            toast("Saved to Downloads/Bear Cave/phone_" + lastGcode.getName());
        } catch (Exception e) { toast("Couldn't save: " + e.getMessage()); }
    }

    static void copy(InputStream in, OutputStream o) throws Exception {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
    }

    static String readStream(InputStream in) throws Exception {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            copy(i, b);
            return b.toString("UTF-8");
        }
    }

    static String readFile(File f) throws Exception { return readStream(new FileInputStream(f)); }

    // ---------- small UI helpers ----------

    void ui(Runnable r) { runOnUiThread(r); }

    android.graphics.drawable.GradientDrawable round(int[] colors, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, colors);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("Couldn't open the link."); }
    }

    void toast(String s) { ui(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    TextView label(String s) {
        TextView t = text(s.toUpperCase(Locale.US), 12, DIM);
        t.setPadding(0, dp(16), 0, dp(4));
        return t;
    }

    Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackground(round(new int[]{PANEL, PANEL}, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    Spinner spinner(LinearLayout root, List<String> files, String prefer) {
        List<String> names = new ArrayList<>();
        int sel = 0;
        for (int i = 0; i < files.size(); i++) {
            String n = new File(files.get(i)).getName().replace(".json", "");
            names.add(n);
            if (n.contains(prefer) && sel == 0) sel = i;
        }
        Spinner s = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
            @Override public View getView(int pos, View cv, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getView(pos, cv, parent);
                v.setTextColor(TEXT);
                return v;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(ad);
        s.setSelection(sel);
        s.setBackgroundColor(PANEL);
        root.addView(s, new LinearLayout.LayoutParams(-1, dp(48)));
        return s;
    }
}
