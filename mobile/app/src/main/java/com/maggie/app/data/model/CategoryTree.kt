package com.maggie.app.data.model

/** A category tree has three levels at most: Loisirs › Bars › Obsydian. */
const val MAX_CATEGORY_DEPTH = 3

private const val PATH_SEPARATOR = " › "

/** The identifier API Platform hands out for a category, as a relation points at it. */
fun categoryIri(id: String): String = "/api/categories/$id"

/** One visible line of the tree: [level] is 1 for a main category. */
data class CategoryRow(
    val category: Category,
    val level: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
) {
    /** A node of the last level cannot take a sub-category. */
    val canAddChild: Boolean get() = level < MAX_CATEGORY_DEPTH
}

/**
 * The categories as a tree, built from the `parent` links alone: the level of a node is
 * its distance from the root, never the `depth` the API sent (a stale list would lie).
 */
class CategoryTree(categories: List<Category>) {
    private val byId = categories.associateBy { it.id }

    // A parent missing from the list (deleted elsewhere) makes its children main categories.
    private val childrenOf: Map<String?, List<Category>> = categories
        .groupBy { c -> c.parentId()?.takeIf { it in byId && it != c.id } }
        .mapValues { (_, list) -> list.sortedBy { it.name.lowercase() } }

    fun children(id: String?): List<Category> = childrenOf[id].orEmpty()

    fun level(id: String): Int {
        var level = 1
        var current = byId[id]?.parentId()
        val seen = mutableSetOf(id)
        while (current != null && current in byId && seen.add(current)) {
            level++
            current = byId[current]?.parentId()
        }
        return level
    }

    /** The ids above [id], nearest first. */
    fun ancestors(id: String): List<String> {
        val chain = mutableListOf<String>()
        var current = byId[id]?.parentId()
        while (current != null && current in byId && current != id && current !in chain) {
            chain += current
            current = byId[current]?.parentId()
        }
        return chain
    }

    fun descendants(id: String): Set<String> {
        val found = linkedSetOf<String>()
        val queue = ArrayDeque(children(id))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (found.add(next.id)) queue.addAll(children(next.id))
        }
        return found
    }

    /** How many levels the subtree of [id] spans, itself included: 1 for a leaf. */
    fun height(id: String): Int = 1 + (children(id).maxOfOrNull { height(it.id) } ?: 0)

    /** « Loisirs › Bars », from the API's `path` or, without it, from the links. */
    fun path(category: Category): String = category.path?.takeIf { it.isNotBlank() }
        ?: (ancestors(category.id).reversed().mapNotNull { byId[it]?.name } + category.name)
            .joinToString(PATH_SEPARATOR)

    /** The path of the category [id], null when it is unknown (or when [id] is). */
    fun pathOf(id: String?): String? = id?.let(byId::get)?.let(::path)

    /** The lines on screen, depth first: a node's children follow it only while it is in [expanded]. */
    fun rows(expanded: Set<String>): List<CategoryRow> {
        val rows = mutableListOf<CategoryRow>()
        fun visit(category: Category, level: Int) {
            val kids = children(category.id)
            val open = kids.isNotEmpty() && category.id in expanded
            rows += CategoryRow(category, level, hasChildren = kids.isNotEmpty(), expanded = open)
            if (open) kids.forEach { visit(it, level + 1) }
        }
        children(null).forEach { visit(it, 1) }
        return rows
    }

    /**
     * What the parent of [editingId] (null: a new category) can be: a node of level 1 or 2,
     * never the category itself nor its descendants, and none that would push the
     * subtree it carries past the third level.
     */
    fun parentCandidates(editingId: String?): List<Category> {
        val excluded = editingId?.let { descendants(it) + it }.orEmpty()
        val carried = editingId?.let(::height) ?: 1
        return byId.values
            .filter { it.id !in excluded && level(it.id) + carried <= MAX_CATEGORY_DEPTH }
            .sortedBy { path(it).lowercase() }
    }
}

fun Category.parentId(): String? = parent?.substringAfterLast('/')
