package com.maggie.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design tokens, as the app reads them (MAG-39).
 *
 * This file is a **mirror** of `design/tokens.json`, which the admin reads too.
 * It is hand-written on purpose — generating it would put a Gradle task between
 * the sources and every Compose preview — and `TokensContractTest` fails the
 * moment the two disagree.
 *
 * Changing a value here alone changes nothing: edit `design/tokens.json`, this
 * file and `admin/src/design/tokens.ts` in the same commit. Rules, and what is
 * deliberately *not* a token: `agent-os/standards/global/design-system.md`.
 */
object MaggieTokens {

    object Brand {
        val primary = Color(0xFF9055FD)
        val onPrimary = Color(0xFFFFFFFF)
        val secondaryLight = Color(0xFFA270FF)
        val secondaryDark = Color(0xFFFF83F6)

        /** Material 3's container roles, per mode. MUI has none, so the admin declares
         * them and does not read them. */
        val containerLight = Color(0xFFE8DEFF)
        val onContainerLight = Color(0xFF21005D)
        val containerDark = Color(0xFF6200EE)
        val onContainerDark = Color(0xFFE8DEFF)
    }

    /** One per mode, and the same two the admin is drawn on. */
    data class Surface(val background: Color, val paper: Color, val text: Color, val textMuted: Color)

    val surfaceLight = Surface(
        background = Color(0xFFF0F1F6),
        paper = Color(0xFFFFFFFF),
        text = Color(0xFF544F5A),
        textMuted = Color(0xFF89868D),
    )

    val surfaceDark = Surface(
        background = Color(0xFF110E1C),
        paper = Color(0xFF151221),
        text = Color(0xFFFFFFFF),
        textMuted = Color(0xFFB8B7BB),
    )

    /** The sign-in, loading and lock screens, and the splash — mode or no mode. */
    object Night {
        val background = Color(0xFF1A1A2E)
        val raised = Color(0xFF16213E)
        val text = Color(0xFFFFFFFF)
        const val TEXT_MUTED_ALPHA = 0.6f

        /** The secondary text of those screens: [text] at [TEXT_MUTED_ALPHA]. */
        val textMuted: Color = text.copy(alpha = TEXT_MUTED_ALPHA)
    }

    /** Feedback about *this* interaction — Material's `error` role, and the admin's alerts. */
    object Feedback {
        val error = Color(0xFFDB488B)
        val warning = Color(0xFFF2E963)
        val info = Color(0xFF3ED0EB)
        val success = Color(0xFF0FBF9F)
    }

    /** Labels on *data* — a criticality, a thread's state, an item just ticked off. */
    object Signal {
        val success = Color(0xFF4CAF50)
        val warning = Color(0xFFFF9800)
        val danger = Color(0xFFF44336)
        val info = Color(0xFF2196F3)
        val critical = Color(0xFF9C27B0)
        val neutral = Color(0xFF9E9E9E)
    }

    /** Slots 1 and 2 of the palette validated on the finance dashboard, per mode. */
    val chartCategorical = listOf(
        Color(0xFF2A78D6) to Color(0xFF3987E5),
        Color(0xFFEB6834) to Color(0xFFD95926),
    )

    /**
     * The scale, mirrored for the contract test and read by the admin. The
     * Compose typography that uses it comes with the Gabarito ticket; until then
     * Material's own defaults *are* these sizes, which is why nothing re-flows
     * when it lands.
     */
    object Typography {
        const val REGULAR = 400
        const val MEDIUM = 500
        const val SEMIBOLD = 600
        const val BOLD = 700

        val xs = 11.sp
        val sm = 12.sp
        val md = 14.sp
        val lg = 16.sp
        val xl = 20.sp
        val xxl = 24.sp
        val xxxl = 28.sp
    }

    object Radius {
        val xs = 4.dp
        val sm = 6.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 28.dp
    }

    /** The 4-dp grid, shared with the admin. */
    object Space {
        val xs: Dp = 4.dp
        val sm: Dp = 8.dp
        val md: Dp = 12.dp
        val lg: Dp = 16.dp
        val xl: Dp = 24.dp
        val xxl: Dp = 32.dp
    }

    /**
     * `criticality` in the token file: the name of a signal, not a colour.
     * An unknown criticality reads as `low` — the API's enum may grow, and a
     * missing colour would draw a transparent chip.
     */
    val criticality: Map<String, Color> = mapOf(
        "low" to Signal.success,
        "medium" to Signal.warning,
        "high" to Signal.danger,
        "critical" to Signal.critical,
    )

    /** `contextState` in the token file. An unknown state reads as `closed`. */
    val contextState: Map<String, Color> = mapOf(
        "active" to Signal.success,
        "dormant" to Signal.warning,
        "closed" to Signal.neutral,
    )

    /** What a calendar entry comes from, when it is not an agenda of its own. */
    object Source {
        val meals = Color(0xFFFF6B35)
        val tasks = Color(0xFF1976D2)
        val done = Signal.neutral
    }
}

/** The colour of a task's criticality; see [MaggieTokens.criticality]. */
fun criticalityColor(criticality: String?): Color =
    MaggieTokens.criticality[criticality] ?: MaggieTokens.Signal.success

/** The colour of a conversation thread's state; see [MaggieTokens.contextState]. */
fun contextStateColor(state: String?): Color =
    MaggieTokens.contextState[state] ?: MaggieTokens.Signal.neutral

/** Categorical slot [index] (1-based, as a chart's legend numbers them). */
fun chartColor(index: Int, dark: Boolean): Color =
    MaggieTokens.chartCategorical[index - 1].let { if (dark) it.second else it.first }
