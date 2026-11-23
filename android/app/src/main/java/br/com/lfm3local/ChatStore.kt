package br.com.lfm3local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Private, offline-only storage. It is not synced or exported to other apps. */
data class LocalConversation(
    val id: Long,
    val title: String,
    val preview: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class LocalMessage(
    val id: Long,
    val chatId: Long,
    val role: String,
    val body: String,
    val status: String,
    val timestamp: Long
)

data class LocalToolEvent(
    val id: Long,
    val chatId: Long,
    val tool: String,
    val parameters: String,
    val outcome: String,
    val success: Boolean,
    val timestamp: Long
)

/** All calls must be dispatched off the Android UI thread. */
class ChatStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "lfm3local_history.db", null, 2
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                preview TEXT NOT NULL DEFAULT '',
                created_ms INTEGER NOT NULL,
                updated_ms INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                role TEXT NOT NULL CHECK (role IN ('user','assistant')),
                body TEXT NOT NULL,
                status TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX messages_by_chat ON messages(chat_id, id)")
        db.execSQL("CREATE INDEX conversations_recent ON conversations(updated_ms DESC)")
        createToolEventsTable(db)
    }

    private fun createToolEventsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE tool_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                tool TEXT NOT NULL,
                parameters TEXT NOT NULL,
                outcome TEXT NOT NULL,
                success INTEGER NOT NULL,
                timestamp_ms INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX tool_events_by_chat ON tool_events(chat_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2 && newVersion >= 2) {
            createToolEventsTable(db)
        }
    }

    /** A process may die during token streaming; mark such turns as interrupted. */
    fun recoverPendingMessages() {
        writableDatabase.execSQL("""
            UPDATE messages
            SET status='interrupted',
                body=CASE WHEN TRIM(body)='' THEN 'Resposta interrompida.' ELSE body END
            WHERE status='draft'
        """.trimIndent())
    }

    fun conversations(): List<LocalConversation> {
        val result = mutableListOf<LocalConversation>()
        readableDatabase.query(
            "conversations", arrayOf("id", "title", "preview", "created_ms", "updated_ms"),
            null, null, null, null, "updated_ms DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(LocalConversation(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                    cursor.getLong(3), cursor.getLong(4)
                ))
            }
        }
        return result
    }

    fun create(title: String = "Nova conversa"): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("title", title)
            put("preview", "")
            put("created_ms", now)
            put("updated_ms", now)
        }
        val id = writableDatabase.insertOrThrow("conversations", null, values)
        require(id > 0) { "Could not create chat" }
        return id
    }

    fun messages(chatId: Long): List<LocalMessage> {
        val result = mutableListOf<LocalMessage>()
        readableDatabase.query(
            "messages",
            arrayOf("id", "chat_id", "role", "body", "status", "timestamp_ms"),
            "chat_id=?", arrayOf(chatId.toString()), null, null, "id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(LocalMessage(
                    cursor.getLong(0), cursor.getLong(1), cursor.getString(2),
                    cursor.getString(3), cursor.getString(4), cursor.getLong(5)
                ))
            }
        }
        return result
    }

    fun append(chatId: Long, role: String, body: String, status: String = "complete"): Long {
        require(role == "user" || role == "assistant")
        val now = System.currentTimeMillis()
        val db = writableDatabase
        val id = db.insertOrThrow("messages", null, ContentValues().apply {
            put("chat_id", chatId)
            put("role", role)
            put("body", body)
            put("status", status)
            put("timestamp_ms", now)
        })
        touch(db, chatId, body, now)
        return id
    }

    fun updateMessage(messageId: Long, chatId: Long, body: String, status: String) {
        val db = writableDatabase
        db.update("messages", ContentValues().apply {
            put("body", body)
            put("status", status)
        }, "id=? AND chat_id=?", arrayOf(messageId.toString(), chatId.toString()))
        touch(db, chatId, body, System.currentTimeMillis())
    }

    /** Audit log of actual tool executions or permission denials. */
    fun appendToolEvent(chatId: Long, tool: String, parameters: String,
                        outcome: String, success: Boolean) {
        writableDatabase.insertOrThrow("tool_events", null, ContentValues().apply {
            put("chat_id", chatId)
            put("tool", tool.take(64))
            put("parameters", parameters.take(300))
            put("outcome", outcome.take(500))
            put("success", if (success) 1 else 0)
            put("timestamp_ms", System.currentTimeMillis())
        })
    }

    fun toolEvents(chatId: Long): List<LocalToolEvent> {
        val result = mutableListOf<LocalToolEvent>()
        readableDatabase.query(
            "tool_events",
            arrayOf("id", "chat_id", "tool", "parameters", "outcome", "success", "timestamp_ms"),
            "chat_id=?", arrayOf(chatId.toString()), null, null, "id DESC", "50"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(LocalToolEvent(
                    cursor.getLong(0), cursor.getLong(1), cursor.getString(2),
                    cursor.getString(3), cursor.getString(4),
                    cursor.getInt(5) == 1, cursor.getLong(6)
                ))
            }
        }
        return result
    }

    fun rename(chatId: Long, title: String) {
        val safeTitle = title.trim().take(72)
        require(safeTitle.isNotBlank())
        writableDatabase.update("conversations", ContentValues().apply {
            put("title", safeTitle)
        }, "id=?", arrayOf(chatId.toString()))
    }

    fun delete(chatId: Long) {
        writableDatabase.delete("conversations", "id=?", arrayOf(chatId.toString()))
    }

    private fun touch(db: SQLiteDatabase, chatId: Long, body: String, now: Long) {
        db.update("conversations", ContentValues().apply {
            put("preview", body.trim().take(240))
            put("updated_ms", now)
        }, "id=?", arrayOf(chatId.toString()))
    }
}
