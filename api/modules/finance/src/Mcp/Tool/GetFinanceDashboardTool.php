<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetFinanceDashboard;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_finance_dashboard', description: 'Read the whole financial picture for a month (defaults to the current one) in one call: the daily score with its reasons, the balance of every account and what of it is the safety cushion, twelve rolling months of money in and out, the budget gauges per envelope, the five biggest spending posts against the same posts last month, and the net saving capacity. Prefer this over calling the budget, score and loan tools one by one. All amounts are integer cents.')]
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

        return json_encode($this->getFinanceDashboard->execute(
            $user,
            $year ?? (int) $now->format('Y'),
            $month ?? (int) $now->format('n'),
        ), JSON_THROW_ON_ERROR);
    }
}
