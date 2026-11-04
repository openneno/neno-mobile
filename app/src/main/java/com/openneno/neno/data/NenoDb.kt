package com.openneno.neno.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 待推送的本地改动, 与网页端 dirty 表一致 */
data class DirtyEntry(
    val key: String,
    val kind: String,
    val id: String,
    val deleted: Boolean,
    val suffix: String?,
    val createdAt: String?,
    val at: Long,
    val rev: String,
)

/**
 * 本地数据库.
 *  notes      笔记, raw 是完整的 JSON, 其余列用于查询
 *  pics       图片的后缀, 文件保存在 files/pics 目录
 *  dirty      待推送的改动
 *  kv         标签设置, 同步状态等
 *  base_shas  上次同步时远端每个文件的 blob sha
 */
class NenoDb(context: Context) : SQLiteOpenHelper(context, "neno.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notes(
                id TEXT PRIMARY KEY,
                created_at TEXT NOT NULL,
                day TEXT NOT NULL,
                parent_id TEXT NOT NULL DEFAULT '',
                plain TEXT NOT NULL DEFAULT '',
                tags TEXT NOT NULL DEFAULT '',
                raw TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_notes_parent ON notes(parent_id)")
        db.execSQL("CREATE INDEX idx_notes_day ON notes(day)")
        db.execSQL("CREATE TABLE pics(id TEXT PRIMARY KEY, suffix TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE dirty(
                key TEXT PRIMARY KEY, kind TEXT NOT NULL, id TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0, suffix TEXT, created_at TEXT,
                at INTEGER NOT NULL, rev TEXT NOT NULL)"""
        )
        db.execSQL("CREATE TABLE kv(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE base_shas(path TEXT PRIMARY KEY, sha TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- 笔记 ----------

    fun putNote(note: Note, db: SQLiteDatabase = writableDatabase) {
        val cv = ContentValues().apply {
            put("id", note.id)
            put("created_at", note.createdAt)
            put("day", note.date)
            put("parent_id", note.parentId)
            put("plain", note.plainText.lowercase())
            // 前后加换行, 方便用 LIKE 精确匹配某个标签
            put("tags", note.tags.joinToString("\n", prefix = "\n", postfix = "\n"))
            put("raw", note.serialize())
        }
        db.insertWithOnConflict("notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getNote(id: String): Note? =
        readableDatabase.rawQuery("SELECT raw FROM notes WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) Note.parse(c.getString(0)) else null
        }

    fun deleteNote(id: String) {
        writableDatabase.delete("notes", "id=?", arrayOf(id))
    }

    fun noteCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM notes", null).use { c ->
        c.moveToFirst(); c.getInt(0)
    }

    /** 按 id 倒序(即创建时间倒序) 查询, 可以按标签, 日期, 关键词筛选 */
    fun queryNotes(
        tag: String? = null,
        day: String? = null,
        keyword: String? = null,
        limit: Int = -1,
        offset: Int = 0,
    ): List<Note> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!tag.isNullOrEmpty()) {
            where += "tags LIKE ? ESCAPE '\\'"; args += "%\n${escapeLike(tag)}\n%"
        }
        if (!day.isNullOrEmpty()) {
            where += "day = ?"; args += day
        }
        if (!keyword.isNullOrBlank()) {
            where += "plain LIKE ? ESCAPE '\\'"; args += "%${escapeLike(keyword.trim().lowercase())}%"
        }
        val sql = buildString {
            append("SELECT raw FROM notes")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY id DESC")
            if (limit > 0) append(" LIMIT $limit OFFSET $offset")
        }
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { it.notes() }
    }

    fun children(parentId: String): List<Note> =
        readableDatabase.rawQuery("SELECT raw FROM notes WHERE parent_id=? ORDER BY id", arrayOf(parentId))
            .use { it.notes() }

    fun allNotes(): List<Note> = queryNotes()

    fun allIds(): List<String> = readableDatabase.rawQuery("SELECT id FROM notes", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun allTagColumns(): List<String> = readableDatabase.rawQuery("SELECT tags FROM notes", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    /** 每天的笔记数量, 按日期升序 */
    fun countByDay(): Map<String, Int> =
        readableDatabase.rawQuery("SELECT day, COUNT(*) FROM notes GROUP BY day ORDER BY day", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }

    private fun Cursor.notes(): List<Note> = buildList {
        while (moveToNext()) runCatching { add(Note.parse(getString(0))) }
    }

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    // ---------- 图片 ----------

    fun putPic(id: String, suffix: String) {
        writableDatabase.insertWithOnConflict(
            "pics", null, ContentValues().apply { put("id", id); put("suffix", suffix) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun picSuffix(id: String): String? =
        readableDatabase.rawQuery("SELECT suffix FROM pics WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun allPics(): List<Pair<String, String>> = readableDatabase.rawQuery("SELECT id, suffix FROM pics", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
    }

    fun deletePic(id: String) {
        writableDatabase.delete("pics", "id=?", arrayOf(id))
    }

    // ---------- dirty ----------

    fun markDirty(kind: String, id: String, deleted: Boolean = false, suffix: String? = null, createdAt: String? = null) {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("key", "$kind:$id")
            put("kind", kind)
            put("id", id)
            put("deleted", if (deleted) 1 else 0)
            put("suffix", suffix)
            put("created_at", createdAt)
            put("at", now)
            put("rev", "$now-${Math.random()}")
        }
        writableDatabase.insertWithOnConflict("dirty", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun dirtyList(): List<DirtyEntry> =
        readableDatabase.rawQuery("SELECT key, kind, id, deleted, suffix, created_at, at, rev FROM dirty", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    DirtyEntry(
                        c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1,
                        c.getStringOrNull(4), c.getStringOrNull(5), c.getLong(6), c.getString(7)
                    )
                )
            }
        }

    fun dirtyCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM dirty", null).use { c ->
        c.moveToFirst(); c.getInt(0)
    }

    /** 只有在记录没有被再次修改时才清除 */
    fun clearDirty(entry: DirtyEntry) {
        writableDatabase.delete("dirty", "key=? AND rev=?", arrayOf(entry.key, entry.rev))
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    // ---------- kv ----------

    fun getKv(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM kv WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun putKv(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "kv", null, ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deleteKv(key: String) {
        writableDatabase.delete("kv", "key=?", arrayOf(key))
    }

    // ---------- base shas ----------

    fun loadBaseShas(): MutableMap<String, String> =
        readableDatabase.rawQuery("SELECT path, sha FROM base_shas", null).use { c ->
            val map = HashMap<String, String>()
            while (c.moveToNext()) map[c.getString(0)] = c.getString(1)
            map
        }

    fun saveBaseShas(map: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("base_shas", null, null)
            val stmt = db.compileStatement("INSERT INTO base_shas(path, sha) VALUES(?, ?)")
            for ((path, sha) in map) {
                stmt.bindString(1, path); stmt.bindString(2, sha); stmt.executeInsert(); stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun inTransaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db); db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
