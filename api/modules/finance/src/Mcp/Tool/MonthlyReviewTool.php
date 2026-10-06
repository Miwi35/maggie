<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\UseCase\GetMonthlyReview;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'monthly_review', description: 'Run the monthly look back over a month (defaults to the one just ended). The review action lists the non-mandatory spends still waiting for a verdict, biggest first, and reports how much was kept, how much was judged avoidable, an optimisation score (the share of the judged amount worth keeping, null when nothing has been judged yet), and how the month compares with the previous one, the average of the last three and the same month last year. The rate action records one verdict: keep, avoidable, or unrated to undo it. Only non-mandatory spends are offered — asking whether someone could have skipped their rent helps nobody.')]
class MonthlyReviewTool
{
    public function __construct(
        private readonly GetMonthlyReview $getMonthlyReview,
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?int $year = null,
        ?int $month = null,
        ?string $transactionId = null,
        ?string $verdict = null,
    ): string {
        try {
            return match ($action) {
                'review' => $this->review($year, $month),
                'rate' => $this->rate($transactionId, $verdict),
                default => json_encode(['error' => "Unknown action: {$action}. Use review or rate."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        } catch (\ValueError $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function review(?int $year, ?int $month): string
    {
        $user = $this->userContext->requireUser();

        // Default to the month just ended: that is what a review looks at.
        $period = new \DateTimeImmutable('first day of last month');

        return json_encode($this->getMonthlyReview->execute(
            $user,
            $year ?? (int) $period->format('Y'),
            $month ?? (int) $period->format('n'),
        ), JSON_THROW_ON_ERROR);
    }

    private function rate(?string $transactionId, ?string $verdict): string
    {
        if (null === $transactionId || null === $verdict) {
            return json_encode(['error' => 'transactionId and verdict are required for rate.'], JSON_THROW_ON_ERROR);
        }

        // Fails loudly on an unknown verdict rather than silently keeping it unrated.
        $parsed = RetrospectVerdict::from($verdict);

        $stamped = $this->bus->dispatch(new UpdateTransactionCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            transactionId: $transactionId,
            retrospect: $parsed->value,
        ));

        /** @var Transaction $transaction */
        $transaction = $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'transaction' => [
                'id' => (string) $transaction->getId(),
                'label' => $transaction->getLabel(),
                'retrospect' => $transaction->getRetrospect()->value,
            ],
        ], JSON_THROW_ON_ERROR);
    }
}
