<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetFinanceDashboard;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_finance_dashboard', description: 'Read the whole financial picture for a month (defaults to the current one) in one call: `scores` (the week and the month with the spending rhythm — every debit spent against the recurring ones planned, never a verdict — and the reasons of each status), `tiles` (`accounts`: total, safety cushion and available with every account; `budget`: the root envelopes with consumed against budgeted, the safety cushion, the expected salary and the balance expected the eve of the salary, both null when unknown; `planning`: the independence counter and the net saving capacity; each account of `accounts` carries `rejectionsToSettleCount`), `nextProject` (the first project of the planning with `gatheredCents` and `purchaseMonth` (YYYY-MM), the two after it in `following`, and `delay` — the project the overspending of the month pushes back, with `months`, `overspentCents`, the overspent `envelopes` and `newPurchaseMonth` — null when nothing moves; the whole value is null with no project or none inside 36 months), `todo` (what waits for the owner: `toSortCount` lines to sort, `rejectionsToSettleCount` rejected payments not settled, `reviewReady` true while the review of the month just ended is not done, `advanceToRepayCents` still to give back to the savings, `owedToMeCents` what people still owe the owner and `iOweCents` what the owner still owes them — créances and dettes), then the older blocks: `score` (the monthly score with its reasons and the reference salary), the balance of every account, twelve rolling months of money in and out, the budget gauges per envelope, and the five biggest spending posts against the same posts last month. Prefer this over calling the budget, score and loan tools one by one. All amounts are integer cents.')]
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
