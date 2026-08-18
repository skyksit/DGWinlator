package com.winlator.bridge;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.core.StreamUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports a DGPlayer game archive into a container directory.
 *
 * <p>The payload arrives as a {@code content://} URI granted by dsam3, whose copy lives in its own
 * private {@code filesDir}. Streaming it with {@link ZipInputStream} avoids staging a second full
 * copy on disk, which matters because these archives run to hundreds of megabytes.
 *
 * <p>{@code ZipUtils.extract} is not reused here: it needs a seekable {@code File}, and its asset
 * variant does not create parent directories for entries whose folders have no explicit zip entry.
 */
abstract class PayloadInstaller {
    private static final String TAG = "DGPlayerBridge";
    /**
     * Written once a payload has been fully extracted, so relaunches skip the import. It stores the
     * payload's {@link #fingerprint} so a repackaged zip under the same game id (same dsam3 file
     * name) is detected and re-imported instead of silently running the stale install.
     */
    private static final String INSTALLED_MARKER = ".dgp_installed";
    /** How much of the zip's tail goes into the fingerprint — covers the central directory. */
    private static final int FINGERPRINT_TAIL_SIZE = 65536;

    /**
     * Cheap identity of the payload archive: its size plus a CRC of its last 64KB. A zip's central
     * directory lives at the end and changes whenever any entry changes, so this catches a swapped
     * archive without streaming hundreds of megabytes on every launch.
     *
     * @return null when the URI cannot be opened or is not a plain seekable file — callers must
     *         treat that as "cannot compare", not as a mismatch
     */
    static String fingerprint(Context context, Uri contentUri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(contentUri, "r")) {
            if (pfd == null) {
                Log.w(TAG, "fingerprint: resolver returned no fd for "+contentUri);
                return null;
            }
            long size = pfd.getStatSize();
            if (size <= 0) {
                Log.w(TAG, "fingerprint: statSize="+size+" for "+contentUri);
                return null;
            }

            try (FileInputStream inStream = new FileInputStream(pfd.getFileDescriptor())) {
                FileChannel channel = inStream.getChannel();
                int tail = (int)Math.min(size, FINGERPRINT_TAIL_SIZE);
                ByteBuffer buffer = ByteBuffer.allocate(tail);
                channel.position(size - tail);
                while (buffer.hasRemaining() && channel.read(buffer) != -1);

                CRC32 crc = new CRC32();
                crc.update(buffer.array(), 0, buffer.position());
                return size+":"+Long.toHexString(crc.getValue());
            }
        }
        catch (Exception e) {
            Log.w(TAG, "could not fingerprint payload "+contentUri, e);
            return null;
        }
    }

    /**
     * @param fingerprint the current payload's {@link #fingerprint}, or null when unavailable —
     *                    null keeps whatever is installed (better than re-importing every launch)
     */
    static boolean isInstalled(File destination, String fingerprint) {
        File marker = new File(destination, INSTALLED_MARKER);
        if (!marker.isFile()) {
            Log.i(TAG, "isInstalled: no marker, fresh install needed");
            return false;
        }
        if (fingerprint == null) {
            Log.w(TAG, "isInstalled: payload fingerprint unavailable, keeping current install");
            return true;
        }
        // Markers from before fingerprinting are empty; treat as mismatch so the one next launch
        // re-imports and records a comparable identity from then on.
        String stored = FileUtils.readString(marker).trim();
        boolean match = fingerprint.equals(stored);
        Log.i(TAG, "isInstalled: payload="+fingerprint+" marker="+stored+" match="+match);
        return match;
    }

    /** Extracts the archive behind {@code contentUri} into {@code destination}. */
    static boolean install(Context context, Uri contentUri, File destination, String fingerprint) {
        if (!destination.isDirectory() && !destination.mkdirs()) return false;

        String destinationPath;
        try {
            destinationPath = destination.getCanonicalPath();
        }
        catch (IOException e) {
            return false;
        }

        try (InputStream inStream = context.getContentResolver().openInputStream(contentUri)) {
            if (inStream == null) return false;

            try (ZipInputStream zipStream = new ZipInputStream(inStream)) {
                ZipEntry entry;
                while ((entry = zipStream.getNextEntry()) != null) {
                    File file = new File(destination, entry.getName());

                    // Zip slip: an entry named ../../foo would otherwise write outside the container.
                    if (!file.getCanonicalPath().startsWith(destinationPath+File.separator)) return false;

                    if (entry.isDirectory()) {
                        if (!file.isDirectory() && !file.mkdirs()) return false;
                    }
                    else {
                        File parent = file.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;

                        try (BufferedOutputStream outStream = new BufferedOutputStream(
                                new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                            if (!StreamUtils.copy(zipStream, outStream)) return false;
                        }
                    }

                    // Same mode the rest of Winlator uses for container content, so the guest can
                    // read and traverse it through PRoot.
                    FileUtils.chmod(file, 0771);
                    zipStream.closeEntry();
                }
            }
        }
        catch (Exception e) {
            Log.e(TAG, "failed to extract payload from "+contentUri, e);
            return false;
        }

        return markInstalled(destination, fingerprint);
    }

    private static boolean markInstalled(File destination, String fingerprint) {
        File marker = new File(destination, INSTALLED_MARKER);
        try {
            if (!marker.createNewFile() && !marker.exists()) return false;
        }
        catch (IOException e) {
            return false;
        }
        if (fingerprint != null) FileUtils.writeString(marker, fingerprint);
        FileUtils.chmod(marker, 0771);
        return true;
    }

    static void deleteRecursively(File file) {
        if (file.exists()) FileUtils.delete(file);
    }
}
