<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetUpcomingProjection;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_upcoming_projection', description: 'Projects the week after this one (Monday to Sunday) and the month after this one from entered data only: unattached occurrences of confirmed recurring series, and planned and committed movements on open checking accounts; transfers excluded. date: YYYY-MM-DD, default today. Amounts are integer cents. week and month each return from, until (excluded), incomeCents, expenseCents, netCents, incomes[] and expenses[] {date, label, amountCents signed, source recurring|planned|committed}, days[]. month adds envelopes[] (top-level monthly envelopes: amountCents, committedCents, projectedCents, isOvercommitted), projectedExpenseCents, remainingCents (expected income minus that spending).')]
class GetUpcomingProjectionTool
{
    public function __construct(
        private readonly GetUpcomingProjection $getUpcomingProjection,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?string $date = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $today = null;
        if (null !== $date) {
            $today = 1 === preg_match('/^\d{4}-\d{2}-\d{2}$/', $date) ? \DateTimeImmutable::createFromFormat('!Y-m-d', $date) : false;
            if (false === $today || $today->format('Y-m-d') !== $date) {
                return json_encode(['error' => 'date must be a day as YYYY-MM-DD'], JSON_THROW_ON_ERROR);
            }
        }

        return json_encode($this->getUpcomingProjection->execute($user, $today), JSON_THROW_ON_ERROR);
    }
}
