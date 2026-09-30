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
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\State\CreateLoanProcessor;
use Maggie\Finance\State\DeleteLoanProcessor;
use Maggie\Finance\State\UpdateLoanProcessor;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

/**
 * A loan being repaid. Its end date is never stored: it falls out of the
 * capital, the payment and the rate, and a fourth field would contradict
 * them at the first early repayment.
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

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
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

    /** Annual rate in basis points: 350 is 3.50 %. Never a float. */
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
        if ($this->principalRemainingCents > 0 && !$this->amortises()) {
            $context->buildViolation('The monthly payment does not cover the interest: this loan would never be repaid.')
                ->atPath('monthlyPaymentCents')
                ->addViolation();
        }
    }

    /** Whether the payment actually eats into the capital. */
    public function amortises(): bool
    {
        return $this->monthlyPaymentCents > $this->monthlyInterestOn($this->principalRemainingCents);
    }

    /** Interest owed for one month on a given capital, in cents. */
    public function monthlyInterestOn(int $principalCents): int
    {
        if (0 === $this->annualRateBasisPoints) {
            return 0;
        }

        // basis points → monthly rate: /10000 for the percent, /12 for the month.
        return intdiv($principalCents * $this->annualRateBasisPoints, 10000 * 12);
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
            'lender' => $this->lender,
            'principalRemainingCents' => $this->principalRemainingCents,
            'monthlyPaymentCents' => $this->monthlyPaymentCents,
            'annualRateBasisPoints' => $this->annualRateBasisPoints,
            'priority' => $this->priority,
            'currency' => $this->currency,
            'userId' => (string) $this->user->getId(),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'name' => $this->name,
            'lender' => $this->lender,
            'principalRemainingCents' => $this->principalRemainingCents,
            'monthlyPaymentCents' => $this->monthlyPaymentCents,
            'annualRateBasisPoints' => $this->annualRateBasisPoints,
            'priority' => $this->priority,
            'currency' => $this->currency,
        ], $changedProperties);
    }
}
