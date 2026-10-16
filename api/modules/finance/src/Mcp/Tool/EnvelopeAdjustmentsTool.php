<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\ApplyEnvelopeAdjustments;
use Maggie\Finance\UseCase\GetEnvelopeAdjustments;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;

#[McpTool(name: 'envelope_adjustments', description: 'Adjust the monthly envelopes to what was really spent ("mes enveloppes sont-elles adaptées ?"). month is YYYY-MM, the month read (defaults to the current one); the amounts proposed are for the month after it. The suggest action lists every monthly envelope of the month with its amountCents, what was really spent (actualCents), what the next month is budgeted at (targetAmountCents) and — only for the envelopes that do not fit — a suggestion {proposedCents, direction raise or lower, reason, seasonal}. An envelope fits, and has no suggestion, unless it was exceeded three months running with spending mostly judged worth keeping (raise), was left under three quarters of its amount three months running (lower), or the same month of the past years was far from next month\'s amount (a season: seasonal {fromMonth, toMonth} names the month, and the amount is for that month of every year). Spending mostly judged avoidable never raises an envelope. Give the reason as it is, in French, it says why. Nothing is written by suggest. The apply action writes next month\'s amounts for the suggestions of categoryIds (comma-separated category ids; all of them when omitted) — only after the owner agreed: never apply on your own. Amounts are integer cents, divide by 100 to speak them.')]
class EnvelopeAdjustmentsTool
{
    public function __construct(
        private readonly GetEnvelopeAdjustments $getEnvelopeAdjustments,
        private readonly ApplyEnvelopeAdjustments $applyEnvelopeAdjustments,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $action, ?string $month = null, ?string $categoryIds = null): string
    {
        try {
            $user = $this->userContext->requireUser();

            $period = $month ?? (new \DateTimeImmutable('today'))->format('Y-m');
            if (1 !== preg_match('/^\d{4}-(0[1-9]|1[0-2])$/', $period)) {
                return json_encode(['error' => 'month must be a month as YYYY-MM'], JSON_THROW_ON_ERROR);
            }
            $first = new \DateTimeImmutable($period.'-01');

            return match ($action) {
                'suggest' => json_encode($this->getEnvelopeAdjustments->execute($user, $first), JSON_THROW_ON_ERROR),
                'apply' => json_encode([
                    'success' => true,
                    'applied' => $this->applyEnvelopeAdjustments->applySuggestions(
                        $user,
                        $first,
                        null === $categoryIds ? null : array_values(array_filter(array_map('trim', explode(',', $categoryIds)))),
                    ),
                ], JSON_THROW_ON_ERROR),
                default => json_encode(['error' => "Unknown action: {$action}. Use suggest or apply."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            return json_encode(['error' => ($e->getPrevious() ?? $e)->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
