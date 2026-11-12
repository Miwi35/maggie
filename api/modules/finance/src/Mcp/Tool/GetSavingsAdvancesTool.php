<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetSalaryDelay;
use Maggie\Finance\UseCase\GetSavingsAdvances;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_savings_advances', description: 'Read the savings advances: what the user took from a savings or investment account to his checking account while his salary was late, and what is left to give back to each source ("combien je dois remettre sur mon livret ?"). No arguments. advances[] lists each one, oldest first: id, sourceAccountId, sourceAccountName, amountCents (avancé), openedOn, repaidCents (rendu), outstandingCents (reste à rendre), isClosed. bySource[] lists each source with something left to give back (sourceAccountId, sourceAccountName, advancedCents (avancé), repaidCents (rendu), outstandingCents (reste à rendre), openCount), largest first; totalOutstandingCents is the total left to give back. A savings account that is not synced (a livret typed by hand) counts the single-legged advance and repayment lines tied to it with advanceSourceAccountId (manage_transactions update): what is left is what was advanced minus what was given back, as a whole; each of its credits is listed in advances[], what was given back covering the oldest first. A settled advance stays in advances[], closed, and leaves the totals. salaryDelay is the salary delay as manage_treasury_settings reports it. Amounts are positive integer cents, divide by 100 to speak them. Nothing here moves money: say what to transfer, never that it was transferred.')]
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
