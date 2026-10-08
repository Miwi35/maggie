<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

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
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\DayRule;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\ReferenceAmountSource;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Service\MatchCriteria;
use Maggie\Finance\State\CreateRecurringOperationProcessor;
use Maggie\Finance\State\DeleteRecurringOperationProcessor;
use Maggie\Finance\State\UpdateRecurringOperationProcessor;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

/**
 * A series the owner expects to see again — rent on the 5th, Netflix on the
 * 12th. Abstract: its occurrences are computed by `RecurrenceSchedule`, never
 * written down, and the real transactions stay what they are.
 *
 * It recognises its transactions through the engine the categorization rules
 * use, with the expected counterparty as the main criterion.
 */
#[ORM\Entity(repositoryClass: RecurringOperationRepository::class)]
#[Indexed(index: 'recurring_operations', module: 'finance')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateRecurringOperationProcessor::class),
    new Patch(processor: UpdateRecurringOperationProcessor::class),
    new Delete(processor: DeleteRecurringOperationProcessor::class),
])]
class RecurringOperation implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use MercurePayloadFilterTrait;

    public const int DEFAULT_AMOUNT_TOLERANCE_PERCENT = 20;
    public const int DEFAULT_DATE_TOLERANCE_DAYS = 5;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    #[Assert\Length(max: 255)]
    #[IndexedField(type: 'text', boost: 2.0, keyword: true)]
    private string $label;

    /** The payee of an expense, the payer of an income: who the bank names. */
    #[ORM\Column(length: 255, nullable: true)]
    #[Assert\Length(max: 255)]
    #[IndexedField(type: 'text', keyword: true)]
    private ?string $counterpartyName = null;

    /** Derived from the name, like a transaction's, so the two compare. */
    #[ORM\Column(length: 255, nullable: true)]
    #[ApiProperty(writable: false)]
    #[IndexedField(type: 'keyword')]
    private ?string $counterpartyKey = null;

    /** Read in the label when there is no counterparty to compare. */
    #[ORM\Column(length: 255, nullable: true)]
    #[Assert\Length(max: 255)]
    #[IndexedField(type: 'text', keyword: true)]
    private ?string $labelPattern = null;

    /** The default category, inherited by the transactions attached to it. */
    #[ORM\ManyToOne(targetEntity: Category::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Assert\NotNull]
    #[IndexedRelation(targetEntity: Category::class, sourceField: 'categoryId')]
    private Category $category;

    /** Its currency is the operation's: there is no other. */
    #[ORM\ManyToOne(targetEntity: Account::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Assert\NotNull]
    #[IndexedRelation(targetEntity: Account::class, sourceField: 'accountId')]
    private Account $account;

    #[ORM\Column(length: 20, enumType: RecurrencePeriod::class)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private RecurrencePeriod $period = RecurrencePeriod::Monthly;

    /** The first occurrence; every other one is counted from it, never from the previous. */
    #[ORM\Column(type: 'date_immutable')]
    #[Assert\NotNull]
    #[IndexedField(type: 'date')]
    private \DateTimeImmutable $anchorOn;

    #[ORM\Column(length: 20, enumType: DayRule::class)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private DayRule $dayRule = DayRule::FixedDay;

    /** Signed cents: negative is an expense, positive an income. */
    #[ORM\Column(type: 'integer')]
    #[Assert\NotEqualTo(0, message: 'The reference amount cannot be zero: negative for an expense, positive for an income.')]
    #[IndexedField(type: 'long')]
    private int $referenceAmountCents = 0;

    #[ORM\Column(length: 20, enumType: ReferenceAmountSource::class)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private ReferenceAmountSource $referenceSource = ReferenceAmountSource::Measured;

    #[ORM\Column(type: 'integer')]
    #[Assert\Range(min: 0, max: 100)]
    #[IndexedField(type: 'integer')]
    private int $amountTolerancePercent = self::DEFAULT_AMOUNT_TOLERANCE_PERCENT;

    #[ORM\Column(type: 'integer')]
    #[Assert\Range(min: 0, max: 31)]
    #[IndexedField(type: 'integer')]
    private int $dateToleranceDays = self::DEFAULT_DATE_TOLERANCE_DAYS;

    /** The last day an occurrence may fall on; null while the series runs. */
    #[ORM\Column(type: 'date_immutable', nullable: true)]
    #[IndexedField(type: 'date')]
    private ?\DateTimeImmutable $endsOn = null;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    #[Assert\Callback]
    public function validateSeries(ExecutionContextInterface $context): void
    {
        if (null !== $this->endsOn && isset($this->anchorOn) && $this->endsOn < $this->anchorOn) {
            $context->buildViolation('The series cannot end before its first occurrence.')
                ->atPath('endsOn')
                ->addViolation();
        }

        if (null === $this->counterpartyKey && null === $this->labelPattern) {
            $context->buildViolation('A recurring operation needs a counterparty or a label pattern to recognise its transactions.')
                ->atPath('counterpartyName')
                ->addViolation();
        }
    }

    public function getId(): Ulid
    {
        return $this->id;
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

    /** Computes the key the way `Transaction::setCounterpartyName()` does. */
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

    public function getLabelPattern(): ?string
    {
        return $this->labelPattern;
    }

    public function setLabelPattern(?string $labelPattern): static
    {
        $labelPattern = null === $labelPattern ? null : trim($labelPattern);
        $this->labelPattern = '' === $labelPattern ? null : $labelPattern;

        return $this;
    }

    public function getCategory(): Category
    {
        return $this->category;
    }

    public function setCategory(Category $category): static
    {
        $this->category = $category;

        return $this;
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

    public function getPeriod(): RecurrencePeriod
    {
        return $this->period;
    }

    public function setPeriod(RecurrencePeriod $period): static
    {
        $this->period = $period;

        return $this;
    }

    public function getAnchorOn(): \DateTimeImmutable
    {
        return $this->anchorOn;
    }

    public function setAnchorOn(\DateTimeImmutable $anchorOn): static
    {
        $this->anchorOn = $anchorOn->setTime(0, 0);

        return $this;
    }

    public function getDayRule(): DayRule
    {
        return $this->dayRule;
    }

    public function setDayRule(DayRule $dayRule): static
    {
        $this->dayRule = $dayRule;

        return $this;
    }

    public function getReferenceAmountCents(): int
    {
        return $this->referenceAmountCents;
    }

    public function setReferenceAmountCents(int $referenceAmountCents): static
    {
        $this->referenceAmountCents = $referenceAmountCents;

        return $this;
    }

    public function getReferenceSource(): ReferenceAmountSource
    {
        return $this->referenceSource;
    }

    public function setReferenceSource(ReferenceAmountSource $referenceSource): static
    {
        $this->referenceSource = $referenceSource;

        return $this;
    }

    public function getAmountTolerancePercent(): int
    {
        return $this->amountTolerancePercent;
    }

    public function setAmountTolerancePercent(int $amountTolerancePercent): static
    {
        $this->amountTolerancePercent = $amountTolerancePercent;

        return $this;
    }

    public function getDateToleranceDays(): int
    {
        return $this->dateToleranceDays;
    }

    public function setDateToleranceDays(int $dateToleranceDays): static
    {
        $this->dateToleranceDays = $dateToleranceDays;

        return $this;
    }

    public function getEndsOn(): ?\DateTimeImmutable
    {
        return $this->endsOn;
    }

    public function setEndsOn(?\DateTimeImmutable $endsOn): static
    {
        $this->endsOn = $endsOn?->setTime(0, 0);

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

    /** Whether an occurrence may fall on this day: from the anchor to the end, both included. */
    public function isActiveOn(\DateTimeImmutable $day): bool
    {
        $day = $day->format('Y-m-d');

        if ($day < $this->anchorOn->format('Y-m-d')) {
            return false;
        }

        return null === $this->endsOn || $day <= $this->endsOn->format('Y-m-d');
    }

    /**
     * Whether an amount is one this series expects: the same sign as the
     * reference, and within the tolerance of it. Integer arithmetic only.
     */
    public function acceptsAmount(int $amountCents): bool
    {
        if (0 === $amountCents || ($amountCents > 0) !== ($this->referenceAmountCents > 0)) {
            return false;
        }

        return abs($amountCents - $this->referenceAmountCents) * 100
            <= abs($this->referenceAmountCents) * $this->amountTolerancePercent;
    }

    /** What the series costs (negative) or brings in (positive) a month, in cents. */
    public function getMonthlyCostCents(): int
    {
        return intdiv($this->getYearlyCostCents(), 12);
    }

    /** What the series costs (negative) or brings in (positive) a year, in cents. */
    public function getYearlyCostCents(): int
    {
        return $this->referenceAmountCents * $this->period->perYear();
    }

    /**
     * What the shared engine recognises its transactions by: the expected
     * counterparty, else the label pattern, in the direction of the reference
     * amount. The amount tolerance is not part of it: `acceptsAmount()` and
     * the date window are conditions of attaching, not of recognising.
     */
    public function matchCriteria(): MatchCriteria
    {
        return new MatchCriteria(
            counterpartyKey: $this->counterpartyKey,
            labelPattern: $this->labelPattern,
            matchType: MatchType::Contains,
            direction: $this->referenceAmountCents < 0 ? AmountDirection::Debit : AmountDirection::Credit,
        );
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'label' => $this->label,
            'counterpartyName' => $this->counterpartyName,
            'counterpartyKey' => $this->counterpartyKey,
            'labelPattern' => $this->labelPattern,
            'period' => $this->period->value,
            'anchorOn' => $this->anchorOn->format('Y-m-d'),
            'dayRule' => $this->dayRule->value,
            'referenceAmountCents' => $this->referenceAmountCents,
            'referenceSource' => $this->referenceSource->value,
            'amountTolerancePercent' => $this->amountTolerancePercent,
            'dateToleranceDays' => $this->dateToleranceDays,
            'endsOn' => $this->endsOn?->format('Y-m-d'),
            'categoryId' => (string) $this->category->getId(),
            'accountId' => (string) $this->account->getId(),
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
            'labelPattern' => $this->labelPattern,
            'period' => $this->period->value,
            'anchorOn' => $this->anchorOn->format('Y-m-d'),
            'dayRule' => $this->dayRule->value,
            'referenceAmountCents' => $this->referenceAmountCents,
            'referenceSource' => $this->referenceSource->value,
            'amountTolerancePercent' => $this->amountTolerancePercent,
            'dateToleranceDays' => $this->dateToleranceDays,
            'endsOn' => $this->endsOn?->format('Y-m-d'),
            'monthlyCostCents' => $this->getMonthlyCostCents(),
            'yearlyCostCents' => $this->getYearlyCostCents(),
            'categoryId' => (string) $this->category->getId(),
            'accountId' => (string) $this->account->getId(),
        ], $changedProperties, ['category' => 'categoryId', 'account' => 'accountId']);
    }
}
