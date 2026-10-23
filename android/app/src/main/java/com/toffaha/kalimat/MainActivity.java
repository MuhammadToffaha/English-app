package com.toffaha.kalimat;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Kid-friendly vocabulary app.
 * - Shows web/ (bundled in the APK, refreshed from GitHub on launch or on demand).
 * - Speaks with Android text-to-speech in American English; the page picks voice, speed and pitch.
 * - Downloads and installs a newer APK from GitHub releases from inside the app.
 */
public class MainActivity extends Activity {

    static final String REPO = "MuhammadToffaha/English-app";
    static final String BRANCH = "main";
    static final String ACTION_INSTALL_STATUS = "com.toffaha.kalimat.INSTALL_STATUS";

    private WebView web;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile String chosenVoice = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private volatile boolean busyUpdating = false;
    private SpeechRecognizer recognizer;
    private boolean pendingListen = false;
    private long lastLevel = 0;
    static final int REQ_MIC = 42;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("kalimat", MODE_PRIVATE);
        chosenVoice = prefs.getString("voice", "");

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
                applyVoice();
                // play speech as clean media audio (not call/assistant routing, which can sound hollow)
                try {
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                } catch (Exception ignored) { }
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

        cleanupPending();
        installBundledIfNeeded();
        loadPage();
        new Thread(() -> runUpdateCheck(false)).start();
        try { DailyUpdateJob.schedule(this); } catch (Exception e) { e.printStackTrace(); }   // 🤫 daily background check; never block opening
        handleInstallIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleInstallIntent(intent);
    }

    private void notifyDone() {
        ui.post(() -> web.evaluateJavascript("window.__ttsDone && window.__ttsDone()", null));
    }

    private void js(String fn, JSONObject arg) {
        final String code = "window." + fn + " && window." + fn + "(" + JSONObject.quote(arg.toString()) + ")";
        ui.post(() -> web.evaluateJavascript(code, null));
    }

    private File contentDir() { return new File(getFilesDir(), "web"); }

    private void loadPage() {
        web.loadUrl("file://" + new File(contentDir(), "index.html").getAbsolutePath());
    }

    // ---------- voices ----------
    private List<Voice> usVoices() {
        List<Voice> out = new ArrayList<>();
        if (tts == null || !ttsReady) return out;
        Set<Voice> all;
        try { all = tts.getVoices(); } catch (Exception e) { return out; }
        if (all == null) return out;
        for (Voice v : all) {
            Locale l = v.getLocale();
            if (l == null || !"en".equals(l.getLanguage()) || !"US".equals(l.getCountry())) continue;
            Set<String> f = v.getFeatures();
            if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            out.add(v);
        }
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    static boolean isMale(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("female")) return false;
        return n.contains("male") || n.contains("-iol") || n.contains("-iom") || n.contains("-tpd")
                || n.contains("iol-") || n.contains("iom-") || n.contains("tpd-")
                || n.contains("smtm") || n.contains("#m");
    }

    static String label(Voice v) {
        String n = v.getName();
        String code = n;
        String[] parts = n.split("-");
        if (parts.length >= 4 && "x".equals(parts[2])) code = parts[3];
        return (isMale(n) ? "Male " : "") + code + (v.isNetworkConnectionRequired() ? " · online" : " · offline");
    }

    /** Lower is calmer: Google's iom / iol / tpd are the American male voices. Offline first. */
    static int maleRank(Voice v) {
        String n = v.getName().toLowerCase(Locale.ROOT);
        int r = n.contains("iom") ? 0 : n.contains("iol") ? 1 : n.contains("tpd") ? 2 : 3;
        return r * 2 + (v.isNetworkConnectionRequired() ? 1 : 0);
    }

    private Voice bestMale() {
        Voice best = null;
        for (Voice v : usVoices()) {
            if (!isMale(v.getName())) continue;
            if (best == null || maleRank(v) < maleRank(best)) best = v;
        }
        return best;
    }

    /** Always a man's voice: the chosen one if it is male, otherwise the calmest male voice installed. */
    private void applyVoice() {
        if (tts == null || !ttsReady) return;
        String want = chosenVoice;
        if (want != null && !want.isEmpty() && isMale(want)) {
            for (Voice v : usVoices()) {
                if (v.getName().equals(want)) {
                    try { tts.setVoice(v); } catch (Exception ignored) { }
                    return;
                }
            }
        }
        Voice m = bestMale();
        if (m != null) { try { tts.setVoice(m); } catch (Exception ignored) { } }
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
    /** fromUser: the Settings button asked; report everything back to the page. */
    private void runUpdateCheck(boolean fromUser) {
        if (busyUpdating) {
            if (fromUser) {
                try { js("onUpdateInfo", new JSONObject().put("error", "⏳ بيدوّر على تحديث دلوقتي، استنى ثواني وجرّب تاني")); }
                catch (Exception ignored) { }
            }
            return;
        }
        busyUpdating = true;
        JSONObject info = new JSONObject();
        try {
            try {
                JSONObject apk = checkApk();
                if (apk != null) {
                    info.put("apk", apk);
                    if (apk.optBoolean("newer")) {
                        final String url = apk.optString("url");
                        final String ver = apk.optString("version");
                        ui.post(() -> web.evaluateJavascript(
                                "window.showApkUpdate && window.showApkUpdate(" + JSONObject.quote(url) + "," + JSONObject.quote(ver) + ")", null));
                    }
                }
            } catch (Exception ignored) { }
            if (!fromUser) prepareSilentUpdate(info.optJSONObject("apk"));
            try {
                boolean changed = updateContent(fromUser);
                info.put("content", changed ? "updated" : "same");
            } catch (Exception e) {
                info.put("error", "مفيش إنترنت أو GitHub مش متاح");
            }

            final boolean reload = "updated".equals(info.optString("content"));
            if (reload) {
                ui.post(() -> {
                    Toast.makeText(this, "✅ اتحدّثت الكلمات", Toast.LENGTH_SHORT).show();
                    loadPage();
                });
                // the reloaded page asks again for the APK state
                final JSONObject apk = info.optJSONObject("apk");
                if (apk != null && apk.optBoolean("newer")) {
                    ui.postDelayed(() -> web.evaluateJavascript(
                            "window.showApkUpdate && window.showApkUpdate(" + JSONObject.quote(apk.optString("url")) + "," + JSONObject.quote(apk.optString("version")) + ")", null), 2500);
                }
            } else if (fromUser) {
                js("onUpdateInfo", info);
            } else {
                JSONObject apk = info.optJSONObject("apk");
                if (apk != null && apk.optBoolean("newer")) {
                    final String url = apk.optString("url");
                    ui.postDelayed(() -> web.evaluateJavascript(
                            "window.showApkUpdate && window.showApkUpdate(" + JSONObject.quote(url) + ")", null), 1200);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            busyUpdating = false;
        }
    }

    /** Pulls web/ from the newest commit that touched it. Returns true when something changed. */
    private boolean updateContent(boolean report) throws Exception {
        JSONArray commits = new JSONArray(httpText(
                "https://api.github.com/repos/" + REPO + "/commits?sha=" + BRANCH + "&path=web&per_page=1"));
        if (commits.length() == 0) return false;
        String sha = commits.getJSONObject(0).getString("sha");
        if (sha.equals(prefs.getString("sha", ""))) return false;

        String base = "https://raw.githubusercontent.com/" + REPO + "/" + sha + "/web/";
        JSONArray files = new JSONObject(httpText(base + "manifest.json")).getJSONArray("files");

        File tmp = new File(getFilesDir(), "web_new");
        deleteRecursive(tmp);
        int n = files.length();
        for (int i = 0; i < n; i++) {
            String f = files.getString(i);
            if (f.contains("..")) continue;
            File out = new File(tmp, f);
            out.getParentFile().mkdirs();
            // pictures never change under the same name: reuse the copy we already have
            File have = new File(contentDir(), f);
            if (f.startsWith("img/") && have.isFile() && have.length() > 0) {
                try (InputStream in = new java.io.FileInputStream(have); OutputStream o = new FileOutputStream(out)) { pipe(in, o); }
            } else {
                // a weak connection must not cancel the whole update: retry, and a missing picture is skipped
                byte[] data = null;
                for (int t = 0; t < 3 && data == null; t++) {
                    try { data = httpBytes(base + f, null); }
                    catch (IOException e) { try { Thread.sleep(800L * (t + 1)); } catch (InterruptedException ignored) { } }
                }
                if (data == null) {
                    if (f.startsWith("img/")) continue;
                    throw new IOException("failed " + f);
                }
                try (OutputStream o = new FileOutputStream(out)) { o.write(data); }
            }
            if (report && (i % 5 == 0 || i == n - 1)) {
                progress((i + 1) * 100 / n, "⏳ بنزّل الكلمات والصور… " + (i + 1) + " / " + n, false, false);
            }
        }
        if (!new File(tmp, "index.html").exists()) return false;

        File old = new File(getFilesDir(), "web_old");
        deleteRecursive(old);
        contentDir().renameTo(old);
        if (!tmp.renameTo(contentDir())) { old.renameTo(contentDir()); return false; }
        deleteRecursive(old);
        prefs.edit().putString("sha", sha).apply();
        return true;
    }

    /** Release tags are v<build number>; the build number is the APK's versionCode. */
    private JSONObject checkApk() throws Exception {
        JSONObject rel = new JSONObject(httpText("https://api.github.com/repos/" + REPO + "/releases/latest"));
        String tag = rel.optString("tag_name", "").replaceAll("[^0-9]", "");
        if (tag.isEmpty()) return null;
        int latest = Integer.parseInt(tag);
        String url = null;
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break; }
            }
        }
        JSONObject o = new JSONObject();
        o.put("newer", latest > BuildConfig.VERSION_CODE && url != null);
        o.put("version", "1.0." + latest);
        o.put("url", url == null ? "" : url);
        return o;
    }

    // ---------- in-app APK install ----------
    private void installApk(String url) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            progress(0, "🔓 اسمح لـ «English for Boudy & Anoos» بتثبيت التطبيقات، ارجع، ودوس «نزّل وثبّت» تاني.", false, true);
            ui.post(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) { }
            });
            return;
        }
        new Thread(() -> {
            try {
                progress(0, "⏳ بنزّل النسخة الجديدة…", false, false);
                byte[] apk = httpBytes(url, p -> progress(p, "⏳ بنزّل… " + p + "%", false, false));
                progress(100, "📦 بنجهّز التثبيت…", false, false);

                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(getPackageName());
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int id = pi.createSession(params);
                try (PackageInstaller.Session session = pi.openSession(id)) {
                    try (OutputStream out = session.openWrite("kalimat.apk", 0, apk.length)) {
                        out.write(apk);
                        session.fsync(out);
                    }
                    Intent i = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent pending = PendingIntent.getActivity(this, 7, i, flags);
                    session.commit(pending.getIntentSender());
                }
                progress(100, "👆 وافق على التثبيت في الرسالة اللي ظهرت", true, false);
            } catch (Exception e) {
                e.printStackTrace();
                progress(0, "❌ التنزيل فشل. اتأكد من الإنترنت وجرّب تاني.", false, true);
            }
        }).start();
    }

    // ---------- silent self-update ----------
    // The new APK downloads in the background; it installs when the kid leaves the app (or on next launch),
    // without any question, once Android allows it (Android 12+, and the app installed itself at least once).
    private File pendingApk() { return new File(getFilesDir(), "update.apk"); }
    private volatile boolean resumed = false;

    private void prepareSilentUpdate(JSONObject apk) {
        try {
            if (apk == null || !apk.optBoolean("newer")) { cleanupPending(); return; }
            if (Build.VERSION.SDK_INT < 31) return;
            if (!getPackageManager().canRequestPackageInstalls()) return;
            int ver = Integer.parseInt(apk.optString("version").replaceAll(".*\\.", ""));
            File f = pendingApk();
            if (!(f.isFile() && prefs.getInt("pendingVer", -1) == ver)) {
                byte[] data = httpBytes(apk.optString("url"), null);
                try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
                prefs.edit().putInt("pendingVer", ver).putBoolean("needsConfirm", false).apply();
            }
            if (!resumed) installPendingSilently();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void cleanupPending() {
        File f = pendingApk();
        if (f.exists() && prefs.getInt("pendingVer", -1) <= BuildConfig.VERSION_CODE) {
            f.delete();
            prefs.edit().remove("pendingVer").remove("needsConfirm").apply();
        }
    }

    private void installPendingSilently() {
        File f = pendingApk();
        if (Build.VERSION.SDK_INT < 31 || !f.isFile()) return;
        if (prefs.getInt("pendingVer", -1) <= BuildConfig.VERSION_CODE) { cleanupPending(); return; }
        if (prefs.getBoolean("needsConfirm", false)) return;   // Android wants a tap: the banner handles it
        new Thread(() -> {
            try {
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(getPackageName());
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int id = pi.createSession(params);
                try (PackageInstaller.Session session = pi.openSession(id)) {
                    try (InputStream in = new java.io.FileInputStream(f);
                         OutputStream out = session.openWrite("kalimat.apk", 0, f.length())) {
                        pipe(in, out);
                        session.fsync(out);
                    }
                    Intent i = new Intent(this, SilentUpdateReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
                    PendingIntent pending = PendingIntent.getBroadcast(this, 9, i, flags);
                    session.commit(pending.getIntentSender());
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    /** Result of a silent install. If Android insists on a tap, remember it and show the update banner next time. */
    public static class SilentUpdateReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                c.getSharedPreferences("kalimat", MODE_PRIVATE).edit().putBoolean("needsConfirm", true).apply();
            }
        }
    }

    @Override protected void onResume() { super.onResume(); resumed = true; }
    @Override protected void onPause() { super.onPause(); resumed = false; }
    @Override protected void onStop() { super.onStop(); installPendingSilently(); }

    private void handleInstallIntent(Intent intent) {
        if (intent == null || !ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try { startActivity(confirm); } catch (Exception ignored) { }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(this, "✅ اتثبّتت النسخة الجديدة", Toast.LENGTH_LONG).show();
        } else if (status != -999) {
            progress(0, "❌ التثبيت ما كملش (" + status + "). جرّب تاني.", false, true);
        }
    }

    interface Progress { void on(int percent); }

    private void progress(int percent, String message, boolean done, boolean error) {
        try {
            JSONObject p = new JSONObject();
            p.put("percent", percent);
            p.put("message", message);
            p.put("done", done);
            p.put("error", error);
            js("onUpdateProgress", p);
        } catch (Exception ignored) { }
    }

    // ---------- small helpers ----------
    private static byte[] httpBytes(String url, Progress progress) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Kalimat-Android");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " " + url);
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n; long got = 0; int last = -1;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    got += n;
                    if (progress != null && total > 0) {
                        int p = (int) (got * 100 / total);
                        if (p != last && p % 5 == 0) { last = p; progress.on(p); }
                    }
                }
                return b.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String httpText(String url) throws IOException {
        return new String(httpBytes(url, null), StandardCharsets.UTF_8);
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
        web.evaluateJavascript("(window.onAndroidBack && window.onAndroidBack()) ? 'y' : 'n'", value -> {
            if (value == null || !value.contains("y")) finish();
        });
    }

    // ---------- the kid says the word: Android speech recognition, results go back to the page ----------
    private void heard(JSONObject o) { js("onHeard", o); }
    private void heardError(String code) {
        try { heard(new JSONObject().put("error", code)); } catch (Exception ignored) { }
    }

    private void startListening() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingListen = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { heardError("no-recognizer"); return; }
        if (tts != null) tts.stop();
        if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) { } }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { micState("listening"); }
            @Override public void onBeginningOfSpeech() { micState("speaking"); }
            @Override public void onRmsChanged(float rms) {
                long now = System.currentTimeMillis();
                if (now - lastLevel < 90) return;
                lastLevel = now;
                final String code = "window.onMicLevel && window.onMicLevel(" + rms + ")";
                web.evaluateJavascript(code, null);
            }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { micState("thinking"); }
            @Override public void onError(int error) { heardError("e" + error); }
            @Override public void onResults(Bundle b) {
                try {
                    ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    float[] conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                    JSONArray arr = new JSONArray();
                    if (list != null) for (String r : list) arr.put(r);
                    JSONObject o = new JSONObject().put("results", arr);
                    if (conf != null && conf.length > 0) o.put("conf", (double) conf[0]);
                    heard(o);
                } catch (Exception e) { heardError("bad-result"); }
            }
            @Override public void onPartialResults(Bundle b) { }
            @Override public void onEvent(int type, Bundle b) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // kids speak slowly: give them time before the recognizer gives up
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        try { recognizer.startListening(i); } catch (Exception e) { heardError("start-failed"); }
    }

    private void micState(String st) {
        web.evaluateJavascript("window.onMicState && window.onMicState(" + JSONObject.quote(st) + ")", null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (ok && pendingListen) { pendingListen = false; startListening(); }
        else { pendingListen = false; heardError("permission"); }
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) { } }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    /** Exposed to the page as window.AndroidTTS */
    class Bridge {
        @JavascriptInterface
        public void speak(String text, float rate) {
            speak(text, rate, 1f);
        }

        @JavascriptInterface
        public void speak(String text, float rate, float pitch) {
            if (tts == null || !ttsReady) { notifyDone(); return; }
            tts.setSpeechRate(rate <= 0 ? 0.75f : rate);
            tts.setPitch(pitch <= 0 ? 1f : pitch);
            Bundle p = new Bundle();
            p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, "k" + System.nanoTime());
        }

        @JavascriptInterface
        public void stop() {
            if (tts != null) tts.stop();
        }

        @JavascriptInterface
        public boolean ready() {
            return ttsReady;
        }

        @JavascriptInterface
        public String voices() {
            JSONArray arr = new JSONArray();
            for (Voice v : usVoices()) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", v.getName());
                    o.put("label", label(v));
                    o.put("male", isMale(v.getName()));
                    o.put("online", v.isNetworkConnectionRequired());
                    arr.put(o);
                } catch (Exception ignored) { }
            }
            return arr.toString();
        }

        @JavascriptInterface
        public void setVoice(String name) {
            chosenVoice = name == null ? "" : name;
            prefs.edit().putString("voice", chosenVoice).apply();
            applyVoice();
        }

        @JavascriptInterface
        public boolean silentUpdates() {
            return Build.VERSION.SDK_INT >= 31 && getPackageManager().canRequestPackageInstalls();
        }

        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void listen() {
            ui.post(MainActivity.this::startListening);
        }

        @JavascriptInterface
        public void stopListening() {
            ui.post(() -> { if (recognizer != null) { try { recognizer.cancel(); } catch (Exception ignored) { } } });
        }

        @JavascriptInterface
        public void checkUpdates() {
            new Thread(() -> runUpdateCheck(true)).start();
        }

        @JavascriptInterface
        public void installApk(String url) {
            MainActivity.this.installApk(url);
        }
    }
}
