<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetAnnualPlan;
use Maggie\Finance\UseCase\PlanningPeriod;
use Maggie\Finance\UseCase\PlanningYear;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'plan_annual_budget', description: 'Reads the yearly planning session: what the year behind cost, with a suggested budget for the year ahead. year: 2000 to 2100 (default: next year in November and December, else the current one). thresholdCents: integer cents, default 10000, selects debits listed individually. period (2027, 2027-Q2 or 2027-S1): plans a quarter or semester instead (reads the period just before); not with year (error). Lists every category that cost something, biggest first: budgeted and consumed, big expenses, plannedCents, committedCents, decidedCents, the annual envelope set, and the suggested amount (suggestedMonthlyCents for a period). Writes go through manage_period_plan.')]
class PlanAnnualBudgetTool
{
    public function __construct(
        private readonly GetAnnualPlan $getAnnualPlan,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?int $year = null, ?int $thresholdCents = null, ?string $period = null): string
    {
        try {
            if (null !== $period && null !== $year) {
                return json_encode(['error' => 'Send period or year, not both.'], JSON_THROW_ON_ERROR);
            }

            $plan = $this->getAnnualPlan->execute(
                $this->userContext->requireUser(),
                null !== $period ? PlanningPeriod::parse($period) : ($year ?? PlanningYear::default(new \DateTimeImmutable())),
                $thresholdCents ?? GetAnnualPlan::DEFAULT_THRESHOLD_CENTS,
            );
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        return json_encode($plan, JSON_THROW_ON_ERROR);
    }
}
