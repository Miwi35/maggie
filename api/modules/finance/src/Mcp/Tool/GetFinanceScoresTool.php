<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetFinanceScores;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_finance_scores', description: 'Read where the user stands in their budget: the score of the week, the score of the month and the global one (the most degraded of the month and the year), for a date (YYYY-MM-DD, defaults to today). Each score is green ("Dans le vert"), neutral ("Dans les clous"), orange ("Attention") or red ("Dépassement"), always with its reasons. Red means the budget of the period is exceeded (for the month also a mandatory category, for the global an annual envelope); otherwise the colour follows the spending rhythm: expected spending at a date is the debits already due (rent, energy, subscriptions: recurring operations) plus the variable part of the budget spread over the days; 95 % of it or more is orange, under 80 % is green. Each week and month carries its rhythm: budgetCents, spentCents, expectedSpentCents, remainingCents, expectedRemainingCents, status (on_track "Dans le rythme" or above "Au-dessus du rythme"), the day-by-day curve and the debits with their dates. Reasons come as codes with amounts in cents (above_rhythm, below_rhythm, on_rhythm, week_budget_exceeded, mandatory_category_exceeded, optional_category_exceeded, plans_exceed_category_budget, total_budget_exceeded, annual_category_exceeded, plans_exceed_annual_category_budget, cushion_incomplete, below_last_year, above_last_year, spending_above_reference_salary, no_budget) — phrase them for the user rather than reading them out.')]
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
