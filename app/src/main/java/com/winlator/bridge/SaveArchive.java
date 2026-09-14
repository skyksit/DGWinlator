package com.winlator.bridge;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.core.StreamUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Reads and writes the save archive that lives in DGPlayer's storage as
 * {@code files/win/save/<fileName>.zip}, reached through a content URI the caller granted.
 *
 * <p>Entry names are relative to {@code drive_c} so the archive is portable between containers and
 * devices, and readable by a human who opens it on a PC. {@code dgp_meta.json} is written first and
 * carries the {@code stamp} that decides whether an incoming archive is newer than what this device
 * already restored (see {@link SaveSync}).
 *
 * <p>Both directions stage through a local temp file. Writing straight into the content URI would
 * truncate DGPlayer's copy the moment the stream opens, so a crash halfway through would destroy the
 * previous save; building the archive first means the truncating write lasts only as long as a
 * copy of an already-complete file.
 */
abstract class SaveArchive {
    private static final String TAG = SaveSync.TAG;

    static final String META_ENTRY = "dgp_meta.json";

    private static final String META_FORMAT_VERSION = "formatVersion";
    private static final String META_GAME_ID = "gameId";
    private static final String META_STAMP = "stamp";
    private static final String META_EXPORTED_AT = "exportedAt";
    private static final String META_CONTAINER_ID = "containerId";
    private static final String META_ENTRIES = "entries";

    /** An empty zip is 22 bytes (end-of-central-directory only). */
    private static final int EMPTY_ZIP_SIZE = 22;

    /** A readable archive staged locally. Close deletes the staging copy. */
    static final class Archive implements Closeable {
        final File file;
        final String stamp;
        final int entryCount;

        private Archive(File file, String stamp, int entryCount) {
            this.file = file;
            this.stamp = stamp;
            this.entryCount = entryCount;
        }

        @Override
        public void close() {
            file.delete();
        }
    }

    /**
     * Stages the archive behind {@code saveUri} and validates its metadata.
     *
     * @return null when there is nothing usable there — no file yet, an empty archive, a revoked
     *         grant, or an archive belonging to another game or a newer format. All of those mean
     *         "do not restore", never "fail the launch".
     */
    static Archive open(Context context, Uri saveUri, File tempDir, String expectedGameId) {
        long size;
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(saveUri, "r")) {
            if (pfd == null) {
                Log.i(TAG, "no save archive yet (no descriptor)");
                return null;
            }
            size = pfd.getStatSize();
        }
        catch (SecurityException e) {
            Log.w(TAG, "save_uri not readable (grant missing?)", e);
            return null;
        }
        catch (Exception e) {
            Log.i(TAG, "no save archive yet: " + e);
            return null;
        }

        if (size <= EMPTY_ZIP_SIZE) {
            Log.i(TAG, "save archive is empty (" + size + " bytes)");
            return null;
        }

        File staged = FileUtils.createTempFile(tempDir, "restore");
        try (InputStream inStream = context.getContentResolver().openInputStream(saveUri);
             OutputStream outStream = new BufferedOutputStream(new FileOutputStream(staged), StreamUtils.BUFFER_SIZE)) {
            if (inStream == null || !StreamUtils.copy(inStream, outStream)) {
                staged.delete();
                return null;
            }
        }
        catch (Exception e) {
            Log.w(TAG, "could not stage save archive", e);
            staged.delete();
            return null;
        }

