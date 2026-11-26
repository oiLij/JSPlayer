package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

public class NetworkBrowser {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_DLNA_SERVER = 1;
    public static final int TYPE_SMB_SERVER = 2;
    public static final int TYPE_FTP_SERVER = 3;
    public static final int TYPE_STREAM_URL = 4;
    public static final int TYPE_ADD_SERVER = 5;
    public static final int TYPE_FOLDER = 6;
    public static final int TYPE_MEDIA = 7;

    public static class NetItem {
        public final int type;
        public final String title;
        public final String id;        // DLNA objectId or saved server index
        public final String protocol;  // "dlna", "smb", "smb1", "ftp"
        public final String host;
        public final int port;
        public final String user;
        public final String pass;
        public final String path;      // folder path or full media/control URL
        public final boolean isSaved;

        public NetItem(int type, String title, String id, String protocol, String host, int port,
                       String user, String pass, String path, boolean isSaved) {
            this.type = type;
            this.title = title;
            this.id = id;
            this.protocol = protocol;
            this.host = host;
            this.port = port;
            this.user = user;
            this.pass = pass;
            this.path = path;
            this.isSaved = isSaved;
        }
    }

    public interface ListCallback {
        void onResult(List<NetItem> items, String error);
    }

    public interface DiscoveryCallback {
        void onFound(NetItem item);
    }

    private static final String PREFS_NAME = "jsplayer_network";
    private static final String KEY_SERVERS = "saved_servers";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final java.util.concurrent.ConcurrentHashMap<String, List<NetItem>> DIR_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, List<NetItem>> DIR_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, FTPClient> FTP_POOL = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener smbListener;
    private NsdManager.DiscoveryListener ftpListener;

    private final Queue<PendingResolve> resolveQueue = new LinkedList<>();
    private boolean isResolving = false;
    
    private static class PendingResolve {
        final NsdServiceInfo service;
        final String proto;
        final int itemType;
        final DiscoveryCallback callback;
    
        PendingResolve(NsdServiceInfo service, String proto, int itemType, DiscoveryCallback callback) {
            this.service = service;
            this.proto = proto;
            this.itemType = itemType;
            this.callback = callback;
        }
    }

    public void startMdnsDiscovery(Context context, DiscoveryCallback callback) {
        stopMdnsDiscovery();
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) return;

        smbListener = createListener("smb", TYPE_SMB_SERVER, callback);
        ftpListener = createListener("ftp", TYPE_FTP_SERVER, callback);

        try {
            nsdManager.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, smbListener);
            nsdManager.discoverServices("_ftp._tcp.", NsdManager.PROTOCOL_DNS_SD, ftpListener);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private NsdManager.DiscoveryListener createListener(String proto, int itemType, DiscoveryCallback callback) {
        return new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String regType) {}
            @Override public void onDiscoveryStopped(String serviceType) {}
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onServiceLost(NsdServiceInfo service) {}
    
