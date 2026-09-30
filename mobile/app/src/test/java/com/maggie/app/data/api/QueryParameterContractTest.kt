package com.maggie.app.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The converse of the API's QueryParameterContractTest: the app sends no
 * query parameter the API has not declared.
 *
 * The API side checks that everything in `api/contract/query-parameters.json`
 * is declared by an ApiFilter and reaches Elasticsearch. It cannot check that
 * the file is complete — that is this test's job, and the gap was not
 * theoretical. `getTransactions` sent `accountId`, which no entity declared;
 * API Platform dropped it, and every account screen showed the full
 * transaction list. Nothing failed. Nothing was logged.
 *
 * Reading MaggieApiService's source rather than calling it is deliberate: the
 * bug is a string that never reaches a filter, so the string is what has to
 * be inspected. A mocked HTTP client would only prove the app sends what the
 * app sends.
 */
class QueryParameterContractTest {

    /**
     * `client.get("<baseUrl>/api/transactions")` → `/api/transactions`.
     *
     * `\${'$'}` and not `\$`: inside a Kotlin raw string a bare dollar starts
     * a template, and a bare dollar in a regex is an end-of-line anchor. Both
     * have to be got past to match a literal one.
     */
    private val requestStart =
        Regex("""client\.(get|patch|post|delete)\("\${'$'}baseUrl(/api/[A-Za-z0-9_/\-]*)""")

    /** `url.parameters.append("order[bookedAt]", …)` → `order[bookedAt]`. */
    private val parameterAppend = Regex("""url\.parameters\.append\("([^"]+)"""")

    /**
     * Provided by API Platform for every collection, declared by no entity,
     * so absent from the contract by design.
     */
    private val pagination = setOf("page", "itemsPerPage")

    @Test
    fun `every query parameter the app sends is declared by the API`() {
        val contract = readContract()
        val sent = parametersByPath()

        assertTrue(
            "Found no query parameter at all in MaggieApiService.kt — the patterns this test greps with no longer " +
                "match the code, so it has been passing without checking anything.",
            sent.isNotEmpty(),
        )

        val undeclared = sent.filter { (call, _) ->
            // Only API Platform collections have a declared filter surface.
            // Custom controllers (/api/finance/…, /api/search) parse their own
            // query string and are not part of this contract.
            val declared = contract[call.path] ?: return@filter false
            call.parameter !in declared
        }

        assertTrue(
            "These query parameters are not declared by the API, so API Platform drops them and the collection comes " +
                "back unfiltered — no error, no log, just the wrong list on screen:\n" +
                undeclared.joinToString("\n") { (call, line) ->
                    "  MaggieApiService.kt:$line  GET ${call.path}  ?${call.parameter}"
                } +
                "\nDeclare each with an #[ApiFilter] on the entity and add it to api/contract/query-parameters.json.",
            undeclared.isEmpty(),
        )
    }

    /**
     * The other direction: a parameter listed in the contract for a path the
     * app calls, but that the app no longer sends, is a filter kept alive for
     * a caller that has gone. Reported, not failed — the admin sends most of
     * them, and this suite cannot see the admin.
     */
    @Test
    fun `the contract names the paths this app actually calls`() {
        val contract = readContract()
        val paths = parametersByPath().map { it.first.path }.toSet()

        assertTrue(
            "The app calls none of the collections the contract describes, which means the path extraction is broken " +
                "rather than that the app changed. Contract paths: ${contract.keys.sorted()}",
            paths.any { it in contract.keys },
        )
    }

    private data class Call(val path: String, val parameter: String)

    /**
     * Walks MaggieApiService line by line, remembering the last request path
     * seen, and attributes each `parameters.append` to it. The file is one
     * suspend function per endpoint, so the nearest preceding path is the
     * right one.
     */
    private fun parametersByPath(): List<Pair<Call, Int>> {
        val source = File(repositoryRoot(), "mobile/app/src/main/java/com/maggie/app/data/api/MaggieApiService.kt")
        assertTrue("Cannot find ${source.absolutePath}", source.isFile)

        var path: String? = null
        val calls = mutableListOf<Pair<Call, Int>>()

        source.readLines().forEachIndexed { index, line ->
            requestStart.find(line)?.let { path = it.groupValues[2] }

            val parameter = parameterAppend.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            val current = path ?: return@forEachIndexed
            if (parameter in pagination) return@forEachIndexed

            calls += Call(current, parameter) to index + 1
        }

        return calls
    }

    private fun readContract(): Map<String, Set<String>> {
        val file = File(repositoryRoot(), "api/contract/query-parameters.json")
        assertTrue(
            "The API contract file ${file.absolutePath} is missing. It is generated by the API suite: " +
                "UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract",
            file.isFile,
        )

        return (Json.parseToJsonElement(file.readText()) as JsonObject)
            .mapValues { (_, senders) -> senders.jsonObject.keys }
    }

    private fun repositoryRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "api/contract").isDirectory) return candidate
            candidate = candidate.parentFile
        }
        throw AssertionError("No directory containing api/contract/ found above ${File("").absolutePath}")
    }
}
