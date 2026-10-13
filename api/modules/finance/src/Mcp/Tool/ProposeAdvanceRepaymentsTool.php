<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\ProposeAdvanceRepayments;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'propose_advance_repayments', description: 'Say what to transfer to give the savings advances back ("quoi virer, où, et combien ?"). No arguments. One counter-transfer per open advance, of exactly what is left of it, from the checking account the money landed on to the savings source it came from: proposals[] gives fromAccountId, toAccountId, amountCents, advanceId, currency and a label ready to copy; totalCents adds them up. The proposal is capped at what the checking account holds: when it cannot cover an advance, the proposal is reduced to the balance (isPartial true) and unproposed[] says what could not be proposed (advanceId, sourceAccountName, amountCents, reason insufficient_balance or no_checking_account), unproposedCents adding it up — say it plainly, never present a reduced transfer as the full return. With no open advance, proposals is empty and that is good news: nothing is owed. Amounts are positive integer cents, divide by 100 to speak them. Nothing here moves money: say what to transfer, never that it was transferred. When the user makes the exact transfer, the next detection settles the advance on its own; a regular top-up of the savings account is never counted as a return (mark it funding with manage_transactions if it could be mistaken for one).')]
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
