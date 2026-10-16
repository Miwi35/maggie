<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\AggregateRoot;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Uid\Ulid;

/**
 * One person's part of a debt: Tom owes 200 € of the gîte. Only ever shown
 * inside its debt, published and indexed as a change to it.
 */
#[ORM\Entity]
#[AggregateRoot('peerDebt')]
class PeerDebtShare
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: PeerDebt::class, inversedBy: 'shares')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Ignore]
    private PeerDebt $peerDebt;

    /** Served as `contactId` and `contactName`. A contact with a share cannot be deleted. */
    #[ORM\ManyToOne(targetEntity: Contact::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[Ignore]
    private Contact $contact;

    #[ORM\Column(type: 'integer')]
    private int $amountCents = 0;

    /** What they already gave back on it (MAG-453 allocates the repayments). */
    #[ORM\Column(type: 'integer', options: ['default' => 0])]
    private int $repaidCents = 0;

    #[ORM\Column(options: ['default' => 0])]
    #[Ignore]
    private int $position = 0;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getPeerDebt(): PeerDebt
    {
        return $this->peerDebt;
    }

    /** Both sides at once: the debt's totals read its shares. */
    public function setPeerDebt(PeerDebt $peerDebt): static
    {
        $this->peerDebt = $peerDebt;
        $peerDebt->attach($this);

        return $this;
    }

    public function getContact(): Contact
    {
        return $this->contact;
    }

    public function setContact(Contact $contact): static
    {
        $this->contact = $contact;

        return $this;
    }

    public function getContactId(): string
    {
        return (string) $this->contact->getId();
    }

    public function getContactName(): string
    {
        return $this->contact->getName();
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

    public function getRepaidCents(): int
    {
        return $this->repaidCents;
    }

    public function setRepaidCents(int $repaidCents): static
    {
        $this->repaidCents = $repaidCents;

        return $this;
    }

    public function getRemainingCents(): int
    {
        return max(0, $this->amountCents - $this->repaidCents);
    }

    public function setPosition(int $position): static
    {
        $this->position = $position;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toArray(): array
    {
        return [
            'id' => (string) $this->id,
            'contactId' => $this->getContactId(),
            'contactName' => $this->getContactName(),
            'amountCents' => $this->amountCents,
            'repaidCents' => $this->repaidCents,
            'remainingCents' => $this->getRemainingCents(),
        ];
    }
}
