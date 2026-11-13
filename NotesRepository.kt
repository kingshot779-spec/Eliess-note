package com.eliosnotes.app.data

import androidx.room.withTransaction
import com.eliosnotes.app.data.document.NoteDocument
import com.eliosnotes.app.data.document.NoteElementDao
import com.eliosnotes.app.data.document.NoteElementEntity
import com.eliosnotes.app.data.document.NoteElementType
import com.eliosnotes.app.data.document.NotePageDao
import com.eliosnotes.app.data.document.NotePageEntity
import com.eliosnotes.app.data.document.PageContent
import com.eliosnotes.app.data.document.PageOrientation
import com.eliosnotes.app.data.document.PageSize
import com.eliosnotes.app.data.document.PageTemplate
import com.eliosnotes.app.data.document.TextCodec
import com.eliosnotes.app.ink.InkCodec
import com.eliosnotes.app.ink.Stroke
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Single entry point for document persistence. Text and ink of a page are written in one
 * transaction, and all operations share one lock so a save can never interleave with a load.
 */
class NotesRepository(private val db: NotesDatabase) {
    val noteDao: NoteDao = db.noteDao()
    val folderDao: FolderDao = db.folderDao()
    private val pageDao: NotePageDao = db.notePageDao()
    private val elementDao: NoteElementDao = db.noteElementDao()
    private val lock = Mutex()

    fun newPage(noteId: String, index: Int, now: Long) = NotePageEntity(
        id = UUID.randomUUID().toString(),
        noteId = noteId,
        pageIndex = index,
        widthDp = PageSize.A4_WIDTH,
        heightDp = PageSize.A4_HEIGHT,
        orientation = PageOrientation.PORTRAIT,
        template = PageTemplate.BLANK,
        createdAt = now,
        updatedAt = now
    )

    suspend fun createNote(folderId: String?): NoteEntity = lock.withLock {
        db.withTransaction {
            val now = System.currentTimeMillis()
            val note = NoteEntity(
                id = UUID.randomUUID().toString(),
                title = "", body = "", createdAt = now, updatedAt = now, folderId = folderId
            )
            noteDao.insert(note)
            pageDao.insert(newPage(note.id, 0, now))
            note
        }
    }

    suspend fun loadDocument(noteId: String): NoteDocument? = lock.withLock {
        db.withTransaction {
            val note = noteDao.getById(noteId) ?: return@withTransaction null
            var pages = pageDao.getPages(noteId)
            if (pages.isEmpty()) {
                // Notes created before the page model existed get their first page lazily.
                val first = newPage(noteId, 0, System.currentTimeMillis())
                pageDao.insert(first)
                pages = listOf(first)
            }
            val content = pages.mapIndexed { index, page ->
                var text = ""
                var hasTextElement = false
                val strokes = ArrayList<Stroke>()
                for (e in elementDao.getElements(page.id)) {
                    when (e.elementType) {
                        NoteElementType.TEXT -> { text = TextCodec.decode(e.payloadJson); hasTextElement = true }
                        NoteElementType.INK -> InkCodec.decode(e.id, e.payloadJson)?.let { strokes.add(it) }
                    }
                }
                // Legacy v1 notes kept their text in notes.body; show it on the first page.
                if (!hasTextElement && index == 0 && note.body.isNotEmpty()) text = note.body
                PageContent(page, text, strokes)
            }
            NoteDocument(note, content)
        }
    }

    suspend fun saveTitle(noteId: String, title: String) = lock.withLock {
        noteDao.updateTitle(noteId, title, System.currentTimeMillis())
    }

    suspend fun savePage(noteId: String, pageId: String, text: String, strokes: List<Stroke>) = lock.withLock {
        db.withTransaction {
            val page = pageDao.getById(pageId) ?: return@withTransaction // page was deleted meanwhile
            val now = System.currentTimeMillis()

            elementDao.upsert(
                NoteElementEntity(
                    id = "text-$pageId", pageId = pageId, elementType = NoteElementType.TEXT,
                    payloadVersion = 1, payloadJson = TextCodec.encode(text),
                    x = 0f, y = 0f, width = page.widthDp, height = page.heightDp,
                    zIndex = 0, createdAt = now, updatedAt = now
                )
            )

            val existing = elementDao.getIdsByType(pageId, NoteElementType.INK).toSet()
            val currentIds = strokes.mapTo(HashSet()) { it.id }
            val stale = existing - currentIds
            stale.toList().chunked(400).forEach { elementDao.deleteByIds(it) }

            val fresh = strokes.filter { it.id !in existing }
            val pureAppend = stale.isEmpty() &&
                strokes.size - fresh.size == existing.size &&
                strokes.take(existing.size).all { it.id in existing }
            // Appending keeps old z-order valid; anything else rewrites the z-order of the page.
            val toWrite = if (pureAppend) fresh else strokes
            val offset = if (pureAppend) existing.size else 0
            elementDao.upsertAll(toWrite.mapIndexed { i, s -> strokeElement(pageId, s, offset + i, now) })

            pageDao.touch(pageId, now)
            val body = elementDao.getTextPayloads(noteId)
                .map { TextCodec.decode(it) }
                .filter { it.isNotBlank() }
                .joinToString("\n")
            noteDao.updateContent(noteId, body, now)
        }
    }

    private fun strokeElement(pageId: String, s: Stroke, z: Int, now: Long): NoteElementEntity {
        val b = s.bounds
        return NoteElementEntity(
            id = s.id, pageId = pageId, elementType = NoteElementType.INK,
            payloadVersion = InkCodec.PAYLOAD_VERSION, payloadJson = InkCodec.encode(s),
            x = b[0], y = b[1], width = b[2] - b[0], height = b[3] - b[1],
            zIndex = z, createdAt = now, updatedAt = now
        )
    }

    suspend fun insertPage(page: NotePageEntity) = lock.withLock { pageDao.insert(page) }

    suspend fun updatePages(pages: List<NotePageEntity>) = lock.withLock {
        pageDao.updateAll(pages)
    }

    suspend fun deletePage(noteId: String, pageId: String, remaining: List<NotePageEntity>) = lock.withLock {
        db.withTransaction {
            pageDao.deleteById(pageId)
            pageDao.updateAll(remaining)
            val body = elementDao.getTextPayloads(noteId)
                .map { TextCodec.decode(it) }.filter { it.isNotBlank() }.joinToString("\n")
            noteDao.updateContent(noteId, body, System.currentTimeMillis())
        }
    }

    suspend fun setPageMode(noteId: String, mode: String) = lock.withLock { noteDao.setPageMode(noteId, mode) }

    suspend fun deleteFolder(folderId: String) = lock.withLock {
        db.withTransaction {
            folderDao.detachNotes(folderId)
            folderDao.deleteById(folderId)
        }
    }

    suspend fun purgeOldTrash(days: Int) = lock.withLock {
        noteDao.purgeTrashedBefore(System.currentTimeMillis() - days * 86_400_000L)
    }
}
