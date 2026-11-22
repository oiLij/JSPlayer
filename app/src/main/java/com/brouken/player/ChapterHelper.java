package com.brouken.player;

import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.Tracks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ChapterHelper {

    public static class ChapterItem {
        public final String title;
        public final long startMs;
        public final long endMs;

        public ChapterItem(String title, long startMs, long endMs) {
            this.title = (title == null || title.trim().isEmpty()) ? "Chapter" : title.trim();
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    public static List<ChapterItem> extractChapters(Tracks tracks) {
        List<ChapterItem> chapters = new ArrayList<>();
        if (tracks == null) return chapters;

        for (Tracks.Group group : tracks.getGroups()) {
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                Metadata meta = format.metadata;
                if (meta == null) continue;
                for (int m = 0; m < meta.length(); m++) {
                    Metadata.Entry entry = meta.get(m);
                    ChapterItem parsed = tryParseEntry(entry);
                    if (parsed != null) {
                        boolean exists = false;
                        for (ChapterItem c : chapters) {
                            if (Math.abs(c.startMs - parsed.startMs) < 500) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) chapters.add(parsed);
                    }
                }
            }
        }
        Collections.sort(chapters, Comparator.comparingLong(c -> c.startMs));
        return chapters;
    }

    private static ChapterItem tryParseEntry(Metadata.Entry entry) {
        String cls = entry.getClass().getSimpleName();
        if (!cls.contains("Chapter")) return null;
        try {
            long startMs = -1;
            long endMs = -1;
            String title = null;

            // Media3 Chapter (startTimeUs / endTimeUs / title)
            Long startUs = readLong(entry, "startTimeUs", "getStartTimeUs");
            Long endUs = readLong(entry, "endTimeUs", "getEndTimeUs");
            if (startUs != null && startUs >= 0) {
                startMs = startUs / 1000L;
                endMs = (endUs != null && endUs > 0) ? (endUs / 1000L) : -1;
            } else {
                // ID3 ChapterFrame (startTimeMs / endTimeMs)
                Long sMs = readLong(entry, "startTimeMs", "getStartTimeMs");
                Long eMs = readLong(entry, "endTimeMs", "getEndTimeMs");
                if (sMs != null && sMs >= 0) {
                    startMs = sMs;
                    endMs = (eMs != null && eMs > 0) ? eMs : -1;
                }
            }

            if (startMs < 0) return null;
            title = readString(entry, "title", "getTitle");
            if (title == null || title.isEmpty()) title = readString(entry, "chapterId", "getChapterId");
            return new ChapterItem(title, startMs, endMs);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long readLong(Object obj, String fieldName, String methodName) {
        try {
            Field f = obj.getClass().getField(fieldName);
            Object v = f.get(obj);
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Exception ignored) {}
        try {
            Method m = obj.getClass().getMethod(methodName);
            Object v = m.invoke(obj);
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Exception ignored) {}
        return null;
    }

    private static String readString(Object obj, String fieldName, String methodName) {
        try {
            Field f = obj.getClass().getField(fieldName);
            Object v = f.get(obj);
            if (v instanceof String) return (String) v;
        } catch (Exception ignored) {}
        try {
            Method m = obj.getClass().getMethod(methodName);
            Object v = m.invoke(obj);
            if (v instanceof String) return (String) v;
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Returns auto-hide duration in ms based on chapter position:
     * - Index 0 (1st): 5000 ms
     * - Index 1 (2nd): 3000 ms
     * - Middle chapters: 0 (never show)
     * - Index N-2 (2nd-to-last): 2000 ms
     * - Index N-1 (last): 5000 ms
     */
    public static long getSkipShowDurationMs(int index, int totalCount) {
        if (totalCount < 2 || index < 0 || index >= totalCount) return 0;
        if (index == 0) return 5000L;
        if (index == totalCount - 1) return 5000L;
        if (index == 1 && totalCount > 2) return 3000L;
        if (index == totalCount - 2 && totalCount > 3) return 2000L;
        return 0;
    }

    /**
     * Seeks to 1 second before the next chapter starts (to preserve title/opening frames).
     */
    public static long getSkipTargetMs(List<ChapterItem> chapters, int index, long durationMs) {
        ChapterItem current = chapters.get(index);
        long rawTarget;
        if (index + 1 < chapters.size()) {
            rawTarget = chapters.get(index + 1).startMs;
        } else if (current.endMs > current.startMs) {
            rawTarget = current.endMs;
        } else {
            rawTarget = durationMs;
        }
        return Math.max(current.startMs, rawTarget - 1000L);
    }

    public static int findChapterIndex(List<ChapterItem> chapters, long positionMs) {
        for (int i = chapters.size() - 1; i >= 0; i--) {
            if (positionMs >= chapters.get(i).startMs) {
                return i;
            }
        }
        return -1;
    }
}
