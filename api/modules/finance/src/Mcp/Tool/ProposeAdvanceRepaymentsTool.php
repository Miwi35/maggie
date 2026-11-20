<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\ProposeAdvanceRepayments;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'propose_advance_repayments', description: 'Proposes transfers to give savings advances back. No arguments. One counter-transfer per open advance, of what is left, from the checking account the money landed on to its savings source: proposals[] (fromAccountId, toAccountId, amountCents, advanceId, currency, label), totalCents. Capped at the checking balance: an advance it cannot cover is reduced to the balance (isPartial true) and listed in unproposed[] (advanceId, sourceAccountName, amountCents, reason insufficient_balance or no_checking_account), unproposedCents. No open advance: proposals empty. Positive integer cents. Moves no money. A matching transfer settles the advance at the next detection; a regular savings top-up is never a return (mark it funding with manage_transactions).')]
class ProposeAdvanceRepaymentsTool
{
    public function __construct(
        private readonly ProposeAdvanceRepayments $proposeAdvanceRepayments,
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

        return json_encode($this->proposeAdvanceRepayments->execute($user), JSON_THROW_ON_ERROR);
    }
}
