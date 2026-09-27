package com.phiraphongbenzz.ktorch

import androidx.compose.ui.graphics.Color
import com.phiraphongbenzz.ktorch.internal.data.Body
import com.phiraphongbenzz.ktorch.internal.data.BodyState
import com.phiraphongbenzz.ktorch.internal.ui.BodyContent
import com.phiraphongbenzz.ktorch.internal.ui.JsonColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyContentTest {

    private val colors = JsonColors(key = Color.Red, string = Color.Green, number = Color.Blue, literal = Color.Magenta)

    @Test
    fun `key search matches key names only, ignoring case`() {
        val content = BodyContent.build(
            json("""{"userId":7,"name":"id","items":[{"ID":1}],"nested":{"valid":true}}"""),
            pretty = true,
            colors = colors,
        )
        val hits = content.findKeys("id").map { it.line to content.lines[it.line].text.substring(it.start, it.end) }
        // The "id" string value on line 2 is not a key.
        assertEquals(listOf(1 to "Id", 5 to "ID", 9 to "id"), hits)
    }

    @Test
    fun `key search needs a query and the pretty-printed view`() {
        val body = json("""{"id":1}""")
        assertTrue(BodyContent.build(body, pretty = true, colors = colors).findKeys("").isEmpty())
        assertTrue(BodyContent.build(body, pretty = false, colors = colors).findKeys("id").isEmpty())
    }

    @Test
    fun `key search still works when a huge document skips syntax colors`() {
        val content = BodyContent.build(json("""{"blob":"${"x".repeat(700_000)}","id":1}"""), pretty = true, colors = colors)
        assertFalse(content.highlighted)
        val hits = content.findKeys("ID")
        assertEquals(1, hits.size)
        assertEquals("  \"id\": 1", content.lines[hits[0].line].text)
    }

    private fun json(text: String) = Body(BodyState.CAPTURED, "application/json", text.length.toLong(), text.toByteArray())
}
