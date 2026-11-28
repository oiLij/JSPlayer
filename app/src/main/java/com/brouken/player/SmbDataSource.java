package com.brouken.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import jcifs.CIFSContext;
import jcifs.SmbRandomAccess;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

public class SmbDataSource extends BaseDataSource {

    public static class Factory implements DataSource.Factory {
        @Override
        public DataSource createDataSource() {
            return new SmbDataSource();
        }
    }

    private static final ConcurrentHashMap<String, CIFSContext> CONTEXT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> FILE_LENGTH_CACHE = new ConcurrentHashMap<>();
    private static final int BUFFER_SIZE = 262144; // 256 KB read-ahead buffer
    private final byte[] internalBuf = new byte[BUFFER_SIZE];
    private int bufPos = 0;
    private int bufLimit = 0;

    private SmbRandomAccess raf;
    private SmbFile smbFile;
    private Uri uri;
    private long bytesRemaining;
    private boolean opened;

    public SmbDataSource() {
        super(/* isNetwork= */ true);
    }

    static CIFSContext getOrCreateContext(String scheme, String host, String userInfo) throws IOException {
        String key = scheme + "|" + host + "|" + (userInfo != null ? userInfo : "");
        CIFSContext cached = CONTEXT_CACHE.get(key);
        if (cached != null) return cached;

        Properties props = new Properties();
        if ("smb1".equals(scheme)) {
            props.setProperty("jcifs.smb.client.minVersion", "SMB1");
            props.setProperty("jcifs.smb.client.maxVersion", "SMB1");
        } else {
            props.setProperty("jcifs.smb.client.minVersion", "SMB202");
            props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        }
        props.setProperty("jcifs.smb.client.soTimeout", "15000");
        props.setProperty("jcifs.smb.client.responseTimeout", "15000");
        props.setProperty("jcifs.smb.client.connTimeout", "10000");

        CIFSContext base = new BaseContext(new PropertyConfiguration(props));
        CIFSContext ctx;
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            String user = colon >= 0 ? URLDecoder.decode(userInfo.substring(0, colon), "UTF-8") : URLDecoder.decode(userInfo, "UTF-8");
            String pass = colon >= 0 ? URLDecoder.decode(userInfo.substring(colon + 1), "UTF-8") : "";
            ctx = base.withCredentials(new NtlmPasswordAuthenticator(null, user, pass));
        } else {
            ctx = base.withAnonymousCredentials();
        }
        CONTEXT_CACHE.put(key, ctx);
        return ctx;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.uri = dataSpec.uri;
        this.bufPos = 0;
        this.bufLimit = 0;
        transferInitializing(dataSpec);

        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "smb";
        String host = uri.getHost();
        int port = uri.getPort();
        String decodedPath = uri.getPath();
        if (decodedPath == null || decodedPath.isEmpty()) decodedPath = "/";

        CIFSContext ctx = getOrCreateContext(scheme, host, uri.getUserInfo());
        String cleanUrl = "smb://" + host + (port > 0 && port != 445 ? ":" + port : "") + decodedPath;

        try {
            smbFile = new SmbFile(cleanUrl, ctx);
            Long cachedLen = FILE_LENGTH_CACHE.get(cleanUrl);
            long fileLength;
            if (cachedLen != null && cachedLen > 0) {
                fileLength = cachedLen;
            } else {
                fileLength = smbFile.length();
                if (fileLength > 0) FILE_LENGTH_CACHE.put(cleanUrl, fileLength);
            }
            raf = smbFile.openRandomAccess("r", SmbFile.FILE_SHARE_READ);
            if (dataSpec.position > 0) {
                raf.seek(dataSpec.position);
            }
            if (dataSpec.length != C.LENGTH_UNSET) {
                bytesRemaining = dataSpec.length;
            } else {
                bytesRemaining = Math.max(0, fileLength - dataSpec.position);
            }
        } catch (Exception e) {
            throw new IOException("SMB Error (" + cleanUrl + "): " + e.getMessage(), e);
        }

        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;

        if (bufPos >= bufLimit) {
            int toFetch = bytesRemaining == C.LENGTH_UNSET ? BUFFER_SIZE : (int) Math.min(bytesRemaining, BUFFER_SIZE);
            int fetched = raf.read(internalBuf, 0, toFetch);
            if (fetched <= 0) {
                return C.RESULT_END_OF_INPUT;
            }
            bufPos = 0;
            bufLimit = fetched;
        }

        int available = bufLimit - bufPos;
        int toCopy = Math.min(length, available);
        if (bytesRemaining != C.LENGTH_UNSET) {
            toCopy = (int) Math.min(toCopy, bytesRemaining);
        }
        System.arraycopy(internalBuf, bufPos, buffer, offset, toCopy);
        bufPos += toCopy;
        if (bytesRemaining != C.LENGTH_UNSET) {
            bytesRemaining -= toCopy;
        }
        bytesTransferred(toCopy);
        return toCopy;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        uri = null;
        bufPos = 0;
        bufLimit = 0;
        try { if (raf != null) raf.close(); } catch (Exception ignored) {}
        try { if (smbFile != null) smbFile.close(); } catch (Exception ignored) {}
        raf = null;
        smbFile = null;
        if (opened) {
            opened = false;
            transferEnded();
        }
    }
}
