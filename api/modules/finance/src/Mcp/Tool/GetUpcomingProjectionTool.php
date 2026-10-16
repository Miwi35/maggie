<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetUpcomingProjection;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_upcoming_projection', description: 'Read what is coming ("qu\'est-ce qui arrive la semaine prochaine ?", "que me restera-t-il le mois prochain ?"): the week after this one (Monday to Sunday) and the month after this one, projected from what is entered. date is optional, a day as YYYY-MM-DD (today by default): the week and the month are the ones after it. Nothing is extrapolated: only the occurrences the owner\'s confirmed recurring series still expect that no line is attached to, and the planned and committed movements on the open checking accounts; transfers between accounts are left out. week and month each give from and until (until is excluded), incomeCents (the expected income, the salary included when it is a confirmed series), expenseCents, netCents, incomes[] and expenses[] ({date, label, amountCents signed, source recurring, planned or committed, categoryId, categoryName}) and days[] ({date, incomeCents, expenseCents}) for a chart. month adds the envelopes as adjusted for that month: envelopes[] ({envelopeId, categoryId, categoryName, categoryPath, amountCents, committedCents, projectedCents, isOvercommitted}) for the top-level monthly envelopes — amountCents is the amount written for the month (its own envelope, else the month under way rolled over; a suggestion from envelope_adjustments that was not applied weighs nothing), committedCents what recurring and planned expenses already fill in its categories, projectedCents the greater of the two. envelopesCents is their sum, unbudgetedCents the commitments outside every envelope, projectedExpenseCents the two together and remainingCents the expected income less that spending: what is left to put toward the projects, negative when the month is short — say so, and name isOvercommitted envelopes. Annual and one-off envelopes are not part of a month. Project tracking is not here yet. Amounts are integer cents, divide by 100 to speak them. A pure read.')]
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
