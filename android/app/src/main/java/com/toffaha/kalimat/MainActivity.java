package com.toffaha.kalimat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Kid-friendly vocabulary app.
 * - Shows the page in web/ (bundled in the APK, refreshed from GitHub on every launch).
 * - Speaks words with Android's own text-to-speech, American English.
 * - Tells the kid's parent when a newer APK is on the GitHub releases page.
 */
public class MainActivity extends Activity {

    static final String REPO = "MuhammadToffaha/English-app";
    static final String BRANCH = "main";

    private WebView web;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("kalimat", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "AndroidTTS");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.US);
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { notifyDone(); }
                    @Override public void onError(String id) { notifyDone(); }
                });
                if (!ttsReady) {
                    ui.post(() -> Toast.makeText(MainActivity.this,
                            "نزّل صوت English (United States) من إعدادات تحويل النص إلى كلام",
                            Toast.LENGTH_LONG).show());
                }
            }
        });

        installBundledIfNeeded();
        loadPage();
        new Thread(this::checkForUpdates).start();
    }

    private void notifyDone() {
        ui.post(() -> web.evaluateJavascript("window.__ttsDone && window.__ttsDone()", null));
    }

    private File contentDir() { return new File(getFilesDir(), "web"); }

    private void loadPage() {
        web.loadUrl("file://" + new File(contentDir(), "index.html").getAbsolutePath());
    }

    // ---------- bundled content ----------
    private void installBundledIfNeeded() {
        int code = BuildConfig.VERSION_CODE;
        boolean present = new File(contentDir(), "index.html").exists();
        if (present && prefs.getInt("installedFromApk", -1) == code) return;
        try {
            deleteRecursive(contentDir());
            copyAssetDir(getAssets(), "web", contentDir());
            String sha = readAssetText("web/sha.txt").trim();
            prefs.edit().putInt("installedFromApk", code).putString("sha", sha).apply();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void copyAssetDir(AssetManager am, String path, File dest) throws IOException {
        String[] list = am.list(path);
        if (list == null || list.length == 0) {
            dest.getParentFile().mkdirs();
            try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dest)) {
                pipe(in, out);
            }
            return;
        }
        dest.mkdirs();
        for (String name : list) copyAssetDir(am, path + "/" + name, new File(dest, name));
    }

    private String readAssetText(String path) {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            pipe(in, b);
            return b.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    // ---------- updates from GitHub ----------
    private void checkForUpdates() {
        try { updateContent(); } catch (Exception e) { e.printStackTrace(); }
        try { checkApk(); } catch (Exception e) { e.printStackTrace(); }
    }

    /** Pulls web/ from the newest commit that touched it, whenever that commit changes. */
    private void updateContent() throws Exception {
        JSONArray commits = new JSONArray(httpText(
                "https://api.github.com/repos/" + REPO + "/commits?sha=" + BRANCH + "&path=web&per_page=1"));
        if (commits.length() == 0) return;
        String sha = commits.getJSONObject(0).getString("sha");
        if (sha.equals(prefs.getString("sha", ""))) return;

        String base = "https://raw.githubusercontent.com/" + REPO + "/" + sha + "/web/";
        JSONArray files = new JSONObject(httpText(base + "manifest.json")).getJSONArray("files");

        File tmp = new File(getFilesDir(), "web_new");
        deleteRecursive(tmp);
        for (int i = 0; i < files.length(); i++) {
            String f = files.getString(i);
            if (f.contains("..")) continue;
            File out = new File(tmp, f);
            out.getParentFile().mkdirs();
            try (OutputStream o = new FileOutputStream(out)) {
                o.write(httpBytes(base + f));
            }
        }
        if (!new File(tmp, "index.html").exists()) return;

        File old = new File(getFilesDir(), "web_old");
        deleteRecursive(old);
        contentDir().renameTo(old);
        if (!tmp.renameTo(contentDir())) { old.renameTo(contentDir()); return; }
        deleteRecursive(old);
        prefs.edit().putString("sha", sha).apply();

        ui.post(() -> {
            Toast.makeText(this, "✅ اتحدّثت الكلمات", Toast.LENGTH_SHORT).show();
            loadPage();
        });
    }

    /** Release tags are v<build number>; the build number is the APK's versionCode. */
    private void checkApk() throws Exception {
        JSONObject rel = new JSONObject(httpText("https://api.github.com/repos/" + REPO + "/releases/latest"));
        String tag = rel.optString("tag_name", "").replaceAll("[^0-9]", "");
        if (tag.isEmpty()) return;
        int latest = Integer.parseInt(tag);
        if (latest <= BuildConfig.VERSION_CODE) return;
        String url = rel.optString("html_url");
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url", url); break; }
            }
        }
        final String dl = url;
        ui.postDelayed(() -> {
            web.evaluateJavascript("window.showApkUpdate && window.showApkUpdate(" + JSONObject.quote(dl) + ")", null);
            if (isFinishing()) return;
            new AlertDialog.Builder(this)
                    .setTitle("🆕 نسخة جديدة من التطبيق")
                    .setMessage("في تحديث جديد. نزّله وثبّته فوق النسخة الحالية.")
                    .setPositiveButton("نزّل", (d, w) -> {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(dl))); } catch (Exception ignored) { }
                    })
                    .setNegativeButton("بعدين", null)
                    .show();
        }, 1500);
    }

    // ---------- small helpers ----------
    private static byte[] httpBytes(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Kalimat-Android");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                pipe(in, b);
                return b.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String httpText(String url) throws IOException {
        return new String(httpBytes(url), StandardCharsets.UTF_8);
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    /** Exposed to the page as window.AndroidTTS */
    class Bridge {
        @JavascriptInterface
        public void speak(String text, float rate) {
            if (tts == null || !ttsReady) { notifyDone(); return; }
            tts.setLanguage(Locale.US);
            tts.setSpeechRate(rate <= 0 ? 0.9f : rate);
            Bundle params = new Bundle();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "k" + System.nanoTime());
        }

        @JavascriptInterface
        public void stop() {
            if (tts != null) tts.stop();
        }

        @JavascriptInterface
        public boolean ready() {
            return ttsReady;
        }
    }
}
