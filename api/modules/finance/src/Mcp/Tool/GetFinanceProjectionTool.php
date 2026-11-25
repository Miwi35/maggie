<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetTreasuryOutlook;
use Maggie\Finance\UseCase\GetUpcomingProjection;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_finance_projection', description: 'What comes in the next months; what happened is get_finance_summary. Projects what comes, from entered data only (nothing is extrapolated). months: 1 to 12 (default: to the end of the month after the expected salary). Integer cents. salary {isConfigured, expectedOn, referenceCents, isLate, delayDays, shortfallCents}, balanceCents (checking today), projection[] day by day, overdraft {isForecast, onDate, worstBalanceCents, limitCents}, outstandingAdvancesCents; nextWeek and nextMonth: the week and month after this one with incomeCents, expenseCents, netCents and their lines. Moves no money.')]
class GetFinanceProjectionTool
{
    public function __construct(
        private readonly GetTreasuryOutlook $getTreasuryOutlook,
        private readonly GetUpcomingProjection $getUpcomingProjection,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?int $months = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        if (null !== $months && ($months < 1 || $months > GetTreasuryOutlook::MAX_MONTHS)) {
            return json_encode(['error' => sprintf('months must be between 1 and %d.', GetTreasuryOutlook::MAX_MONTHS)], JSON_THROW_ON_ERROR);
        }

        $upcoming = $this->getUpcomingProjection->execute($user);

        return json_encode(
            $this->getTreasuryOutlook->execute($user, null, $months) + [
                'nextWeek' => $upcoming['week'],
                'nextMonth' => $upcoming['month'],
            ],
            JSON_THROW_ON_ERROR,
        );
    }
}