        // ZipFile (random access) rather than ZipInputStream: the metadata must be readable without
        // depending on it having been written first, and entries are extracted selectively.
        try (ZipFile zip = new ZipFile(staged)) {
            ZipEntry metaEntry = zip.getEntry(META_ENTRY);
            if (metaEntry == null) {
                Log.w(TAG, "save archive has no " + META_ENTRY + ", ignoring it");
                staged.delete();
                return null;
            }

            JSONObject meta;
            try (InputStream inStream = zip.getInputStream(metaEntry)) {
                meta = new JSONObject(new String(StreamUtils.copyToByteArray(inStream), "UTF-8"));
            }

            int formatVersion = meta.optInt(META_FORMAT_VERSION, 0);
            if (formatVersion <= 0 || formatVersion > SaveSync.FORMAT_VERSION) {
                Log.w(TAG, "save archive formatVersion=" + formatVersion + " unsupported, ignoring it");
                staged.delete();
                return null;
            }

            String gameId = meta.optString(META_GAME_ID, "");
            // Compared against the sanitized id, never against DGPlayer's file name: the two differ
            // whenever the file name contains characters sanitizeGameId collapses.
            if (!gameId.equals(expectedGameId)) {
                Log.w(TAG, "save archive belongs to " + gameId + ", expected " + expectedGameId);
                staged.delete();
                return null;
            }

            String stamp = meta.optString(META_STAMP, "");
            if (stamp.isEmpty()) {
                Log.w(TAG, "save archive has no stamp, ignoring it");
                staged.delete();
                return null;
            }
            return new Archive(staged, stamp, zip.size() - 1);
        }
        catch (Exception e) {
            Log.w(TAG, "unreadable save archive, ignoring it", e);
            staged.delete();
            return null;
        }
    }

    /**
     * Extracts the archive into the container.
     *
     * <p>Nothing is ever deleted: the archive holds only the paths this game is known to write, so a
     * path being absent is not evidence it was deleted — and the shared roots belong to every game
     * in the container.
     *
     * @param gameDirOnly restore only the game folder, used when the payload was just re-imported
     *                    and wiped it while the rest of the container survived
     * @param onlyMissing  write only files that are absent from the container. Used when the archive
     *                     was already applied here: the container's copies are then at least as new,
     *                     so they must not be overwritten, but a gap means the file was lost and the
     *                     archive is the only copy left
     * @param restored    receives the stat of each file written, so the caller can record it without
     *                    a second walk
     * @return the number of files written
     */
    static int extract(Archive archive, SaveSnapshot.Roots roots, boolean gameDirOnly,
                       boolean onlyMissing, Map<String, long[]> restored) {
        int written = 0;
        try (ZipFile zip = new ZipFile(archive.file)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName().replace('\\', '/');
                if (name.equals(META_ENTRY)) continue;
                if (!isAllowed(roots, name, gameDirOnly)) {
                    Log.w(TAG, "rejecting save entry " + name);
                    continue;
                }

                File file = new File(roots.driveC, name);
                if (onlyMissing && file.exists()) continue;
                // Zip slip, the same guard PayloadInstaller uses.
                if (!file.getCanonicalPath().startsWith(roots.driveC.getCanonicalPath() + File.separator)) {
                    Log.w(TAG, "rejecting escaping save entry " + name);
                    continue;
                }

                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    Log.w(TAG, "could not create " + parent);
                    continue;
                }

                try (InputStream inStream = zip.getInputStream(entry);
                     OutputStream outStream = new BufferedOutputStream(
                             new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                    if (!StreamUtils.copy(inStream, outStream)) {
                        Log.w(TAG, "could not write " + name);
                        continue;
                    }
                }

                // Same mode as the rest of the container so the guest can read it through PRoot.
                FileUtils.chmod(file, 0771);
                if (entry.getTime() > 0) file.setLastModified(entry.getTime());
                if (restored != null) restored.put(name, new long[]{file.length(), file.lastModified()});
                written++;
            }
        }
        catch (Exception e) {
            Log.e(TAG, "failed to extract save archive", e);
        }
        return written;
    }

    /**
     * Only the places a save can legitimately live. A tampered archive must not be able to drop a
     * DLL beside the executable, flip the install marker, or rewrite the manifest.
     */
    private static boolean isAllowed(SaveSnapshot.Roots roots, String name, boolean gameDirOnly) {
        if (name.isEmpty() || name.startsWith("/")) return false;
        if (name.equals("..") || name.startsWith("../") || name.contains("/../") || name.endsWith("/..")) return false;
        if (SaveSnapshot.isExcluded(roots, name)) return false;

        if (name.startsWith(roots.gameDirPrefix)) return true;
        if (gameDirOnly) return false;
        if (name.startsWith("users/xuser/")) return true;
        if (name.startsWith("ProgramData/")) return true;
        // C:\windows: top level only, mirroring what the snapshot watches.
        return name.startsWith("windows/") && name.indexOf('/', "windows/".length()) == -1;
    }

    /**
     * Builds the archive from {@code paths} and copies it over {@code saveUri}.
     *
     * @return the new stamp on success, null when nothing was written
     */
    static String write(Context context, Uri saveUri, File tempDir, String gameId, int containerId,
                        Collection<String> paths, SaveSnapshot.Roots roots,
                        Map<String, long[]> current, long[] bytesOut) {
        String stamp = UUID.randomUUID().toString();
        File staged = FileUtils.createTempFile(tempDir, "export");
        long total = 0;
        int count = 0;

        try {
            JSONArray entries = new JSONArray();
            try (ZipOutputStream zipStream = new ZipOutputStream(new BufferedOutputStream(
                    new FileOutputStream(staged), StreamUtils.BUFFER_SIZE))) {
                // Placeholder first so the metadata is the first entry even though its entry list is
                // only complete at the end; rewritten below once the contents are known.
                JSONObject meta = new JSONObject();
                meta.put(META_FORMAT_VERSION, SaveSync.FORMAT_VERSION);
                meta.put(META_GAME_ID, gameId);
                meta.put(META_STAMP, stamp);
                meta.put(META_EXPORTED_AT, System.currentTimeMillis());
                meta.put(META_CONTAINER_ID, containerId);

                for (String path : paths) {
                    File file = new File(roots.driveC, path);
                    if (!file.isFile()) continue;

                    long length = file.length();
                    if (length > SaveSync.MAX_FILE_BYTES) {
                        Log.w(TAG, "skipping oversized save " + path + " (" + length + " bytes)");
                        continue;
                    }
                    if (total + length > SaveSync.MAX_TOTAL_BYTES) {
                        Log.w(TAG, "save archive size cap reached, skipping " + path);
                        continue;
                    }

                    JSONObject info = new JSONObject();
                    info.put("path", path);
                    info.put("size", length);
                    info.put("mtime", file.lastModified());
                    entries.put(info);

                    total += length;
                    count++;
                    if (current != null) current.put(path, new long[]{length, file.lastModified()});
                }

                if (count == 0) {
                    // Never truncate DGPlayer's copy with an empty archive: this also happens when
                    // the container was deleted, and the existing archive is then the only copy left.
                    Log.i(TAG, "nothing to export, leaving the existing archive untouched");
                    return null;
                }

                meta.put(META_ENTRIES, entries);
                ZipEntry metaEntry = new ZipEntry(META_ENTRY);
                zipStream.putNextEntry(metaEntry);
                zipStream.write(meta.toString().getBytes("UTF-8"));
                zipStream.closeEntry();

                for (int i = 0; i < entries.length(); i++) {
                    String path = entries.getJSONObject(i).getString("path");
                    File file = new File(roots.driveC, path);
                    if (!file.isFile()) continue;

                    ZipEntry entry = new ZipEntry(path);
                    entry.setTime(file.lastModified());
                    zipStream.putNextEntry(entry);
                    try (InputStream inStream = new FileInputStream(file)) {
                        if (!StreamUtils.copy(inStream, zipStream)) {
                            Log.w(TAG, "could not read " + path);
                        }
                    }
                    zipStream.closeEntry();
                }
            }

            // Only now is DGPlayer's copy touched. "wt" truncates and creates; the parent directory
            // must already exist on the caller's side (FileProvider does not mkdirs).
            try (InputStream inStream = new FileInputStream(staged);
                 OutputStream outStream = context.getContentResolver().openOutputStream(saveUri, "wt")) {
                if (outStream == null) {
                    Log.e(TAG, "save_uri gave no output stream");
                    return null;
                }
                if (!StreamUtils.copy(inStream, outStream)) {
                    Log.e(TAG, "could not copy the archive into save_uri");
                    return null;
                }
            }

            if (bytesOut != null && bytesOut.length > 0) bytesOut[0] = total;
            Log.i(TAG, String.format(Locale.ENGLISH,
                    "save archive written: files=%d bytes=%d stamp=%s", count, total, stamp));
            return stamp;
        }
        catch (SecurityException e) {
            Log.e(TAG, "save_uri not writable (grant missing?)", e);
            return null;
        }
        catch (Exception e) {
            Log.e(TAG, "failed to write the save archive", e);
            return null;
        }
        finally {
            staged.delete();
        }
    }
}
