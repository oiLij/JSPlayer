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

import jcifs.CIFSContext;
import jcifs.SmbResource;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.SmbRandomAccess;

public class SmbDataSource extends BaseDataSource {

    public static class Factory implements DataSource.Factory {
        @Override
        public DataSource createDataSource() {
            return new SmbDataSource();
        }
    }

    private SmbRandomAccess raf;
    private SmbFile smbFile;
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

        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "smb";
        String host = uri.getHost();
        int port = uri.getPort();
        String encodedPath = uri.getEncodedPath();
        if (encodedPath == null || encodedPath.isEmpty()) encodedPath = "/";

        Properties props = new Properties();
        if ("smb1".equals(scheme)) {
            props.setProperty("jcifs.smb.client.minVersion", "SMB1");
            props.setProperty("jcifs.smb.client.maxVersion", "SMB1");
        } else {
            props.setProperty("jcifs.smb.client.minVersion", "SMB202");
            props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        }
        props.setProperty("jcifs.smb.client.responseTimeout", "10000");
        props.setProperty("jcifs.smb.client.connTimeout", "8000");

        CIFSContext base = new BaseContext(new PropertyConfiguration(props));
        String userInfo = uri.getUserInfo();
        CIFSContext ctx;
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            String user = colon >= 0 ? URLDecoder.decode(userInfo.substring(0, colon), "UTF-8") : URLDecoder.decode(userInfo, "UTF-8");
            String pass = colon >= 0 ? URLDecoder.decode(userInfo.substring(colon + 1), "UTF-8") : "";
            ctx = base.withCredentials(new NtlmPasswordAuthenticator(null, user, pass));
        } else {
            ctx = base.withAnonymousCredentials();
        }

        String cleanUrl = "smb://" + host + (port > 0 && port != 445 ? ":" + port : "") + encodedPath;
        smbFile = new SmbFile(cleanUrl, ctx);
        long fileLength = smbFile.length();

        raf = smbFile.openRandomAccess("r");
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
