<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use ApiPlatform\Doctrine\Orm\Filter\OrderFilter;
use ApiPlatform\Doctrine\Orm\Filter\SearchFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiProperty;
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
use Maggie\Core\Doctrine\Filter\UlidRelationFilter;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\State\CreateTransactionProcessor;
use Maggie\Finance\State\DeleteTransactionProcessor;
use Maggie\Finance\State\TransactionCollectionProvider;
use Maggie\Finance\State\UpdateTransactionProcessor;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: TransactionRepository::class)]
#[ORM\Index(columns: ['user_id', 'transfer_kind'], name: 'idx_transaction_user_transfer_kind')]
#[ORM\Index(columns: ['user_id', 'counterparty_key'], name: 'idx_transaction_user_counterparty_key')]
#[ORM\Index(columns: ['account_id', 'external_id'], name: 'idx_transaction_account_external_id')]
#[ApiFilter(OrderFilter::class, properties: ['bookedAt'])]
#[ApiFilter(UlidRelationFilter::class, properties: ['account'])]
// The list leaves the rejected payments out unless `transferKind` names a kind (TransactionCollectionProvider).
#[ApiFilter(SearchFilter::class, properties: ['transferKind' => 'exact'])]
#[Indexed(index: 'transactions', module: 'finance')]
#[ApiResource(operations: [
    new GetCollection(provider: TransactionCollectionProvider::class),
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

    /**
     * Who the money came from or went to, kept apart from the label: the bank
     * rewrites the label every month, never the creditor. Not the same thing
     * as `counterpart`, the opposite leg of an internal transfer.
     *
     * Read-only over REST: it is derived (`setCounterpartyName` computes the
     * key), so a client patching one without the other would split a payee in two.
     */
    #[ORM\Column(length: 255, nullable: true)]
    #[ApiProperty(writable: false)]
    #[IndexedField(type: 'text', keyword: true)]
    private ?string $counterpartyName = null;

    /**
     * The bank's own reference for the movement (`entry_reference`), when it
     * gives one: the same movement read twice, whatever its label became.
     */
    #[ORM\Column(length: 255, nullable: true)]
    #[ApiProperty(writable: false)]
    private ?string $externalId = null;

    /** The name folded so two spellings of one payee group together. */
    #[ORM\Column(length: 255, nullable: true)]
    #[ApiProperty(writable: false)]
    #[IndexedField(type: 'keyword')]
    private ?string $counterpartyKey = null;

    #[ORM\Column(length: 20, enumType: TransactionStatus::class)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private TransactionStatus $status = TransactionStatus::Spent;

    #[ORM\Column(type: 'boolean')]
    #[IndexedField(type: 'boolean')]
    private bool $isExceptional = false;

    /** What the user made of this spend at the monthly review. */
    #[ORM\Column(length: 20, enumType: RetrospectVerdict::class)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private RetrospectVerdict $retrospect = RetrospectVerdict::Unrated;

    /** How the category was set: by hand, by a rule, or not at all. */
    #[ORM\Column(length: 20, enumType: CategorySource::class)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private CategorySource $categorySource = CategorySource::None;

    #[ORM\Column(type: 'datetime_immutable', nullable: true)]
    #[IndexedField(type: 'date')]
    private ?\DateTimeImmutable $categorizedAt = null;

    /**
     * What kind of neutral movement this is — an internal transfer, a rejected
     * payment — if any. Anything but `None` is neither an expense nor an
     * income, and leaves every aggregate.
     *
     * Read-only over REST, like the two fields below: a merge-patch cannot say
     * whether a leg was left out or set to null, so it could unpair one side
     * and leave the other pointing at it. The write paths are the detection,
     * the catch-up endpoint and `manage_transactions`, which pair both legs.
     */
    #[ORM\Column(length: 20, enumType: TransferKind::class)]
    #[ApiProperty(writable: false)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private TransferKind $transferKind = TransferKind::None;

    /** Who decided it: the detection, or the user by hand. */
    #[ORM\Column(length: 20, enumType: TransferSource::class)]
    #[ApiProperty(writable: false)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private TransferSource $transferSource = TransferSource::Auto;

    /**
     * The other leg of the same movement. Null when only one of the two
     * accounts is known — the normal case when a single bank is synced.
     */
    #[ORM\ManyToOne(targetEntity: self::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[ApiProperty(writable: false)]
    #[IndexedRelation(targetEntity: self::class, sourceField: 'counterpartId')]
    private ?self $counterpart = null;

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

    public function getCounterpartyName(): ?string
    {
        return $this->counterpartyName;
    }

    public function getCounterpartyKey(): ?string
    {
        return $this->counterpartyKey;
    }

    /**
     * The one place the key is computed, so every writer — sync, import,
     * manual entry, backfill — groups a payee the same way.
     */
    public function setCounterpartyName(?string $name): static
    {
        $name = null === $name ? '' : mb_substr(trim(preg_replace('/\s+/u', ' ', $name) ?? $name), 0, 255);

        if ('' === $name) {
            $this->counterpartyName = null;
            $this->counterpartyKey = null;

            return $this;
        }

        $key = MerchantExtractor::key($name);

        $this->counterpartyName = $name;
        $this->counterpartyKey = '' === $key ? null : $key;

        return $this;
    }

    public function getExternalId(): ?string
    {
        return $this->externalId;
    }

    public function setExternalId(?string $externalId): static
    {
        $this->externalId = $externalId;

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

    public function getRetrospect(): RetrospectVerdict
    {
        return $this->retrospect;
    }

    public function setRetrospect(RetrospectVerdict $retrospect): static
    {
        $this->retrospect = $retrospect;

        return $this;
    }

    public function getCategorySource(): CategorySource
    {
        return $this->categorySource;
    }

    public function setCategorySource(CategorySource $categorySource): static
    {
        $this->categorySource = $categorySource;

        return $this;
    }

    public function getCategorizedAt(): ?\DateTimeImmutable
    {
        return $this->categorizedAt;
    }

    public function setCategorizedAt(?\DateTimeImmutable $categorizedAt): static
    {
        $this->categorizedAt = $categorizedAt;

        return $this;
    }

    /** Record who decided the category, stamping when it happened. */
    public function assignCategory(?Category $category, CategorySource $source): static
    {
        $this->category = $category;
        $this->categorySource = null === $category ? CategorySource::None : $source;
        $this->categorizedAt = null === $category ? null : new \DateTimeImmutable();

        return $this;
    }

    public function getTransferKind(): TransferKind
    {
        return $this->transferKind;
    }

    public function setTransferKind(TransferKind $transferKind): static
    {
        $this->transferKind = $transferKind;

        return $this;
    }

    public function getTransferSource(): TransferSource
    {
        return $this->transferSource;
    }

    public function setTransferSource(TransferSource $transferSource): static
    {
        $this->transferSource = $transferSource;

        return $this;
    }

    public function getCounterpart(): ?self
    {
        return $this->counterpart;
    }

    public function setCounterpart(?self $counterpart): static
    {
        $this->counterpart = $counterpart;

        return $this;
    }

    /** Not serialized: `transferKind` is the one truth a client reads. */
    #[ApiProperty(readable: false)]
    public function isInternalTransfer(): bool
    {
        return TransferKind::None !== $this->transferKind;
    }

    /**
     * Pairs the two legs of one movement, recording who decided it.
     *
     * Both sides carry the same judgement — a transfer is a single fact seen
     * twice — and whatever either side pointed at before is freed: a third
     * line left pointing at a line that disowns it would be excluded from
     * every aggregate for good, with nothing to justify it.
     */
    public function markAsInternalTransfer(self $counterpart, TransferSource $source): static
    {
        return $this->pairWith($counterpart, TransferKind::Internal, $source);
    }

    /**
     * Pairs a rejected payment with the credit that cancels it: the payment
     * did not happen, so neither line is an expense or an income.
     */
    public function markAsRejection(self $counterpart, TransferSource $source): static
    {
        return $this->pairWith($counterpart, TransferKind::Rejected, $source);
    }

    /** Both legs carry the same kind and the same source, pointing at each other. */
    public function pairWith(self $counterpart, TransferKind $kind, TransferSource $source): static
    {
        $this->unpair();
        $counterpart->unpair();

        $this->transferKind = $kind;
        $this->transferSource = $source;
        $this->counterpart = $counterpart;

        $counterpart->transferKind = $kind;
        $counterpart->transferSource = $source;
        $counterpart->counterpart = $this;

        return $this;
    }

    /** Takes this line out of the transfers, with who decided that. */
    public function releaseInternalTransfer(TransferSource $source): static
    {
        $this->unpair();
        $this->transferSource = $source;

        return $this;
    }

    /**
     * Frees the leg this one pointed at, which goes back to `auto`.
     *
     * The pair cannot come back — the line the user judged is sealed `manual`
     * and the detection skips it — but the leg in front may well belong to a
     * third line, and sealing it too would hide that pairing for ever.
     */
    private function unpair(): void
    {
        $former = $this->counterpart;

        $this->transferKind = TransferKind::None;
        $this->counterpart = null;

        if (null !== $former && $former->counterpart === $this) {
            $former->transferKind = TransferKind::None;
            $former->transferSource = TransferSource::Auto;
            $former->counterpart = null;
        }
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
            'counterpartyName' => $this->counterpartyName,
            'counterpartyKey' => $this->counterpartyKey,
            'amountCents' => $this->amountCents,
            'currency' => $this->currency,
            'bookedAt' => $this->bookedAt->format('Y-m-d'),
            'status' => $this->status->value,
            'isExceptional' => $this->isExceptional,
            'categorySource' => $this->categorySource->value,
            'retrospect' => $this->retrospect->value,
            'categorizedAt' => $this->categorizedAt?->format(\DateTimeInterface::ATOM),
            'transferKind' => $this->transferKind->value,
            'transferSource' => $this->transferSource->value,
            'accountId' => (string) $this->account->getId(),
            'categoryId' => null !== $this->category ? (string) $this->category->getId() : null,
            'counterpartId' => null !== $this->counterpart ? (string) $this->counterpart->getId() : null,
            'userId' => (string) $this->user->getId(),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'label' => $this->label,
            'counterpartyName' => $this->counterpartyName,
            'counterpartyKey' => $this->counterpartyKey,
            'amountCents' => $this->amountCents,
            'currency' => $this->currency,
            'bookedAt' => $this->bookedAt->format('Y-m-d'),
            'status' => $this->status->value,
            'isExceptional' => $this->isExceptional,
            'categorySource' => $this->categorySource->value,
            'retrospect' => $this->retrospect->value,
            'transferKind' => $this->transferKind->value,
            'transferSource' => $this->transferSource->value,
            'accountId' => (string) $this->account->getId(),
            'categoryId' => null !== $this->category ? (string) $this->category->getId() : null,
            'counterpartId' => null !== $this->counterpart ? (string) $this->counterpart->getId() : null,
        ], $changedProperties, [
            'account' => 'accountId',
            'category' => 'categoryId',
            'counterpart' => 'counterpartId',
        ]);
    }
}
