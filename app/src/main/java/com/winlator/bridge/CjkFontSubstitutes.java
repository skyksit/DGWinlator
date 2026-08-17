package com.winlator.bridge;

import android.util.Log;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;
import com.winlator.core.WineRegistryEditor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Points the legacy CJK typefaces a 90s game asks for at a font that actually exists here.
 *
 * <p>Wine already registers the Android system fonts, so the glyphs are present — but a Korean or
 * Japanese game requests faces by their original names (굴림, MS Sans Serif, …) and Wine has no
 * mapping for them, so every character comes out as a blank box even with the right codepage. Adding
 * the names to {@code Software\Wine\Fonts\Replacements} is what makes the text appear.
 *
 * <p>The replacement face is discovered from the prefix's "External Fonts" list rather than
 * hardcoded: which CJK font ships varies by vendor (Samsung registers "SEC CJK KR", other builds
 * "Noto Sans CJK KR"), so the right one can only be known by looking.
 */
abstract class CjkFontSubstitutes {
    private static final String TAG = "DGPlayerBridge";
    private static final String KEY = "Software\\Wine\\Fonts\\Replacements";

    /** Faces old Korean/Japanese titles ask for. Latin aliases included — dialogs use those too. */
    private static final String[] REQUESTED = {
        "MS Sans Serif", "MS Shell Dlg", "MS Shell Dlg 2", "System", "Tahoma",
        "Gulim", "GulimChe", "Batang", "BatangChe", "Dotum", "DotumChe",
        "굴림", "굴림체", "바탕", "돋움",   // 굴림 굴림체 바탕 돋움
        "MS Gothic", "MS PGothic", "MS Mincho",
        "ＭＳ Ｐゴシック",                          // ＭＳ Ｐゴシック
    };

    /** Matches an External Fonts entry, e.g. {@code "@Noto Sans CJK KR (TrueType)"=...} */
    private static final Pattern EXTERNAL_FONT = Pattern.compile("\"@([^\"]+?) \\(TrueType\\)\"=");

    /**
     * @param locale the guest locale (e.g. {@code ko_KR.UTF-8}); decides which regional face wins
     * @return true if replacements were written
     */
    static boolean apply(Container container, String locale) {
        File userReg = new File(container.getRootDir(), ".wine/user.reg");
        if (!userReg.isFile()) return false;

        String face = pickFace(userReg, regionOf(locale));
        if (face == null) {
            Log.w(TAG, "no CJK face registered in the prefix; text may render as blank boxes");
            return false;
        }

        try (WineRegistryEditor editor = new WineRegistryEditor(userReg)) {
            editor.setCreateKeyIfNotExist(true);
            for (String requested : REQUESTED) editor.setStringValue(KEY, requested, face);
        }
        Log.i(TAG, "CJK font substitutes -> "+face);
        return true;
    }

    /** ko/ja/zh → the suffix Noto-style CJK faces use. Null for anything else. */
    private static String regionOf(String locale) {
        if (locale == null) return null;
        String lower = locale.toLowerCase();
        if (lower.startsWith("ko")) return "KR";
        if (lower.startsWith("ja")) return "JP";
        if (lower.startsWith("zh_tw") || lower.startsWith("zh_hk")) return "TC";
        if (lower.startsWith("zh")) return "SC";
        return null;
    }

    private static String pickFace(File userReg, String region) {
        List<String> faces = new ArrayList<>();
        for (String line : FileUtils.readLines(userReg)) {
            Matcher m = EXTERNAL_FONT.matcher(line);
            if (m.find()) faces.add(m.group(1));
        }

        // Prefer the region's own face, then a sans CJK face, then anything CJK at all.
        if (region != null) {
            for (String f : faces) if (f.endsWith(" "+region) && f.contains("Sans")) return f;
            for (String f : faces) if (f.endsWith(" "+region)) return f;
        }
        for (String f : faces) if (f.contains("CJK") && f.contains("Sans")) return f;
        for (String f : faces) if (f.contains("CJK")) return f;
        return null;
    }
}
