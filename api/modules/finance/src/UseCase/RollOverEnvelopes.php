<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Message\CreateEnvelopeCommand;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Carries the envelopes of one period over to the next, so a monthly budget
 * does not have to be retyped every month.
 *
 * An envelope already set on the target period is left alone, which makes the
 * pass safe to re-run.
 */
class RollOverEnvelopes
{
    public function __construct(
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /**
     * @param bool $useActualSpending Budget the target period on what the
     *                                source period actually consumed, rather
     *                                than on what it had budgeted.
     *
     * @return array{created: int, skipped: int, envelopes: array<int, array<string, mixed>>}
     */
    public function execute(
        User $user,
        int $fromYear,
        ?int $fromMonth,
        int $toYear,
        ?int $toMonth,
        bool $useActualSpending = false,
    ): array {
        $sources = $this->envelopeRepository->findForPeriod($user, $fromYear, $fromMonth);

        $created = 0;
        $skipped = 0;
        $envelopes = [];

        foreach ($sources as $source) {
            $mode = $source->getMode();
            $targetMonth = $mode === BudgetMode::Monthly ? $toMonth : null;

            if ($mode === BudgetMode::Monthly && $targetMonth === null) {
                // Nothing sensible to roll a monthly envelope into.
                ++$skipped;
                continue;
            }

            $existing = $this->envelopeRepository->findOneForPeriod(
                $source->getCategory(),
                $mode,
                $toYear,
                $targetMonth,
            );

            if ($existing !== null) {
                ++$skipped;
                continue;
            }

            $amountCents = $useActualSpending
                ? $this->consumedBy($user, $source)
                : $source->getAmountCents();

            $stamped = $this->bus->dispatch(new CreateEnvelopeCommand(
                userId: (string) $user->getId(),
                categoryId: (string) $source->getCategory()->getId(),
                amountCents: $amountCents,
                year: $toYear,
                mode: $mode->value,
                month: $targetMonth,
                currency: $source->getCurrency(),
            ));

            /** @var Envelope $envelope */
            $envelope = $stamped->last(HandledStamp::class)->getResult();
            ++$created;

            $envelopes[] = [
                'id' => (string) $envelope->getId(),
                'categoryId' => (string) $envelope->getCategory()->getId(),
                'categoryName' => $envelope->getCategory()->getName(),
                'mode' => $envelope->getMode()->value,
                'amountCents' => $envelope->getAmountCents(),
                'currency' => $envelope->getCurrency(),
                'year' => $envelope->getYear(),
                'month' => $envelope->getMonth(),
            ];
        }

        return ['created' => $created, 'skipped' => $skipped, 'envelopes' => $envelopes];
    }

    /** What the source envelope's category actually consumed over its period. */
    private function consumedBy(User $user, Envelope $source): int
    {
        $byStatus = $this->transactionRepository->sumByStatusForCategoryBetween(
            $user,
            $source->getCategory(),
            $source->getPeriodStart(),
            $source->getPeriodEnd(),
        );

        return $byStatus[TransactionStatus::Spent->value] + $byStatus[TransactionStatus::Committed->value];
    }
}
