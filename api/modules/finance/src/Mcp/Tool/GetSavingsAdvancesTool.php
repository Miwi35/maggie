<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetSalaryDelay;
use Maggie\Finance\UseCase\GetSavingsAdvances;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_savings_advances', description: 'Reads savings advances: money taken from a savings or investment account to checking while the salary was late, and what remains to give back per source. No arguments. advances[], oldest first: id, sourceAccountId, sourceAccountName, amountCents, openedOn, repaidCents, outstandingCents, isClosed (settled ones stay, closed, outside totals). bySource[], largest first, sources with something left: sourceAccountId, sourceAccountName, advancedCents, repaidCents, outstandingCents, openCount; totalOutstandingCents. Unsynced savings account: single-legged lines tied by advanceSourceAccountId (manage_transactions update) count; outstanding = advanced minus repaid, repayments covering the oldest first. salaryDelay as in manage_treasury_settings. Positive integer cents. Moves no money.')]
class GetSavingsAdvancesTool
{
    public function __construct(
        private readonly GetSavingsAdvances $getSavingsAdvances,
        private readonly GetSalaryDelay $getSalaryDelay,
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

        return json_encode(
            $this->getSavingsAdvances->execute($user) + ['salaryDelay' => $this->getSalaryDelay->execute($user)],
            JSON_THROW_ON_ERROR,
        );
    }
}
