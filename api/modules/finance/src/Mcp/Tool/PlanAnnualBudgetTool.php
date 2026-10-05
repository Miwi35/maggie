<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetAnnualPlan;
use Maggie\Finance\UseCase\PlanningYear;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'plan_annual_budget', description: 'Run the yearly planning session: what the year behind cost, and what the year ahead should therefore be budgeted. Lists every category the year behind cost something, biggest decision first, and reports for each what it was budgeted and really consumed, its big expenses one by one (the debits over thresholdCents, 10000 by default — the threshold decides which expenses are listed, never which categories are in the session), what the target year has already planned and committed, what is only to arbitrate (reported, never counted), the annual envelope already set, and a suggested amount: what the target year has already decided on if anything, otherwise what the year behind consumed. year is between 2000 and 2100 and defaults to the one being prepared, which is next year in November and December and the current one the rest of the time. This reads and writes nothing: once the owner has decided, set the amounts with manage_envelopes (mode annual, the suggested amount) and the expenses with manage_transactions (status planned, committed or to_arbitrate). It never touches the calendar either: a planned expense is a transaction, not an appointment.')]
class PlanAnnualBudgetTool
{
    public function __construct(
        private readonly GetAnnualPlan $getAnnualPlan,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?int $year = null, ?int $thresholdCents = null): string
    {
        try {
            $plan = $this->getAnnualPlan->execute(
                $this->userContext->requireUser(),
                $year ?? PlanningYear::default(new \DateTimeImmutable()),
                $thresholdCents ?? GetAnnualPlan::DEFAULT_THRESHOLD_CENTS,
            );
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        return json_encode($plan, JSON_THROW_ON_ERROR);
    }
}
