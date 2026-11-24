// Adapted from sung9155/i30-pd-dashboard (i30pd_port/installer/android, MIT) for the Veloster N public repo.
package com.veloster.installer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lends one file from files/diag to the mail app the user picks, or the downloaded update in files/update to the system
 * installer (read only, by a one-time grant; not exported).
 * Does what androidx FileProvider would, without a Gradle build.
 */
public final class DiagProvider extends ContentProvider {
    static final String AUTHORITY = "com.veloster.installer.diag";

    static Uri uri(File f) {
        String dir = f.getParentFile().getName().equals("update") ? "update/" : "";
        return Uri.parse("content://" + AUTHORITY + "/" + dir + Uri.encode(f.getName()));
    }

    private File file(Uri u) throws FileNotFoundException {
        boolean upd = u.getPathSegments().size() == 2 && "update".equals(u.getPathSegments().get(0));
        File dir = new File(getContext().getFilesDir(), upd ? "update" : "diag"), f = new File(dir, String.valueOf(u.getLastPathSegment()));
        if (!dir.equals(f.getParentFile()) || !f.isFile()) throw new FileNotFoundException(String.valueOf(u));
        return f;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri u) {
        String n = String.valueOf(u.getLastPathSegment());
        return n.endsWith(".apk") ? "application/vnd.android.package-archive" : "text/plain";
    }

    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(u), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Mail apps ask for the attachment's name and size first. */
    @Override public Cursor query(Uri u, String[] projection, String selection, String[] args, String order) {
        try {
            File f = file(u);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) { return null; }
    }

    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
