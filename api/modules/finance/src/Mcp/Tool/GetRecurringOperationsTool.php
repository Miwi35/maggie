<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetRecurringOperations;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_recurring_operations', description: 'Reads recurring operations (subscriptions, rent, salary), no arguments. totals: monthlyCostCents, yearlyCostCents (expenses, negative), monthlyIncomeCents, activeCount; signed integer cents; an ended series (endsOn past) is listed but not totalled. operations[]: id, label, category, account, period, dayRule, referenceAmountCents, monthlyCostCents, yearlyCostCents, currency, nextOccurrenceOn (null once ended), lastAttachedOn, attachmentCount, lastOccurrence (dateGapDays, deviations late, early, amount_up, amount_down), missedOccurrence (occurrenceOn, daysOverdue; set when the latest due date is past tolerance with no transaction), priceChange (previousCents, currentCents, percent, since). proposals[]: attachments awaiting confirmation with reason (late, early, amount_up, amount_down, ambiguous); confirmed with manage_operations attach.')]
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
