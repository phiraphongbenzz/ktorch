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


    @Test
    fun `folds cover multi-line objects and arrays and hide their lines`() {
        val content = BodyContent.build(json("""{"a":{"b":1,"c":[1,2]},"d":[],"e":2}"""), pretty = true, colors = colors)
        // {  "a": {  "b": 1,  "c": [  1,  2  ]  },  "d": [],  "e": 2  }
        assertEquals(listOf(Triple(0, 10, 3), Triple(1, 7, 2), Triple(3, 6, 2)), content.folds.map { Triple(it.start, it.end, it.size) })
        assertTrue(content.folds.all { it.closed })
        assertEquals(null, content.foldAt(8)) // "d": [] is empty: nothing to fold

        assertEquals(listOf(0, 1, 8, 9, 10), content.visibleLines(setOf(1)).toList())
        // A folded line shows its own closing bracket, so the root folds into one line.
        assertEquals(listOf(0), content.visibleLines(setOf(0, 1)).toList())
        assertEquals(setOf(1, 3), content.collapseAll())
        // Revealing a line inside nested folds opens every fold around it, and only those.
        assertEquals(setOf(9), content.reveal(setOf(1, 3, 9), 4))
    }

    @Test
    fun `a truncated JSON body is still pretty-printed and its open containers fold to the end`() {
        val text = """{"items":[{"id":1},{"id":2},{"id":3"""
        val body = Body(BodyState.TRUNCATED, "application/json", 1_000, text.toByteArray())
        val content = BodyContent.build(body, pretty = true, colors = colors)
        assertTrue(content.isJson)
        assertEquals("{", content.lines.first().text)
        val last = content.lines.lastIndex
        assertEquals(listOf(0 to last, 1 to last), content.folds.filter { !it.closed }.map { it.start to it.end })
    }

    private fun json(text: String) = Body(BodyState.CAPTURED, "application/json", text.length.toLong(), text.toByteArray())
}
