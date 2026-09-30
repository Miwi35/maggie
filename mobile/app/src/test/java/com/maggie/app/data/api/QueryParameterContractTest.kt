package com.maggie.app.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * theoretical. `getTransactions` sent `accountId`, which no entity declared.
 * It narrowed the list anyway, because the Elasticsearch translator turned
 * any unrecognised string into a term query and the index happened to hold a
 * field by that name — so the feature worked in production and returned
 * every account's transactions on the Doctrine path, and nothing anywhere
 * said the parameter was undeclared.
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

    /** Any request, so that one this contract ignores still clears the path. */
    private val anyRequestStart = Regex("""client\.(get|patch|post|delete)\("\${'$'}baseUrl""")

    /** `url.parameters.append("order[bookedAt]", …)` → `order[bookedAt]`. */
    private val parameterAppend = Regex("""url\.parameters\.append\("([^"]+)"""")

    /**
     * Provided by API Platform for every collection, declared by no entity,
     * so absent from the contract by design.
     */
    private val pagination = setOf("page", "itemsPerPage")

    @Test
    fun `every query parameter the app sends is declared by the API`() {
        val declaredByPath = openApiParameters()
        val sent = parametersByPath()

        assertTrue(
            "Found no query parameter at all in MaggieApiService.kt — the patterns this test greps with no longer " +
                "match the code, so it has been passing without checking anything.",
            sent.isNotEmpty(),
        )

        // Keyed on the OpenAPI document, not on the hand-written contract: a
        // path missing from the hand-written list would otherwise opt itself
        // out of the whole check. `accountId` was caught only because
        // /api/transactions happened to already have an entry — a parameter
        // sent to a collection with no entry at all would have sailed past.
        // openapi.json describes every path the API exposes, so "absent from
        // it" means "not an API Platform collection", which is the only
        // exemption that makes sense: custom controllers
        // (/api/finance/…, /api/search) parse their own query string.
        val undeclared = sent.filter { (call, _) ->
            val declared = declaredByPath[call.path] ?: return@filter false
            call.parameter !in declared
        }

        assertTrue(
            "These query parameters are not declared by the API, so API Platform drops them and the collection comes " +
                "back unfiltered — no error, no log, just the wrong list on screen:\n" +
                undeclared.joinToString("\n") { (call, line) ->
                    "  MaggieApiService.kt:$line  GET ${call.path}  ?${call.parameter}" +
                        "  (declared: ${declaredByPath[call.path]?.sorted()?.joinToString(", ")})"
                } +
                "\nDeclare each with an #[ApiFilter] on the entity.",
            undeclared.isEmpty(),
        )
    }

    /**
     * The hand-written list the API suite checks against has to know about
     * them too, or the API side stops asserting anything about a parameter
     * this app depends on.
     */
    @Test
    fun `every query parameter the app sends is named in the published contract`() {
        val contract = readContract()
        val declaredByPath = openApiParameters()

        val unlisted = parametersByPath().filter { (call, _) ->
            // Same exemption as above: only API Platform collections.
            declaredByPath[call.path] ?: return@filter false
            call.parameter !in contract[call.path].orEmpty()
        }

        assertTrue(
            "These parameters are declared by the API but missing from api/contract/query-parameters.json, so " +
                "nothing on the API side proves they still narrow anything:\n" +
                unlisted.joinToString("\n") { (call, line) ->
                    "  MaggieApiService.kt:$line  GET ${call.path}  ?${call.parameter}"
                },
            unlisted.isEmpty(),
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
            // Every request resets the path, including one this contract does
            // not cover. Only matching /api/… requests would leave the
            // previous endpoint in scope, and the agent calls that follow
            // (`$baseUrl/agent/messages`) would have their parameters
            // attributed to whatever collection came before them.
            anyRequestStart.find(line)?.let {
                path = requestStart.find(line)?.groupValues?.get(2)
            }

            val parameter = parameterAppend.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            val current = path ?: return@forEachIndexed
            if (parameter in pagination) return@forEachIndexed

            calls += Call(current, parameter) to index + 1
        }

        return calls
    }

    /**
     * Collection path → the query parameters the API declares for its GET,
     * read from the OpenAPI document the API suite publishes. A path absent
     * from it is not an API Platform collection.
     */
    private fun openApiParameters(): Map<String, Set<String>> {
        val file = File(repositoryRoot(), "api/contract/openapi.json")
        assertTrue(
            "The OpenAPI contract ${file.absolutePath} is missing. It is generated by the API suite: " +
                "UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract",
            file.isFile,
        )

        val paths = (Json.parseToJsonElement(file.readText()) as JsonObject)["paths"]?.jsonObject
            ?: throw AssertionError("The OpenAPI contract has no \"paths\" object.")

        return paths.mapNotNull { (path, operations) ->
            val get = operations.jsonObject["get"]?.jsonObject ?: return@mapNotNull null
            val names = get["parameters"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                ?.toSet()
                .orEmpty()

            path to names
        }.toMap()
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
