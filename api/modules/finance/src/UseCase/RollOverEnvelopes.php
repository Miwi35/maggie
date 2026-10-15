<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Message\CreateEnvelopeCommand;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Carries the envelopes of one period over to the next, so a monthly budget
 * does not have to be retyped every month.
 *
 * An envelope already set on the target period is left alone, which makes the
 * pass safe to re-run. A monthly envelope's seasons are carried over with it,
 * so each month lands at the amount of the season covering it (MAG-415).
 */
class RollOverEnvelopes
{
    public function __construct(
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly CategoryRepository $categoryRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /**
     * @param bool $useActualSpending budget the target period on what the
     *                                source period actually consumed, rather
     *                                than on what it had budgeted
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
            $targetMonth = BudgetMode::Monthly === $mode ? $toMonth : null;

            if (BudgetMode::Monthly === $mode && null === $targetMonth) {
                // Nothing sensible to roll a monthly envelope into.
                ++$skipped;
                continue;
            }

            $existing = $this->envelopeRepository->findOneForPeriod(
                $source->requireCategory(),
                $mode,
                $toYear,
                $targetMonth,
            );

            if (null !== $existing) {
                ++$skipped;
                continue;
            }

            $amountCents = $source->getAmountCents();
            $seasonalAmounts = $source->getSeasonalAmounts();
            if ($useActualSpending) {
                // What was consumed replaces the amount that budgeted the
                // source month: its season's if one covers it, else the base.
                $consumed = $this->consumedBy($user, $source);
                $season = $source->getSeason();
                if (null === $season) {
                    $amountCents = $consumed;
                } else {
                    $seasonalAmounts = array_map(
                        static fn (array $s) => $s === $season ? array_replace($s, ['amountCents' => $consumed]) : $s,
                        $seasonalAmounts,
                    );
                }
            }

            $stamped = $this->bus->dispatch(new CreateEnvelopeCommand(
                userId: (string) $user->getId(),
                categoryId: (string) $source->requireCategory()->getId(),
                amountCents: $amountCents,
                year: $toYear,
                mode: $mode->value,
                month: $targetMonth,
                currency: $source->getCurrency(),
                // The threshold is a setting of the envelope, not of the month.
                alertThresholdPercent: $source->getAlertThresholdPercent(),
                seasonalAmounts: $seasonalAmounts,
            ));

            /** @var Envelope $envelope */
            $envelope = $stamped->last(HandledStamp::class)->getResult();
            ++$created;

            $envelopes[] = [
                'id' => (string) $envelope->getId(),
                'categoryId' => (string) $envelope->requireCategory()->getId(),
                'categoryName' => $envelope->requireCategory()->getName(),
                'mode' => $envelope->getMode()->value,
                'amountCents' => $envelope->getAmountCents(),
                'seasonalAmounts' => $envelope->getSeasonalAmounts(),
                // What the new month is budgeted: the season covering it, else amountCents.
                'monthAmountCents' => $envelope->getMonthAmountCents(),
                'currency' => $envelope->getCurrency(),
                'year' => $envelope->getYear(),
                'month' => $envelope->getMonth(),
            ];
        }

        return ['created' => $created, 'skipped' => $skipped, 'envelopes' => $envelopes];
    }

    /** What the source envelope's category and its sub-categories actually consumed over its period. */
    private function consumedBy(User $user, Envelope $source): int
    {
        $byStatus = $this->transactionRepository->sumByStatusForCategoriesBetween(
            $user,
            $this->categoryRepository->findSelfAndDescendantIds($source->requireCategory()),
            $source->getPeriodStart() ?? throw new \LogicException('A one-off envelope has no period.'),
            $source->getPeriodEnd() ?? throw new \LogicException('A one-off envelope has no period.'),
        );

        return $byStatus[TransactionStatus::Spent->value] + $byStatus[TransactionStatus::Committed->value];
    }
}
