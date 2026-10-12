<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetRecurringOperations;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_recurring_operations', description: 'Read what the recurring operations (subscriptions, rent, salary, any series the user expects to see again) cost and bring in, in one call, without arguments. totals: monthlyCostCents and yearlyCostCents are the expenses (negative cents), monthlyIncomeCents the incomes (positive), activeCount the series that have not ended; an ended series (endsOn past) is listed but left out of the totals. Amounts are signed integer cents, divide by 100 to speak them: say "38,47 € par mois", never the raw cents. operations[]: id, label, category, account, period, dayRule, referenceAmountCents, monthlyCostCents, yearlyCostCents, currency, nextOccurrenceOn (the next due date not yet settled by a transaction, null once ended), lastAttachedOn, attachmentCount, and lastOccurrence (the latest transaction attached, with its dateGapDays and the deviations it carried: late, early, amount_up, amount_down). missedOccurrence is set when the latest due date is past its date tolerance with no transaction attached ("the rent did not come this month"), with occurrenceOn and daysOverdue; it is a fact to report, never a reason to alarm. priceChange (previousCents, currentCents, percent, since) is set when the amount the series is paid at moved from the one before. proposals[] are the attachments still waiting for the user\'s yes, each with its reason (late, early, amount_up, amount_down, ambiguous): confirm one with manage_recurring_operations attach. To create, change or delete a series use manage_recurring_operations.')]
class GetRecurringOperationsTool
{
    public function __construct(
        private readonly GetRecurringOperations $getRecurringOperations,
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

        return json_encode($this->getRecurringOperations->execute($user), JSON_THROW_ON_ERROR);
    }
}
