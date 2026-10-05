<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetIndependenceCounter;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_independence_counter', description: 'Read how far the rentes already cover the way the user lives. Both sides are measured over the last three complete months, never declared: passiveIncomeCents is the monthly average of the credits of the categories flagged as rentes (manage_categories, passiveIncome), lifestyleCents is what a month actually costs, loan payments and exceptional movements excluded. coveragePercent is the first over the second and is NOT capped — above 100 the rentes cover more than the month costs. gapCents is what is still missing each month, byCategory breaks the rentes down, and milestones gives 25/50/75/100 % with the monthly rente each one takes. isMeasurable is false when no month has been measured yet, so there is no percentage to give; hasPassiveIncomeCategories is false when the user has not declared any rente category yet — say that, rather than reporting 0 %. coverageWithDebtPercent is the same ratio against the month including loan payments. The target date of independence and the progression curve are NOT available: they are Premium, so never estimate a date.')]
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
