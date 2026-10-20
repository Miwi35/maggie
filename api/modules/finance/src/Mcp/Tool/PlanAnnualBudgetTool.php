<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetAnnualPlan;
use Maggie\Finance\UseCase\PlanningPeriod;
use Maggie\Finance\UseCase\PlanningYear;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'plan_annual_budget', description: 'Run the yearly planning session: what the year behind cost, and what the year ahead should therefore be budgeted. Lists every category the year behind cost something, biggest decision first, and reports for each what it was budgeted and really consumed, its big expenses one by one (the debits over thresholdCents, 10000 by default — the threshold decides which expenses are listed, never which categories are in the session), what the target year has already planned and committed, what its confirmed recurring series still debit (recurringCents per category: the occurrences with no line attached, each counted once — a rent already booked is the line\'s, never counted twice; decidedCents is planned + committed + recurring), what is only to arbitrate (reported, never counted), the annual envelope already set, and a suggested amount: what the target year has already decided on if anything, otherwise what the year behind consumed. year is between 2000 and 2100 and defaults to the one being prepared, which is next year in November and December and the current one the rest of the time. This only reads; it writes nothing: once the owner has decided, set the amounts and the planned expenses with manage_period_plan, one element per call (manage_envelopes and manage_transactions still work for a single line). It never touches the calendar either: a planned expense is a transaction, not an appointment. period (2027, 2027-Q2 or 2027-S1) plans a quarter or a semester instead of a year, as the review of a quarter, semester or year end does: the period read is the one of the same length right before it, the budget of a period under a year is what its monthly envelopes add up to, and suggestedMonthlyCents is the suggestion per month. Do not send period and year together. To write the plan of a period, use manage_period_plan, one element per call.')]
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
