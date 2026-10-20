# Revue augmentée — Shape

## Problème
La revue mensuelle (MAG-456) enchaîne cinq étapes. À la fin d'un trimestre, d'un semestre ou d'une année, il faut aussi regarder la période écoulée et décider de la suivante ; `GET /api/finance/annual-plan` et `plan_annual_budget` ne lisent que l'année, et rien ne l'écrit en un geste.

## Décisions (Dilemme · Options · Choix · Pourquoi)

1. **Rendez-vous augmentés** · un événement par niveau, ou le rendez-vous mensuel existant · *Choix : le rendez-vous du 25 reste unique ; son titre devient « Revue de fin de trimestre / de semestre / d'année » aux mois concernés, et l'activité ajoute les deux étapes.* · Pourquoi : trois événements de plus se superposeraient au rendez-vous existant à la même heure et exigeraient une migration des agendas déjà créés.
2. **Qui décide du niveau** · chaque client recalcule, ou l'API · *Choix : l'API (`level`, `period`, `nextPeriod` dans la progression).* · Pourquoi : une règle, trois clients (PHP, TS, Kotlin).
3. **Place des étapes** · avant ou après Projets · *Choix : après Projets (`period_balance`, `period_plan`, puis `done`).* · Pourquoi : le propriétaire a fixé l'ordre des cinq premières ; « deux étapes en plus ».
4. **Plan stocké ?** · non, comme `finance-annual-planning` (décision 1) : le plan *est* les enveloppes et les dépenses planifiées.
5. **Période du plan** · `period` = `2027`, `2027-Q1`, `2027-S1` ; la source est la période précédente de même durée ; `year` reste accepté. Les champs existants (`lastYear`, `sourceYear`) gardent leur nom et valent « période source ».
6. **Enveloppes d'une période de moins d'un an** · enveloppe annuelle impossible hors année · *Choix : `monthly` pose une enveloppe mensuelle sur chaque mois de la période (avec saisons si données) ; `annual` n'est accepté que pour une période d'un an.* · Pourquoi : une enveloppe annuelle appartient à une année civile.
7. **Écrire en un geste** · plusieurs commandes bus, ou un message unique · *Choix : un `ApplyPeriodPlanCommand`, validé en entier puis écrit en un seul `flush`.* · Pourquoi : un flush est une transaction (rien en base si l'une échoue) et la projection Mercure/ES se fait une fois pour le message racine. Précédent : `ApplyEnvelopeAdjustments` (valider avant la première écriture).
8. **Rejouer** · une dépense planifiée est retrouvée par catégorie + libellé + mois et mise à jour ; une enveloppe par catégorie + période ; une ponctuelle par nom. Rien ne se duplique après une reprise.
9. **Compte d'une dépense planifiée** · `accountId` facultatif ; à défaut, le premier compte courant. Aucun compte courant → 400.
10. **Côté Maggie** · une action d'écriture par élément (`set_annual_envelope`, `set_monthly_envelope`, `set_one_off_envelope`, `set_event`), paramètres scalaires, dans un outil `manage_period_plan` ; pas de tableau, pas de nom en `add_*` / `plan_*`. Une saison par appel (`seasonFromMonth`, `seasonToMonth`, `seasonAmountCents`). Le « tout en un geste » est le POST, pour l'écran.
11. **Projets réalisés ou décalés** · il n'existe pas encore d'entité Projet (MAG-425 : carte d'exemple). *Choix : le bilan lit les dépenses planifiées de la période — « réalisées » = engagées (`committed`), « décalées » = encore `planned` / `to_arbitrate` alors que leur mois est passé.* À remplacer quand les projets existeront.
12. **Graphiques** · composants anneaux et courbe de MAG-417 / MAG-457, réutilisés ; le bilan renvoie une série mensuelle pour la courbe.
13. **Saisons au rejeu** · garder les saisons de l'enveloppe existante, ou les remplacer · *Choix : le plan est la source — `seasons` remplace celles de l'enveloppe (aucune si absent).* · Pourquoi : un montant posé par le plan doit être celui que l'enveloppe applique ce mois-là.
14. **Pas de vrai modèle** : ce que Maggie dit se teste avec le faux modèle.

## Standards appliqués
- `global/testing` — Définition de « terminé ».
- `api/mcp-tools`, `api/entities`, `api/testing` — outil MCP, message/handler/use case, tests.
- `global/e2e-environment` — parcours web et mobile.

## Hors périmètre
Les scénarios de projets ; l'entité Projet ; les rendez-vous mensuels existants ne changent pas.
