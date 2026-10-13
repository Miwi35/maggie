<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Recognises the transfer that gives an advance back (shape decisions 14 and
 * 15): a checking → source transfer whose amount is **exactly** what is left
 * of an open advance of that very source. It becomes a `repayment`, and its
 * debit leg points at the advance with `repays`.
 *
 * Everything else stays a neutral internal transfer. The regular top-up of the
 * savings account goes the same way — checking → savings — and only its
 * amount tells it from a repayment: reconciling a partial amount would eat the
 * month's top-up as a partial repayment, which is exactly what must never
 * happen. A line the owner marked `funding` is never looked at, and neither is
 * anything marked by hand.
 *
 * Deterministic: lines are taken oldest first, and when two advances have the
 * same balance the older one is repaid first, so two passes agree.
 *
 * Runs behind `DetectInternalTransfers`: on creation, when the second leg
 * arrives, and in the catch-up pass.
 */
class ReconcileAdvanceRepayments
{
    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /**
     * The advances still open, oldest first, with what is left of each — the
     * same figure `GetSavingsAdvances` reports.
     *
     * @return array<string, array{advance: Transaction, outstandingCents: int}> by the advance's id
     */
    public function openAdvances(User $user): array
    {
        $debits = $this->transactionRepository->findAdvanceDebits($user);
        $repaid = $this->transactionRepository->sumRepaidByAdvance($user, $debits);

        $open = [];
        foreach ($debits as $debit) {
            $id = (string) $debit->getId();
            $outstanding = max(0, -$debit->getAmountCents() - ($repaid[$id] ?? 0));
            if ($outstanding > 0) {
                $open[$id] = ['advance' => $debit, 'outstandingCents' => $outstanding];
            }
        }

        return $open;
    }

    /**
     * The advance that these two legs of one transfer give back in full, taken
     * out of `$open` so a second transfer cannot repay it again.
     *
     * @param array<string, array{advance: Transaction, outstandingCents: int}> $open
     */
    public function take(array &$open, Transaction $leg, Transaction $otherLeg): ?Transaction
    {
        [$debit, $credit] = $leg->getAmountCents() < 0 ? [$leg, $otherLeg] : [$otherLeg, $leg];

        if ($debit->getAmountCents() >= 0 || $credit->getAmountCents() !== -$debit->getAmountCents()
            || TransferSource::Manual === $debit->getTransferSource()
            || TransferSource::Manual === $credit->getTransferSource()
            || TransferKind::Funding === $debit->getTransferKind()
            || TransferKind::Funding === $credit->getTransferKind()
            || AccountType::Checking !== $debit->getAccount()->getType()) {
            return null;
        }

        foreach ($open as $id => $entry) {
            $advance = $entry['advance'];

            if ($entry['outstandingCents'] === $credit->getAmountCents()
                && $advance->getAccount()->getId()->equals($credit->getAccount()->getId())
                && $advance->getCurrency() === $debit->getCurrency()
                // A transfer cannot give back what was not taken yet.
                && $advance->getBookedAt() <= $debit->getBookedAt()) {
                unset($open[$id]);

                return $advance;
            }
        }

        return null;
    }

    /** The advance this just-paired transfer gives back, or null when it is not a repayment. */
    public function advanceRepaidBy(Transaction $leg, Transaction $otherLeg): ?Transaction
    {
        $open = $this->openAdvances($leg->getUser());

        return $this->take($open, $leg, $otherLeg);
    }

    /**
     * Requalifies the internal transfers of one user that give an advance
     * back, writing through the bus so both legs reach Mercure and the index.
     *
     * @param \DateTimeImmutable|null                                                $since  how far back to look, or null for the whole history
     * @param bool                                                                   $dryRun report without writing anything
     * @param array<string, array{advance: Transaction, outstandingCents: int}>|null $open   the advances still open, when the caller has already taken some
     *
     * @return array{matched: int, repayments: list<array{transactionId: string, counterpartId: string, advanceId: string, amountCents: int, bookedAt: string}>}
     */
    public function execute(User $user, ?\DateTimeImmutable $since = null, bool $dryRun = false, ?array $open = null): array
    {
        $open ??= $this->openAdvances($user);
        $repayments = [];

        if ([] !== $open) {
            foreach ($this->transactionRepository->findAutoPairedDebits($user, TransferKind::Internal, $since) as $debit) {
                $credit = $debit->getCounterpart();
                $advance = null === $credit ? null : $this->take($open, $debit, $credit);
                if (null === $credit || null === $advance) {
                    continue;
                }

                $repayments[] = [
                    'transactionId' => (string) $debit->getId(),
                    'counterpartId' => (string) $credit->getId(),
                    'advanceId' => (string) $advance->getId(),
                    'amountCents' => -$debit->getAmountCents(),
                    'bookedAt' => $debit->getBookedAt()->format('Y-m-d'),
                ];

                if (!$dryRun) {
                    $this->bus->dispatch(new UpdateTransactionCommand(
                        userId: (string) $user->getId(),
                        transactionId: (string) $debit->getId(),
                        transferKind: TransferKind::Repayment->value,
                        transferSource: TransferSource::Auto->value,
                        counterpartId: (string) $credit->getId(),
                        repaysTransactionId: (string) $advance->getId(),
                    ));
                }
            }
        }

        return ['matched' => \count($repayments), 'repayments' => $repayments];
    }
}
