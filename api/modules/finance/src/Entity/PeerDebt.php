<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Enum\PeerDebtStatus;
use Maggie\Finance\Repository\PeerDebtRepository;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Uid\Ulid;

/**
 * Money between the owner and their contacts (MAG-451): a créance when they
 * owe the owner — what is left of a shared spend once the owner's part is set —,
 * a dette the other way round (MAG-452). Split into one share per person.
 *
 * Read only over REST: it is written through the transaction it comes from
 * (`PUT /api/finance/transactions/{id}/my-share`). Served from Doctrine, like
 * the counterparties: what is left to give back moves with every repayment,
 * a search document would hold it stale. Indexed for the search all the same.
 */
#[ORM\Entity(repositoryClass: PeerDebtRepository::class)]
// One créance per shared spend: setting the part again updates it.
#[ORM\UniqueConstraint(name: 'uniq_peer_debt_origin_transaction', columns: ['origin_transaction_id'])]
#[Indexed(index: 'peer_debts', module: 'finance', globalSearch: false)]
#[ApiResource(
    operations: [
        // Unpaginated: a handful of debts, which the screens filter by person.
        // `/api/peer_debts` like `/api/contacts` (MAG-450): the path the
        // Mercure topic and the admin's resource name follow.
        new GetCollection(paginationEnabled: false),
        new Get(),
    ],
    order: ['createdAt' => 'DESC', 'id' => 'DESC'],
)]
class PeerDebt implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use MercurePayloadFilterTrait;

    public const int OBJECT_MAX_LENGTH = 255;
    public const int NOTES_MAX_LENGTH = 2000;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    #[ORM\Column(length: 20, enumType: PeerDebtDirection::class)]
    #[IndexedField(type: 'keyword')]
    private PeerDebtDirection $direction = PeerDebtDirection::OwedToMe;

    /** What it was for: « Gîte Bretagne ». The spend's label by default. */
    #[ORM\Column(name: 'object', length: self::OBJECT_MAX_LENGTH)]
    #[IndexedField(type: 'text', boost: 2.0, keyword: true)]
    private string $object = '';

    #[ORM\Column(type: 'text', nullable: true)]
    #[IndexedField(type: 'text')]
    private ?string $notes = null;

    /** The spend it was split off, if any. Served as `originTransactionId`. */
    #[ORM\ManyToOne(targetEntity: Transaction::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[IndexedRelation(targetEntity: Transaction::class, sourceField: 'originTransactionId')]
    #[Ignore]
    private ?Transaction $originTransaction = null;

    /** @var Collection<int, PeerDebtShare> */
    #[ORM\OneToMany(targetEntity: PeerDebtShare::class, mappedBy: 'peerDebt', cascade: ['persist', 'remove'], orphanRemoval: true)]
    #[ORM\OrderBy(['position' => 'ASC'])]
    // Plain objects in the debt, without a generated `@id`.
    #[ApiProperty(genId: false)]
    #[IndexedField(type: 'nested', properties: [
        'contactId' => ['type' => 'keyword'],
        'contactName' => ['type' => 'text'],
        'amountCents' => ['type' => 'long'],
        'repaidCents' => ['type' => 'long'],
    ])]
    private Collection $shares;

    #[ORM\Column(type: 'datetime_immutable')]
    #[IndexedField(type: 'date')]
    private \DateTimeImmutable $createdAt;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    #[ApiProperty(readable: false, writable: false)]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->shares = new ArrayCollection();
        $this->createdAt = new \DateTimeImmutable();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getDirection(): PeerDebtDirection
    {
        return $this->direction;
    }

    public function setDirection(PeerDebtDirection $direction): static
    {
        $this->direction = $direction;

        return $this;
    }

    public function getObject(): string
    {
        return $this->object;
    }

    public function setObject(string $object): static
    {
        $this->object = mb_substr(trim(preg_replace('/\s+/u', ' ', $object) ?? $object), 0, self::OBJECT_MAX_LENGTH);

        return $this;
    }

    public function getNotes(): ?string
    {
        return $this->notes;
    }

    public function setNotes(?string $notes): static
    {
        $notes = null === $notes ? null : trim($notes);
        $this->notes = '' === $notes ? null : $notes;

        return $this;
    }

    public function getOriginTransaction(): ?Transaction
    {
        return $this->originTransaction;
    }

    public function setOriginTransaction(?Transaction $originTransaction): static
    {
        $this->originTransaction = $originTransaction;

        return $this;
    }

    public function getOriginTransactionId(): ?string
    {
        return null !== $this->originTransaction ? (string) $this->originTransaction->getId() : null;
    }

    /** @return list<PeerDebtShare> */
    public function getShares(): array
    {
        return array_values($this->shares->toArray());
    }

    /**
     * Replaces the shares with these amounts per contact, in this order. A
     * contact already in keeps their share — and what they gave back on it.
     *
     * @param list<array{contact: Contact, amountCents: int}> $shares
     */
    public function replaceShares(array $shares): static
    {
        $kept = [];
        foreach ($shares as $position => ['contact' => $contact, 'amountCents' => $amountCents]) {
            $share = $this->shareOf($contact) ?? (new PeerDebtShare())->setContact($contact)->setPeerDebt($this);
            $share->setAmountCents($amountCents)->setPosition($position);
            $kept[] = $share;
        }

        foreach ($this->shares->toArray() as $share) {
            if (!\in_array($share, $kept, true)) {
                $this->shares->removeElement($share);
            }
        }

        return $this;
    }

    /** For `PeerDebtShare::setPeerDebt` only. */
    public function attach(PeerDebtShare $share): void
    {
        if (!$this->shares->contains($share)) {
            $this->shares->add($share);
        }
    }

    public function shareOf(Contact $contact): ?PeerDebtShare
    {
        foreach ($this->shares as $share) {
            if ($share->getContact()->getId()->equals($contact->getId())) {
                return $share;
            }
        }

        return null;
    }

    /** What the shares add up to. */
    public function getAmountCents(): int
    {
        return array_sum(array_map(static fn (PeerDebtShare $share): int => $share->getAmountCents(), $this->shares->toArray()));
    }

    public function getRepaidCents(): int
    {
        return array_sum(array_map(static fn (PeerDebtShare $share): int => $share->getRepaidCents(), $this->shares->toArray()));
    }

    public function getRemainingCents(): int
    {
        return array_sum(array_map(static fn (PeerDebtShare $share): int => $share->getRemainingCents(), $this->shares->toArray()));
    }

    public function getStatus(): PeerDebtStatus
    {
        return $this->getRemainingCents() > 0 ? PeerDebtStatus::Open : PeerDebtStatus::Settled;
    }

    public function getCreatedAt(): \DateTimeImmutable
    {
        return $this->createdAt;
    }

    public function getUser(): User
    {
        return $this->user;
    }

    public function setUser(User $user): static
    {
        $this->user = $user;

        return $this;
    }

    /**
     * The debt as the my-share endpoint and the MCP tools give it, the same
     * fields as over REST.
     *
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        return [
            'id' => (string) $this->id,
            'direction' => $this->direction->value,
            'object' => $this->object,
            'notes' => $this->notes,
            'originTransactionId' => $this->getOriginTransactionId(),
            'amountCents' => $this->getAmountCents(),
            'repaidCents' => $this->getRepaidCents(),
            'remainingCents' => $this->getRemainingCents(),
            'status' => $this->getStatus()->value,
            'createdAt' => $this->createdAt->format(\DateTimeInterface::ATOM),
            'shares' => array_map(static fn (PeerDebtShare $share): array => $share->toArray(), $this->getShares()),
        ];
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'direction' => $this->direction->value,
            'object' => $this->object,
            'notes' => $this->notes,
            'originTransactionId' => $this->getOriginTransactionId(),
            'shares' => array_map(static fn (PeerDebtShare $share): array => [
                'contactId' => $share->getContactId(),
                'contactName' => $share->getContactName(),
                'amountCents' => $share->getAmountCents(),
                'repaidCents' => $share->getRepaidCents(),
            ], $this->getShares()),
            'createdAt' => $this->createdAt->format(\DateTimeInterface::ATOM),
            'userId' => (string) $this->user->getId(),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'direction' => $this->direction->value,
            'object' => $this->object,
            'notes' => $this->notes,
            'originTransactionId' => $this->getOriginTransactionId(),
            'shares' => array_map(static fn (PeerDebtShare $share): array => $share->toArray(), $this->getShares()),
            'amountCents' => $this->getAmountCents(),
            'remainingCents' => $this->getRemainingCents(),
            'status' => $this->getStatus()->value,
            'createdAt' => $this->createdAt->format(\DateTimeInterface::ATOM),
        ], self::withDerivedProperties($changedProperties), ['originTransaction' => 'originTransactionId']);
    }

    /**
     * The totals and the status are read off the shares: a change of shares
     * carries them.
     *
     * @param string[]|null $changedProperties
     *
     * @return string[]|null
     */
    private static function withDerivedProperties(?array $changedProperties): ?array
    {
        if (null === $changedProperties || !\in_array('shares', $changedProperties, true)) {
            return $changedProperties;
        }

        return [...$changedProperties, 'amountCents', 'remainingCents', 'status'];
    }
}
