<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetProjectPlanning;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_project_planning', description: 'Read when the projects can be bought ("quand pourrai-je acheter le canapé ?", "dans quel ordre mes projets passent ?"): the next 36 months, computed from the net saving capacity, never stored. The safety cushion comes first: while it is being built nothing is bought, and while it recharges the capacity is lower. The capacity is then given to the projects in priority order; a project with a fixed date keeps it and takes its even share first. No arguments. startMonth and every month are YYYY-MM; amounts are integer cents, divide by 100 to speak them. netCapacityCents is the saving capacity per month, isIncomeKnown false when there is no known income (the capacity is then 0: say so, do not guess). cushion is {state building, recharging or complete, completeMonth: the month its deficit is covered, null when already complete or not within 36 months}. projects[] in priority order: {projectId, name, position, wellbeing, amountCents, dateMode none, fixed or deadline, targetOn, savingFromMonth, purchaseMonth (null when beyond the horizon), gatheredCents (the savings outside the cushion set aside for it today), loan {startMonth, endMonth, monthlyPaymentCents} when a loan is projected (it starts the month after the purchase), isBeyondHorizon, isDeadlineMissed, delayMonths (months after the deadline; at least that when beyond the horizon), unmetDependencies}. A project is never bought before the ones it depends on. months[] {month, capacityCents (what the salary can give the projects that month), cushionCents, salaryCents, savingsCents, capitalCents (what funds the projects, by source), loanPaymentsCents (projected loans running), isOverCapacity (salaryCents above capacityCents: a fixed date asks more than the month can give — a fact, not a fault)}. To change the order, dates or funding use manage_projects and manage_project_funding. A pure read.')]
class GetProjectPlanningTool
{
    public function __construct(
        private readonly GetProjectPlanning $getProjectPlanning,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        return json_encode($this->getProjectPlanning->execute($user), JSON_THROW_ON_ERROR);
    }
}
