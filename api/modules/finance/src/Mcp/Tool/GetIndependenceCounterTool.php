<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetIndependenceCounter;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_independence_counter', description: 'Reads how far the rentes cover the lifestyle, measured over the last three complete months. Amounts are integer cents. Returns passiveIncomeCents (monthly average of credits in categories flagged passiveIncome), lifestyleCents (monthly cost), coveragePercent (not capped), gapCents, byCategory, milestones (25/50/75/100 %), isMeasurable (false: no month measured), coverageWithDebtPercent, projectedCoveragePercent (adds rentes expected by confirmed recurring series). projection (a simulation, see get_project_planning): nDate (YYYY-MM when rentes cover the lifestyle, null beyond 20 years), curve [{month, coverage 0..1}], milestones, renteSources.')]
class GetIndependenceCounterTool
{
    public function __construct(
        private readonly GetIndependenceCounter $getIndependenceCounter,
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

        return json_encode($this->getIndependenceCounter->execute($user), JSON_THROW_ON_ERROR);
    }
}
