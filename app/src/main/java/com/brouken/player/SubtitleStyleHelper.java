package com.brouken.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class SubtitleStyleHelper {
    public static final int REQUEST_PICK_FONT = 9913;
    public static final float DEFAULT_BOTTOM_PADDING = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION * 2f / 3f;
    private static final String PREFS_NAME = "jsplayer_sub_style";

    private static final String[] TEXT_COLOR_NAMES = {"White", "Yellow", "Cyan", "Green", "Light Gray", "Orange", "Magenta"};
    private static final int[] TEXT_COLOR_RGB = {0xFFFFFF, 0xFFEB3B, 0x00E5FF, 0x76FF03, 0xCFD8DC, 0xFF9800, 0xFF4081};

    private static final String[] TEXT_ALPHA_NAMES = {"100%", "85%", "70%"};
    private static final int[] TEXT_ALPHA_VALS = {255, 217, 179};

    private static final String[] BG_NAMES = {"None (0%)", "Black 35%", "Black 60%", "Black 85%", "Black 100%", "Dark Gray 75%", "Navy 75%"};
    private static final int[] BG_COLORS = {
            Color.TRANSPARENT,
            Color.argb(90, 0, 0, 0),
            Color.argb(153, 0, 0, 0),
            Color.argb(217, 0, 0, 0),
            Color.BLACK,
            Color.argb(191, 38, 50, 56),
            Color.argb(191, 13, 27, 62)
    };

    private static final String[] EDGE_TYPE_NAMES = {"Outline", "Drop Shadow", "Raised", "Depressed", "None"};
    private static final int[] EDGE_TYPES = {
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            CaptionStyleCompat.EDGE_TYPE_RAISED,
            CaptionStyleCompat.EDGE_TYPE_DEPRESSED,
            CaptionStyleCompat.EDGE_TYPE_NONE
    };

    private static final String[] EDGE_COLOR_NAMES = {"Black", "Dark Gray", "White", "Blue", "Red"};
    private static final int[] EDGE_COLORS = {Color.BLACK, Color.DKGRAY, Color.WHITE, Color.BLUE, Color.RED};

    private static final String[] WINDOW_NAMES = {"None", "Black 40%", "Black 75%"};
    private static final int[] WINDOW_COLORS = {Color.TRANSPARENT, Color.argb(102, 0, 0, 0), Color.argb(191, 0, 0, 0)};

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static float getSizeMultiplier(Context ctx) {
        return prefs(ctx).getInt("sizePercent", 100) / 100f;
    }

    public static void applyStyle(Context ctx, SubtitleView subtitleView, Prefs appPrefs) {
        if (subtitleView == null) return;
        SharedPreferences p = prefs(ctx);

        float bottomPadding = p.getFloat("bottomPadding", DEFAULT_BOTTOM_PADDING);
        boolean bold = p.getBoolean("bold", appPrefs != null && appPrefs.subtitleStyleBold);
        boolean italic = p.getBoolean("italic", false);
        int textColorIdx = p.getInt("textColorIdx", 0) % TEXT_COLOR_RGB.length;
        int textAlphaIdx = p.getInt("textAlphaIdx", 0) % TEXT_ALPHA_VALS.length;
        int bgColorIdx = p.getInt("bgColorIdx", 0) % BG_COLORS.length;
        int edgeTypeIdx = p.getInt("edgeTypeIdx", 0) % EDGE_TYPES.length;
        int edgeColorIdx = p.getInt("edgeColorIdx", 0) % EDGE_COLORS.length;
        int windowColorIdx = p.getInt("windowColorIdx", 0) % WINDOW_COLORS.length;
        boolean overrideEmbedded = p.getBoolean("overrideEmbedded", false);
        String fontPath = p.getString("customFontPath", "");

        int rgb = TEXT_COLOR_RGB[textColorIdx];
        int fgColor = Color.argb(TEXT_ALPHA_VALS[textAlphaIdx], Color.red(rgb), Color.green(rgb), Color.blue(rgb));

        Typeface baseTf = Typeface.DEFAULT;
        if (fontPath != null && !fontPath.isEmpty()) {
            File f = new File(fontPath);
            if (f.exists() && f.length() > 0) {
                try {
                    baseTf = Typeface.createFromFile(f);
                } catch (Exception ignored) {
                }
            }
        }
        int tfStyle = (bold && italic) ? Typeface.BOLD_ITALIC : (bold ? Typeface.BOLD : (italic ? Typeface.ITALIC : Typeface.NORMAL));
        Typeface finalTf = Typeface.create(baseTf, tfStyle);

        CaptionStyleCompat style = new CaptionStyleCompat(
                fgColor,
                BG_COLORS[bgColorIdx],
                WINDOW_COLORS[windowColorIdx],
                EDGE_TYPES[edgeTypeIdx],
                EDGE_COLORS[edgeColorIdx],
                finalTf
        );

        subtitleView.setStyle(style);
        subtitleView.setBottomPaddingFraction(bottomPadding);
        boolean applyEmbedded = !overrideEmbedded && (appPrefs == null || appPrefs.subtitleStyleEmbedded);
        subtitleView.setApplyEmbeddedStyles(applyEmbedded);
    }

    public static boolean importFontFromUri(Context ctx, Uri uri) {
        if (uri == null) return false;
        File outFile = new File(ctx.getFilesDir(), "custom_subtitle_font.ttf");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(outFile)) {
            if (in == null) return false;
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
            if (outFile.length() == 0) return false;
            Typeface tf = Typeface.createFromFile(outFile);
            if (tf == null) return false;

            String name = "Custom Font";
            try (Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = cursor.getString(idx);
                }
            } catch (Exception ignored) {
            }

            prefs(ctx).edit()
                    .putString("customFontPath", outFile.getAbsolutePath())
                    .putString("customFontName", name)
                    .putBoolean("overrideEmbedded", true)
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void showAppearanceDialog(Activity activity, Runnable onLiveUpdate) {
        SharedPreferences p = prefs(activity);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 24, 36, 20);
        scroll.addView(root);

        TextView posLabel = new TextView(activity);
        posLabel.setTextSize(15);
        root.addView(posLabel);

        LinearLayout posRow = new LinearLayout(activity);
        posRow.setOrientation(LinearLayout.HORIZONTAL);
        Button posDown = new Button(activity);
        posDown.setText("-1% (Down)");
        Button posReset = new Button(activity);
        posReset.setText("Reset");
        Button posUp = new Button(activity);
        posUp.setText("+1% (Up)");
        posRow.addView(posDown);
        posRow.addView(posReset);
        posRow.addView(posUp);
        root.addView(posRow);

        TextView sizeLabel = new TextView(activity);
        sizeLabel.setTextSize(15);
        sizeLabel.setPadding(0, 14, 0, 0);
        root.addView(sizeLabel);

        LinearLayout sizeRow = new LinearLayout(activity);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button sizeDown = new Button(activity);
        sizeDown.setText("-10%");
        Button sizeReset = new Button(activity);
        sizeReset.setText("100%");
        Button sizeUp = new Button(activity);
        sizeUp.setText("+10%");
        sizeRow.addView(sizeDown);
        sizeRow.addView(sizeReset);
        sizeRow.addView(sizeUp);
        root.addView(sizeRow);

        TextView fontLabel = new TextView(activity);
        fontLabel.setTextSize(15);
        fontLabel.setPadding(0, 14, 0, 0);
        root.addView(fontLabel);

        LinearLayout fontRow = new LinearLayout(activity);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        Button pickFontBtn = new Button(activity);
        pickFontBtn.setText("Pick .ttf/.otf");
        Button resetFontBtn = new Button(activity);
        resetFontBtn.setText("Default Font");
        fontRow.addView(pickFontBtn);
        fontRow.addView(resetFontBtn);
        root.addView(fontRow);

        LinearLayout styleRow = new LinearLayout(activity);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        styleRow.setPadding(0, 12, 0, 0);
        Button boldBtn = new Button(activity);
        Button italicBtn = new Button(activity);
        styleRow.addView(boldBtn);
        styleRow.addView(italicBtn);
        root.addView(styleRow);

        LinearLayout colorRow = new LinearLayout(activity);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setPadding(0, 8, 0, 0);
        Button textColorBtn = new Button(activity);
        Button textAlphaBtn = new Button(activity);
        colorRow.addView(textColorBtn);
        colorRow.addView(textAlphaBtn);
        root.addView(colorRow);

        Button bgBtn = new Button(activity);
        root.addView(bgBtn);

        LinearLayout edgeRow = new LinearLayout(activity);
        edgeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button edgeTypeBtn = new Button(activity);
        Button edgeColorBtn = new Button(activity);
        edgeRow.addView(edgeTypeBtn);
        edgeRow.addView(edgeColorBtn);
        root.addView(edgeRow);

        Button windowBtn = new Button(activity);
        root.addView(windowBtn);

        Button overrideBtn = new Button(activity);
        root.addView(overrideBtn);

        Button resetAllBtn = new Button(activity);
        resetAllBtn.setText("Reset All Subtitle Styles");
        root.addView(resetAllBtn);

        Runnable refresh = () -> {
            float pos = p.getFloat("bottomPadding", DEFAULT_BOTTOM_PADDING);
            posLabel.setText(String.format(Locale.US, "Vertical Position: %.1f%% (0%% = Bottom Edge)", pos * 100f));
            sizeLabel.setText("Font Size: " + p.getInt("sizePercent", 100) + "%");
            fontLabel.setText("Font: " + p.getString("customFontName", "Default"));
            boldBtn.setText("Bold: " + (p.getBoolean("bold", false) ? "ON" : "OFF"));
            italicBtn.setText("Italic: " + (p.getBoolean("italic", false) ? "ON" : "OFF"));
            textColorBtn.setText("Color: " + TEXT_COLOR_NAMES[p.getInt("textColorIdx", 0) % TEXT_COLOR_NAMES.length]);
            textAlphaBtn.setText("Opacity: " + TEXT_ALPHA_NAMES[p.getInt("textAlphaIdx", 0) % TEXT_ALPHA_NAMES.length]);
            bgBtn.setText("Background: " + BG_NAMES[p.getInt("bgColorIdx", 0) % BG_NAMES.length]);
            edgeTypeBtn.setText("Edge: " + EDGE_TYPE_NAMES[p.getInt("edgeTypeIdx", 0) % EDGE_TYPE_NAMES.length]);
            edgeColorBtn.setText("Edge Color: " + EDGE_COLOR_NAMES[p.getInt("edgeColorIdx", 0) % EDGE_COLOR_NAMES.length]);
            windowBtn.setText("Window Box: " + WINDOW_NAMES[p.getInt("windowColorIdx", 0) % WINDOW_NAMES.length]);
            overrideBtn.setText("Override ASS/Embedded Styles: " + (p.getBoolean("overrideEmbedded", false) ? "ON" : "OFF"));
            if (onLiveUpdate != null) onLiveUpdate.run();
        };

        posDown.setOnClickListener(v -> {
            float next = Math.max(0.0f, p.getFloat("bottomPadding", DEFAULT_BOTTOM_PADDING) - 0.01f);
            p.edit().putFloat("bottomPadding", next).apply();
            refresh.run();
        });
        posUp.setOnClickListener(v -> {
            float next = Math.min(0.35f, p.getFloat("bottomPadding", DEFAULT_BOTTOM_PADDING) + 0.01f);
            p.edit().putFloat("bottomPadding", next).apply();
            refresh.run();
        });
        posReset.setOnClickListener(v -> {
            p.edit().putFloat("bottomPadding", DEFAULT_BOTTOM_PADDING).apply();
            refresh.run();
        });

        sizeDown.setOnClickListener(v -> {
            int next = Math.max(50, p.getInt("sizePercent", 100) - 10);
            p.edit().putInt("sizePercent", next).apply();
            refresh.run();
        });
        sizeUp.setOnClickListener(v -> {
            int next = Math.min(200, p.getInt("sizePercent", 100) + 10);
            p.edit().putInt("sizePercent", next).apply();
            refresh.run();
        });
        sizeReset.setOnClickListener(v -> {
            p.edit().putInt("sizePercent", 100).apply();
            refresh.run();
        });

        boldBtn.setOnClickListener(v -> {
            p.edit().putBoolean("bold", !p.getBoolean("bold", false)).apply();
            refresh.run();
        });
        italicBtn.setOnClickListener(v -> {
            p.edit().putBoolean("italic", !p.getBoolean("italic", false)).apply();
            refresh.run();
        });
        textColorBtn.setOnClickListener(v -> {
            p.edit().putInt("textColorIdx", (p.getInt("textColorIdx", 0) + 1) % TEXT_COLOR_NAMES.length).apply();
            refresh.run();
        });
        textAlphaBtn.setOnClickListener(v -> {
            p.edit().putInt("textAlphaIdx", (p.getInt("textAlphaIdx", 0) + 1) % TEXT_ALPHA_NAMES.length).apply();
            refresh.run();
        });
        bgBtn.setOnClickListener(v -> {
            p.edit().putInt("bgColorIdx", (p.getInt("bgColorIdx", 0) + 1) % BG_NAMES.length).apply();
            refresh.run();
        });
        edgeTypeBtn.setOnClickListener(v -> {
            p.edit().putInt("edgeTypeIdx", (p.getInt("edgeTypeIdx", 0) + 1) % EDGE_TYPE_NAMES.length).apply();
            refresh.run();
        });
        edgeColorBtn.setOnClickListener(v -> {
            p.edit().putInt("edgeColorIdx", (p.getInt("edgeColorIdx", 0) + 1) % EDGE_COLOR_NAMES.length).apply();
            refresh.run();
        });
        windowBtn.setOnClickListener(v -> {
            p.edit().putInt("windowColorIdx", (p.getInt("windowColorIdx", 0) + 1) % WINDOW_NAMES.length).apply();
            refresh.run();
        });
        overrideBtn.setOnClickListener(v -> {
            p.edit().putBoolean("overrideEmbedded", !p.getBoolean("overrideEmbedded", false)).apply();
            refresh.run();
        });
        resetFontBtn.setOnClickListener(v -> {
            p.edit().remove("customFontPath").remove("customFontName").apply();
            refresh.run();
        });
        resetAllBtn.setOnClickListener(v -> {
            p.edit().clear().apply();
            refresh.run();
        });

        refresh.run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Subtitle Appearance & Position")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .create();

        pickFontBtn.setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            activity.startActivityForResult(intent, REQUEST_PICK_FONT);
        });

        dialog.show();
    }
}
