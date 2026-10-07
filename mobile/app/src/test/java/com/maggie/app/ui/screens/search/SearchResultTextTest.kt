package com.maggie.app.ui.screens.search

import com.maggie.app.data.model.SearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** MAG-337: the search screen crashed on the first result whose highlight came as a list of fragments. */
class SearchResultTextTest {

    private fun result(json: String): SearchResult = Json.decodeFromString(SearchResult.serializer(), json)

    @Test
    fun `a highlight sent as a list of fragments reads its first fragment, without the tags`() {
        val r = result("""{"index":"events","id":"01","data":{"summary":"Piano"},"highlights":{"summary":["<em>Piano</em> du jeudi","autre"]}}""")
        assertEquals("Piano du jeudi", searchResultHighlight(r))
        assertEquals("Piano", searchResultTitle(r))
    }

    @Test
    fun `a highlight sent as a plain string still reads`() {
        val r = result("""{"index":"recipes","id":"02","data":{"name":"Tarte"},"highlights":{"name":"<em>Tarte</em> aux pommes"}}""")
        assertEquals("Tarte aux pommes", searchResultHighlight(r))
    }

    @Test
    fun `fields of an unexpected shape never throw`() {
        val r = result("""{"index":"tasks","id":"03","data":{"title":{"x":1},"name":["Liste","b"]},"highlights":{"title":{"x":1},"n":[1,2]}}""")
        assertEquals("Liste", searchResultTitle(r))
        assertNull(searchResultHighlight(r))
    }

    @Test
    fun `no usable field falls back to the id`() {
        assertEquals("04", searchResultTitle(SearchResult(index = "x", id = "04", data = JsonObject(emptyMap()))))
    }
}
