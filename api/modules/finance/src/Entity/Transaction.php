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
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\State\CreateTransactionProcessor;
use Maggie\Finance\State\DeleteTransactionProcessor;
use Maggie\Finance\State\UpdateTransactionProcessor;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: TransactionRepository::class)]
#[Indexed(index: 'transactions', module: 'finance')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateTransactionProcessor::class),
    new Patch(processor: UpdateTransactionProcessor::class),
    new Delete(processor: DeleteTransactionProcessor::class),
])]
class Transaction implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Account::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Assert\NotNull]
    #[IndexedRelation(targetEntity: Account::class, sourceField: 'accountId')]
    private Account $account;

    #[ORM\ManyToOne(targetEntity: Category::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[IndexedRelation(targetEntity: Category::class, sourceField: 'categoryId')]
    private ?Category $category = null;

    /** Signed amount in integer cents: negative = expense/debit, positive = income/credit. */
    #[ORM\Column(type: 'integer')]
    #[IndexedField(type: 'long')]
    private int $amountCents = 0;

    #[ORM\Column(length: 3)]
    #[Assert\NotBlank]
    #[Assert\Length(exactly: 3)]
    #[Assert\Regex(pattern: '/^[A-Z]{3}$/', message: 'The currency must be a 3-letter ISO 4217 code.')]
    #[IndexedField(type: 'keyword')]
    private string $currency = 'EUR';

    #[ORM\Column(type: 'date_immutable')]
    #[Assert\NotNull]
    #[IndexedField(type: 'date')]
    private \DateTimeImmutable $bookedAt;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    #[IndexedField(type: 'text', boost: 2.0, keyword: true)]
    private string $label;

    #[ORM\Column(length: 20, enumType: TransactionStatus::class)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private TransactionStatus $status = TransactionStatus::Spent;

    #[ORM\Column(type: 'boolean')]
    #[IndexedField(type: 'boolean')]
    private bool $isExceptional = false;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->bookedAt = new \DateTimeImmutable();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getAccount(): Account
    {
        return $this->account;
    }

    public function setAccount(Account $account): static
    {
        $this->account = $account;

        return $this;
    }

    public function getCategory(): ?Category
    {
        return $this->category;
    }

    public function setCategory(?Category $category): static
    {
        $this->category = $category;

        return $this;
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

    public function getCurrency(): string
    {
        return $this->currency;
    }

    public function setCurrency(string $currency): static
    {
        $this->currency = $currency;

        return $this;
    }

    public function getBookedAt(): \DateTimeImmutable
    {
        return $this->bookedAt;
    }

    public function setBookedAt(\DateTimeImmutable $bookedAt): static
    {
        $this->bookedAt = $bookedAt;

        return $this;
    }

    public function getLabel(): string
    {
        return $this->label;
    }

    public function setLabel(string $label): static
    {
        $this->label = $label;

        return $this;
    }

    public function getStatus(): TransactionStatus
    {
        return $this->status;
    }

    public function setStatus(TransactionStatus $status): static
    {
        $this->status = $status;

        return $this;
    }

    public function isExceptional(): bool
    {
        return $this->isExceptional;
    }

    public function setIsExceptional(bool $isExceptional): static
    {
        $this->isExceptional = $isExceptional;

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
            'label' => $this->label,
            'amountCents' => $this->amountCents,
            'currency' => $this->currency,
            'bookedAt' => $this->bookedAt->format('Y-m-d'),
            'status' => $this->status->value,
            'isExceptional' => $this->isExceptional,
            'accountId' => (string) $this->account->getId(),
            'categoryId' => $this->category !== null ? (string) $this->category->getId() : null,
            'userId' => (string) $this->user->getId(),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'label' => $this->label,
            'amountCents' => $this->amountCents,
            'currency' => $this->currency,
            'bookedAt' => $this->bookedAt->format('Y-m-d'),
            'status' => $this->status->value,
            'isExceptional' => $this->isExceptional,
            'accountId' => (string) $this->account->getId(),
            'categoryId' => $this->category !== null ? (string) $this->category->getId() : null,
        ], $changedProperties, ['account' => 'accountId', 'category' => 'categoryId']);
    }
}
