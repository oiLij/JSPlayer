package com.brouken.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.SubtitleParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ExternalAudioHelper {
    public static final int REQUEST_PICK_AUDIO = 9914;

    public interface AudioSelectedCallback {
        void onAudioSelected(Uri audioUri);
        void onAudioCleared();
        default void onDialogClosedWithoutSelection() {}
    }

    public static class RoutingDataSource implements DataSource {
        private final Context context;
        private DataSource activeDataSource;
        private final List<TransferListener> listeners = new ArrayList<>();

        public RoutingDataSource(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            if (transferListener != null && !listeners.contains(transferListener)) {
                listeners.add(transferListener);
            }
            if (activeDataSource != null) {
                activeDataSource.addTransferListener(transferListener);
            }
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            String scheme = (dataSpec.uri != null && dataSpec.uri.getScheme() != null)
                    ? dataSpec.uri.getScheme().toLowerCase(Locale.US) : "";
            if ("ftp".equals(scheme)) {
                activeDataSource = new FtpDataSource();
            } else if ("smb".equals(scheme) || "smb1".equals(scheme)) {
                activeDataSource = new SmbDataSource();
            } else if ("http".equals(scheme) || "https".equals(scheme)) {
                DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                        .setUserAgent("JSPlayer")
                        .setConnectTimeoutMs(20000)
                        .setReadTimeoutMs(20000)
                        .setAllowCrossProtocolRedirects(true);
                String userInfo = dataSpec.uri.getUserInfo();
                if (userInfo != null && userInfo.contains(":")) {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Authorization", "Basic " + Base64.encodeToString(userInfo.getBytes(), Base64.NO_WRAP));
                    httpFactory.setDefaultRequestProperties(headers);
                }
                activeDataSource = httpFactory.createDataSource();
            } else {
                activeDataSource = new DefaultDataSource.Factory(context).createDataSource();
            }
            for (TransferListener listener : listeners) {
                activeDataSource.addTransferListener(listener);
            }
            return activeDataSource.open(dataSpec);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return activeDataSource.read(buffer, offset, length);
        }

        @Override
        public Uri getUri() {
            return activeDataSource != null ? activeDataSource.getUri() : null;
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return activeDataSource != null ? activeDataSource.getResponseHeaders() : Collections.emptyMap();
        }

        @Override
        public void close() throws IOException {
            if (activeDataSource != null) {
                try {
                    activeDataSource.close();
                } finally {
                    activeDataSource = null;
                }
            }
        }
    }

    public static MediaSource buildMergedSource(
            Context context,
            MediaItem videoItem,
            Uri externalAudioUri,
            ExtractorsFactory extractorsFactory,
            SubtitleParser.Factory subtitleParserFactory) {
        DataSource.Factory routingFactory = () -> new RoutingDataSource(context);
        MediaSource videoSource = new DefaultMediaSourceFactory(context, extractorsFactory)
                .setDataSourceFactory(routingFactory)
                .setSubtitleParserFactory(subtitleParserFactory)
                .createMediaSource(videoItem);

        String label = externalAudioUri.getLastPathSegment();
        if (label == null || label.isEmpty()) label = "External Audio";
        MediaItem audioItem = new MediaItem.Builder()
                .setUri(externalAudioUri)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(label).setDisplayTitle(label).build())
                .build();

        MediaSource audioSource = new DefaultMediaSourceFactory(context, extractorsFactory)
                .setDataSourceFactory(routingFactory)
                .createMediaSource(audioItem);

        return new MergingMediaSource(videoSource, audioSource);
    }

    public static boolean selectLastAudioTrack(ExoPlayer player, DefaultTrackSelector trackSelector) {
        if (player == null) return false;
        Tracks tracks = player.getCurrentTracks();
        List<Tracks.Group> audioGroups = new ArrayList<>();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_AUDIO && group.length > 0) {
                audioGroups.add(group);
            }
        }
        if (audioGroups.isEmpty()) return false;
        Tracks.Group target = audioGroups.get(audioGroups.size() - 1);
        TrackSelectionOverride override = new TrackSelectionOverride(target.getMediaTrackGroup(), 0);
        if (trackSelector != null) {
            trackSelector.setParameters(
                    trackSelector.buildUponParameters()
                            .setOverrideForType(override)
                            .build()
            );
        }
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters()
                        .buildUpon()
                        .setOverrideForType(override)
                        .build()
        );
        return audioGroups.size() >= 2;
    }

    public static void showSourceDialog(Activity activity, boolean hasExternalAudio, AudioSelectedCallback callback) {
        List<String> opts = new ArrayList<>();
        opts.add("Load Audio from Local / USB Storage");
        opts.add("Browse DLNA / SMB / FTP Network");
        if (hasExternalAudio) {
            opts.add("Remove External Audio Track");
        }

        final boolean[] pickedOrSubDialog = {false};
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("External Audio Track")
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    pickedOrSubDialog[0] = true;
                    if (which == 0) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*", "application/octet-stream"});
                        activity.startActivityForResult(intent, REQUEST_PICK_AUDIO);
                    } else if (which == 1) {
                        showNetworkServersDialog(activity, callback);
                    } else if (which == 2 && hasExternalAudio) {
                        callback.onAudioCleared();
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> callback.onDialogClosedWithoutSelection())
                .create();
        dialog.setOnCancelListener(d -> {
            if (!pickedOrSubDialog[0]) {
                callback.onDialogClosedWithoutSelection();
            }
        });
        dialog.show();
    }

    private static void showNetworkServersDialog(Activity activity, AudioSelectedCallback callback) {
        List<NetworkBrowser.NetItem> servers = new ArrayList<>(NetworkBrowser.getSavedServers(activity));
        List<String> labels = new ArrayList<>();
        for (NetworkBrowser.NetItem s : servers) {
            labels.add(s.title);
        }
        if (labels.isEmpty()) {
            labels.add("Scanning DLNA & LAN servers...");
        }

        final boolean[] chosenNav = {false};
        NetworkBrowser mdnsBrowser = new NetworkBrowser();
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Select Network Source")
                .setItems(labels.toArray(new String[0]), null)
                .setNegativeButton("Cancel", (d, w) -> {
                    mdnsBrowser.stopMdnsDiscovery();
                    callback.onDialogClosedWithoutSelection();
                })
                .create();

        dialog.setOnCancelListener(d -> {
            mdnsBrowser.stopMdnsDiscovery();
            if (!chosenNav[0]) {
                callback.onDialogClosedWithoutSelection();
            }
        });
        dialog.setOnDismissListener(d -> mdnsBrowser.stopMdnsDiscovery());

        Runnable refreshList = () -> {
            if (!dialog.isShowing()) return;
            android.widget.ListView lv = dialog.getListView();
            if (lv == null) return;
            List<String> updated = new ArrayList<>();
            for (NetworkBrowser.NetItem s : servers) {
                updated.add(s.title);
            }
            if (updated.isEmpty()) {
                updated.add("Scanning DLNA & LAN servers...");
            }
            lv.setAdapter(new android.widget.ArrayAdapter<>(activity, android.R.layout.select_dialog_item, updated));
            lv.setOnItemClickListener((parent, view, pos, id) -> {
                if (pos >= 0 && pos < servers.size()) {
                    chosenNav[0] = true;
                    dialog.dismiss();
                    NetworkBrowser.NetItem chosen = servers.get(pos);
                    if ("dlna".equals(chosen.protocol)) {
                        browseDlnaFolder(activity, chosen.path, "0", chosen.title, new ArrayList<>(), callback);
                    } else {
                        browseSmbOrFtpFolder(activity, chosen, "/", new ArrayList<>(), callback);
                    }
                }
            });
        };

        dialog.show();
        refreshList.run();

        DlnaBrowser.discoverServers(dlnaServers -> {
            for (DlnaBrowser.DlnaItem d : dlnaServers) {
                boolean exists = false;
                for (NetworkBrowser.NetItem existing : servers) {
                    if (d.url != null && d.url.equals(existing.path)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    servers.add(new NetworkBrowser.NetItem(
                            NetworkBrowser.TYPE_DLNA_SERVER, "[DLNA] " + d.title, d.id,
                            "dlna", null, 0, "", "", d.url, false));
                }
            }
            refreshList.run();
        });

        mdnsBrowser.startMdnsDiscovery(activity, item -> {
            boolean exists = false;
            for (NetworkBrowser.NetItem existing : servers) {
                if (item.protocol.equals(existing.protocol) && item.host.equals(existing.host) && item.port == existing.port) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                servers.add(item);
                refreshList.run();
            }
        });
    }

    private static void browseDlnaFolder(
            Activity activity,
            String controlUrl,
            String objectId,
            String title,
            List<String> history,
            AudioSelectedCallback callback) {
        DlnaBrowser.browse(controlUrl, objectId, items -> {
            List<String> names = new ArrayList<>();
            boolean hasParent = !history.isEmpty();
            if (hasParent) names.add(".. (Back)");
            for (DlnaBrowser.DlnaItem item : items) {
                names.add(item.isFolder ? "[Folder] " + item.title : item.title);
            }
            final boolean[] navigated = {false};
            AlertDialog dlg = new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setItems(names.toArray(new String[0]), (d, which) -> {
                        navigated[0] = true;
                        if (hasParent && which == 0) {
                            String prevId = history.remove(history.size() - 1);
                            browseDlnaFolder(activity, controlUrl, prevId, title, history, callback);
                            return;
                        }
                        int idx = hasParent ? which - 1 : which;
                        if (idx < 0 || idx >= items.size()) return;
                        DlnaBrowser.DlnaItem picked = items.get(idx);
                        if (picked.isFolder) {
                            history.add(objectId);
                            browseDlnaFolder(activity, controlUrl, picked.id, picked.title, history, callback);
                        } else if (picked.url != null) {
                            callback.onAudioSelected(Uri.parse(picked.url));
                        }
                    })
                    .setNegativeButton("Close", (d, w) -> callback.onDialogClosedWithoutSelection())
                    .create();
            dlg.setOnCancelListener(d -> {
                if (!navigated[0]) callback.onDialogClosedWithoutSelection();
            });
            dlg.show();
        });
    }

    private static void browseSmbOrFtpFolder(
            Activity activity,
            NetworkBrowser.NetItem server,
            String dirPath,
            List<String> history,
            AudioSelectedCallback callback) {
        NetworkBrowser.ListCallback listCb = (items, error) -> {
            if (error != null) {
                Toast.makeText(activity, "Network error: " + error, Toast.LENGTH_LONG).show();
                callback.onDialogClosedWithoutSelection();
                return;
            }
            List<String> names = new ArrayList<>();
            boolean hasParent = !history.isEmpty();
            if (hasParent) names.add(".. (Back)");
            for (NetworkBrowser.NetItem item : items) {
                names.add(item.title);
            }
            final boolean[] navigated = {false};
            AlertDialog dlg = new AlertDialog.Builder(activity)
                    .setTitle(server.title + " : " + dirPath)
                    .setItems(names.toArray(new String[0]), (d, which) -> {
                        navigated[0] = true;
                        if (hasParent && which == 0) {
                            String prevPath = history.remove(history.size() - 1);
                            browseSmbOrFtpFolder(activity, server, prevPath, history, callback);
                            return;
                        }
                        int idx = hasParent ? which - 1 : which;
                        if (idx < 0 || idx >= items.size()) return;
                        NetworkBrowser.NetItem picked = items.get(idx);
                        if (picked.type == NetworkBrowser.TYPE_FOLDER) {
                            history.add(dirPath);
                            browseSmbOrFtpFolder(activity, server, picked.path, history, callback);
                        } else if (picked.path != null) {
                            callback.onAudioSelected(Uri.parse(picked.path));
                        }
                    })
                    .setNegativeButton("Close", (d, w) -> callback.onDialogClosedWithoutSelection())
                    .create();
            dlg.setOnCancelListener(d -> {
                if (!navigated[0]) callback.onDialogClosedWithoutSelection();
            });
            dlg.show();
        };

        if ("ftp".equals(server.protocol)) {
            NetworkBrowser.browseFtp(server.host, server.port, server.user, server.pass, dirPath, listCb);
        } else {
            NetworkBrowser.browseSmb(server.protocol, server.host, server.port, server.user, server.pass, dirPath, listCb);
        }
    }
}
