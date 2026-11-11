package com.logan.multiurlmanager.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 测试包独立进程没有宿主的 Kotlin 运行时，只使用平台 API 提供受控文件。 */
public final class ConfigurationTestProvider extends ContentProvider {
    private File directory;

    @Override
    public boolean onCreate() {
        directory = new File(getContext().getCacheDir(), "configuration-file-tests");
        return directory.mkdirs() || directory.isDirectory();
    }

    @Override
    public String getType(Uri uri) {
        return "application/json";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if ("read-failure".equals(name) || "write-failure".equals(name)) {
            throw new FileNotFoundException("Injected file provider failure");
        }
        if (!"incoming".equals(name) && !"exported".equals(name)) {
            throw new FileNotFoundException("Unknown test file");
        }
        File file = new File(directory, name + ".json");
        if ("incoming".equals(name)) {
            if (!"r".equals(mode)) throw new FileNotFoundException("Read-only test file");
            String json = "{\"schemaVersion\":1,\"environments\":[{\"id\":\"dev\",\"name\":\"Development\","
                    + "\"urls\":[{\"configKey\":\"order\",\"url\":\"https://file-team.example.com/\",\"select\":true}]}]}";
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) {
                throw new FileNotFoundException("Cannot prepare test file");
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{"configuration.json", null});
        return cursor;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (!"exported".equals(uri.getLastPathSegment())) return 0;
        return new File(directory, "exported.json").delete() ? 1 : 0;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
