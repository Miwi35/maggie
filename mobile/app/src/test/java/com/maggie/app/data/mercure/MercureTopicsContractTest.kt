package com.maggie.app.data.mercure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app subscribes to the topics the API and the agent publish, and to no others.
 *
 * `api/contract/mercure-topics.json` is written by the API's
 * MercureTopicContractTest from `Maggie\Core\Mercure\MercureTopic`, the single
 * place that spells a topic on the server; `agent/contract/mercure-topics.json`
 * is its counterpart for the agent's `/chat/{userId}`-style topics, written by
 * the agent's own suite. This test reads both files — the same bytes each
 * service committed — so the sides are compared rather than all believed.
 *
 * It is worth the file-reading because the failure it prevents is completely
 * silent. A Mercure subscription to a topic nobody publishes connects, stays
 * connected, reconnects when dropped, and never delivers an event. Two of the
 * app's subscriptions were in that state when this test was written:
 * GroceryViewModel passed the literal `{userId}` MercureService never
 * substitutes, and RecipeListViewModel left out the `/users/{userId}` scope
 * entirely. The chat was dead the same way (MAG-138), on the agent's side of
 * the contract.
 */
class MercureTopicsContractTest {

    private val contract: Map<String, String> by lazy { readContract("api") }

    private val agentContract: Map<String, String> by lazy { readContract("agent") }

    private fun readContract(service: String): Map<String, String> =
        Json.parseToJsonElement(contractFile(service, "mercure-topics.json").readText())
            .let { it as kotlinx.serialization.json.JsonObject }
            .mapValues { (_, value) -> value.jsonPrimitive.content }

    @Test
    fun `every collection the app subscribes to is published by the API`() {
        val published = contract.values.toSet()

        MercureTopics.SUBSCRIBED.forEach { collection ->
            val topic = MercureTopics.userScoped("{userId}", collection)

            assertTrue(
                "The app subscribes to \"$topic\", which the API does not publish.\n" +
                    "Nothing will fail at runtime: the SSE connection opens and simply never delivers an event.\n" +
                    "Published topics: ${published.sorted().joinToString("\n  ", prefix = "\n  ")}",
                published.contains(topic),
            )
        }
    }

    @Test
    fun `every agent stream the app subscribes to is published by the agent`() {
        val published = agentContract.values.toSet()

        MercureTopics.SUBSCRIBED_AGENT.forEach { stream ->
            val topic = MercureTopics.agentScoped("{userId}", stream)

            assertTrue(
                "The app subscribes to \"$topic\", which the agent does not publish.\n" +
                    "Nothing will fail at runtime: the SSE connection opens and simply never delivers an event.\n" +
                    "Published topics: ${published.sorted().joinToString("\n  ", prefix = "\n  ")}",
                published.contains(topic),
            )
        }
    }

    @Test
    fun `no topic the app builds still carries a user placeholder`() {
        // MercureService passes the topic through untouched, so a `{userId}`
        // that survives the builder is sent to the hub as it is.
        val built = MercureTopics.SUBSCRIBED.map { MercureTopics.userScoped("01HXYZ", it) } +
            MercureTopics.SUBSCRIBED_AGENT.map { MercureTopics.agentScoped("01HXYZ", it) }

        val offenders = built.filter { Regex("""\{user_?[iI]d}""").containsMatchIn(it) }

        assertTrue("These topics keep an unsubstituted user placeholder: $offenders", offenders.isEmpty())
        assertTrue(built.all { it.contains("01HXYZ") })
    }

    @Test
    fun `the topic builder produces the agent's convention verbatim`() {
        assertEquals("/chat/01HXYZ", MercureTopics.agentScoped("01HXYZ", MercureTopics.CHAT))
        assertEquals("/contexts/01HXYZ", MercureTopics.agentScoped("01HXYZ", MercureTopics.CONTEXTS))
    }

    @Test
    fun `the topic builder produces the API's convention verbatim`() {
        // Spelled out rather than derived, so a change to userScoped() that
        // happens to keep matching the contract file still has to be read by
        // somebody.
        assertEquals(
            "/users/01HXYZ/api/grocery_lists/{id}",
            MercureTopics.userScoped("01HXYZ", MercureTopics.GROCERY_LISTS),
        )
    }

    /**
     * The half that catches a new subscription written by hand.
     *
     * MercureTopics is only a contract if everything goes through it; a
     * ViewModel that builds its own string — an API topic, an agent topic, or
     * a literal with a `{userId}` nobody substitutes — is exactly how the dead
     * subscriptions got in. No namespace is exempt: the chat's `/chat/{userId}`
     * was one (MAG-138).
     */
    @Test
    fun `no ViewModel builds a topic by hand`() {
        val subscriptions = sourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("""mercureService\.subscribe\(([^)]*)\)""")
                    .findAll(file.readText())
                    .map { file.name to it.groupValues[1].trim() }
            }
            .toList()

        // Without this the test passes on nothing the day subscribe() moves
        // behind a helper, which is how the dead subscriptions it was
        // written for would come back.
        assertTrue(
            "Found no mercureService.subscribe call at all, so this test checked nothing.",
            subscriptions.size >= 10,
        )

        val offenders = subscriptions.filter { (_, argument) ->
            !argument.startsWith("MercureTopics.userScoped(") && !argument.startsWith("MercureTopics.agentScoped(")
        }

        assertTrue(
            "These subscriptions build a topic from a literal instead of going through MercureTopics, " +
                "so nothing checks them against the API's and the agent's contracts:\n" +
                offenders.joinToString("\n") { (file, argument) -> "  $file: $argument" },
            offenders.isEmpty(),
        )
    }

    private fun sourceRoot(): File {
        val root = repositoryRoot()
        return File(root, "mobile/app/src/main/java").also {
            assertTrue("Cannot find the mobile sources at ${it.absolutePath}", it.isDirectory)
        }
    }

    private fun contractFile(service: String, name: String): File =
        File(repositoryRoot(), "$service/contract/$name").also {
            val generator = if (service == "api") {
                "UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract"
            } else {
                "UPDATE_CONTRACT=1 task wt:test:agent -- tests/test_mercure_topics_contract.py"
            }
            assertTrue(
                "The $service contract file ${it.absolutePath} is missing. It is generated by that service's suite: $generator",
                it.isFile,
            )
        }

    /**
     * Walks up from the working directory — Gradle runs unit tests from
     * mobile/app — until it finds the checkout. Resolving by relative depth
     * would break the day the module moves.
     */
    private fun repositoryRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "api/contract").isDirectory && File(candidate, "agent/contract").isDirectory) {
                return candidate
            }
            candidate = candidate.parentFile
        }
        throw AssertionError("No directory containing api/contract/ and agent/contract/ found above ${File("").absolutePath}")
    }
}
