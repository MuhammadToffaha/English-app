package com.toffaha.kalimat;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 🤫 Once a day (Wi-Fi, battery not low), even when the app is closed:
 * check GitHub Releases, download a newer APK and install it silently (Android 12+, after the first self-update).
 */
public class DailyUpdateJob extends JobService {
    private static final int JOB_ID = 4711;

    public static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;   // already scheduled
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, DailyUpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(24L * 60 * 60 * 1000)
                .setPersisted(true);
        if (Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(true);
        js.schedule(b.build());
    }

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try { run(this); } catch (Exception e) { e.printStackTrace(); }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }

    static void run(Context c) throws Exception {
        if (Build.VERSION.SDK_INT < 31) return;
        if (!c.getPackageManager().canRequestPackageInstalls()) return;
        SharedPreferences prefs = c.getSharedPreferences("kalimat", MODE_PRIVATE);
        if (prefs.getBoolean("needsConfirm", false)) return;   // Android wants a tap: the in-app banner handles it
        JSONObject rel = new JSONObject(new String(get("https://api.github.com/repos/" + MainActivity.REPO + "/releases/latest"), "UTF-8"));
        String tag = rel.optString("tag_name", "").replaceAll("[^0-9]", "");
        if (tag.isEmpty()) return;
        int latest = Integer.parseInt(tag);
        if (latest <= BuildConfig.VERSION_CODE) return;
        String url = null;
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break; }
        }
        if (url == null) return;
        File f = new File(c.getFilesDir(), "update.apk");   // same file the app uses
        if (!(f.isFile() && prefs.getInt("pendingVer", -1) == latest)) {
            byte[] data = get(url);
            try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
            prefs.edit().putInt("pendingVer", latest).putBoolean("needsConfirm", false).apply();
        }
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("kalimat.apk", 0, f.length())) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(c, MainActivity.SilentUpdateReceiver.class);
            s.commit(PendingIntent.getBroadcast(c, 9, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE).getIntentSender());
        }
    }

    private static byte[] get(String u) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setRequestProperty("User-Agent", "kalimat-android");
            h.setConnectTimeout(15000); h.setReadTimeout(60000);
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) { u = new URL(new URL(u), h.getHeaderField("Location")).toString(); h.disconnect(); continue; }
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                return bo.toByteArray();
            }
        }
        throw new Exception("too many redirects");
    }
}
