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
use Maggie\Finance\Enum\RecurringLinkSource;
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
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Normalizer\AbstractObjectNormalizer;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: TransactionRepository::class)]
#[ORM\Index(columns: ['user_id', 'transfer_kind'], name: 'idx_transaction_user_transfer_kind')]
#[ORM\Index(columns: ['user_id', 'counterparty_key'], name: 'idx_transaction_user_counterparty_key')]
#[ORM\Index(columns: ['account_id', 'external_id'], name: 'idx_transaction_account_external_id')]
// One transaction per occurrence: the database refuses a second one, the
// handlers say so first (422) so this is never what the user meets.
#[ORM\UniqueConstraint(name: 'uniq_transaction_recurring_occurrence', columns: ['recurring_operation_id', 'recurring_occurrence_on'])]
#[ApiFilter(OrderFilter::class, properties: ['bookedAt'])]
#[ApiFilter(UlidRelationFilter::class, properties: ['account', 'category', 'recurringOperation', 'qualifiedBySeries', 'qualifiedByRule'])]
// The list leaves the rejected payments out unless `transferKind` names a kind (TransactionCollectionProvider).
#[ApiFilter(SearchFilter::class, properties: ['transferKind' => 'exact'])]
#[Indexed(index: 'transactions', module: 'finance', globalSearch: false)]
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

    public const int NOTE_MAX_LENGTH = 2000;

    // The admin sends a record back with `id` set to its IRI; the id is never
    // writable, so it must not be parsed as a Ulid on the way in.
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
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

    /**
     * Who set the qualification — the category with it: by hand, a rule, the
     * series, or nobody. Read over REST as `qualifiedBy`, which names the
     * operation too.
     */
    #[ORM\Column(length: 20, enumType: CategorySource::class)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private CategorySource $categorySource = CategorySource::None;

    /**
     * Whether the line is mandatory and what kind of movement it is, copied
     * whole from the operation that recognised it, or set by hand. Its
     * category is `category` above (see `Qualification`).
     */
    #[ORM\Embedded(class: Qualification::class)]
    #[IndexedField(type: 'object', properties: Qualification::SEARCH_MAPPING)]
    // Built anew from the body, never merged into the stored one: a PATCH
    // naming one parameter and not the other is refused, not completed.
    #[ApiProperty(genId: false)]
    #[Context(denormalizationContext: [AbstractObjectNormalizer::DEEP_OBJECT_TO_POPULATE => false])]
    private Qualification $qualification;

    /** The operation with a recurrence that qualified this line, while `categorySource` is `series`. */
    #[ORM\ManyToOne(targetEntity: RecurringOperation::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[ApiProperty(writable: false)]
    #[IndexedRelation(targetEntity: RecurringOperation::class, sourceField: 'qualifiedBySeriesId')]
    private ?RecurringOperation $qualifiedBySeries = null;

    /**
     * The operation without recurrence — what a categorization rule was — that
     * qualified this line, while `categorySource` is `rule`.
     */
    #[ORM\ManyToOne(targetEntity: RecurringOperation::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[ApiProperty(writable: false)]
    #[IndexedRelation(targetEntity: RecurringOperation::class, sourceField: 'qualifiedByRuleId')]
    private ?RecurringOperation $qualifiedByRule = null;

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

    /**
     * When the owner said this rejected payment was dealt with (paid again,
     * by another means), so it leaves the account's incidents. Carried by
     * both legs, like the marking; it goes with the marking.
     */
    #[ORM\Column(type: 'datetime_immutable', nullable: true)]
    #[ApiProperty(writable: false)]
    #[IndexedField(type: 'date')]
    private ?\DateTimeImmutable $rejectionSettledAt = null;

    /**
     * The advance this line gives back: the debit leg of a repayment points at
     * the debit leg of the advance. What is left to give back is never stored —
     * it is the advance minus the lines pointing at it.
     */
    #[ORM\ManyToOne(targetEntity: self::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[ApiProperty(writable: false)]
    #[IndexedRelation(targetEntity: self::class, sourceField: 'repaysId')]
    private ?self $repays = null;

    /**
     * The recurring operation this line is one occurrence of. Written over
     * REST by a PATCH that names it with `recurringOccurrenceOn`, which the
     * processor turns into a manual attachment: the setters below only exist
     * for the deserializer, the domain goes through `attachToRecurring()`.
     */
    #[ORM\ManyToOne(targetEntity: RecurringOperation::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[IndexedRelation(targetEntity: RecurringOperation::class, sourceField: 'recurringOperationId')]
    private ?RecurringOperation $recurringOperation = null;

    /** The due date of the occurrence this line settles, not its booking day. */
    #[ORM\Column(type: 'date_immutable', nullable: true)]
    #[IndexedField(type: 'date')]
    private ?\DateTimeImmutable $recurringOccurrenceOn = null;

    /** Who attached or detached it: the automatic attachment leaves `manual` alone for good. */
    #[ORM\Column(length: 20, enumType: RecurringLinkSource::class, options: ['default' => 'auto'])]
    #[ApiProperty(writable: false)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private RecurringLinkSource $recurringSource = RecurringLinkSource::Auto;

    /**
     * The one-off envelope (Hellfest 2026) this spend was attributed to by
     * hand. It keeps its category but leaves the monthly and annual envelopes.
     */
    #[ORM\ManyToOne(targetEntity: Envelope::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[IndexedRelation(targetEntity: Envelope::class, sourceField: 'oneOffEnvelopeId')]
    private ?Envelope $oneOffEnvelope = null;

    /** What the owner wants to remember about this line; blank means none. */
    #[ORM\Column(type: 'text', nullable: true)]
    #[Assert\Length(max: self::NOTE_MAX_LENGTH)]
    #[IndexedField(type: 'text')]
    private ?string $note = null;

    /**
     * The person this transfer comes from or goes to, recognised by one of
     * their counterparties (MAG-450). Read-only over REST: it is derived from
     * the label, on every write and when a counterparty is linked or removed.
     */
    #[ORM\ManyToOne(targetEntity: Contact::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[ApiProperty(writable: false)]
    #[IndexedRelation(targetEntity: Contact::class, sourceField: 'contactId')]
    private ?Contact $contact = null;

    /**
     * The owner's own part of a shared spend, as positive cents (MAG-451):
     * the gîte cost 800 €, my part is 200 €. Only it counts in the envelopes,
     * the score, the review and the lifestyle (`getOwnAmountCents`); the rest
     * is a créance (`PeerDebt`). Null when the spend is not shared.
     *
     * Read-only over REST: it goes with its créance, through
     * `PUT /api/finance/transactions/{id}/my-share`.
     */
    #[ORM\Column(type: 'integer', nullable: true)]
    #[ApiProperty(writable: false)]
    #[IndexedField(type: 'long')]
    private ?int $myShareCents = null;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->bookedAt = new \DateTimeImmutable();
        $this->qualification = Qualification::none();
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

    /**
     * Whether a satisfaction can be given: a spend only — a recipe or a
     * movement between the owner's own accounts is neutral, there is nothing
     * to judge. Not serialized: the clients read the sign and `transferKind`.
     */
    #[ApiProperty(readable: false)]
    public function canBeJudged(): bool
    {
        return $this->amountCents < 0 && TransferKind::None === $this->transferKind;
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

    /** The whole qualification, its category included. */
    public function getQualification(): Qualification
    {
        return $this->qualification->withCategory($this->category);
    }

    /**
     * For the deserializer only: the processor turns a changed qualification
     * into `qualify(…, null)`, the owner's own. The category travels apart,
     * as `category`.
     */
    public function setQualification(Qualification $qualification): static
    {
        $this->qualification = $qualification->withCategory(null);

        return $this;
    }

    /**
     * Copies a qualification whole onto this line — never field by field —
     * recording who set it: the operation that recognised the line, or the
     * owner by hand (`null`). A qualification by hand that says nothing (no
     * category, every parameter at its default) leaves the line unqualified,
     * free for the rules again.
     */
    public function qualify(Qualification $qualification, ?RecurringOperation $by): static
    {
        $this->category = $qualification->getCategory();
        $this->qualification = $qualification->withCategory(null);
        $this->qualifiedBySeries = true === $by?->hasRecurrence() ? $by : null;
        $this->qualifiedByRule = false === $by?->hasRecurrence() ? $by : null;
        $this->categorySource = match (true) {
            null !== $this->qualifiedBySeries => CategorySource::Series,
            null !== $this->qualifiedByRule => CategorySource::Rule,
            $qualification->carriesNothing() => CategorySource::None,
            default => CategorySource::Manual,
        };
        $this->categorizedAt = null === $this->category ? null : new \DateTimeImmutable();

        return $this;
    }

    /** Takes over the qualification of another line, with who set it: the twin a merge keeps. */
    public function takeQualificationOf(self $other): static
    {
        $this->category = $other->category;
        $this->qualification = $other->qualification;
        $this->categorySource = $other->categorySource;
        $this->qualifiedBySeries = $other->qualifiedBySeries;
        $this->qualifiedByRule = $other->qualifiedByRule;
        $this->categorizedAt = $other->categorizedAt;

        return $this;
    }

    /**
     * Who set the qualification: `manual`, `series` (`qualifiedBySeries`
     * names it), `rule` (`qualifiedByRule`), or null when nobody did.
     */
    #[ApiProperty(writable: false)]
    public function getQualifiedBy(): ?string
    {
        return CategorySource::None === $this->categorySource ? null : $this->categorySource->value;
    }

    public function getQualifiedBySeries(): ?RecurringOperation
    {
        return $this->qualifiedBySeries;
    }

    public function getQualifiedByRule(): ?RecurringOperation
    {
        return $this->qualifiedByRule;
    }

    /** Whether the owner qualified this line by hand: no operation requalifies it. Not serialized: `qualifiedBy` says it. */
    #[ApiProperty(readable: false)]
    public function isQualifiedByHand(): bool
    {
        return CategorySource::Manual === $this->categorySource;
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

    public function getRepays(): ?self
    {
        return $this->repays;
    }

    public function setRepays(?self $repays): static
    {
        $this->repays = $repays;

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

    public function getRejectionSettledAt(): ?\DateTimeImmutable
    {
        return $this->rejectionSettledAt;
    }

    /**
     * Says the rejected payment was dealt with, on both legs; null takes it
     * back to the pile.
     *
     * @throws \DomainException when this line is not a rejected payment
     */
    public function settleRejection(?\DateTimeImmutable $settledAt): static
    {
        if (TransferKind::Rejected !== $this->transferKind) {
            throw new \DomainException('This transaction is not a rejected payment: only a rejection is settled.');
        }

        $this->rejectionSettledAt = $settledAt;
        if (null !== $this->counterpart && TransferKind::Rejected === $this->counterpart->transferKind) {
            $this->counterpart->rejectionSettledAt = $settledAt;
        }

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
        // A line that is no longer a repayment gives nothing back, nor a rejection anything settled.
        $this->repays = null;
        $this->rejectionSettledAt = null;

        if (null !== $former && $former->counterpart === $this) {
            $former->transferKind = TransferKind::None;
            $former->transferSource = TransferSource::Auto;
            $former->counterpart = null;
            $former->repays = null;
            $former->rejectionSettledAt = null;
        }
    }

    public function getRecurringOperation(): ?RecurringOperation
    {
        return $this->recurringOperation;
    }

    /** For the deserializer only: see `attachToRecurring()`. */
    public function setRecurringOperation(?RecurringOperation $recurringOperation): static
    {
        $this->recurringOperation = $recurringOperation;

        return $this;
    }

    public function getRecurringOccurrenceOn(): ?\DateTimeImmutable
    {
        return $this->recurringOccurrenceOn;
    }

    /** For the deserializer only: see `attachToRecurring()`. */
    public function setRecurringOccurrenceOn(?\DateTimeImmutable $recurringOccurrenceOn): static
    {
        $this->recurringOccurrenceOn = $recurringOccurrenceOn?->setTime(0, 0);

        return $this;
    }

    public function getRecurringSource(): RecurringLinkSource
    {
        return $this->recurringSource;
    }

    /**
     * Makes this line the given occurrence of a series, recording who decided
     * it. The series' qualification comes with it, as a rule's would, unless
     * the owner qualified the line by hand: the transaction's qualification is
     * the truth, the series' only a default.
     */
    public function attachToRecurring(RecurringOperation $operation, \DateTimeImmutable $occurrenceOn, RecurringLinkSource $source): static
    {
        $this->recurringOperation = $operation;
        $this->recurringOccurrenceOn = $occurrenceOn->setTime(0, 0);
        $this->recurringSource = $source;

        if (!$this->isQualifiedByHand()) {
            $this->qualify($operation->getQualification(), $operation);
        }

        return $this;
    }

    /** Takes this line out of its series; the qualification it inherited stays. */
    public function detachFromRecurring(RecurringLinkSource $source): static
    {
        $this->recurringOperation = null;
        $this->recurringOccurrenceOn = null;
        $this->recurringSource = $source;

        return $this;
    }

    public function getNote(): ?string
    {
        return $this->note;
    }

    /** The REST door is validated before it gets here; the MCP tool is not. */
    public static function assertNoteFits(?string $note): void
    {
        if (null !== $note && mb_strlen($note) > self::NOTE_MAX_LENGTH) {
            throw new \DomainException(sprintf('note is at most %d characters.', self::NOTE_MAX_LENGTH));
        }
    }

    public function setNote(?string $note): static
    {
        $this->note = null === $note || '' === trim($note) ? null : $note;

        return $this;
    }

    public function getOneOffEnvelope(): ?Envelope
    {
        return $this->oneOffEnvelope;
    }

    public function setOneOffEnvelope(?Envelope $oneOffEnvelope): static
    {
        $this->oneOffEnvelope = $oneOffEnvelope;

        return $this;
    }

    public function getContact(): ?Contact
    {
        return $this->contact;
    }

    public function setContact(?Contact $contact): static
    {
        $this->contact = $contact;

        return $this;
    }

    public function getMyShareCents(): ?int
    {
        return $this->myShareCents;
    }

    /** @throws \DomainException when the line is not a spend, or the part not below its amount */
    public function setMyShareCents(?int $myShareCents): static
    {
        if (null !== $myShareCents) {
            $this->assertCanKeep($myShareCents);
        }

        $this->myShareCents = $myShareCents;

        return $this;
    }

    /** @throws \DomainException when the line is not a spend, or the part not below its amount */
    public function assertCanKeep(int $myShareCents): void
    {
        if (!$this->canBeShared()) {
            throw new \DomainException('Only a spend can be shared: not an income, an internal transfer or a rejection.');
        }
        if ($myShareCents < 0 || $myShareCents >= abs($this->amountCents)) {
            throw new \DomainException(sprintf('myShareCents must be between 0 and %d.', abs($this->amountCents) - 1));
        }
    }

    /** A spend that is neither a transfer between the owner's accounts nor a rejection. Not serialized. */
    #[ApiProperty(readable: false)]
    public function canBeShared(): bool
    {
        return $this->amountCents < 0 && TransferKind::None === $this->transferKind;
    }

    /** @throws \DomainException when the line is not a received transfer, or the amount to give back not within it */
    public function assertCanOwe(int $amountCents): void
    {
        if (!$this->canBeDebt()) {
            throw new \DomainException('Only a received transfer can be declared as a dette: not a spend, an internal transfer or a rejection.');
        }
        if ($amountCents <= 0 || $amountCents > $this->amountCents) {
            throw new \DomainException(sprintf('amountCents must be between 1 and %d.', $this->amountCents));
        }
    }

    /** An income that is neither a transfer between the owner's accounts nor a rejection. Not serialized. */
    #[ApiProperty(readable: false)]
    public function canBeDebt(): bool
    {
        return $this->amountCents > 0 && TransferKind::None === $this->transferKind;
    }

    /**
     * The signed amount that is the owner's own: the part they kept of a
     * shared spend, the whole amount otherwise. What every budget figure
     * reads; `TransactionRepository::OWN_AMOUNT` is its DQL twin.
     */
    #[ApiProperty(readable: false)]
    public function getOwnAmountCents(): int
    {
        return null === $this->myShareCents ? $this->amountCents : -$this->myShareCents;
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
            'qualification' => $this->qualification->toArray(),
            'qualifiedBySeriesId' => null !== $this->qualifiedBySeries ? (string) $this->qualifiedBySeries->getId() : null,
            'qualifiedByRuleId' => null !== $this->qualifiedByRule ? (string) $this->qualifiedByRule->getId() : null,
            'retrospect' => $this->retrospect->value,
            'categorizedAt' => $this->categorizedAt?->format(\DateTimeInterface::ATOM),
            'transferKind' => $this->transferKind->value,
            'transferSource' => $this->transferSource->value,
            'accountId' => (string) $this->account->getId(),
            'categoryId' => null !== $this->category ? (string) $this->category->getId() : null,
            'counterpartId' => null !== $this->counterpart ? (string) $this->counterpart->getId() : null,
            'rejectionSettledAt' => $this->rejectionSettledAt?->format(\DateTimeInterface::ATOM),
            'repaysId' => null !== $this->repays ? (string) $this->repays->getId() : null,
            'recurringOperationId' => null !== $this->recurringOperation ? (string) $this->recurringOperation->getId() : null,
            'recurringOccurrenceOn' => $this->recurringOccurrenceOn?->format('Y-m-d'),
            'recurringSource' => $this->recurringSource->value,
            'note' => $this->note,
            'oneOffEnvelopeId' => null !== $this->oneOffEnvelope ? (string) $this->oneOffEnvelope->getId() : null,
            'contactId' => null !== $this->contact ? (string) $this->contact->getId() : null,
            'myShareCents' => $this->myShareCents,
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
            'qualification' => $this->qualification->toArray(),
            'qualifiedBy' => $this->getQualifiedBy(),
            'qualifiedBySeriesId' => null !== $this->qualifiedBySeries ? (string) $this->qualifiedBySeries->getId() : null,
            'qualifiedByRuleId' => null !== $this->qualifiedByRule ? (string) $this->qualifiedByRule->getId() : null,
            'retrospect' => $this->retrospect->value,
            'transferKind' => $this->transferKind->value,
            'transferSource' => $this->transferSource->value,
            'accountId' => (string) $this->account->getId(),
            'categoryId' => null !== $this->category ? (string) $this->category->getId() : null,
            'counterpartId' => null !== $this->counterpart ? (string) $this->counterpart->getId() : null,
            'rejectionSettledAt' => $this->rejectionSettledAt?->format(\DateTimeInterface::ATOM),
            'repaysId' => null !== $this->repays ? (string) $this->repays->getId() : null,
            'recurringOperationId' => null !== $this->recurringOperation ? (string) $this->recurringOperation->getId() : null,
            'recurringOccurrenceOn' => $this->recurringOccurrenceOn?->format('Y-m-d'),
            'recurringSource' => $this->recurringSource->value,
            'note' => $this->note,
            'oneOffEnvelopeId' => null !== $this->oneOffEnvelope ? (string) $this->oneOffEnvelope->getId() : null,
            'contactId' => null !== $this->contact ? (string) $this->contact->getId() : null,
            'myShareCents' => $this->myShareCents,
        ], self::withDerivedProperties($changedProperties), [
            ...Qualification::changedPropertyMap(),
            'qualifiedBySeries' => 'qualifiedBySeriesId',
            'qualifiedByRule' => 'qualifiedByRuleId',
            'account' => 'accountId',
            'category' => 'categoryId',
            'counterpart' => 'counterpartId',
            'repays' => 'repaysId',
            'recurringOperation' => 'recurringOperationId',
            'oneOffEnvelope' => 'oneOffEnvelopeId',
            'contact' => 'contactId',
        ]);
    }

    /**
     * `qualifiedBy` is read off `categorySource`: a differential update that
     * changes the one must carry the other.
     *
     * @param string[]|null $changedProperties
     *
     * @return string[]|null
     */
    private static function withDerivedProperties(?array $changedProperties): ?array
    {
        if (null === $changedProperties || !\in_array('categorySource', $changedProperties, true)) {
            return $changedProperties;
        }

        return [...$changedProperties, 'qualifiedBy'];
    }
}
