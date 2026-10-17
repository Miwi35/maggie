<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\PeerDebtAllocation;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Enum\PeerDebtStatus;
use Maggie\Finance\Repository\PeerDebtAllocationRepository;
use Maggie\Finance\Repository\PeerDebtRepository;

/**
 * The créances and dettes of the owner, newest first, each with its shares and
 * the transfers that gave it back (MAG-455): what the « Créances et dettes »
 * screen and the contact's page read. Filtered by status, direction and person.
 */
class ListPeerDebts
{
    public function __construct(
        private readonly PeerDebtRepository $peerDebtRepository,
        private readonly PeerDebtAllocationRepository $allocationRepository,
    ) {
    }

    /** @return list<array<string, mixed>> */
    public function execute(User $user, ?PeerDebtStatus $status = null, ?PeerDebtDirection $direction = null, ?Contact $contact = null): array
    {
        $debts = array_values(array_filter(
            $this->peerDebtRepository->findByUser($user, $contact, $direction),
            static fn (PeerDebt $debt): bool => null === $status || $debt->getStatus() === $status,
        ));

        $history = [];
        foreach ($this->allocationRepository->findByPeerDebts($debts) as $allocation) {
            $history[(string) $allocation->getShare()->getPeerDebt()->getId()][] = self::historyEntry($allocation);
        }

        return array_map(
            static fn (PeerDebt $debt): array => $debt->toArray() + ['history' => $history[(string) $debt->getId()] ?? []],
            $debts,
        );
    }

    /** @return array{transactionId: string, bookedAt: string, label: string, amountCents: int, contactId: string, contactName: string} */
    private static function historyEntry(PeerDebtAllocation $allocation): array
    {
        $transaction = $allocation->getTransaction();

        return [
            'transactionId' => (string) $transaction->getId(),
            'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
            'label' => $transaction->getLabel(),
            'amountCents' => $allocation->getAmountCents(),
            'contactId' => $allocation->getShare()->getContactId(),
            'contactName' => $allocation->getShare()->getContactName(),
        ];
    }
}
