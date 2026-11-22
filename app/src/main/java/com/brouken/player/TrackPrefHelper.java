package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;

import java.util.ArrayList;
import java.util.List;

public class TrackPrefHelper {

    public static final String[] LANG_LABELS = {
            "Default (MKV / Forced)", "English", "Tamil", "Hindi", "Malayalam", "Telugu", "Kannada", "Japanese", "Korean", "Spanish"
    };
    public static final String[] LANG_CODES = {
            "", "en", "ta", "hi", "ml", "te", "kn", "ja", "ko", "es"
    };

    public static final String[] SUB_SDH_MODES = {
            "Normal (fallback to SDH)",
            "Prefer SDH (English SDH, etc.)",
            "Subtitles Off by Default"
    };

    private static final String PREFS = "jsplayer_track_prefs";
    private static final String K_AUD1 = "aud1";
    private static final String K_AUD2 = "aud2";
    private static final String K_SUB1 = "sub1";
    private static final String K_SUB2 = "sub2";
    private static final String K_SDH = "sdh_mode"; // 0 = normal+fallback, 1 = prefer SDH, 2 = off

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int get(Context c, String key, int def) { return sp(c).getInt(key, def); }
    public static void set(Context c, String key, int val) { sp(c).edit().putInt(key, val).apply(); }

    public static int getAudio1(Context c) { return get(c, K_AUD1, 0); }
    public static int getAudio2(Context c) { return get(c, K_AUD2, 0); }
    public static int getSub1(Context c) { return get(c, K_SUB1, 1); } // Default: English
    public static int getSub2(Context c) { return get(c, K_SUB2, 0); }
    public static int getSdhMode(Context c) { return get(c, K_SDH, 0); }

    public static void setAudio1(Context c, int idx) { set(c, K_AUD1, idx); }
    public static void setAudio2(Context c, int idx) { set(c, K_AUD2, idx); }
    public static void setSub1(Context c, int idx) { set(c, K_SUB1, idx); }
    public static void setSub2(Context c, int idx) { set(c, K_SUB2, idx); }
    public static void setSdhMode(Context c, int mode) { set(c, K_SDH, mode); }

    /**
     * Applies Primary + Secondary Audio & Subtitle preferences to ExoPlayer.
     * If neither language exists in the MKV, Media3 falls back to the file's default/forced track.
     */
    public static void applyToPlayer(Context ctx, Player player) {
        if (player == null) return;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();

        List<String> audioLangs = new ArrayList<>();
        String a1 = LANG_CODES[getAudio1(ctx)];
        String a2 = LANG_CODES[getAudio2(ctx)];
        if (!a1.isEmpty()) audioLangs.add(a1);
        if (!a2.isEmpty() && !a2.equals(a1)) audioLangs.add(a2);
        b.setPreferredAudioLanguages(audioLangs.toArray(new String[0]));

        int sdhMode = getSdhMode(ctx);
        if (sdhMode == 2) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
            List<String> subLangs = new ArrayList<>();
            String s1 = LANG_CODES[getSub1(ctx)];
            String s2 = LANG_CODES[getSub2(ctx)];
            if (!s1.isEmpty()) subLangs.add(s1);
            if (!s2.isEmpty() && !s2.equals(s1)) subLangs.add(s2);
            b.setPreferredTextLanguages(subLangs.toArray(new String[0]));
            b.setPreferredTextRoleFlags(sdhMode == 1 ? C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND : 0);
        }

        player.setTrackSelectionParameters(b.build());
    }

    /**
     * Smart SDH title/flag matcher: runs once when tracks load to pick Normal vs SDH accurately.
     */
    public static void applySmartSdhOverride(Context ctx, Player player, Tracks tracks) {
        if (player == null || tracks == null) return;
        int sdhMode = getSdhMode(ctx);
        if (sdhMode == 2) return; // Subtitles off

        String[] wanted = { LANG_CODES[getSub1(ctx)], LANG_CODES[getSub2(ctx)] };
        boolean preferSdh = (sdhMode == 1);

        for (String lang : wanted) {
            if (lang == null || lang.isEmpty()) continue;

            Tracks.Group bestGroup = null;
            int bestIndex = -1;
            Tracks.Group fallbackGroup = null;
            int fallbackIndex = -1;

            for (Tracks.Group g : tracks.getGroups()) {
                if (g.getType() != C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < g.length; i++) {
                    Format f = g.getTrackFormat(i);
                    if (!matchesLang(f.language, lang)) continue;
                    boolean isSdh = isSdhTrack(f);

                    if (isSdh == preferSdh && bestGroup == null) {
                        bestGroup = g;
                        bestIndex = i;
                    } else if (fallbackGroup == null) {
                        fallbackGroup = g;
                        fallbackIndex = i;
                    }
                }
            }

            Tracks.Group chosenGroup = bestGroup != null ? bestGroup : fallbackGroup;
            int chosenIndex = bestGroup != null ? bestIndex : fallbackIndex;
            if (chosenGroup != null) {
                TrackSelectionParameters params = player.getTrackSelectionParameters().buildUpon()
                        .setOverrideForType(new TrackSelectionOverride(chosenGroup.getMediaTrackGroup(), chosenIndex))
                        .build();
                player.setTrackSelectionParameters(params);
                return;
            }
        }
    }

    private static boolean matchesLang(String trackLang, String prefCode) {
        if (trackLang == null || prefCode == null) return false;
        String t = trackLang.toLowerCase();
        String p = prefCode.toLowerCase();
        return t.equals(p) || t.startsWith(p + "-") || t.startsWith(p + "_")
                || (p.equals("en") && t.equals("eng"))
                || (p.equals("ta") && t.equals("tam"))
                || (p.equals("hi") && t.equals("hin"))
                || (p.equals("ml") && t.equals("mal"))
                || (p.equals("te") && t.equals("tel"))
                || (p.equals("kn") && t.equals("kan"))
                || (p.equals("ja") && (t.equals("jpn") || t.equals("jap")))
                || (p.equals("ko") && t.equals("kor"))
                || (p.equals("es") && t.equals("spa"));
    }

    private static boolean isSdhTrack(Format f) {
        if ((f.roleFlags & C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) return true;
        if (f.label != null) {
            String l = f.label.toLowerCase();
            return l.contains("sdh") || l.contains("hearing") || l.contains("closed caption") || l.contains("[cc]") || l.contains("(cc)");
        }
        return false;
    }
}
