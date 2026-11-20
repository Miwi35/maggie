<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\ApplyEnvelopeAdjustments;
use Maggie\Finance\UseCase\GetEnvelopeAdjustments;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;

#[McpTool(name: 'envelope_adjustments', description: 'Adjusts monthly envelopes to actual spending. month: YYYY-MM read (default current); amounts proposed are for the next month. Action suggest (writes nothing) lists each monthly envelope with amountCents, actualCents, targetAmountCents (next month\'s budget) and, only if it does not fit, suggestion {proposedCents, direction raise or lower, reason (French), seasonal}. Raise: exceeded three months running, spending mostly judged worth keeping. Lower: under three quarters three months running. Seasonal {fromMonth, toMonth}: same month of past years far from next month\'s amount, applied to that month every year. Avoidable spending never raises. Action apply writes next month\'s amounts for categoryIds (comma-separated; all when omitted), returns applied. Integer cents.')]
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
