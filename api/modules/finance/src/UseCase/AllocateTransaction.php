<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\PeerDebtAllocation;
use Maggie\Finance\Entity\PeerDebtShare;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Enum\PeerKind;
use Maggie\Finance\Repository\PeerDebtAllocationRepository;
use Maggie\Finance\Repository\PeerDebtRepository;
use Symfony\Component\Uid\Ulid;

/**
 * Rule 5 of the spec « Créances, dettes et contacts » (MAG-453): a received
 * transfer is an income, the repayment of créances — one or more, in whole or
 * in part, what is not allocated stays an income — or a new dette; a sent
 * transfer is the repayment of dettes, a new créance or anything else.
 *
 * Answering again replaces the previous answer: the allocations it drops give
 * back what they repaid, the dette or créance it created goes — refused once
 * someone gave money back on it.
 */
class AllocateTransaction
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly PeerDebtRepository $peerDebtRepository,
        private readonly PeerDebtAllocationRepository $allocationRepository,
        private readonly RecordDebt $recordDebt,
        private readonly SetMyShare $setMyShare,
    ) {
    }

    /**
     * @param list<array{shareId: string, amountCents: int}>                                    $allocations for a repayment
     * @param array{contactId: ?string, amountCents: int, object: ?string, notes: ?string}|null $debt        for a new dette
     * @param array{contactId: ?string, object: ?string, notes: ?string}|null                   $claim       for a new créance
     *
     * @throws \DomainException when the transfer, the answer or an amount does not fit
     */
    public function execute(Transaction $transaction, PeerKind $kind, array $allocations = [], ?array $debt = null, ?array $claim = null): Transaction
    {
        if (!$transaction->canBeAllocated()) {
            throw new \DomainException('Only a transfer with a person can be allocated: not an internal transfer nor a rejection.');
        }
        $incoming = $transaction->getAmountCents() > 0;
        if (!$kind->fits($incoming)) {
            throw new \DomainException(sprintf('A %s transfer is one of: %s.', $incoming ? 'received' : 'sent', implode(', ', PeerKind::valuesFor($incoming))));
        }
        if (PeerKind::Repayment !== $kind && [] !== $allocations) {
            throw new \DomainException('allocations go with kind repayment only.');
        }
        if (PeerKind::NewDebt === $kind && null === $debt) {
            throw new \DomainException('kind new_debt needs debt: {contactId, amountCents, object, notes}.');
        }
        if (PeerKind::NewClaim === $kind && null === $claim) {
            throw new \DomainException('kind new_claim needs claim: {contactId, object, notes}.');
        }

        // Everything is checked before anything is written.
        $wanted = PeerKind::Repayment === $kind ? $this->checkedAllocations($transaction, $allocations) : [];
        $this->release($transaction, $kind, $wanted);
        $transaction->setPeerKind($kind);

        match ($kind) {
            PeerKind::NewDebt => $this->recordDebt->execute(
                $transaction,
                $debt['contactId'] ?? $this->contactIdOf($transaction),
                $debt['amountCents'] ?? 0,
                $debt['object'] ?? null,
                $debt['notes'] ?? null,
            ),
            PeerKind::NewClaim => $this->setMyShare->execute(
                $transaction,
                0,
                [['contactId' => $claim['contactId'] ?? $this->contactIdOf($transaction), 'amountCents' => null]],
                $claim['object'] ?? null,
                $claim['notes'] ?? null,
            ),
            default => null,
        };

        $this->allocate($transaction, $wanted);
        $this->em->flush();

        return $transaction;
    }

    /**
     * The transfer is no longer told apart: its allocations give back what
     * they repaid, the dette or créance it created goes.
     *
     * @throws \DomainException when someone gave money back on what it created
     */
    public function remove(Transaction $transaction): Transaction
    {
        $this->release($transaction, null, []);
        $transaction->setPeerKind(null);
        $this->em->flush();

        return $transaction;
    }

    /**
     * Takes back every allocation the next answer does not keep, and the dette
     * or créance of the previous answer when the next one is something else.
     *
     * @param array<string, array{share: PeerDebtShare, amountCents: int}> $kept by share id
     */
    private function release(Transaction $transaction, ?PeerKind $next, array $kept): void
    {
        foreach ($this->allocationRepository->findByTransaction($transaction) as $allocation) {
            if (isset($kept[(string) $allocation->getShare()->getId()])) {
                continue;
            }
            $share = $allocation->getShare();
            $share->setRepaidCents(max(0, $share->getRepaidCents() - $allocation->getAmountCents()));
            $this->em->remove($allocation);
        }
        if ([] === $kept) {
            $transaction->setPeerAllocatedCents(0);
        }

        $created = $this->peerDebtRepository->findOneByOriginTransaction($transaction);
        if (null === $created) {
            return;
        }
        $previous = $transaction->getPeerKind();
        $isDette = PeerDebtDirection::IOwe === $created->getDirection();

        if ($isDette && PeerKind::NewDebt !== $next) {
            if ($created->getRepaidCents() > 0) {
                throw new \DomainException('Part of the dette of this transfer was already given back: it can no longer be something else.');
            }
            $this->em->remove($created);

            return;
        }

        if (!$isDette && PeerKind::NewClaim !== $next) {
            if (PeerKind::NewClaim === $previous) {
                if ($created->getRepaidCents() > 0) {
                    throw new \DomainException('Part of the créance of this transfer was already given back: it can no longer be something else.');
                }
                $this->em->remove($created);
                $transaction->setMyShareCents(null);

                return;
            }
            // A spend shared with MAG-451 stays shared, unless it becomes a repayment.
            if (PeerKind::Repayment === $next) {
                throw new \DomainException('This spend is shared: remove the share (DELETE /api/finance/transactions/{id}/my-share) before making it a repayment.');
            }
        }
    }

    /**
     * @param list<array{shareId: string, amountCents: int}> $allocations
     *
     * @return array<string, array{share: PeerDebtShare, amountCents: int}> by share id
     */
    private function checkedAllocations(Transaction $transaction, array $allocations): array
    {
        if ([] === $allocations) {
            throw new \DomainException('A repayment covers at least one share: allocations cannot be empty.');
        }

        $direction = $transaction->getAmountCents() > 0 ? PeerDebtDirection::OwedToMe : PeerDebtDirection::IOwe;
        $current = [];
        foreach ($this->allocationRepository->findByTransaction($transaction) as $allocation) {
            $current[(string) $allocation->getShare()->getId()] = $allocation->getAmountCents();
        }

        $wanted = [];
        foreach ($allocations as ['shareId' => $shareId, 'amountCents' => $amountCents]) {
            $share = Ulid::isValid($shareId) ? $this->peerDebtRepository->findOneOwnedShare($shareId, $transaction->getUser()) : null;
            if (null === $share) {
                throw new \DomainException("Share not found: {$shareId}");
            }
            if ($share->getPeerDebt()->getDirection() !== $direction) {
                throw new \DomainException(PeerDebtDirection::OwedToMe === $direction ? 'A received transfer gives back créances (owed_to_me), not dettes.' : 'A sent transfer gives back dettes (i_owe), not créances.');
            }
            $key = (string) $share->getId();
            if (isset($wanted[$key])) {
                throw new \DomainException(sprintf('The share of %s is named twice: one allocation per share.', $share->getContactName()));
            }
            $available = $share->getRemainingCents() + ($current[$key] ?? 0);
            if ($amountCents <= 0 || $amountCents > $available) {
                throw new \DomainException(sprintf('%s owes %d cents on « %s »: an allocation is between 1 and that.', $share->getContactName(), $available, $share->getPeerDebt()->getObject()));
            }
            $wanted[$key] = ['share' => $share, 'amountCents' => $amountCents];
        }

        $total = array_sum(array_column($wanted, 'amountCents'));
        if ($total > abs($transaction->getAmountCents())) {
            throw new \DomainException(sprintf('The allocations add up to %d cents, more than the %d cents of the transfer.', $total, abs($transaction->getAmountCents())));
        }

        return $wanted;
    }

    /** @param array<string, array{share: PeerDebtShare, amountCents: int}> $wanted by share id */
    private function allocate(Transaction $transaction, array $wanted): void
    {
        $existing = [];
        foreach ($this->allocationRepository->findByTransaction($transaction) as $allocation) {
            $existing[(string) $allocation->getShare()->getId()] = $allocation;
        }

        foreach ($wanted as $key => ['share' => $share, 'amountCents' => $amountCents]) {
            $allocation = $existing[$key] ?? null;
            $before = $allocation?->getAmountCents() ?? 0;
            if (null === $allocation) {
                $this->em->persist(new PeerDebtAllocation($transaction, $share, $amountCents));
            } else {
                $allocation->setAmountCents($amountCents);
            }
            $share->setRepaidCents($share->getRepaidCents() - $before + $amountCents);
        }

        $transaction->setPeerAllocatedCents(array_sum(array_column($wanted, 'amountCents')));
    }

    private function contactIdOf(Transaction $transaction): string
    {
        $contact = $transaction->getContact()
            ?? throw new \DomainException('contactId is required: the transfer is linked to no contact.');

        return (string) $contact->getId();
    }
}
