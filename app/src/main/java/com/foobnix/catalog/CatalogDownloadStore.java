package com.foobnix.catalog;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Small independent queue; the reader's generated greenDAO schema stays untouched. */
final class CatalogDownloadStore extends SQLiteOpenHelper {
    CatalogDownloadStore(Context context) { super(context, "catalog-downloads.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY NOT NULL, created INTEGER NOT NULL, body TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Missing catalog queue migration");
    }
    synchronized CatalogDownloads.Task get(String id) {
        try (Cursor cursor = getReadableDatabase().query("tasks", new String[]{"body"}, "id=?", new String[]{id}, null, null, null)) {
            return cursor.moveToFirst() ? decode(cursor.getString(0)) : null;
        }
    }
    synchronized List<CatalogDownloads.Task> all() {
        List<CatalogDownloads.Task> tasks = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("tasks", new String[]{"body"}, null, null, null, null, "created DESC")) {
            while (cursor.moveToNext()) tasks.add(decode(cursor.getString(0)));
        }
        return tasks;
    }
    synchronized void put(CatalogDownloads.Task task) {
        ContentValues values = new ContentValues();
        values.put("id", task.id); values.put("created", task.created); values.put("body", encode(task).toString());
        if (getWritableDatabase().insertWithOnConflict("tasks", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1) {
            throw new IllegalStateException("Cannot persist download task");
        }
    }
    static JSONObject encode(CatalogDownloads.Task t) {
        try {
            return new JSONObject().put("id",t.id).put("bookId",t.bookId).put("title",t.title)
                    .put("author",t.author).put("format",t.format).put("status",t.status)
                    .put("error",t.error).put("errorCode",t.errorCode).put("localPath",t.localPath)
                    .put("localUri",t.localUri).put("bytes",t.bytes).put("total",t.total)
                    .put("url",t.url).put("mime",t.mime).put("tree",t.tree).put("generation",t.generation)
                    .put("workId",t.workId).put("created",t.created).put("pendingUri",t.pendingUri)
                    .put("pendingPath",t.pendingPath).put("cleanupUris",t.cleanupUris).put("indexed",t.indexed);
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    static CatalogDownloads.Task decode(String json) {
        try {
            JSONObject o = new JSONObject(json); CatalogDownloads.Task t = new CatalogDownloads.Task();
            t.id=o.getString("id"); t.bookId=o.optString("bookId"); t.title=o.optString("title");
            t.author=o.optString("author"); t.format=o.optString("format"); t.status=o.optString("status");
            t.error=o.optString("error"); t.errorCode=o.optString("errorCode");
            t.localPath=o.optString("localPath"); t.localUri=o.optString("localUri");
            t.bytes=o.optLong("bytes"); t.total=o.optLong("total",-1); t.url=o.optString("url");
            t.mime=o.optString("mime"); t.tree=o.optString("tree"); t.generation=o.optString("generation");
            t.workId=o.optString("workId"); t.created=o.optLong("created");
            t.pendingUri=o.optString("pendingUri"); t.pendingPath=o.optString("pendingPath"); t.cleanupUris=o.optString("cleanupUris","[]"); t.indexed=o.optBoolean("indexed"); return t;
        } catch (JSONException e) { throw new IllegalStateException("Invalid download record", e); }
    }
}
