package com.maggie.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * `Tokens.kt` against `design/tokens.json`, which the admin's theme reads too
 * (MAG-39).
 *
 * The mirror is hand-written — generating it would put a Gradle task between
 * the sources and every Compose preview — so this is what keeps the two
 * platforms on one identity. Same shape as `DtoContractTest`: walk up to the
 * file, read it, do not restate it, and **fail** rather than skip when it is
 * not there.
 *
 * Both halves matter. Comparing the two as whole maps is what catches a token
 * *added* to the source and forgotten here — the failure mode a per-key
 * assertion cannot see.
 */
class TokensContractTest {

    private val source: JsonObject =
        Json.parseToJsonElement(tokenFile().readText()).let { it as JsonObject }

    /** The signal a mapping names, found back from the colour the mirror resolved it to. */
    private val signalNames: Map<Color, String> = mapOf(
        MaggieTokens.Signal.success to "success",
        MaggieTokens.Signal.warning to "warning",
        MaggieTokens.Signal.danger to "danger",
        MaggieTokens.Signal.info to "info",
        MaggieTokens.Signal.critical to "critical",
        MaggieTokens.Signal.neutral to "neutral",
    )

    @Test
    fun `the mirror holds every token of design tokens json, and no other`() {
        // `typography.family` is the one token with no Compose equivalent — a CSS
        // stack is a string, a FontFamily is a resource — so it has its own test.
        assertEquals(flatten(source) - "typography.family", mirror())
    }

    @Test
    fun `the typeface the token file names is the one the admin loads`() {
        // The Compose typography that reads it comes with the Gabarito ticket —
        // the font has to be bundled in res/font/ first. What holds here is that
        // the shared stack says Gabarito, so both platforms start from one name.
        val stack = (source["typography"] as JsonObject)["family"]!!.jsonPrimitive.content

        assertEquals("Gabarito", stack.substringBefore(',').trim())
    }

    @Test
    fun `the splash reads the night background from the same token as the screens`() {
        // `android:windowSplashScreenBackground` is drawn before a single
        // composable exists, so it cannot read MaggieTokens — it reads
        // @color/maggie_night, and this is what keeps the two equal.
        val colors = File(repositoryRoot(), "mobile/app/src/main/res/values/colors.xml").readText()
        val declared = Regex("""<color name="maggie_night">(#[0-9A-Fa-f]{6})</color>""")
            .find(colors)
            ?.groupValues
            ?.get(1)

        assertEquals(MaggieTokens.Night.background.hex(), declared?.uppercase())
    }

    @Test
    fun `an unknown criticality or context state still draws a colour`() {
        // The API's enums may grow; a null would draw nothing at all.
        assertEquals(MaggieTokens.Signal.success, criticalityColor("whatever-comes-next"))
        assertEquals(MaggieTokens.Signal.success, criticalityColor(null))
        assertEquals(MaggieTokens.Signal.neutral, contextStateColor("whatever-comes-next"))
        assertEquals(MaggieTokens.Signal.neutral, contextStateColor(null))
    }

    @Test
    fun `the chart slots are numbered the way a legend numbers them`() {
        assertEquals(Color(0xFF2A78D6), chartColor(1, dark = false))
        assertEquals(Color(0xFFD95926), chartColor(2, dark = true))
    }

    // ---------------------------------------------------------------------

    /** `design/tokens.json`, flattened to dotted paths — `brand.primary` → `#9055FD`. */
    private fun flatten(element: JsonElement, prefix: String = ""): Map<String, String> =
        when (element) {
            is JsonObject -> element.entries.flatMap { (key, value) ->
                flatten(value, if (prefix.isEmpty()) key else "$prefix.$key").entries.map { it.toPair() }
            }.toMap()
            is JsonArray -> element.withIndex().flatMap { (index, value) ->
                flatten(value, "$prefix.$index").entries.map { it.toPair() }
            }.toMap()
            is JsonPrimitive -> mapOf(prefix to element.content)
            else -> emptyMap()
        }

