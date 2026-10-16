<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Post;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Doctrine\Filter\UlidRelationFilter;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Finance\Contact\CounterpartyMatch;
use Maggie\Finance\Repository\ContactCounterpartyRepository;
use Maggie\Finance\State\ContactCounterpartyProvider;
use Maggie\Finance\State\CreateContactCounterpartyProcessor;
use Maggie\Finance\State\DeleteContactCounterpartyProcessor;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

/**
 * One way a contact shows on the owner's statements: « VIR. DE TOM MARTIN »,
 * « PAYPAL *TOMMARTIN ». Every transfer it recognises is attached to the
 * contact (`Transaction.contact`); a label belongs to one contact only.
 *
 * Linked and removed, never edited: a different label is another counterparty.
 * Read from Doctrine, with the number of transfers it recognises and the date
 * of the last one, which a search index would hold stale.
 */
#[ORM\Entity(repositoryClass: ContactCounterpartyRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_contact_counterparty_user_key', columns: ['user_id', 'match_key'])]
#[ApiFilter(UlidRelationFilter::class, properties: ['contact'])]
#[ApiResource(operations: [
    new GetCollection(provider: ContactCounterpartyProvider::class),
    new Get(provider: ContactCounterpartyProvider::class),
    new Post(processor: CreateContactCounterpartyProcessor::class),
    new Delete(processor: DeleteContactCounterpartyProcessor::class),
])]
class ContactCounterparty implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Contact::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Assert\NotNull]
    private Contact $contact;

    /** As the statement spells it. */
    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    #[Assert\Length(max: 255)]
    private string $label = '';

    /** `MerchantExtractor::key($label)`: what a transaction is recognised by. */
    #[ORM\Column(length: 255)]
    #[ApiProperty(writable: false)]
    private string $matchKey = '';

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[ApiProperty(readable: false, writable: false)]
    private User $user;

    /** Filled on read (ContactCounterpartyProvider): how many transfers it recognises. */
    #[ApiProperty(writable: false)]
    private int $transactionCount = 0;

    /** Filled on read: the date of the last of them. */
    #[ApiProperty(writable: false)]
    private ?\DateTimeImmutable $lastSeenOn = null;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
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

    public function getLabel(): string
    {
        return $this->label;
    }

    /** The one place the key is computed, like `Transaction::setCounterpartyName`. */
    public function setLabel(string $label): static
    {
        $this->label = mb_substr(trim(preg_replace('/\s+/u', ' ', $label) ?? $label), 0, 255);
        $this->matchKey = CounterpartyMatch::keyOf($this->label);

        return $this;
    }

    public function getMatchKey(): string
    {
        return $this->matchKey;
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

    public function getTransactionCount(): int
    {
        return $this->transactionCount;
    }

    /** @return string|null `Y-m-d` */
    public function getLastSeenOn(): ?string
    {
        return $this->lastSeenOn?->format('Y-m-d');
    }

    public function useUsage(int $transactionCount, ?\DateTimeImmutable $lastSeenOn): static
    {
        $this->transactionCount = $transactionCount;
        $this->lastSeenOn = $lastSeenOn;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'contactId' => (string) $this->contact->getId(),
            'label' => $this->label,
            'matchKey' => $this->matchKey,
        ], $changedProperties, ['contact' => 'contactId']);
    }
}
