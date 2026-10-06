<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\DetectInternalTransfers;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'detect_internal_transfers', description: 'Pair the movements that went from one of the user\'s accounts to another, over his history. A transfer between two of his own accounts is neither an expense nor an income, so both legs leave every figure: the lifestyle measured, the saving capacity, the monthly review and the day-over-year comparison of the score. Two lines pair when they are on two different accounts of his, carry exactly opposite amounts in the same currency, are booked four days apart at most, and are already spent or committed. limitDays narrows the pass to the last days (the whole history by default), dryRun reports the pairs it would write without writing any of them — use it first and tell the user which lines would be paired. A line he marked by hand is never requalified. To mark or unmark one line, use manage_transactions with action update, transferKind and counterpartId.')]
class DetectInternalTransfersTool
{
    public function __construct(
        private readonly DetectInternalTransfers $detectInternalTransfers,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?int $limitDays = null, ?bool $dryRun = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        if (null !== $limitDays && $limitDays < 0) {
            return json_encode(['error' => 'limitDays must be a positive integer.'], JSON_THROW_ON_ERROR);
        }

        return json_encode(
            ['success' => true] + $this->detectInternalTransfers->execute($user, $limitDays, $dryRun ?? false),
            JSON_THROW_ON_ERROR,
        );
    }
}
