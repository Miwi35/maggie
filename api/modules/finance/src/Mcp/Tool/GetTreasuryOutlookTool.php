<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetTreasuryOutlook;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_treasury_outlook', description: 'Read the treasury until the salary comes ("je vais être à découvert ?", "combien je dois avancer ?"): the salary delay, the projected checking balance, the overdraft ahead and how much to advance from which savings. No arguments. It only reads what is entered — the balances of the checking accounts, their planned and committed movements to come, and the occurrences the owner\'s confirmed recurring series still expect that no line is attached to yet — no average, no guess: every figure can be recomputed by hand, say so if asked. salary: isConfigured (false when no expected day or no reference salary is known: then nothing is projected and nothing is late — say so instead of saying all is fine), expectedOn (the salary awaited; in the past when late), referenceCents, isReceived (this month\'s salary is in), isLate, delayDays, shortfallCents. balanceCents is today\'s checking balance. projection[] gives, day by day from today to the eve of the expected salary (60 days at most; when late, to the next expected day), date, balanceCents and the movements of the day (id, label, amountCents, status planned, committed or expected, source transaction or recurring, accountId, accountName; source recurring, status expected is a debit or credit of a confirmed recurring series that nothing is attached to yet — id is then the series\', say it comes from the series, not from a line entered). balanceBeforeSalary {onDate, balanceCents} is the balance the eve of the salary (when late, today\'s end-of-day balance, after today\'s movements): use it, never recompute it. overdraft: isForecast, onDate (the first day below minus limitCents; today when already below), worstBalanceCents, limitCents (the overdraft the bank allows, set with manage_treasury_settings). advice, null when no overdraft is forecast or the salary is in: amountCents (what is missing to stay above the limit), sourceAccountId, sourceAccountName, sourceAmountCents (what to take from it) from the savings with the largest balance; alternatives[] (accountId, accountName, balanceCents, amountCents, isCushion) when it is not enough; uncoveredCents, what no source covers. A cushion account comes last: when isCushion or touchesCushion is true, say it breaks into the safety net and starts a recharge plan. outstandingAdvancesCents is what is still to give back from earlier advances (get_savings_advances for the detail). Amounts are integer cents, divide by 100 to speak them. Nothing here moves money: propose the transfer, never say it was made.')]
class GetTreasuryOutlookTool
{
    public function __construct(
        private readonly GetTreasuryOutlook $getTreasuryOutlook,
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

        return json_encode($this->getTreasuryOutlook->execute($user), JSON_THROW_ON_ERROR);
    }
}
