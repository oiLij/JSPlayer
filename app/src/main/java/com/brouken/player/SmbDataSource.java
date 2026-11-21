package com.brouken.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.net.URLDecoder;

import jcifs.CIFSContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

public class SmbDataSource extends BaseDataSource {

    public static class Factory implements DataSource.Factory {
        @Override
        public DataSource createDataSource() {
            return new SmbDataSource();
        }
    }

    private SmbRandomAccessFile raf;
    private Uri uri;
    private long bytesRemaining;
    private boolean opened;

    public SmbDataSource() {
        super(/* isNetwork= */ true);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.uri = dataSpec.uri;
        transferInitializing(dataSpec);

        String host = uri.getHost();
        int port = uri.getPort();
        String path = uri.getPath();
        if (path == null) path = "/";

        String userInfo = uri.getUserInfo();
        CIFSContext base = SingletonContext.getInstance();
        CIFSContext ctx;
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            String user = colon >= 0 ? URLDecoder.decode(userInfo.substring(0, colon), "UTF-8") : URLDecoder.decode(userInfo, "UTF-8");
            String pass = colon >= 0 ? URLDecoder.decode(userInfo.substring(colon + 1), "UTF-8") : "";
            ctx = base.withCredentials(new NtlmPasswordAuthenticator(null, user, pass));
        } else {
            ctx = base.withAnonymousCredentials();
        }

        String cleanUrl = "smb://" + host + (port > 0 ? ":" + port : "") + path;
        SmbFile smbFile = new SmbFile(cleanUrl, ctx);
        long fileLength = smbFile.length();

        raf = new SmbRandomAccessFile(smbFile, "r");
        if (dataSpec.position > 0) {
            raf.seek(dataSpec.position);
        }

        if (dataSpec.length != C.LENGTH_UNSET) {
            bytesRemaining = dataSpec.length;
        } else {
            bytesRemaining = Math.max(0, fileLength - dataSpec.position);
        }

        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;

        int toRead = bytesRemaining == C.LENGTH_UNSET ? length : (int) Math.min(bytesRemaining, length);
        int read = raf.read(buffer, offset, toRead);
        if (read == -1) {
            return C.RESULT_END_OF_INPUT;
        }
        if (bytesRemaining != C.LENGTH_UNSET) {
            bytesRemaining -= read;
        }
        bytesTransferred(read);
        return read;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        uri = null;
        try {
            if (raf != null) {
                raf.close();
            }
        } catch (Exception ignored) {
        } finally {
            raf = null;
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }
}
