/*#######################################################
 *
 *   Extended Notepad - multi-clipboard buffer
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import net.gsantner.markor.model.AppSettings;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Multi-entry clipboard history, stored as individual .txt files on disk so it can
 * later be synced between devices (e.g. via Syncthing) like the notebook container.
 * <p>
 * File naming: clip_<epochMillis>.txt - the timestamp both makes names unique and
 * gives natural chronological sort order without needing an index file.
 */
public class ClipboardBufferHelper {

    private static final String TAG = "ClipboardBufferHelper";
    private static final String FILE_PREFIX = "clip_";
    private static final String FILE_SUFFIX = ".txt";

    public static class Entry {
        public final File file;
        public final long timestampMillis;

        public Entry(final File file, final long timestampMillis) {
            this.file = file;
            this.timestampMillis = timestampMillis;
        }

        public String readPreview(final int maxChars) {
            final String content = readContent(this.file);
            if (content == null) {
                return "";
            }
            final String firstLine = content.replace("\r\n", "\n").split("\n", 2)[0];
            return firstLine.length() > maxChars ? firstLine.substring(0, maxChars) + "…" : firstLine;
        }
    }

    private static final FilenameFilter CLIP_FILE_FILTER = (dir, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);

    /**
     * Append new clip text as the newest entry. Ignores blank/whitespace-only text
     * and exact duplicates of the current newest entry (so retyping the same
     * selection twice doesn't spam the history).
     */
    public static synchronized void addEntry(final Context context, final String text) {
        if (TextUtils.isEmpty(text) || text.trim().isEmpty()) {
            return;
        }

        final File dir = AppSettings.get(context).getClipboardBufferDirectory();
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create clipboard buffer directory: " + dir);
            return;
        }

        final List<Entry> existing = listEntries(context);
        if (!existing.isEmpty()) {
            final String newestContent = readContent(existing.get(0).file);
            if (text.equals(newestContent)) {
                return; // duplicate of most recent entry, skip
            }
        }

        final File out = new File(dir, FILE_PREFIX + System.currentTimeMillis() + FILE_SUFFIX);
        GsFileUtils.writeFile(out, text.getBytes(StandardCharsets.UTF_8), null);

        enforceMaxEntries(context);
    }

    /**
     * List all entries, newest first.
     */
    public static List<Entry> listEntries(final Context context) {
        final File dir = AppSettings.get(context).getClipboardBufferDirectory();
        final List<Entry> result = new ArrayList<>();
        final File[] files = dir.exists() ? dir.listFiles(CLIP_FILE_FILTER) : null;
        if (files == null) {
            return result;
        }
        for (final File f : files) {
            result.add(new Entry(f, parseTimestamp(f)));
        }
        Collections.sort(result, (a, b) -> Long.compare(b.timestampMillis, a.timestampMillis));
        return result;
    }

    public static String readContent(final File file) {
        try {
            final byte[] raw = GsFileUtils.readBinaryFile(file);
            return new String(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static void deleteEntry(final Entry entry) {
        //noinspection ResultOfMethodCallIgnored
        entry.file.delete();
    }

    public static void clearAll(final Context context) {
        for (final Entry e : listEntries(context)) {
            deleteEntry(e);
        }
    }

    private static void enforceMaxEntries(final Context context) {
        final int max = Math.max(1, AppSettings.get(context).getClipboardBufferMaxEntries());
        final List<Entry> entries = listEntries(context); // newest first
        for (int i = entries.size() - 1; i >= max; i--) {
            deleteEntry(entries.get(i));
        }
    }

    private static long parseTimestamp(final File f) {
        try {
            final String name = f.getName();
            final String num = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
            return Long.parseLong(num);
        } catch (Exception e) {
            return f.lastModified();
        }
    }

    // ---- System clipboard listener plumbing ----

    /**
     * Registers a listener that mirrors every system-clipboard change (copy/cut,
     * from our own context menu or the standard Android text-selection toolbar)
     * into the multi-buffer. Call unregister() with the same instance in onPause.
     */
    public static class SystemClipboardBridge implements ClipboardManager.OnPrimaryClipChangedListener {
        private final Context _appContext;
        private final ClipboardManager _cm;

        public SystemClipboardBridge(final Context context) {
            _appContext = context.getApplicationContext();
            _cm = (ClipboardManager) _appContext.getSystemService(Context.CLIPBOARD_SERVICE);
        }

        public void register() {
            if (_cm != null) {
                _cm.addPrimaryClipChangedListener(this);
            }
        }

        public void unregister() {
            if (_cm != null) {
                _cm.removePrimaryClipChangedListener(this);
            }
        }

        @Override
        public void onPrimaryClipChanged() {
            try {
                if (_cm == null || !_cm.hasPrimaryClip()) {
                    return;
                }
                final ClipData clip = _cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) {
                    return;
                }
                final CharSequence text = clip.getItemAt(0).coerceToText(_appContext);
                if (text != null) {
                    addEntry(_appContext, text.toString());
                }
            } catch (Exception e) {
                Log.w(TAG, "onPrimaryClipChanged failed", e);
            }
        }
    }
}
