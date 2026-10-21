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
use Doctrine\DBAL\Types\Types;
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
use Maggie\Core\Serializer\StrictDayNormalizer;
use Maggie\Finance\Enum\LoanPeriodicity;
use Maggie\Finance\Enum\LoanType;
use Maggie\Finance\Enum\PartialRepaymentEffect;
use Maggie\Finance\Enum\PrepaymentPenaltyMode;
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\State\CreateLoanProcessor;
use Maggie\Finance\State\DeleteLoanProcessor;
use Maggie\Finance\State\UpdateLoanProcessor;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Normalizer\AbstractObjectNormalizer;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

/**
 * A loan being repaid, or one a project would take out (`projected`). Its end
 * date is never stored: it falls out of the capital, the payment, the rate and
 * the type, and a stored one would contradict them at the first early
 * repayment.
 */
#[ORM\Entity(repositoryClass: LoanRepository::class)]
#[Indexed(index: 'loans', module: 'finance')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateLoanProcessor::class),
    new Patch(processor: UpdateLoanProcessor::class),
    new Delete(processor: DeleteLoanProcessor::class),
])]
class Loan implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use MercurePayloadFilterTrait;

    /** The optional conditions a PATCH or `clear` may empty (MAG-522). */
    public const CLEARABLE_FIELDS = [
        'lender', 'initialAmountCents', 'aprBasisPoints', 'durationMonths', 'firstPaymentOn',
        'insuranceRateBasisPoints', 'insuranceMonthlyCents', 'feesCents', 'referenceIndex',
        'marginBasisPoints', 'rateCapBasisPoints', 'rateFloorBasisPoints', 'steps', 'deferralMonths',
        'prepaymentPenaltyFixedCents', 'minPartialRepaymentCents', 'partialRepaymentEffect', 'noticeDays',
    ];

    // The admin sends the record back with its IRI as `id`: never denormalized.
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    #[IndexedField(type: 'text', boost: 2.0, keyword: true)]
    private string $name;

    #[ORM\Column(length: 255, nullable: true)]
    #[IndexedField(type: 'text', keyword: true)]
    private ?string $lender = null;

    /** Capital still owed, in cents. */
    #[ORM\Column(type: 'integer')]
    #[Assert\PositiveOrZero]
    #[IndexedField(type: 'long')]
    private int $principalRemainingCents = 0;

    #[ORM\Column(type: 'integer')]
    #[Assert\Positive]
    #[IndexedField(type: 'long')]
    private int $monthlyPaymentCents = 0;

    /** Annual nominal rate in basis points: 350 is 3.50 %. Never a float. */
    #[ORM\Column(type: 'integer')]
    #[Assert\Range(min: 0, max: 5000)]
    #[IndexedField(type: 'integer')]
    private int $annualRateBasisPoints = 0;

    /** Which loan the user would rather see gone first. */
    #[ORM\Column(type: 'integer')]
    #[IndexedField(type: 'integer')]
    private int $priority = 0;

    #[ORM\Column(length: 3)]
    #[Assert\NotBlank]
    #[Assert\Length(exactly: 3)]
    #[Assert\Regex(pattern: '/^[A-Z]{3}$/', message: 'The currency must be a 3-letter ISO 4217 code.')]
    #[IndexedField(type: 'keyword')]
    private string $currency = 'EUR';

    #[ORM\Column(length: 24, enumType: LoanType::class, options: ['default' => 'amortizing_fixed'])]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(description: 'amortizing_fixed (default), amortizing_variable, in_fine, stepped, deferred_partial, deferred_total or bridge: decides the schedule.')]
    private LoanType $type = LoanType::AmortizingFixed;

    #[ORM\Column(options: ['default' => false])]
    #[IndexedField(type: 'boolean')]
    #[ApiProperty(description: 'A loan a project would take out, not signed: left out of the debt timeline, the saving capacity, the independence counter and the dashboard.')]
    private bool $projected = false;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'long')]
    #[ApiProperty(description: 'The amount borrowed, in cents.')]
    private ?int $initialAmountCents = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'integer')]
    #[ApiProperty(description: 'The APR (TAEG) in basis points, as the offer states it.')]
    private ?int $aprBasisPoints = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'integer')]
    #[ApiProperty(description: 'The whole duration in months, from the first payment. Required by in_fine and bridge.')]
    private ?int $durationMonths = null;

    // A day, `Y-m-d` both ways (see Project::$targetOn).
    #[ORM\Column(type: Types::DATE_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date', format: 'yyyy-MM-dd')]
    #[ApiProperty(
        description: 'The first payment, YYYY-MM-DD: places today within the schedule (deferral, steps, last in fine payment). Empty: the next payment is the first.',
        openapiContext: ['type' => 'string', 'format' => 'date', 'nullable' => true, 'example' => '2024-02-05'],
    )]
    #[Context(
        normalizationContext: [DateTimeNormalizer::FORMAT_KEY => 'Y-m-d', AbstractObjectNormalizer::SKIP_NULL_VALUES => false],
        denormalizationContext: [DateTimeNormalizer::FORMAT_KEY => StrictDayNormalizer::DAY_FORMAT],
    )]
    private ?\DateTimeImmutable $firstPaymentOn = null;

    #[ORM\Column(length: 16, enumType: LoanPeriodicity::class, options: ['default' => 'monthly'])]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(description: 'monthly, quarterly, semiannual or annual, as the offer says. The schedule stays monthly: monthlyPaymentCents is the monthly equivalent.')]
    private LoanPeriodicity $periodicity = LoanPeriodicity::Monthly;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'integer')]
    #[ApiProperty(description: 'Borrower insurance as a yearly rate of the amount borrowed, in basis points. Needs initialAmountCents; insuranceMonthlyCents wins over it.')]
    private ?int $insuranceRateBasisPoints = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'long')]
    #[ApiProperty(description: 'Borrower insurance per month, in cents, on top of monthlyPaymentCents.')]
    private ?int $insuranceMonthlyCents = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'long')]
    #[ApiProperty(description: 'Application and guarantee fees, in cents.')]
    private ?int $feesCents = null;

    #[ORM\Column(length: 64, nullable: true)]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(description: 'The index a variable rate follows (Euribor 3M…). Required by amortizing_variable; recorded, not followed.')]
    private ?string $referenceIndex = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'integer')]
    #[ApiProperty(description: 'The margin over the index, in basis points. Required by amortizing_variable.')]
    private ?int $marginBasisPoints = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'integer')]
    #[ApiProperty(description: 'The highest rate a variable loan can reach, in basis points.')]
    private ?int $rateCapBasisPoints = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'integer')]
    #[ApiProperty(description: 'The lowest rate a variable loan can fall to, in basis points.')]
    private ?int $rateFloorBasisPoints = null;

    /** @var list<array{fromMonth: int, monthlyPaymentCents: int}>|null */
    #[ORM\Column(type: Types::JSON, nullable: true)]
    #[IndexedField(type: 'object', properties: [
        'fromMonth' => ['type' => 'integer'],
        'monthlyPaymentCents' => ['type' => 'long'],
    ])]
    #[ApiProperty(description: 'The steps of a stepped loan: [{fromMonth, monthlyPaymentCents}], fromMonth counted from 1 at the first payment. Required by stepped.')]
    private ?array $steps = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'integer')]
    #[ApiProperty(description: 'Months of deferral from the first payment. Required, above 0, by deferred_partial and deferred_total.')]
    private ?int $deferralMonths = null;

    #[ORM\Column(options: ['default' => true])]
    #[IndexedField(type: 'boolean')]
    #[ApiProperty(description: 'Whether the offer allows an early repayment at all.')]
    private bool $earlyRepaymentAllowed = true;

    #[ORM\Column(length: 16, enumType: PrepaymentPenaltyMode::class, options: ['default' => 'legal_cap'])]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(description: 'legal_cap (default): min(6 months of interest on the amount repaid, 3 % of the capital owed); fixed: prepaymentPenaltyFixedCents; exempt: 0; consumer: 1 % of the amount, 0.5 % with 12 months or less left.')]
    private PrepaymentPenaltyMode $prepaymentPenaltyMode = PrepaymentPenaltyMode::LegalCap;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'long')]
    #[ApiProperty(description: 'The penalty the offer states, in cents. Required by the fixed mode.')]
    private ?int $prepaymentPenaltyFixedCents = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'long')]
    #[ApiProperty(description: 'The smallest partial early repayment the lender takes, in cents.')]
    private ?int $minPartialRepaymentCents = null;

    #[ORM\Column(length: 16, nullable: true, enumType: PartialRepaymentEffect::class)]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(description: 'After a partial early repayment: shorten (the default when empty) or lower_payment.')]
    private ?PartialRepaymentEffect $partialRepaymentEffect = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'integer')]
    #[ApiProperty(description: 'The notice the lender asks before an early repayment, in days.')]
    private ?int $noticeDays = null;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    #[Assert\Callback]
    public function validateItAmortises(ExecutionContextInterface $context): void
    {
        if ($this->type->mustAmortiseNow() && $this->principalRemainingCents > 0 && !$this->amortises()) {
            $context->buildViolation('The monthly payment does not cover the interest: this loan would never be repaid.')
                ->atPath('monthlyPaymentCents')
                ->addViolation();
        }
    }

    /**
     * What the conditions lack for the type, or null when they hold together.
     * Nothing is deduced: a missing condition is refused, never guessed.
     */
    public function conditionsProblem(): ?string
    {
        foreach ([
            'initialAmountCents' => $this->initialAmountCents,
            'aprBasisPoints' => $this->aprBasisPoints,
            'insuranceRateBasisPoints' => $this->insuranceRateBasisPoints,
            'insuranceMonthlyCents' => $this->insuranceMonthlyCents,
            'feesCents' => $this->feesCents,
            'marginBasisPoints' => $this->marginBasisPoints,
            'rateCapBasisPoints' => $this->rateCapBasisPoints,
            'rateFloorBasisPoints' => $this->rateFloorBasisPoints,
            'deferralMonths' => $this->deferralMonths,
            'prepaymentPenaltyFixedCents' => $this->prepaymentPenaltyFixedCents,
            'minPartialRepaymentCents' => $this->minPartialRepaymentCents,
            'noticeDays' => $this->noticeDays,
        ] as $field => $value) {
            if (null !== $value && $value < 0) {
                return "{$field} cannot be negative.";
            }
        }

        if (null !== $this->durationMonths && $this->durationMonths <= 0) {
            return 'durationMonths must be above 0.';
        }

        if (null !== $problem = $this->typeProblem()) {
            return $problem;
        }

        if (null !== $this->rateFloorBasisPoints && null !== $this->rateCapBasisPoints
            && $this->rateFloorBasisPoints > $this->rateCapBasisPoints) {
            return 'rateFloorBasisPoints cannot be above rateCapBasisPoints.';
        }

        if (PrepaymentPenaltyMode::Fixed === $this->prepaymentPenaltyMode && null === $this->prepaymentPenaltyFixedCents) {
            return 'prepaymentPenaltyMode fixed requires prepaymentPenaltyFixedCents.';
        }

        if (null !== $this->insuranceRateBasisPoints && null === $this->insuranceMonthlyCents && null === $this->initialAmountCents) {
            return 'insuranceRateBasisPoints is a rate of the amount borrowed: it requires initialAmountCents.';
        }

        return null;
    }

    private function typeProblem(): ?string
    {
        $type = $this->type->value;

        if (LoanType::AmortizingVariable === $this->type && (null === $this->referenceIndex || '' === $this->referenceIndex || null === $this->marginBasisPoints)) {
            return "type {$type} requires referenceIndex and marginBasisPoints.";
        }

        if (LoanType::Stepped === $this->type) {
            if (null === $this->steps || [] === $this->steps) {
                return "type {$type} requires steps: [{fromMonth, monthlyPaymentCents}].";
            }
            // The API denormalizes whatever JSON it is sent: trust no shape.
            /** @var list<mixed> $steps */
            $steps = $this->steps;
            foreach ($steps as $step) {
                if (!\is_array($step) || !\is_int($step['fromMonth'] ?? null) || !\is_int($step['monthlyPaymentCents'] ?? null)
                    || $step['fromMonth'] < 1 || $step['monthlyPaymentCents'] <= 0) {
                    return 'Each step is {fromMonth: an integer from 1, monthlyPaymentCents: a positive integer}.';
                }
            }
        }

        if ($this->type->isDeferred() && (null === $this->deferralMonths || $this->deferralMonths <= 0)) {
            return "type {$type} requires deferralMonths above 0.";
        }

        if ($this->type->isInterestOnly() && null === $this->durationMonths) {
            return "type {$type} repays the capital at the last payment: it requires durationMonths.";
        }

        return null;
    }

    /** Whether the payment actually eats into the capital. */
    public function amortises(): bool
    {
        return $this->monthlyPaymentCents > $this->monthlyInterestOn($this->principalRemainingCents);
    }

    /**
     * The rate the schedule runs at: the nominal one, held between the floor
     * and the cap for a variable loan — assumed constant, no index is followed.
     */
    public function currentRateBasisPoints(): int
    {
        $rate = $this->annualRateBasisPoints;

        if (LoanType::AmortizingVariable === $this->type) {
            if (null !== $this->rateCapBasisPoints) {
                $rate = min($rate, $this->rateCapBasisPoints);
            }
            if (null !== $this->rateFloorBasisPoints) {
                $rate = max($rate, $this->rateFloorBasisPoints);
            }
        }

        return $rate;
    }

    /** Interest owed for one month on a given capital, in cents. */
    public function monthlyInterestOn(int $principalCents): int
    {
        $rate = $this->currentRateBasisPoints();
        if (0 === $rate) {
            return 0;
        }

        // basis points → monthly rate: /10000 for the percent, /12 for the month.
        return intdiv($principalCents * $rate, 10000 * 12);
    }

    /** Insurance per month: the amount entered, else the rate on the amount borrowed. */
    public function insuranceMonthlyCentsDue(): int
    {
        if (null !== $this->insuranceMonthlyCents) {
            return $this->insuranceMonthlyCents;
        }

        if (null !== $this->insuranceRateBasisPoints && null !== $this->initialAmountCents) {
            return intdiv($this->initialAmountCents * $this->insuranceRateBasisPoints, 10000 * 12);
        }

        return 0;
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

    public function getLender(): ?string
    {
        return $this->lender;
    }

    public function setLender(?string $lender): static
    {
        $this->lender = $lender;

        return $this;
    }

    public function getPrincipalRemainingCents(): int
    {
        return $this->principalRemainingCents;
    }

    public function setPrincipalRemainingCents(int $principalRemainingCents): static
    {
        $this->principalRemainingCents = $principalRemainingCents;

        return $this;
    }

    public function getMonthlyPaymentCents(): int
    {
        return $this->monthlyPaymentCents;
    }

    public function setMonthlyPaymentCents(int $monthlyPaymentCents): static
    {
        $this->monthlyPaymentCents = $monthlyPaymentCents;

        return $this;
    }

    public function getAnnualRateBasisPoints(): int
    {
        return $this->annualRateBasisPoints;
    }

    public function setAnnualRateBasisPoints(int $annualRateBasisPoints): static
    {
        $this->annualRateBasisPoints = $annualRateBasisPoints;

        return $this;
    }

    public function getPriority(): int
    {
        return $this->priority;
    }

    public function setPriority(int $priority): static
    {
        $this->priority = $priority;

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

    public function getType(): LoanType
    {
        return $this->type;
    }

    public function setType(LoanType $type): static
    {
        $this->type = $type;

        return $this;
    }

    public function isProjected(): bool
    {
        return $this->projected;
    }

    public function setProjected(bool $projected): static
    {
        $this->projected = $projected;

        return $this;
    }

    public function getInitialAmountCents(): ?int
    {
        return $this->initialAmountCents;
    }

    public function setInitialAmountCents(?int $initialAmountCents): static
    {
        $this->initialAmountCents = $initialAmountCents;

        return $this;
    }

    public function getAprBasisPoints(): ?int
    {
        return $this->aprBasisPoints;
    }

    public function setAprBasisPoints(?int $aprBasisPoints): static
    {
        $this->aprBasisPoints = $aprBasisPoints;

        return $this;
    }

    public function getDurationMonths(): ?int
    {
        return $this->durationMonths;
    }

    public function setDurationMonths(?int $durationMonths): static
    {
        $this->durationMonths = $durationMonths;

        return $this;
    }

    public function getFirstPaymentOn(): ?\DateTimeImmutable
    {
        return $this->firstPaymentOn;
    }

    public function setFirstPaymentOn(?\DateTimeImmutable $firstPaymentOn): static
    {
        $this->firstPaymentOn = $firstPaymentOn;

        return $this;
    }

    public function getPeriodicity(): LoanPeriodicity
    {
        return $this->periodicity;
    }

    public function setPeriodicity(LoanPeriodicity $periodicity): static
    {
        $this->periodicity = $periodicity;

        return $this;
    }

    public function getInsuranceRateBasisPoints(): ?int
    {
        return $this->insuranceRateBasisPoints;
    }

    public function setInsuranceRateBasisPoints(?int $insuranceRateBasisPoints): static
    {
        $this->insuranceRateBasisPoints = $insuranceRateBasisPoints;

        return $this;
    }

    public function getInsuranceMonthlyCents(): ?int
    {
        return $this->insuranceMonthlyCents;
    }

    public function setInsuranceMonthlyCents(?int $insuranceMonthlyCents): static
    {
        $this->insuranceMonthlyCents = $insuranceMonthlyCents;

        return $this;
    }

    public function getFeesCents(): ?int
    {
        return $this->feesCents;
    }

    public function setFeesCents(?int $feesCents): static
    {
        $this->feesCents = $feesCents;

        return $this;
    }

    public function getReferenceIndex(): ?string
    {
        return $this->referenceIndex;
    }

    public function setReferenceIndex(?string $referenceIndex): static
    {
        $this->referenceIndex = $referenceIndex;

        return $this;
    }

    public function getMarginBasisPoints(): ?int
    {
        return $this->marginBasisPoints;
    }

    public function setMarginBasisPoints(?int $marginBasisPoints): static
    {
        $this->marginBasisPoints = $marginBasisPoints;

        return $this;
    }

    public function getRateCapBasisPoints(): ?int
    {
        return $this->rateCapBasisPoints;
    }

    public function setRateCapBasisPoints(?int $rateCapBasisPoints): static
    {
        $this->rateCapBasisPoints = $rateCapBasisPoints;

        return $this;
    }

    public function getRateFloorBasisPoints(): ?int
    {
        return $this->rateFloorBasisPoints;
    }

    public function setRateFloorBasisPoints(?int $rateFloorBasisPoints): static
    {
        $this->rateFloorBasisPoints = $rateFloorBasisPoints;

        return $this;
    }

    /** @return list<array{fromMonth: int, monthlyPaymentCents: int}>|null */
    public function getSteps(): ?array
    {
        return $this->steps;
    }

    /** @param list<array{fromMonth: int, monthlyPaymentCents: int}>|null $steps */
    public function setSteps(?array $steps): static
    {
        $this->steps = $steps;

        return $this;
    }

    public function getDeferralMonths(): ?int
    {
        return $this->deferralMonths;
    }

    public function setDeferralMonths(?int $deferralMonths): static
    {
        $this->deferralMonths = $deferralMonths;

        return $this;
    }

    public function isEarlyRepaymentAllowed(): bool
    {
        return $this->earlyRepaymentAllowed;
    }

    public function setEarlyRepaymentAllowed(bool $earlyRepaymentAllowed): static
    {
        $this->earlyRepaymentAllowed = $earlyRepaymentAllowed;

        return $this;
    }

    public function getPrepaymentPenaltyMode(): PrepaymentPenaltyMode
    {
        return $this->prepaymentPenaltyMode;
    }

    public function setPrepaymentPenaltyMode(PrepaymentPenaltyMode $prepaymentPenaltyMode): static
    {
        $this->prepaymentPenaltyMode = $prepaymentPenaltyMode;

        return $this;
    }

    public function getPrepaymentPenaltyFixedCents(): ?int
    {
        return $this->prepaymentPenaltyFixedCents;
    }

    public function setPrepaymentPenaltyFixedCents(?int $prepaymentPenaltyFixedCents): static
    {
        $this->prepaymentPenaltyFixedCents = $prepaymentPenaltyFixedCents;

        return $this;
    }

    public function getMinPartialRepaymentCents(): ?int
    {
        return $this->minPartialRepaymentCents;
    }

    public function setMinPartialRepaymentCents(?int $minPartialRepaymentCents): static
    {
        $this->minPartialRepaymentCents = $minPartialRepaymentCents;

        return $this;
    }

    public function getPartialRepaymentEffect(): ?PartialRepaymentEffect
    {
        return $this->partialRepaymentEffect;
    }

    public function setPartialRepaymentEffect(?PartialRepaymentEffect $partialRepaymentEffect): static
    {
        $this->partialRepaymentEffect = $partialRepaymentEffect;

        return $this;
    }

    public function getNoticeDays(): ?int
    {
        return $this->noticeDays;
    }

    public function setNoticeDays(?int $noticeDays): static
    {
        $this->noticeDays = $noticeDays;

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

    /**
     * Every field a client reads, as scalars — the search document, the
     * Mercure payload and the MCP tool all say the same thing.
     *
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        return [
            'name' => $this->name,
            'lender' => $this->lender,
            'principalRemainingCents' => $this->principalRemainingCents,
            'monthlyPaymentCents' => $this->monthlyPaymentCents,
            'annualRateBasisPoints' => $this->annualRateBasisPoints,
            'priority' => $this->priority,
            'currency' => $this->currency,
            'type' => $this->type->value,
            'projected' => $this->projected,
            'initialAmountCents' => $this->initialAmountCents,
            'aprBasisPoints' => $this->aprBasisPoints,
            'durationMonths' => $this->durationMonths,
            'firstPaymentOn' => $this->firstPaymentOn?->format('Y-m-d'),
            'periodicity' => $this->periodicity->value,
            'insuranceRateBasisPoints' => $this->insuranceRateBasisPoints,
            'insuranceMonthlyCents' => $this->insuranceMonthlyCents,
            'feesCents' => $this->feesCents,
            'referenceIndex' => $this->referenceIndex,
            'marginBasisPoints' => $this->marginBasisPoints,
            'rateCapBasisPoints' => $this->rateCapBasisPoints,
            'rateFloorBasisPoints' => $this->rateFloorBasisPoints,
            'steps' => $this->steps,
            'deferralMonths' => $this->deferralMonths,
            'earlyRepaymentAllowed' => $this->earlyRepaymentAllowed,
            'prepaymentPenaltyMode' => $this->prepaymentPenaltyMode->value,
            'prepaymentPenaltyFixedCents' => $this->prepaymentPenaltyFixedCents,
            'minPartialRepaymentCents' => $this->minPartialRepaymentCents,
            'partialRepaymentEffect' => $this->partialRepaymentEffect?->value,
            'noticeDays' => $this->noticeDays,
        ];
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return $this->toArray() + ['userId' => (string) $this->user->getId()];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload($this->toArray(), $changedProperties);
    }
}
