package com.eliosnotes.app.data.document

import com.eliosnotes.app.data.NoteEntity
import com.eliosnotes.app.ink.Stroke
import org.json.JSONObject

/** Everything one page holds, decoded and ready for rendering or export. */
data class PageContent(val page: NotePageEntity, val text: String, val strokes: List<Stroke>)

data class NoteDocument(val note: NoteEntity, val pages: List<PageContent>)

object TextCodec {
    fun encode(text: String): String = JSONObject().put("text", text).toString()

    fun decode(payload: String): String = try {
        JSONObject(payload).optString("text", "")
    } catch (e: Exception) {
        ""
    }
}

/** Shared page geometry in "page units" (A4 at 96 dpi). */
object PageSize {
    const val A4_WIDTH = 794f
    const val A4_HEIGHT = 1123f

    /** Page text metrics, shared by the on-screen text layer and the PDF exporter. */
    const val TEXT_SIZE = 20f
    const val TEXT_PADDING = 56f
}
