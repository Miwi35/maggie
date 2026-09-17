<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\State\CreateAccountProcessor;
use Maggie\Finance\State\DeleteAccountProcessor;
use Maggie\Finance\State\UpdateAccountProcessor;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: AccountRepository::class)]
#[Indexed(index: 'accounts', module: 'finance')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateAccountProcessor::class),
    new Patch(processor: UpdateAccountProcessor::class),
    new Delete(processor: DeleteAccountProcessor::class),
])]
class Account implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    #[IndexedField(type: 'text', boost: 3.0, keyword: true)]
    private string $name;

    #[ORM\Column(length: 120, nullable: true)]
    #[IndexedField(type: 'keyword')]
    private ?string $bank = null;

    #[ORM\Column(length: 20, enumType: AccountType::class)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private AccountType $type = AccountType::Checking;

    #[ORM\Column(length: 3)]
    #[Assert\NotBlank]
    #[Assert\Length(exactly: 3)]
    #[Assert\Regex(pattern: '/^[A-Z]{3}$/', message: 'The currency must be a 3-letter ISO 4217 code.')]
    #[IndexedField(type: 'keyword')]
    private string $currency = 'EUR';

    #[ORM\Column(type: 'integer')]
    #[IndexedField(type: 'long')]
    private int $balanceCents = 0;

    #[ORM\Column(type: 'boolean')]
    #[IndexedField(type: 'boolean')]
    private bool $isCushion = false;

    #[ORM\Column(length: 255, nullable: true)]
    private ?string $externalAccountId = null;

    /** The live bank link this account came from, when it was not typed in. */
    #[ORM\ManyToOne(targetEntity: BankConnection::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?BankConnection $bankConnection = null;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getName(): string
    {
        return $this->name;
    }

    public function setName(string $name): static
    {
        $this->name = $name;

        return $this;
    }

    public function getBank(): ?string
    {
        return $this->bank;
    }

    public function setBank(?string $bank): static
    {
        $this->bank = $bank;

        return $this;
    }

    public function getType(): AccountType
    {
        return $this->type;
    }

    public function setType(AccountType $type): static
    {
        $this->type = $type;

        return $this;
    }

    public function getCurrency(): string
    {
        return $this->currency;
    }

    public function setCurrency(string $currency): static
    {
        $this->currency = $currency;

        return $this;
    }

    public function getBalanceCents(): int
    {
        return $this->balanceCents;
    }

    public function setBalanceCents(int $balanceCents): static
    {
        $this->balanceCents = $balanceCents;

        return $this;
    }

    public function isCushion(): bool
    {
        return $this->isCushion;
    }

    public function setIsCushion(bool $isCushion): static
    {
        $this->isCushion = $isCushion;

        return $this;
    }

    public function getExternalAccountId(): ?string
    {
        return $this->externalAccountId;
    }

    public function setExternalAccountId(?string $externalAccountId): static
    {
        $this->externalAccountId = $externalAccountId;

        return $this;
    }

    public function getBankConnection(): ?BankConnection
    {
        return $this->bankConnection;
    }

    public function setBankConnection(?BankConnection $bankConnection): static
    {
        $this->bankConnection = $bankConnection;

        return $this;
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

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'name' => $this->name,
            'bank' => $this->bank,
            'type' => $this->type->value,
            'currency' => $this->currency,
            'balanceCents' => $this->balanceCents,
            'isCushion' => $this->isCushion,
            'userId' => (string) $this->user->getId(),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'name' => $this->name,
            'bank' => $this->bank,
            'type' => $this->type->value,
            'currency' => $this->currency,
            'balanceCents' => $this->balanceCents,
            'isCushion' => $this->isCushion,
        ], $changedProperties);
    }
}
