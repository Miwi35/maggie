<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\AggregateRoot;
use Maggie\Finance\Repository\PeerDebtAllocationRepository;
use Symfony\Component\Uid\Ulid;

/**
 * The part of a transfer that gives back one share of a debt (MAG-453): Tom
 * sends 400 €, 200 € of it cover his share of the gîte. What a share was given
 * back (`PeerDebtShare::repaidCents`) moves with its allocations. Only ever
 * shown inside the transfer's allocation, published as a change to it.
 */
#[ORM\Entity(repositoryClass: PeerDebtAllocationRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_peer_debt_allocation_transaction_share', columns: ['transaction_id', 'share_id'])]
#[AggregateRoot('transaction')]
class PeerDebtAllocation
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Transaction::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Transaction $transaction;

    #[ORM\ManyToOne(targetEntity: PeerDebtShare::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private PeerDebtShare $share;

    #[ORM\Column(type: 'integer')]
    private int $amountCents = 0;

    public function __construct(Transaction $transaction, PeerDebtShare $share, int $amountCents)
    {
        $this->id = new Ulid();
        $this->transaction = $transaction;
        $this->share = $share;
        $this->amountCents = $amountCents;
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getTransaction(): Transaction
    {
        return $this->transaction;
    }

    public function getShare(): PeerDebtShare
    {
        return $this->share;
    }

    public function getAmountCents(): int
    {
        return $this->amountCents;
    }

    public function setAmountCents(int $amountCents): static
    {
        $this->amountCents = $amountCents;

        return $this;
    }

    /** @return array{shareId: string, peerDebtId: string, amountCents: int} */
    public function toArray(): array
    {
        return [
            'shareId' => (string) $this->share->getId(),
            'peerDebtId' => (string) $this->share->getPeerDebt()->getId(),
            'amountCents' => $this->amountCents,
        ];
    }
}
