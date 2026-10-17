<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\RecurrenceSchedule;

/**
 * The occurrences the user's confirmed series expect in a window and that no
 * line settles yet: what the projections (treasury, annual plan, independence
 * counter) add to the lines already entered.
 *
 * The one rule against counting twice is the attachment: an occurrence with a
 * transaction attached — booked, planned or committed — is the transaction's,
 * and disappears here. A line of the right amount that nobody attached does
 * not hide it; a second matching rule would be a second truth.
 *
 * A pure read. Nothing is materialised and nothing is extrapolated: only the
 * series the owner confirmed project, a series that has ended stops, and so
 * does one on an account that has been closed. The
 * amount is the series' signed reference, in the currency of its account.
 */
class GetRecurringOccurrences
{
    public const int MAX_DAYS = 400;

    public function __construct(
        private readonly RecurringOperationRepository $operationRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly RecurrenceSchedule $schedule,
    ) {
    }

    /**
     * Over the half-open window [from, until), in date order.
     *
     * @return list<array{date: string, recurringOperationId: string, label: string, categoryId: string, categoryName: string, accountId: string, amountCents: int, currency: string}>
     *
     * @throws \InvalidArgumentException on a window wider than {@see MAX_DAYS} days
     */
    public function execute(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        $from = $from->setTime(0, 0);
        $until = $until->setTime(0, 0);

        if ($until <= $from) {
            return [];
        }

        if ($from->modify(sprintf('+%d days', self::MAX_DAYS)) < $until) {
            throw new \InvalidArgumentException(sprintf('The window of occurrences is at most %d days.', self::MAX_DAYS));
        }

        $series = $this->operationRepository->findSeriesByUser($user);
        if ([] === $series) {
            return [];
        }

        $taken = $this->transactionRepository->findTakenOccurrences($user);

        $occurrences = [];
        foreach ($series as $operation) {
            if ($operation->getAccount()->isClosed()) {
                continue;
            }

            $operationId = (string) $operation->getId();

            foreach ($this->schedule->occurrencesBetween($operation, $from, $until) as $date) {
                if (isset($taken[TransactionRepository::occurrenceKey($operationId, $date)])) {
                    continue;
                }

                $occurrences[] = [
                    'date' => $date->format('Y-m-d'),
                    'recurringOperationId' => $operationId,
                    'label' => $operation->getLabel(),
                    'categoryId' => (string) $operation->getCategory()->getId(),
                    'categoryName' => $operation->getCategory()->getName(),
                    'accountId' => (string) $operation->getAccount()->getId(),
                    'amountCents' => $operation->getReferenceAmountCents(),
                    'currency' => $operation->getAccount()->getCurrency(),
                ];
            }
        }

        usort($occurrences, static fn (array $a, array $b): int => [$a['date'], $a['label'], $a['recurringOperationId']]
            <=> [$b['date'], $b['label'], $b['recurringOperationId']]);

        return $occurrences;
    }
}
