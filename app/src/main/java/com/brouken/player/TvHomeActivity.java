package com.brouken.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;

public class TvHomeActivity extends Activity {

    private TextView tvHeader;
    private RecyclerView tvList;
    private BrowserAdapter adapter;
    private NetworkBrowser networkBrowser;

    private final List<NetworkBrowser.NetItem> discoveredItems = new ArrayList<>();
    private final Set<String> discoveredKeys = new HashSet<>();

    // Active browse state
    private NetworkBrowser.NetItem activeServer = null;
    private final Stack<String> pathStack = new Stack<>();
    private final Stack<String> titleStack = new Stack<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_home);

        tvHeader = findViewById(R.id.tv_header);
        tvList = findViewById(R.id.tv_list);
        tvList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BrowserAdapter();
        tvList.setAdapter(adapter);
        networkBrowser = new NetworkBrowser();

        TextView navNetwork = findViewById(R.id.nav_dlna);
        TextView navLocal = findViewById(R.id.nav_local);
        TextView navSettings = findViewById(R.id.nav_settings);

        navNetwork.setOnClickListener(v -> loadNetworkHub());
        navLocal.setOnClickListener(v -> startActivity(new Intent(this, PlayerActivity.class)));
        navSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        navNetwork.requestFocus();
        loadNetworkHub();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (networkBrowser != null) {
            networkBrowser.stopMdnsDiscovery();
        }
    }

    private void loadNetworkHub() {
        activeServer = null;
        pathStack.clear();
        titleStack.clear();
        discoveredItems.clear();
        discoveredKeys.clear();
        tvHeader.setText("Network (Scanning LAN...)");
        refreshHubList();

        // 1. DLNA SSDP scan
        DlnaBrowser.discoverServers(items -> {
            for (DlnaBrowser.DlnaItem d : items) {
                String key = "dlna:" + d.url;
                if (discoveredKeys.add(key)) {
                    discoveredItems.add(new NetworkBrowser.NetItem(
                            NetworkBrowser.TYPE_DLNA_SERVER, "[DLNA] " + d.title, "0",
                            "dlna", null, 0, "", "", d.url, false));
                }
            }
            if (activeServer == null) {
                tvHeader.setText("Network");
                refreshHubList();
            }
        });

        // 2. SMB & FTP mDNS scan
        networkBrowser.startMdnsDiscovery(this, item -> {
            String key = item.protocol + ":" + item.host + ":" + item.port;
            if (discoveredKeys.add(key)) {
                discoveredItems.add(item);
                if (activeServer == null) {
                    refreshHubList();
                }
            }
        });
    }

    private void refreshHubList() {
        List<NetworkBrowser.NetItem> list = new ArrayList<>();
        list.add(new NetworkBrowser.NetItem(NetworkBrowser.TYPE_HEADER, "DISCOVERED ON LAN", null, null, null, 0, null, null, null, false));
        if (discoveredItems.isEmpty()) {
            list.add(new NetworkBrowser.NetItem(NetworkBrowser.TYPE_HEADER, "  Scanning for DLNA / SMB / FTP...", null, null, null, 0, null, null, null, false));
        } else {
            list.addAll(discoveredItems);
        }

        list.add(new NetworkBrowser.NetItem(NetworkBrowser.TYPE_HEADER, "ACTIONS & SAVED", null, null, null, 0, null, null, null, false));
        list.add(new NetworkBrowser.NetItem(NetworkBrowser.TYPE_STREAM_URL, "[Link] Stream URL (HTTP / HLS / RTSP)", null, null, null, 0, null, null, null, false));
        list.add(new NetworkBrowser.NetItem(NetworkBrowser.TYPE_ADD_SERVER, "[+] Add Connection (SMB / FTP)", null, null, null, 0, null, null, null, false));
        list.addAll(NetworkBrowser.getSavedServers(this));

        adapter.setItems(list);
    }

    private void openFolder(NetworkBrowser.NetItem server, String pathOrId, String title, boolean push) {
        activeServer = server;
        if (push) {
            pathStack.push(pathOrId);
            titleStack.push(title);
        }
        tvHeader.setText("Loading " + title + "...");

        if ("dlna".equals(server.protocol)) {
            DlnaBrowser.browse(server.path, pathOrId, items -> {
                List<NetworkBrowser.NetItem> out = new ArrayList<>();
                for (DlnaBrowser.DlnaItem d : items) {
                    out.add(new NetworkBrowser.NetItem(
                            d.isFolder ? NetworkBrowser.TYPE_FOLDER : NetworkBrowser.TYPE_MEDIA,
                            d.title, d.id, "dlna", null, 0, "", "", d.url, false));
                }
                tvHeader.setText(title + " (" + out.size() + ")");
                adapter.setItems(out);
            });
        } else if ("ftp".equals(server.protocol)) {
            NetworkBrowser.browseFtp(server.host, server.port, server.user, server.pass, pathOrId, (items, err) -> {
                if (err != null) Toast.makeText(this, "FTP Error: " + err, Toast.LENGTH_LONG).show();
                tvHeader.setText(title + " (" + items.size() + ")");
                adapter.setItems(items);
            });
        } else {
            NetworkBrowser.browseSmb(server.protocol, server.host, server.port, server.user, server.pass, pathOrId, (items, err) -> {
                if (err != null) Toast.makeText(this, "SMB Error: " + err, Toast.LENGTH_LONG).show();
                tvHeader.setText(title + " (" + items.size() + ")");
                adapter.setItems(items);
            });
        }
    }

    private void showStreamUrlDialog() {
        EditText input = new EditText(this);
        input.setHint("https://... or http://... or rtsp://...");
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle("Stream URL")
                .setView(input)
                .setPositiveButton("Play", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        launchPlayer(url, url, null, null, 0);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddServerDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(36, 20, 36, 10);

        Spinner protoSpinner = new Spinner(this);
        String[] protos = {"SMB (v2/v3)", "SMB v1", "FTP"};
        protoSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, protos));
        layout.addView(protoSpinner);

        EditText etName = new EditText(this); etName.setHint("Name (optional)"); layout.addView(etName);
        EditText etHost = new EditText(this); etHost.setHint("Host / IP (e.g. 192.168.43.1)"); layout.addView(etHost);
        EditText etPort = new EditText(this); etPort.setHint("Port (default: 445 SMB, 21 FTP)");
        etPort.setInputType(InputType.TYPE_CLASS_NUMBER); layout.addView(etPort);
        EditText etUser = new EditText(this); etUser.setHint("Username (blank = anonymous)"); layout.addView(etUser);
        EditText etPass = new EditText(this); etPass.setHint("Password");
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); layout.addView(etPass);

        new AlertDialog.Builder(this)
                .setTitle("Add Connection")
                .setView(layout)
                .setPositiveButton("Save", (d, w) -> {
                    String host = etHost.getText().toString().trim();
                    if (host.isEmpty()) return;
                    int pos = protoSpinner.getSelectedItemPosition();
                    String proto = pos == 2 ? "ftp" : (pos == 1 ? "smb1" : "smb");
                    int defaultPort = proto.equals("ftp") ? 21 : 445;
                    int port = defaultPort;
                    try {
                        String pStr = etPort.getText().toString().trim();
                        if (!pStr.isEmpty()) port = Integer.parseInt(pStr);
                    } catch (Exception ignored) {}

                    NetworkBrowser.saveServer(this, etName.getText().toString().trim(), proto, host, port,
                            etUser.getText().toString().trim(), etPass.getText().toString());
                    refreshHubList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launchPlayer(String url, String title, ArrayList<String> urls, ArrayList<String> titles, int index) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(url));
        intent.putExtra(PlayerActivity.API_TITLE, title);
        if (urls != null && urls.size() > 1) {
            intent.putStringArrayListExtra("playlist_urls", urls);
            intent.putStringArrayListExtra("playlist_titles", titles);
            intent.putExtra("playlist_index", index);
        }
        startActivity(intent);
    }

    @Override
    public void onBackPressed() {
        if (activeServer != null && pathStack.size() > 1) {
            pathStack.pop();
            titleStack.pop();
            openFolder(activeServer, pathStack.peek(), titleStack.peek(), false);
        } else if (activeServer != null) {
            activeServer = null;
            pathStack.clear();
            titleStack.clear();
            tvHeader.setText("Network");
            refreshHubList();
        } else {
            super.onBackPressed();
        }
    }

    private class BrowserAdapter extends RecyclerView.Adapter<BrowserAdapter.VH> {
        private List<NetworkBrowser.NetItem> items = new ArrayList<>();

        void setItems(List<NetworkBrowser.NetItem> newItems) {
            this.items = newItems;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            NetworkBrowser.NetItem item = items.get(position);
            TextView tv = holder.textView;
            tv.setText(item.title);

            if (item.type == NetworkBrowser.TYPE_HEADER) {
                tv.setPadding(16, 22, 16, 8);
                tv.setTextColor(Color.parseColor("#90A4AE"));
                tv.setTextSize(13);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setBackground(null);
                tv.setFocusable(false);
                tv.setClickable(false);
                tv.setOnClickListener(null);
                tv.setOnLongClickListener(null);
                return;
            }

            tv.setPadding(20, 16, 20, 16);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(16);
            tv.setTypeface(Typeface.DEFAULT);
            tv.setBackgroundResource(R.drawable.tv_item_bg);
            tv.setFocusable(true);
            tv.setClickable(true);

            tv.setOnClickListener(v -> {
                switch (item.type) {
                    case NetworkBrowser.TYPE_STREAM_URL:
                        showStreamUrlDialog();
                        break;
                    case NetworkBrowser.TYPE_ADD_SERVER:
                        showAddServerDialog();
                        break;
                    case NetworkBrowser.TYPE_DLNA_SERVER:
                    case NetworkBrowser.TYPE_SMB_SERVER:
                    case NetworkBrowser.TYPE_FTP_SERVER:
                        pathStack.clear();
                        titleStack.clear();
                        String startPath = "dlna".equals(item.protocol) ? "0" : "/";
                        openFolder(item, startPath, item.title, true);
                        break;
                    case NetworkBrowser.TYPE_FOLDER:
                        String nextTarget = "dlna".equals(item.protocol) ? item.id : item.path;
                        openFolder(activeServer, nextTarget, item.title.replace("[Folder] ", ""), true);
                        break;
                    case NetworkBrowser.TYPE_MEDIA:
                        if (item.path != null) {
                            ArrayList<String> urls = new ArrayList<>();
                            ArrayList<String> titles = new ArrayList<>();
                            int startIndex = 0;
                            for (NetworkBrowser.NetItem n : items) {
                                if (n.type == NetworkBrowser.TYPE_MEDIA && n.path != null) {
                                    if (n == item) startIndex = urls.size();
                                    urls.add(n.path);
                                    titles.add(n.title);
                                }
                            }
                            launchPlayer(item.path, item.title, urls, titles, startIndex);
                        }
                        break;
                }
            });

            if (item.isSaved) {
                tv.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(TvHomeActivity.this)
                            .setTitle("Delete " + item.title + "?")
                            .setPositiveButton("Delete", (d, w) -> {
                                NetworkBrowser.deleteSavedServer(TvHomeActivity.this, Integer.parseInt(item.id));
                                refreshHubList();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    return true;
                });
            } else {
                tv.setOnLongClickListener(null);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView textView;
            VH(TextView tv) {
                super(tv);
                this.textView = tv;
            }
        }
    }
}
