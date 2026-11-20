<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetTreasuryOutlook;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_treasury_outlook', description: 'Projects the checking balance day by day until the salary, from entered data only (checking balances, planned and committed movements, recurring occurrences). months: 1 to 12 (default: to the end of the month after the expected salary). Amounts are integer cents. Returns salary {isConfigured (false: nothing projected), expectedOn, referenceCents, isLate, delayDays, shortfallCents}; balanceCents (today); projection[] {date, balanceCents, movements[] {id, label, amountCents, status planned|committed|expected, source transaction|recurring}}; overdraft {isForecast, onDate, worstBalanceCents, limitCents}; advice (null without forecast overdraft); outstandingAdvancesCents. Moves no money.')]
class GetTreasuryOutlookTool
{
    public function __construct(
        private readonly GetTreasuryOutlook $getTreasuryOutlook,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?int $months = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        if (null !== $months && ($months < 1 || $months > GetTreasuryOutlook::MAX_MONTHS)) {
            return json_encode(['error' => sprintf('months must be between 1 and %d.', GetTreasuryOutlook::MAX_MONTHS)], JSON_THROW_ON_ERROR);
        }

        return json_encode($this->getTreasuryOutlook->execute($user, null, $months), JSON_THROW_ON_ERROR);
    }
}
