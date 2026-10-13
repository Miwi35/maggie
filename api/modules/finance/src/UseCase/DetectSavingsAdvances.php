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
 * Recognises a savings advance: an internal transfer, already paired, from a
 * savings or investment account to a checking one, booked while the salary
 * was late (shape decisions 12 and 13).
 *
 * The account's type says what it is; `isCushion` says what it is for, and is
 * not the rule: dipping into the safety net is the cushion's recharge plan, a
 * different mechanism. A pairing the owner made by hand is never requalified.
 *
 * Runs behind `DetectInternalTransfers`: on creation, when the second leg
 * arrives, and in the catch-up pass, which also requalifies the pairs made
 * before the salary was late or before its day was known.
 */
class DetectSavingsAdvances
{
    private const array SOURCE_TYPES = [AccountType::Savings, AccountType::Investment];

    public function __construct(
        private readonly GetSalaryDelay $getSalaryDelay,
        private readonly TransactionRepository $transactionRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** What the two legs of one internal transfer are: an advance, or a neutral internal transfer. */
    public function kindFor(Transaction $leg, Transaction $otherLeg): TransferKind
    {
        return $this->isAdvance($leg, $otherLeg) ? TransferKind::Advance : TransferKind::Internal;
    }

    /**
     * Requalifies the internal transfers of one user that are advances,
     * writing through the bus so both legs reach Mercure and the index.
     *
     * @param \DateTimeImmutable|null $since  how far back to look, or null for the whole history
     * @param bool                    $dryRun report without writing anything
     *
     * @return array{matched: int, advances: list<array{transactionId: string, counterpartId: string, amountCents: int, bookedAt: string}>}
     */
    public function execute(User $user, ?\DateTimeImmutable $since = null, bool $dryRun = false): array
    {
        $advances = [];

        foreach ($this->transactionRepository->findAutoPairedDebits($user, TransferKind::Internal, $since) as $debit) {
            $credit = $debit->getCounterpart();
            if (null === $credit || !$this->isAdvance($debit, $credit)) {
                continue;
            }

            $advances[] = [
                'transactionId' => (string) $debit->getId(),
                'counterpartId' => (string) $credit->getId(),
                'amountCents' => -$debit->getAmountCents(),
                'bookedAt' => $debit->getBookedAt()->format('Y-m-d'),
            ];

            if (!$dryRun) {
                $this->bus->dispatch(new UpdateTransactionCommand(
                    userId: (string) $user->getId(),
                    transactionId: (string) $debit->getId(),
                    transferKind: TransferKind::Advance->value,
                    transferSource: TransferSource::Auto->value,
                    counterpartId: (string) $credit->getId(),
                ));
            }
        }

        return ['matched' => \count($advances), 'advances' => $advances];
    }

    private function isAdvance(Transaction $leg, Transaction $otherLeg): bool
    {
        [$debit, $credit] = $leg->getAmountCents() < 0 ? [$leg, $otherLeg] : [$otherLeg, $leg];

        if ($debit->getAmountCents() >= 0 || $credit->getAmountCents() <= 0
            || TransferSource::Manual === $debit->getTransferSource()
            || TransferSource::Manual === $credit->getTransferSource()
            || !\in_array($debit->getAccount()->getType(), self::SOURCE_TYPES, true)
            || AccountType::Checking !== $credit->getAccount()->getType()) {
            return false;
        }

        // The delay as it stood the day the money moved.
        return $this->getSalaryDelay->execute($debit->getUser(), $debit->getBookedAt())['isLate'];
    }
}
