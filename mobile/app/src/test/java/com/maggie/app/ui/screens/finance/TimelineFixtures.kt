package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.FinanceTimeline
import kotlinx.serialization.json.Json

/**
 * The frise as `GET /api/finance/timeline` writes it (MAG-554, `FinanceTimelineControllerTest`): the
 * followed scenario « Mon plan » reaches N in December 2045, « Qualité de vie » (compared) in September 2043.
 * Snake_case names and `detail` as the API sends them.
 */
internal object TimelineFixtures {
    private const val CATEGORIES = """
        [{"key":"projet","label":"Projets","color":"#B8DE6F"},{"key":"dette","label":"Dettes","color":"#8FB8FF"},
         {"key":"investissement","label":"Investissements","color":"#F2C46D"},{"key":"indépendance","label":"Indépendance","color":"#7FD1AE"},
         {"key":"fiscal","label":"Fiscal","color":"#C7B5FF"},{"key":"matelas","label":"Matelas","color":"#ABA6B8"}]
    """

    private const val CURRENT = """
        {"scenarioId":"s-mine","name":"Mon plan","nDate":"2045-12","events":[
          {"id":"independence_reached","month":"2045-12","type":"independence_reached","category":"indépendance",
           "label":"Indépendance financière","sublabel":"Les rentes couvrent le train de vie",
           "amount_eur":null,"monthly_impact_eur":null,"n_impact_months":null,"detail":{"percent":100}},
          {"id":"debt_end:loan:l1","month":"2027-08","type":"debt_end","category":"dette",
           "label":"Crédit conso soldé","sublabel":null,"amount_eur":null,"monthly_impact_eur":180.0,"n_impact_months":-2,"detail":{"loanId":"l1"}},
          {"id":"project_purchase:p1","month":"2027-03","type":"project_purchase","category":"projet",
           "label":"Déménagement","sublabel":"Achat","amount_eur":2500.0,"monthly_impact_eur":null,"n_impact_months":3,"detail":{"projectId":"p1"}}
        ]}
    """

    private const val COMPARED = """
        {"scenarioId":"auto-quality","name":"Qualité de vie","nDate":"2043-09","events":[
          {"id":"independence_reached","month":"2043-09","type":"independence_reached","category":"indépendance",
           "label":"Indépendance financière","sublabel":"Les rentes couvrent le train de vie",
           "amount_eur":null,"monthly_impact_eur":null,"n_impact_months":null,"detail":{"percent":100}}
        ]}
    """

    private const val ASSUMPTIONS = "Hypothèses : matelas intact, revenus stables, sans vacance locative ni imprévu"

    val ALONE_JSON = """
        {"startMonth":"2026-10","horizonMonths":240,"assumptions":"$ASSUMPTIONS","categories":$CATEGORIES,
         "current":$CURRENT,"compared":null,"difference":null}
    """.trimIndent()

    val COMPARED_JSON = """
        {"startMonth":"2026-10","horizonMonths":240,"assumptions":"$ASSUMPTIONS","categories":$CATEGORIES,
         "current":$CURRENT,"compared":$COMPARED,
         "difference":{"months":-27,"sentence":"N 2 ans 3 mois plus tôt que « Mon plan »"}}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    fun alone(): FinanceTimeline = json.decodeFromString(ALONE_JSON)

    fun compared(): FinanceTimeline = json.decodeFromString(COMPARED_JSON)
}
