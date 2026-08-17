package com.winlator.bridge;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.core.StreamUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    /** Written once a payload has been fully extracted, so relaunches skip the import entirely. */
    private static final String INSTALLED_MARKER = ".dgp_installed";

    static boolean isInstalled(File destination) {
        return new File(destination, INSTALLED_MARKER).exists();
    }

    /** Extracts the archive behind {@code contentUri} into {@code destination}. */
    static boolean install(Context context, Uri contentUri, File destination) {
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

        return markInstalled(destination);
    }

    private static boolean markInstalled(File destination) {
        File marker = new File(destination, INSTALLED_MARKER);
        try {
            if (!marker.createNewFile() && !marker.exists()) return false;
        }
        catch (IOException e) {
            return false;
        }
        FileUtils.chmod(marker, 0771);
        return true;
    }

    static void deleteRecursively(File file) {
        if (file.exists()) FileUtils.delete(file);
    }
}
