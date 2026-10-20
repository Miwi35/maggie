package com.maggie.app.data.model

import com.maggie.app.data.api.ApiCollection
import com.maggie.app.data.api.ProjectReorderResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A project as the API sends it (MAG-519): the recorded collection of `GET /api/finance/projects`,
 * and the answer of `POST /api/finance/projects/reorder`, whose dependencies are plain ids.
 */
class ProjectTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun recording(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "api/contract").isDirectory) dir = dir.parentFile
        return File(dir ?: error("api/contract not found"), "api/contract/responses/finance_projects.collection.json").readText()
    }

    @Test
    fun `the recorded collection reads every field of a project`() {
        val projects = json.decodeFromString(ApiCollection.serializer(Project.serializer()), recording()).member

        val move = projects[0]
        assertEquals("Recorded move", move.name)
        assertEquals(250000, move.amountCents)
        assertEquals(ProjectWellbeing.ESSENTIAL, ProjectWellbeing.of(move.wellbeing))
        assertEquals(4, move.wellbeingScore)
        assertEquals(ProjectDateMode.FIXED, ProjectDateMode.of(move.dateMode))
        assertEquals("2027-03-01", move.targetOn)
        assertNull(move.note)

        val sofa = projects[1]
        assertEquals(1, sofa.position)
        assertEquals(1, sofa.wellbeingScore)
        assertEquals(listOf("01ARZ3NDEKTSV4RRFFQ69G5FAV"), sofa.dependencyIds)
        assertEquals(listOf("01ARZ3NDEKTSV4RRFFQ69G5FAV"), sofa.unmetDependencies)
        assertEquals("Recorded note", sofa.note)
    }

    @Test
    fun `the reorder answer carries plain ids, read the same way as IRIs`() {
        val body = """
            {"success":true,"projects":[
              {"id":"p2","name":"Canapé","amountCents":120000,"currency":"EUR","wellbeing":"comfort","wellbeingScore":1,
               "position":0,"dateMode":"none","targetOn":null,"dependsOn":["p1"],"note":null,"unmetDependencies":["p1"]},
              {"id":"p1","name":"Déménagement","amountCents":250000,"currency":"EUR","wellbeing":"essential","wellbeingScore":4,
               "position":1,"dateMode":"fixed","targetOn":"2027-03-01","dependsOn":[],"note":null,"unmetDependencies":[]}
            ]}
        """.trimIndent()

        val result = json.decodeFromString(ProjectReorderResult.serializer(), body)

        assertTrue(result.success)
        assertEquals(listOf("p2", "p1"), result.projects.map { it.id })
        assertEquals(listOf("p1"), result.projects[0].dependencyIds)
    }

    @Test
    fun `the date reads as its mode, with the date in French when it has one`() {
        assertEquals("Sans date", projectDateLabel(Project(id = "a", name = "A")))
        assertEquals(
            "Date fixe · 1 mars 2027",
            projectDateLabel(Project(id = "a", name = "A", dateMode = "fixed", targetOn = "2027-03-01")),
        )
        assertEquals(
            "Date limite · 15 janvier 2027",
            projectDateLabel(Project(id = "a", name = "A", dateMode = "deadline", targetOn = "2027-01-15")),
        )
    }

    @Test
    fun `a dependency reads as the name of the project waited for`() {
        val move = Project(id = "p1", name = "Déménagement")
        val car = Project(id = "p3", name = "Voiture")
        val sofa = Project(id = "p2", name = "Canapé", dependsOn = listOf("/api/finance/projects/p1", "/api/finance/projects/p3"))

        assertEquals("après Déménagement, Voiture", projectDependenciesLabel(sofa, listOf(move, sofa, car)))
        assertNull(projectDependenciesLabel(move, listOf(move, sofa, car)))
    }

    @Test
    fun `an unknown wellbeing or date mode falls back rather than breaking`() {
        assertEquals(ProjectWellbeing.MEDIUM, ProjectWellbeing.of("nonsense"))
        assertEquals(ProjectDateMode.NONE, ProjectDateMode.of("nonsense"))
        assertEquals(listOf(4, 3, 2, 1), ProjectWellbeing.entries.map { it.score })
        assertEquals(listOf("Indispensable", "Élevé", "Moyen", "Confort"), ProjectWellbeing.entries.map { it.label })
    }
}
