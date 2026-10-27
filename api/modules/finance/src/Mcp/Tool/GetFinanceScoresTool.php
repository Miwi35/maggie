<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetFinanceScores;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_finance_scores', description: 'Read where the user stands in their budget: the score of the week, the score of the month and the global one (the most degraded of the month and the year), for a date (YYYY-MM-DD, defaults to today). Each score is green ("Dans le vert"), neutral ("Dans les clous"), orange ("Attention") or red ("Dépassement"), always with its reasons. The colour comes from the envelopes alone (red: a mandatory category is exceeded, for the global an annual envelope; orange: an optional category is exceeded or the plans would exceed one) — never from the total spent: the week has no colour of its own (always neutral). Each week and month carries its rhythm, two curves to read, never a verdict: spentCents is every real debit of the period to date, classified or not, with or without an envelope (the same figure as the period balance); expectedSpentCents is what was planned ahead to date — the recurring operations of every category; plannedCents is the same over the whole period; the day-by-day curve and the debits (the planned ones) with their dates. Spending more than expected is normal — envelopes only say where the money goes — so never call the user over or under pace. unclassifiedCount and unclassifiedCents say how many debits of the period (and how much) no category claims yet: they are in spentCents, and worth inviting the user to classify. Reasons come as codes with amounts in cents (mandatory_category_exceeded, optional_category_exceeded, plans_exceed_category_budget, annual_category_exceeded, plans_exceed_annual_category_budget, cushion_incomplete, below_last_year, above_last_year, spending_above_reference_salary, no_budget) — phrase them for the user rather than reading them out.')]
class GetFinanceScoresTool
{
    public function __construct(
        private readonly GetFinanceScores $getFinanceScores,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?string $date = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        try {
            $day = $this->getFinanceScores->resolveDate($user, $date);
        } catch (\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        return json_encode($this->getFinanceScores->execute($user, $day), JSON_THROW_ON_ERROR);
    }
}
