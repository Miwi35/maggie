<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\PeerDebtAllocation;
use Maggie\Finance\Entity\PeerDebtShare;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Repository\PeerDebtAllocationRepository;
use Maggie\Finance\Repository\PeerDebtRepository;

/**
 * What a transfer with a person is and what it may give back (MAG-453): the
 * answer, its allocations, and the shares it can cover — the open créances for
 * a received transfer, the open dettes for a sent one, those of the transfer's
 * contact first; every other one stays choosable.
 */
class GetTransactionAllocation
{
    public function __construct(
        private readonly PeerDebtRepository $peerDebtRepository,
        private readonly PeerDebtAllocationRepository $allocationRepository,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(Transaction $transaction): array
    {
        $incoming = $transaction->getAmountCents() > 0;
        $contact = $transaction->getContact();

        $allocations = $this->allocationRepository->findByTransaction($transaction);
        $allocated = [];
        $shares = [];
        foreach ($allocations as $allocation) {
            $key = (string) $allocation->getShare()->getId();
            $allocated[$key] = $allocation->getAmountCents();
            $shares[$key] = $allocation->getShare();
        }
        if ($transaction->canBeAllocated()) {
            $direction = $incoming ? PeerDebtDirection::OwedToMe : PeerDebtDirection::IOwe;
            foreach ($this->peerDebtRepository->findOpenShares($transaction->getUser(), $direction) as $share) {
                // The debt this very transfer created is no debt it gives back.
                if ($share->getPeerDebt()->getOriginTransaction() !== $transaction) {
                    $shares[(string) $share->getId()] ??= $share;
                }
            }
        }

        $candidates = array_map(static function (PeerDebtShare $share) use ($allocated, $contact): array {
            $mine = $allocated[(string) $share->getId()] ?? 0;

            return [
                'shareId' => (string) $share->getId(),
                'peerDebtId' => (string) $share->getPeerDebt()->getId(),
                'object' => $share->getPeerDebt()->getObject(),
                'contactId' => $share->getContactId(),
                'contactName' => $share->getContactName(),
                'amountCents' => $share->getAmountCents(),
                'repaidCents' => $share->getRepaidCents(),
                'remainingCents' => $share->getRemainingCents(),
                'allocatedCents' => $mine,
                'availableCents' => $share->getRemainingCents() + $mine,
                'fromContact' => null !== $contact && $share->getContact()->getId()->equals($contact->getId()),
            ];
        }, array_values($shares));
        // The contact's first, then the oldest debt first (the order they came in).
        usort($candidates, static fn (array $a, array $b): int => $b['fromContact'] <=> $a['fromContact']);

        $created = $this->peerDebtRepository->findOneByOriginTransaction($transaction);
        $allocatedCents = array_sum($allocated);

        return [
            'transactionId' => (string) $transaction->getId(),
            'amountCents' => $transaction->getAmountCents(),
            'direction' => $incoming ? 'incoming' : 'outgoing',
            'canBeAllocated' => $transaction->canBeAllocated(),
            'peerKind' => $transaction->getPeerKind()?->value,
            'contact' => null === $contact ? null : ['id' => (string) $contact->getId(), 'name' => $contact->getName()],
            'allocatedCents' => $allocatedCents,
            'unallocatedCents' => abs($transaction->getAmountCents()) - $allocatedCents,
            'allocations' => array_map(static fn (PeerDebtAllocation $allocation): array => $allocation->toArray(), $allocations),
            'candidates' => $candidates,
            'peerDebt' => $created instanceof PeerDebt ? $created->toArray() : null,
        ];
    }
}
