<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\DetectInternalTransfers;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'detect_internal_transfers', description: 'Pairs movements between two of the user\'s own accounts so both legs leave every figure. Lines pair when on different accounts, with exactly opposite amounts, same currency, at most four days apart, already spent or committed. limitDays: integer >= 0, restricts to the last days (default: whole history). dryRun: true reports the pairs without writing. A savings or investment to checking transfer booked while the salary was late is marked advance; the checking to source transfer settling an open advance is marked repayment. A line with no other leg naming the holder or his bank is marked internal without counterpart (loneLegs). Hand-marked lines are never requalified.')]
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