    /** The same paths, read off `MaggieTokens`. Written out: that is the contract. */
    private fun mirror(): Map<String, String> = buildMap {
        with(MaggieTokens.Brand) {
            put("brand.primary", primary.hex())
            put("brand.onPrimary", onPrimary.hex())
            put("brand.secondaryLight", secondaryLight.hex())
            put("brand.secondaryDark", secondaryDark.hex())
            put("brand.containerLight", containerLight.hex())
            put("brand.onContainerLight", onContainerLight.hex())
            put("brand.containerDark", containerDark.hex())
            put("brand.onContainerDark", onContainerDark.hex())
        }
        for ((mode, surface) in listOf("light" to MaggieTokens.surfaceLight, "dark" to MaggieTokens.surfaceDark)) {
            put("surface.$mode.background", surface.background.hex())
            put("surface.$mode.paper", surface.paper.hex())
            put("surface.$mode.text", surface.text.hex())
            put("surface.$mode.textMuted", surface.textMuted.hex())
        }
        with(MaggieTokens.Night) {
            put("night.background", background.hex())
            put("night.raised", raised.hex())
            put("night.text", text.hex())
            put("night.textMutedAlpha", num(TEXT_MUTED_ALPHA))
        }
        with(MaggieTokens.Feedback) {
            put("feedback.error", error.hex())
            put("feedback.warning", warning.hex())
            put("feedback.info", info.hex())
            put("feedback.success", success.hex())
        }
        with(MaggieTokens.Signal) {
            put("signal.success", success.hex())
            put("signal.warning", warning.hex())
            put("signal.danger", danger.hex())
            put("signal.info", info.hex())
            put("signal.critical", critical.hex())
            put("signal.neutral", neutral.hex())
        }
        for ((name, color) in MaggieTokens.criticality) {
            put("criticality.$name", signalNames.getValue(color))
        }
        for ((name, color) in MaggieTokens.contextState) {
            put("contextState.$name", signalNames.getValue(color))
        }
        put("source.meals", MaggieTokens.Source.meals.hex())
        put("source.tasks", MaggieTokens.Source.tasks.hex())
        put("source.done", signalNames.getValue(MaggieTokens.Source.done))
        MaggieTokens.chartCategorical.forEachIndexed { index, (light, dark) ->
            put("chart.categorical.$index.light", light.hex())
            put("chart.categorical.$index.dark", dark.hex())
        }
        with(MaggieTokens.Typography) {
            put("typography.weight.regular", REGULAR.toString())
            put("typography.weight.medium", MEDIUM.toString())
            put("typography.weight.semibold", SEMIBOLD.toString())
            put("typography.weight.bold", BOLD.toString())
            put("typography.size.xs", num(xs))
            put("typography.size.sm", num(sm))
            put("typography.size.md", num(md))
            put("typography.size.lg", num(lg))
            put("typography.size.xl", num(xl))
            put("typography.size.xxl", num(xxl))
            put("typography.size.xxxl", num(xxxl))
        }
        with(MaggieTokens.Radius) {
            put("radius.xs", num(xs))
            put("radius.sm", num(sm))
            put("radius.md", num(md))
            put("radius.lg", num(lg))
            put("radius.xl", num(xl))
        }
        with(MaggieTokens.Space) {
            put("space.xs", num(xs))
            put("space.sm", num(sm))
            put("space.md", num(md))
            put("space.lg", num(lg))
            put("space.xl", num(xl))
            put("space.xxl", num(xxl))
        }
    }

    private fun Color.hex(): String = "#%02X%02X%02X".format(
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )

    private fun num(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

    private fun num(value: Dp): String = num(value.value)

    private fun num(value: TextUnit): String = num(value.value)

    private fun tokenFile(): File = File(repositoryRoot(), "design/tokens.json").also {
        if (!it.isFile) throw AssertionError("design/tokens.json is missing at ${it.absolutePath}")
    }

    private fun repositoryRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "design/tokens.json").isFile) return candidate
            candidate = candidate.parentFile
        }
        throw AssertionError(
            "No directory containing design/tokens.json found above ${File("").absolutePath}. " +
                "It is the source of the design system — agent-os/standards/global/design-system.md.",
        )
    }
}
