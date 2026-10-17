<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\EnvelopeAlert;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Repository\EnvelopeAlertRepository;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\CreateNotificationCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Maggie interrupts when a bank sync takes an envelope past its threshold —
 * the one set on it (80 % by default), then 100 % (MAG-49).
 *
 * Spending reaches Maggie with the sync, not as it happens: an alert says
 * what that sync brought, so only the envelopes it fed are weighed, and a
 * threshold counts as crossed when the envelope was below it before the
 * sync's movements and is at or above it after. Each threshold alerts once
 * per period; when one sync crosses both, one notification says where the
 * envelope stands, and both are kept as said.
 *
 * Only the periods running today: a first sync reaches months back, and
 * nobody needs to hear that July went over in October.
 */
class AlertEnvelopeLimits
{
    private const FULL_PERCENT = 100;

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly EnvelopeAlertRepository $alertRepository,
        private readonly GetBudgetStatus $getBudgetStatus,
        private readonly EntityManagerInterface $em,
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    /**
     * @param list<string> $transactionIds the movements the sync stored
     *
     * @return int the notifications sent
     */
    public function execute(User $user, array $transactionIds, ?\DateTimeImmutable $today = null): int
    {
        $spends = $this->consumedSpends($this->transactionRepository->findByIdsForUser($user, $transactionIds));
        if ([] === $spends) {
            return 0;
        }

        // The user's day, not the server's: at 01:00 in Paris on the 1st, the
        // month that has started is the new one.
        $today ??= new \DateTimeImmutable('today', new \DateTimeZone('Europe/Paris'));
        $status = $this->getBudgetStatus->execute($user, (int) $today->format('Y'), (int) $today->format('n'));

        $sent = 0;
        foreach ($status['budgets'] as $budget) {
            $envelope = $this->envelopeRepository->find($budget['id']);
            $amountCents = $budget['amountCents'];
            if (!$envelope instanceof Envelope || $amountCents <= 0) {
                continue;
            }

            $broughtCents = $this->broughtBySync($envelope, $spends);
            if (0 === $broughtCents) {
                continue;
            }

            $afterCents = $budget['consumedCents'];
            $beforeCents = $afterCents - $broughtCents;
            $period = EnvelopeAlert::periodOf($envelope);

            $crossed = [];
            foreach (array_unique([$envelope->getEffectiveAlertThresholdPercent(), self::FULL_PERCENT]) as $threshold) {
                if ($beforeCents * 100 < $threshold * $amountCents
                    && $afterCents * 100 >= $threshold * $amountCents
                    && !$this->alertRepository->wasSent($envelope, $period, $threshold)) {
                    $crossed[] = $threshold;
                }
            }

            if ([] === $crossed) {
                continue;
            }

            foreach ($crossed as $threshold) {
                $this->em->persist(new EnvelopeAlert($envelope, $period, $threshold));
            }
            $this->em->flush();

            $this->notify($user, $envelope, $afterCents, $amountCents);
            ++$sent;
        }

        return $sent;
    }

    /**
     * What weighs on an envelope: debits filed under a category, spent or
     * committed, that move money out — the same as the budget status counts.
     *
     * @param list<Transaction> $transactions
     *
     * @return list<Transaction>
     */
    private function consumedSpends(array $transactions): array
    {
        return array_values(array_filter(
            $transactions,
            static fn (Transaction $t) => null !== $t->getCategory()
                && $t->getAmountCents() < 0
                && TransferKind::None === $t->getTransferKind()
                && \in_array($t->getStatus(), [TransactionStatus::Spent, TransactionStatus::Committed], true),
        ));
    }

    /**
     * The cents this sync added to an envelope: its movements of the period
     * filed under the envelope's category or any category below it.
     *
     * @param list<Transaction> $spends
     */
    private function broughtBySync(Envelope $envelope, array $spends): int
    {
        $cents = 0;
        foreach ($spends as $spend) {
            if ($spend->getBookedAt() < $envelope->getPeriodStart() || $spend->getBookedAt() >= $envelope->getPeriodEnd()) {
                continue;
            }
            if ($this->isWithin($spend->getCategory(), $envelope->requireCategory())) {
                $cents += abs($spend->getOwnAmountCents());
            }
        }

        return $cents;
    }

    private function isWithin(?Category $category, Category $root): bool
    {
        for (; null !== $category; $category = $category->getParent()) {
            if ($category->getId()->equals($root->getId())) {
                return true;
            }
        }

        return false;
    }

    /** A fact, not a verdict: where the envelope stands, in its own words. */
    private function notify(User $user, Envelope $envelope, int $consumedCents, int $amountCents): void
    {
        $percent = intdiv($consumedCents * 100, $amountCents);

        $this->commandBus->dispatch(new CreateNotificationCommand(
            type: NotificationType::Finance->value,
            title: sprintf(
                '%s : %d %% du budget (%s / %s)',
                $envelope->requireCategory()->getName(),
                $percent,
                $this->amount($consumedCents),
                $this->amount($amountCents, $envelope->getCurrency()),
            ),
            // The envelopes screen, not this envelope: the alert is read
            // against the others, and nothing is adjusted from it.
            relatedEntityIri: '/finance/budgets',
            userId: (string) $user->getId(),
        ));
    }

    /** « 246 », « 246,50 », and the currency on the last figure only. */
    private function amount(int $cents, ?string $currency = null): string
    {
        $figure = 0 === $cents % 100
            ? number_format(intdiv($cents, 100), 0, ',', ' ')
            : number_format($cents / 100, 2, ',', ' ');

        return match ($currency) {
            null => $figure,
            'EUR' => $figure.' €',
            default => $figure.' '.$currency,
        };
    }
}
