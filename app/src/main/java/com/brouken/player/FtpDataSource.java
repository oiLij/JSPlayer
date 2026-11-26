package com.brouken.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;

public class FtpDataSource extends BaseDataSource {

    public static class Factory implements DataSource.Factory {
        @Override
        public DataSource createDataSource() {
            return new FtpDataSource();
        }
    }

    private FTPClient ftpClient;
    private InputStream inputStream;
    private Uri uri;
    private long bytesRemaining;
    private boolean opened;

    public FtpDataSource() {
        super(/* isNetwork= */ true);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.uri = dataSpec.uri;
        transferInitializing(dataSpec);

        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 21;
        String userInfo = uri.getUserInfo();
        String user = "anonymous";
        String pass = "";
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                user = URLDecoder.decode(userInfo.substring(0, colon), "UTF-8");
                pass = URLDecoder.decode(userInfo.substring(colon + 1), "UTF-8");
            } else {
                user = URLDecoder.decode(userInfo, "UTF-8");
            }
        }

        String path = uri.getPath();
        if (path == null) path = "/";

        ftpClient = new FTPClient();
        ftpClient.setConnectTimeout(5000);
        ftpClient.setDefaultTimeout(10000);
        ftpClient.setControlEncoding("UTF-8");
        ftpClient.connect(host, port);

        if (!ftpClient.login(user, pass)) {
            throw new IOException("FTP login failed for " + user + "@" + host);
        }

        ftpClient.enterLocalPassiveMode();
        ftpClient.setFileType(FTP.BINARY_FILE_TYPE);

        long fileLength = C.LENGTH_UNSET;
        try {
            String sizeStr = ftpClient.getSize(path);
            if (sizeStr != null) {
                fileLength = Long.parseLong(sizeStr.trim());
            }
        } catch (Exception ignored) {
        }

        if (dataSpec.position > 0) {
            ftpClient.setRestartOffset(dataSpec.position);
        }

        java.io.InputStream raw = ftpClient.retrieveFileStream(path);
        inputStream = raw != null ? new java.io.BufferedInputStream(raw, 262144) : null;
        if (inputStream == null) {
            throw new IOException("FTP failed to open stream: " + path + " (" + ftpClient.getReplyString() + ")");
        }

        if (dataSpec.length != C.LENGTH_UNSET) {
            bytesRemaining = dataSpec.length;
        } else if (fileLength != C.LENGTH_UNSET) {
            bytesRemaining = Math.max(0, fileLength - dataSpec.position);
        } else {
            bytesRemaining = C.LENGTH_UNSET;
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
        int read = inputStream.read(buffer, offset, toRead);
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
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (Exception ignored) {
        } finally {
            inputStream = null;
            if (ftpClient != null) {
                try {
                    if (ftpClient.isConnected()) {
                        ftpClient.disconnect();
                    }
                } catch (Exception ignored) {
                }
                ftpClient = null;
            }
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }
}