            @Override
            public void onServiceFound(NsdServiceInfo service) {
                synchronized (resolveQueue) {
                    resolveQueue.add(new PendingResolve(service, proto, itemType, callback));
                    if (!isResolving) {
                        resolveNext();
                    }
                }
            }
        };
    }
    
    private void resolveNext() {
        PendingResolve next;
        synchronized (resolveQueue) {
            next = resolveQueue.poll();
            if (next == null) {
                isResolving = false;
                return;
            }
            isResolving = true;
        }
        try {
            nsdManager.resolveService(next.service, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    resolveNext();
                }
    
                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    InetAddress addr = info.getHost();
                    if (addr != null) {
                        String host = addr.getHostAddress();
                        int port = info.getPort();
                        String tag = next.proto.equals("smb") ? "[SMB] " : "[FTP] ";
                        String title = tag + info.getServiceName() + " (" + host + ":" + port + ")";
                        NetItem item = new NetItem(next.itemType, title, null, next.proto, host, port, "", "", "/", false);
                        mainHandler.post(() -> next.callback.onFound(item));
                    }
                    resolveNext();
                }
            });
        } catch (Exception e) {
            resolveNext();
        }
    }

    public void stopMdnsDiscovery() {
        if (nsdManager != null) {
            try { if (smbListener != null) nsdManager.stopServiceDiscovery(smbListener); } catch (Exception ignored) {}
            try { if (ftpListener != null) nsdManager.stopServiceDiscovery(ftpListener); } catch (Exception ignored) {}
        }
        smbListener = null;
        ftpListener = null;
        synchronized (resolveQueue) {
            resolveQueue.clear();
            isResolving = false;
        }
    }

    private static List<NetItem> listFtpDirSync(String host, int port, String user, String pass, String dirPath) throws Exception {
        String cacheKey = "ftp|" + host + "|" + port + "|" + (user != null ? user : "") + "|" + dirPath;
        List<NetItem> cached = DIR_CACHE.get(cacheKey);
        if (cached != null) return new ArrayList<>(cached);
    
        String poolKey = host + "|" + port + "|" + (user != null ? user : "");
        FTPClient client = FTP_POOL.computeIfAbsent(poolKey, k -> new FTPClient());
        List<NetItem> result = new ArrayList<>();
        synchronized (client) {
            cached = DIR_CACHE.get(cacheKey);
            if (cached != null) return new ArrayList<>(cached);
            try {
                if (!client.isConnected() || !client.sendNoOp()) {
                    try { client.disconnect(); } catch (Exception ignored) {}
                    client.setConnectTimeout(4000);
                    client.setDefaultTimeout(6000);
                    client.setControlEncoding("UTF-8");
                    client.connect(host, port > 0 ? port : 21);
                    String u = (user == null || user.isEmpty()) ? "anonymous" : user;
                    String p = (pass == null) ? "" : pass;
                    if (!client.login(u, p)) {
                        throw new Exception("FTP Login Failed");
                    }
                    client.enterLocalPassiveMode();
                    client.setFileType(FTP.BINARY_FILE_TYPE);
                }
            } catch (Exception reconnectErr) {
                try { client.disconnect(); } catch (Exception ignored) {}
                client.setConnectTimeout(4000);
                client.setDefaultTimeout(6000);
                client.setControlEncoding("UTF-8");
                client.connect(host, port > 0 ? port : 21);
                String u = (user == null || user.isEmpty()) ? "anonymous" : user;
                String p = (pass == null) ? "" : pass;
                if (!client.login(u, p)) {
                    throw new Exception("FTP Login Failed");
                }
                client.enterLocalPassiveMode();
                client.setFileType(FTP.BINARY_FILE_TYPE);
            }
    
            String cleanDir = dirPath.endsWith("/") ? dirPath : dirPath + "/";
            FTPFile[] files = client.listFiles(cleanDir);
            if (files != null) {
                for (FTPFile f : files) {
                    String name = f.getName();
                    if (name == null || ".".equals(name) || "..".equals(name)) continue;
                    String fullPath = cleanDir + name;
                    if (f.isDirectory()) {
                        result.add(new NetItem(TYPE_FOLDER, "[Folder] " + name, null, "ftp", host, port, user, pass, fullPath + "/", false));
                    } else if (isMediaFile(name)) {
                        String url = buildAuthUrl("ftp", host, port, user, pass, fullPath);
                        result.add(new NetItem(TYPE_MEDIA, name, null, "ftp", host, port, user, pass, url, false));
                    }
                }
            }
            DIR_CACHE.put(cacheKey, new ArrayList<>(result));
        }
        return result;
    }
    
    public static void browseFtp(String host, int port, String user, String pass, String dirPath, ListCallback callback) {
        String cacheKey = "ftp|" + host + "|" + port + "|" + (user != null ? user : "") + "|" + dirPath;
        List<NetItem> cached = DIR_CACHE.get(cacheKey);
        if (cached != null) {
            mainHandler.post(() -> callback.onResult(new ArrayList<>(cached), null));
            return;
        }
        executor.execute(() -> {
            try {
                List<NetItem> result = listFtpDirSync(host, port, user, pass, dirPath);
                mainHandler.post(() -> callback.onResult(result, null));
                int prefetched = 0;
                for (NetItem item : result) {
                    if (item.type == TYPE_FOLDER && prefetched < 6) {
                        prefetched++;
                        try { listFtpDirSync(host, port, user, pass, item.path); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onResult(new ArrayList<>(), e.getMessage()));
            }
        });
    }

    public static void browseSmb(String protocol, String host, int port, String user, String pass, String dirPath, ListCallback callback) {
        String cacheKey = protocol + "|" + host + "|" + port + "|" + (user != null ? user : "") + "|" + dirPath;
        List<NetItem> cached = DIR_CACHE.get(cacheKey);
        if (cached != null) {
            mainHandler.post(() -> callback.onResult(new ArrayList<>(cached), null));
            return;
        }
        executor.execute(() -> {
            List<NetItem> result = new ArrayList<>();
            try {
                String userInfo = null;
                if (user != null && !user.isEmpty()) {
                    userInfo = URLEncoder.encode(user, "UTF-8") + ":" + URLEncoder.encode(pass != null ? pass : "", "UTF-8");
                }
                CIFSContext ctx = SmbDataSource.getOrCreateContext(protocol, host, userInfo);

                String cleanDir = dirPath.startsWith("/") ? dirPath : "/" + dirPath;
                if (!cleanDir.endsWith("/")) cleanDir += "/";
                String smbUrl = "smb://" + host + (port > 0 && port != 445 ? ":" + port : "") + cleanDir;

                SmbFile dir = new SmbFile(smbUrl, ctx);
                SmbFile[] files = dir.listFiles();
                if (files != null) {
                    for (SmbFile f : files) {
                        String name = f.getName();
                        if (name == null || name.endsWith("$/")) continue;
                        if (f.isDirectory()) {
                            String display = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                            result.add(new NetItem(TYPE_FOLDER, "[Folder] " + display, null, protocol, host, port, user, pass, cleanDir + display + "/", false));
                        } else if (isMediaFile(name)) {
                            String url = buildAuthUrl("smb", host, port, user, pass, cleanDir + name);
                            result.add(new NetItem(TYPE_MEDIA, name, null, protocol, host, port, user, pass, url, false));
                        }
                    }
                }
                mainHandler.post(() -> callback.onResult(result, null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onResult(result, e.getMessage()));
            }
        });
    }

    private static String buildAuthUrl(String scheme, String host, int port, String user, String pass, String path) throws Exception {
        StringBuilder sb = new StringBuilder(scheme).append("://");
        if (user != null && !user.isEmpty()) {
            sb.append(URLEncoder.encode(user, "UTF-8"));
            if (pass != null && !pass.isEmpty()) {
                sb.append(":").append(URLEncoder.encode(pass, "UTF-8"));
            }
            sb.append("@");
        }
        sb.append(host);
        if (port > 0) sb.append(":").append(port);
        String[] segments = path.split("/");
        for (String seg : segments) {
            if (!seg.isEmpty()) {
                sb.append("/").append(Uri.encode(seg));
            }
        }
        return sb.toString();
    }

    private static boolean isMediaFile(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        return n.endsWith(".mkv") || n.endsWith(".mp4") || n.endsWith(".avi") || n.endsWith(".mov")
                || n.endsWith(".webm") || n.endsWith(".ts") || n.endsWith(".m4v") || n.endsWith(".flv")
                || n.endsWith(".flac") || n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav")
                || n.endsWith(".ogg") || n.endsWith(".opus") || n.endsWith(".eac3") || n.endsWith(".ec3")
                || n.endsWith(".ac3") || n.endsWith(".dts") || n.endsWith(".dtshd") || n.endsWith(".thd")
                || n.endsWith(".truehd") || n.endsWith(".mka") || n.endsWith(".aac") || n.endsWith(".wma")
                || n.endsWith(".alac") || n.endsWith(".aiff") || n.endsWith(".mp2") || n.endsWith(".mpa");
    }

    public static List<NetItem> getSavedServers(Context context) {
        List<NetItem> list = new ArrayList<>();
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_SERVERS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String proto = o.optString("protocol", "smb");
                String name = o.optString("name", o.optString("host"));
                String tag = proto.equals("ftp") ? "[FTP] " : (proto.equals("smb1") ? "[SMB1] " : "[SMB] ");
                int type = proto.equals("ftp") ? TYPE_FTP_SERVER : TYPE_SMB_SERVER;
                list.add(new NetItem(type, tag + name, String.valueOf(i), proto,
                        o.optString("host"), o.optInt("port", proto.equals("ftp") ? 21 : 445),
                        o.optString("user", ""), o.optString("pass", ""), "/", true));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public static void saveServer(Context context, String name, String protocol, String host, int port, String user, String pass) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_SERVERS, "[]"));
            JSONObject o = new JSONObject();
            o.put("name", name.isEmpty() ? host : name);
            o.put("protocol", protocol);
            o.put("host", host);
            o.put("port", port);
            o.put("user", user);
            o.put("pass", pass);
            arr.put(o);
            sp.edit().putString(KEY_SERVERS, arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void deleteSavedServer(Context context, int index) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_SERVERS, "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (i != index) next.put(arr.get(i));
            }
            sp.edit().putString(KEY_SERVERS, next.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
