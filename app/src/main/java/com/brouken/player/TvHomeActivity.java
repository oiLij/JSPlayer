package com.brouken.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class TvHomeActivity extends Activity {

    private TextView tvHeader;
    private RecyclerView tvList;
    private BrowserAdapter adapter;

    private String currentControlUrl = null;
    private final Stack<String> folderStack = new Stack<>();
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

        TextView navDlna = findViewById(R.id.nav_dlna);
        TextView navLocal = findViewById(R.id.nav_local);
        TextView navSettings = findViewById(R.id.nav_settings);

        navDlna.setOnClickListener(v -> loadDlnaServers());
        navLocal.setOnClickListener(v -> startActivity(new Intent(this, PlayerActivity.class)));
        navSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        navDlna.requestFocus();
        loadDlnaServers();
    }

    private void loadDlnaServers() {
        currentControlUrl = null;
        folderStack.clear();
        titleStack.clear();
        tvHeader.setText("Scanning DLNA servers...");
        adapter.setItems(new ArrayList<>());

        DlnaBrowser.discoverServers(items -> {
            if (items.isEmpty()) {
                tvHeader.setText("No DLNA servers found (click DLNA to retry)");
            } else {
                tvHeader.setText("DLNA Servers (" + items.size() + ")");
            }
            adapter.setItems(items);
        });
    }

    private void browseFolder(String controlUrl, String objectId, String title, boolean push) {
        currentControlUrl = controlUrl;
        if (push) {
            folderStack.push(objectId);
            titleStack.push(title);
        }
        tvHeader.setText("Loading " + title + "...");
        DlnaBrowser.browse(controlUrl, objectId, items -> {
            tvHeader.setText(title + " (" + items.size() + ")");
            adapter.setItems(items);
        });
    }

    @Override
    public void onBackPressed() {
        if (currentControlUrl != null && folderStack.size() > 1) {
            folderStack.pop();
            titleStack.pop();
            browseFolder(currentControlUrl, folderStack.peek(), titleStack.peek(), false);
        } else if (currentControlUrl != null) {
            loadDlnaServers();
        } else {
            super.onBackPressed();
        }
    }

    private class BrowserAdapter extends RecyclerView.Adapter<BrowserAdapter.VH> {
        private List<DlnaBrowser.DlnaItem> items = new ArrayList<>();

        void setItems(List<DlnaBrowser.DlnaItem> newItems) {
            this.items = newItems;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setPadding(20, 18, 20, 18);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(16);
            tv.setBackgroundResource(R.drawable.tv_item_bg);
            tv.setFocusable(true);
            tv.setClickable(true);
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            DlnaBrowser.DlnaItem item = items.get(position);
            holder.textView.setText(item.title);
            holder.textView.setOnClickListener(v -> {
                if (item.isServer) {
                    folderStack.clear();
                    titleStack.clear();
                    browseFolder(item.url, "0", item.title, true);
                } else if (item.isFolder) {
                    browseFolder(currentControlUrl, item.id, item.title.replace("[Folder] ", ""), true);
                } else if (item.url != null) {
                    Intent intent = new Intent(TvHomeActivity.this, PlayerActivity.class);
                    intent.setAction(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(item.url));
                    intent.putExtra(PlayerActivity.API_TITLE, item.title);
                    startActivity(intent);
                }
            });
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
