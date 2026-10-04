package com.maggie.app.voice

import android.app.assist.AssistStructure

/**
 * One node of the view tree Android hands the assistant, reduced to what Maggie
 * needs (MAG-30).
 *
 * The interface exists so [AssistTextCollector] can be tested: an
 * `AssistStructure.ViewNode` has no public constructor and `android.jar` only
 * ships stubs that throw, so a unit test cannot build a tree of them.
 * [assistRoots] is the adapter over the real thing.
 */
interface AssistTextNode {
    val text: String?
    val contentDescription: String?
    val children: List<AssistTextNode>
}

/**
 * Flattens the view tree into the lines of text that go to the model.
 *
 * In reading order, de-duplicated, and bounded: a screen can hold hundreds of
 * nodes, the whole thing is prefixed to a chat message ([ScreenContext]), and a
 * wall of navigation labels buries the two lines that actually answer « ajoute ça
 * à mon agenda ». The caps are a budget, not a safety net — they decide what the
 * model reads.
 */
object AssistTextCollector {
    /** How many lines travel at most. */
    const val MAX_ENTRIES = 40

    /** The total character budget across every line. */
    const val MAX_CHARS = 1500

    /** One node's share of it, so a single paragraph cannot eat the budget. */
    const val MAX_ENTRY_CHARS = 200

    /** Below this, a truncated line says nothing: stop instead. */
    private const val MIN_USEFUL_CHARS = 20

    private val WHITESPACE = Regex("\\s+")

    fun collect(
        roots: List<AssistTextNode>,
        maxEntries: Int = MAX_ENTRIES,
        maxChars: Int = MAX_CHARS,
    ): List<String> {
        val collected = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var used = 0

        // Explicit stack rather than recursion: the tree comes from another app
        // and its depth is nobody's promise.
        val stack = ArrayDeque<AssistTextNode>()
        roots.asReversed().forEach { stack.addLast(it) }

        while (stack.isNotEmpty()) {
            if (collected.size >= maxEntries) break
            val node = stack.removeLast()
            node.children.asReversed().forEach { stack.addLast(it) }

            val entry = normalize(node) ?: continue
            if (!seen.add(entry.lowercase())) continue

            val remaining = maxChars - used
            if (remaining < MIN_USEFUL_CHARS) break
            if (entry.length > remaining) {
                collected += entry.take(remaining).trimEnd() + "…"
                break
            }
            collected += entry
            used += entry.length
        }

        return collected
    }

    /** The adapter over a real [AssistStructure], one root per window. */
    fun assistRoots(structure: AssistStructure): List<AssistTextNode> =
        (0 until structure.windowNodeCount).mapNotNull { index ->
            structure.getWindowNodeAt(index).rootViewNode?.let(::ViewNodeAdapter)
        }

    /**
     * The visible text of a node, or its description when it has none — an image
     * is often the only thing naming a product.
     */
    private fun normalize(node: AssistTextNode): String? {
        val raw = node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription
        val clean = raw?.replace(WHITESPACE, " ")?.trim()
        if (clean.isNullOrEmpty()) return null
        return if (clean.length > MAX_ENTRY_CHARS) {
            clean.take(MAX_ENTRY_CHARS).trimEnd() + "…"
        } else {
            clean
        }
    }

    private class ViewNodeAdapter(private val node: AssistStructure.ViewNode) : AssistTextNode {
        override val text: String? get() = node.text?.toString()
        override val contentDescription: String? get() = node.contentDescription?.toString()
        override val children: List<AssistTextNode>
            get() = (0 until node.childCount).mapNotNull { index ->
                node.getChildAt(index)?.let(::ViewNodeAdapter)
            }
    }
}
