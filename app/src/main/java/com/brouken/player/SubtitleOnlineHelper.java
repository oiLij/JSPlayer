package com.brouken.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SubtitleOnlineHelper {

    public static final String[] LANG_NAMES = {
            "None (English Only)", "Tamil", "Hindi", "Malayalam", "Telugu", "Kannada", "Spanish", "French", "Arabic"
    };
    private static final String[] SUBDL_CODES = {"", "TA", "HI", "ML", "TE", "KN", "ES", "FR", "AR"};
    private static final String[] OS_CODES = {"", "tam", "hin", "mal", "tel", "kan", "spa", "fre", "ara"};

    public static class SubResult {
        public final String title;
        public final String downloadUrl;
        public final boolean isGzip; // true = OpenSubtitles (.gz), false = SubDL (.zip)

        public SubResult(String title, String downloadUrl, boolean isGzip) {
            this.title = title;
            this.downloadUrl = downloadUrl;
            this.isGzip = isGzip;
        }
    }

    public interface SearchCallback {
        void onResult(List<SubResult> results, String error);
    }

    public interface DownloadCallback {
        void onDownloaded(File subtitleFile, String error);
    }

    private static final String PREFS = "jsplayer_subs";
    private static final String KEY_API = "subdl_api_key";
    private static final String KEY_SEC_LANG_IDX = "secondary_lang_idx";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static String getApiKey(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, "");
    }

    public static void saveApiKey(Context ctx, String key) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API, key.trim()).apply();
    }

    public static int getSecondaryLangIndex(Context ctx) {
        int idx = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SEC_LANG_IDX, 0);
        return (idx >= 0 && idx < LANG_NAMES.length) ? idx : 0;
    }

    public static void saveSecondaryLangIndex(Context ctx, int idx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SEC_LANG_IDX, idx).apply();
    }

    public static String getSecondaryLangName(Context ctx) {
        return LANG_NAMES[getSecondaryLangIndex(ctx)];
    }

    public static String cleanQuery(String rawName) {
        if (rawName == null) return "";
        String s = rawName.replaceAll("\\.[a-zA-Z0-9]{2,4}$", "");
        s = s.replaceAll("[._]+", " ");
        s = s.replaceAll("(?i)\\b(1080p|720p|2160p|4k|x264|x265|hevc|10bit|web-dl|webrip|bluray|ddp5\\.1|eac3|ac3|aac|ds4k)\\b.*", "");
        return s.trim();
    }

    public static void searchOpenSubtitles(Context ctx, String query, SearchCallback cb) {
        int secIdx = getSecondaryLangIndex(ctx);
        String langs = secIdx > 0 ? ("eng," + OS_CODES[secIdx]) : "eng";

        executor.execute(() -> {
            List<SubResult> list = new ArrayList<>();
            try {
                String encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20");
                String urlStr = "https://rest.opensubtitles.org/search/query-" + encoded + "/sublanguageid-" + langs;

                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "VLSub 0.10.2");

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONArray arr = new JSONArray(sb.toString());
                int max = Math.min(arr.length(), 25);
                for (int i = 0; i < max; i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String name = o.optString("SubFileName", o.optString("MovieReleaseName", "Subtitle " + (i + 1)));
                    String lang = o.optString("ISO639", o.optString("SubLanguageID", "en")).toUpperCase();
                    String dlUrl = o.optString("SubDownloadLink", "");
                    if (!dlUrl.isEmpty()) {
                        list.add(new SubResult("[" + lang + "] " + name, dlUrl, true));
                    }
                }
                mainHandler.post(() -> cb.onResult(list, null));
            } catch (Exception e) {
                mainHandler.post(() -> cb.onResult(list, e.getMessage()));
            }
        });
    }

    public static void searchSubdl(Context ctx, String apiKey, String query, SearchCallback cb) {
        int secIdx = getSecondaryLangIndex(ctx);
        String langs = secIdx > 0 ? ("EN," + SUBDL_CODES[secIdx]) : "EN";

        executor.execute(() -> {
            List<SubResult> list = new ArrayList<>();
            try {
                String urlStr = "https://api.subdl.com/api/v1/subtitles?api_key=" + URLEncoder.encode(apiKey, "UTF-8")
                        + "&film_name=" + URLEncoder.encode(query, "UTF-8")
                        + "&languages=" + URLEncoder.encode(langs, "UTF-8")
                        + "&subs_per_page=20";

                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "JSPlayer/1.0");

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json = new JSONObject(sb.toString());
                if (!json.optBoolean("status", false)) {
                    throw new Exception(json.optString("error", "SubDL search failed"));
                }

                JSONArray subs = json.optJSONArray("subtitles");
                if (subs != null) {
                    for (int i = 0; i < subs.length(); i++) {
                        JSONObject o = subs.getJSONObject(i);
                        String release = o.optString("release_name", o.optString("name", "Subtitle " + (i + 1)));
                        String lang = o.optString("lang", o.optString("language", ""));
                        String dlPath = o.optString("url", "");
                        if (!dlPath.isEmpty()) {
                            String fullUrl = dlPath.startsWith("http") ? dlPath : "https://dl.subdl.com" + dlPath;
                            list.add(new SubResult("[" + lang + "] " + release, fullUrl, false));
                        }
                    }
                }
                mainHandler.post(() -> cb.onResult(list, null));
            } catch (Exception e) {
                mainHandler.post(() -> cb.onResult(list, e.getMessage()));
            }
        });
    }

    public static void downloadAndExtract(Context ctx, SubResult item, DownloadCallback cb) {
        executor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(item.downloadUrl).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", item.isGzip ? "VLSub 0.10.2" : "JSPlayer/1.0");

                InputStream is = conn.getInputStream();
                File cacheDir = new File(ctx.getCacheDir(), "online_subs");
                if (!cacheDir.exists()) cacheDir.mkdirs();

                File extracted = null;
                if (item.isGzip) {
                    extracted = new File(cacheDir, "sub_" + System.currentTimeMillis() + ".srt");
                    try (GZIPInputStream gis = new GZIPInputStream(is);
                         FileOutputStream fos = new FileOutputStream(extracted)) {
                        byte[] buf = new byte[4096];
                        int len;
                        while ((len = gis.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                } else {
                    try (ZipInputStream zis = new ZipInputStream(is)) {
                        ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            if (entry.isDirectory()) continue;
                            String name = entry.getName().toLowerCase();
                            if (name.endsWith(".srt") || name.endsWith(".ass") || name.endsWith(".ssa") || name.endsWith(".vtt")) {
                                String ext = name.substring(name.lastIndexOf('.'));
                                extracted = new File(cacheDir, "sub_" + System.currentTimeMillis() + ext);
                                try (FileOutputStream fos = new FileOutputStream(extracted)) {
                                    byte[] buf = new byte[4096];
                                    int len;
                                    while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                                }
                                break;
                            }
                        }
                    }
                }

                if (extracted == null) {
                    throw new Exception("No subtitle file found in download");
                }
                File finalFile = extracted;
                mainHandler.post(() -> cb.onDownloaded(finalFile, null));
            } catch (Exception e) {
                mainHandler.post(() -> cb.onDownloaded(null, e.getMessage()));
            }
        });
    }
}
