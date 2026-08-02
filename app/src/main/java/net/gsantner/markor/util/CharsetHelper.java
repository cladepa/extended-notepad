/*#######################################################
 *
 *   Extended Notepad - charset detection & per-file memory
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.mozilla.universalchardet.UniversalDetector;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Handles everything related to text file character encoding:
 * - BOM detection for UTF-8 / UTF-16LE / UTF-16BE / UTF-32LE / UTF-32BE
 * - Heuristic detection (Cyrillic-aware) via juniversalchardet when no BOM is present
 * - Per-file remembered overrides, for files the heuristic keeps guessing wrong
 */
public class CharsetHelper {

    private static final String PREF_NAME = "extnotepad_charset_overrides";
    private static final String KEY_CHARSET_PREFIX = "cs::";
    private static final String KEY_BOM_PREFIX = "bom::";

    /**
     * Common encodings offered in the quick-switch UI, in display order.
     * LinkedHashMap: display label -> Charset
     */
    public static final LinkedHashMap<String, Charset> COMMON_CHARSETS = new LinkedHashMap<>();

    static {
        put("UTF-8", "UTF-8");
        put("UTF-16LE", "UTF-16LE");
        put("UTF-16BE", "UTF-16BE");
        put("Windows-1251 (кириллица)", "windows-1251");
        put("KOI8-R", "KOI8-R");
        put("CP866 (DOS, кириллица)", "IBM866");
        put("ISO-8859-1", "ISO-8859-1");
        put("Windows-1252", "windows-1252");
        put("US-ASCII", "US-ASCII");
    }

    private static void put(final String label, final String javaName) {
        try {
            COMMON_CHARSETS.put(label, Charset.forName(javaName));
        } catch (Exception ignored) {
            // Charset not available on this platform, skip it
        }
    }

    public static String labelFor(final Charset charset) {
        if (charset == null) {
            return "UTF-8";
        }
        for (final Map.Entry<String, Charset> e : COMMON_CHARSETS.entrySet()) {
            if (e.getValue().equals(charset)) {
                return e.getKey();
            }
        }
        return charset.name();
    }

    public static class Result {
        public final Charset charset;
        public final boolean hasBom;
        public final int skipBytes; // number of leading BOM bytes to skip when decoding

        public Result(final Charset charset, final boolean hasBom, final int skipBytes) {
            this.charset = charset;
            this.hasBom = hasBom;
            this.skipBytes = skipBytes;
        }
    }

    /**
     * Detect a BOM at the start of the byte array. Returns null if none of the
     * known BOM signatures match.
     */
    public static Result detectBom(final byte[] bytes) {
        if (bytes.length >= 4 && u(bytes[0]) == 0xFF && u(bytes[1]) == 0xFE && bytes[2] == 0 && bytes[3] == 0) {
            return new Result(forNameOrNull("UTF-32LE"), true, 4);
        }
        if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] == 0 && u(bytes[2]) == 0xFE && u(bytes[3]) == 0xFF) {
            return new Result(forNameOrNull("UTF-32BE"), true, 4);
        }
        if (bytes.length >= 3 && u(bytes[0]) == 0xEF && u(bytes[1]) == 0xBB && u(bytes[2]) == 0xBF) {
            return new Result(StandardCharsets.UTF_8, true, 3);
        }
        if (bytes.length >= 2 && u(bytes[0]) == 0xFF && u(bytes[1]) == 0xFE) {
            return new Result(forNameOrNull("UTF-16LE"), true, 2);
        }
        if (bytes.length >= 2 && u(bytes[0]) == 0xFE && u(bytes[1]) == 0xFF) {
            return new Result(forNameOrNull("UTF-16BE"), true, 2);
        }
        return null;
    }

    private static int u(final byte b) {
        return b & 0xFF;
    }

    private static Charset forNameOrNull(final String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Best-effort heuristic detection over the (start of the) byte content.
     * Returns null if detection fails or confidence is too low.
     */
    public static Charset detectHeuristic(final byte[] bytes) {
        if (bytes.length == 0) {
            return null;
        }
        try {
            final UniversalDetector detector = new UniversalDetector(null);
            final int len = Math.min(bytes.length, 262144); // cap sample size
            detector.handleData(bytes, 0, len);
            detector.dataEnd();
            final String encoding = detector.getDetectedCharset();
            detector.reset();
            if (encoding != null) {
                return forNameOrNull(encoding);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Full detection pipeline for a freshly-read file:
     * 1) remembered per-file override (survives even if heuristic keeps getting it wrong)
     * 2) BOM
     * 3) heuristic detection
     * 4) fall back to provided default charset
     */
    public static Result detect(final Context context, final File file, final byte[] bytes, final Charset defaultCharset) {
        final Result bom = detectBom(bytes);

        final Result saved = getSavedForFile(context, file);
        if (saved != null) {
            final int skip = (bom != null && bom.charset.equals(saved.charset)) ? bom.skipBytes : 0;
            return new Result(saved.charset, saved.hasBom, skip);
        }

        if (bom != null) {
            return bom;
        }

        final Charset heuristic = detectHeuristic(bytes);
        if (heuristic != null) {
            return new Result(heuristic, false, 0);
        }

        return new Result(defaultCharset != null ? defaultCharset : StandardCharsets.UTF_8, false, 0);
    }

    // ---- Per-file persisted override ----

    private static SharedPreferences prefs(final Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static Result getSavedForFile(final Context context, final File file) {
        final SharedPreferences p = prefs(context);
        final String name = p.getString(KEY_CHARSET_PREFIX + file.getAbsolutePath(), null);
        if (name == null) {
            return null;
        }
        try {
            final Charset cs = Charset.forName(name);
            final boolean hasBom = p.getBoolean(KEY_BOM_PREFIX + file.getAbsolutePath(), false);
            return new Result(cs, hasBom, 0);
        } catch (Exception e) {
            return null;
        }
    }

    public static void saveForFile(final Context context, final File file, final Charset charset, final boolean hasBom) {
        prefs(context).edit()
                .putString(KEY_CHARSET_PREFIX + file.getAbsolutePath(), charset.name())
                .putBoolean(KEY_BOM_PREFIX + file.getAbsolutePath(), hasBom)
                .apply();
    }

    public static void clearForFile(final Context context, final File file) {
        prefs(context).edit()
                .remove(KEY_CHARSET_PREFIX + file.getAbsolutePath())
                .remove(KEY_BOM_PREFIX + file.getAbsolutePath())
                .apply();
    }

    // Called when a file is renamed/moved so the remembered encoding follows it
    public static void migrateFile(final Context context, final File oldFile, final File newFile) {
        final Result saved = getSavedForFile(context, oldFile);
        if (saved != null) {
            saveForFile(context, newFile, saved.charset, saved.hasBom);
            clearForFile(context, oldFile);
        }
    }
}
