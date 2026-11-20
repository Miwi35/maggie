<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetFinanceDashboard;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_finance_dashboard', description: 'Reads the financial picture of a month in one call. year and month: the month to read (default: current). Amounts are integer cents. Returns scores (week and month rhythm); tiles {accounts (total, safety cushion, available), budget (root envelopes consumed vs budgeted, expected salary), planning (independence counter, net saving capacity)}; nextProject (first planned project with gatheredCents and purchaseMonth YYYY-MM, following[], delay; null without project within 36 months); todo {toSortCount, rejectionsToSettleCount, reviewReady, advanceToRepayCents, owedToMeCents, iOweCents}; plus account balances, rolling twelve months, envelope gauges and biggest spending posts.')]
class GetFinanceDashboardTool
{
    public function __construct(
        private readonly GetFinanceDashboard $getFinanceDashboard,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?int $year = null, ?int $month = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $now = new \DateTimeImmutable();
        $year ??= (int) $now->format('Y');
        $month ??= (int) $now->format('n');

        if ($month < 1 || $month > 12) {
            return json_encode(['error' => 'month must be between 1 and 12'], JSON_THROW_ON_ERROR);
        }

        if ($year < 2000 || $year > 2100) {
            return json_encode(['error' => 'year must be between 2000 and 2100'], JSON_THROW_ON_ERROR);
        }

        return json_encode($this->getFinanceDashboard->execute($user, $year, $month), JSON_THROW_ON_ERROR);
    }
}
