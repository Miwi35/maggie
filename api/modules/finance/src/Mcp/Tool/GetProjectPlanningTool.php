<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetProjectPlanning;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_project_planning', description: 'Computes the next 36 months of project purchases from the net saving capacity. No arguments. Months are YYYY-MM, amounts integer cents. The safety cushion is filled first, then projects take the rest in priority order; none is bought before its dependencies. Returns startMonth; netCapacityCents; isIncomeKnown (false: capacity is 0); cushion {state building|recharging|complete, completeMonth}; projects[] {projectId, name, position, amountCents, dateMode, targetOn, savingFromMonth, purchaseMonth (null beyond horizon), gatheredCents, isDeadlineMissed, delayMonths, unmetDependencies}; months[] {month, capacityCents, cushionCents, salaryCents, savingsCents, loanPaymentsCents, isOverCapacity}.')]
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
