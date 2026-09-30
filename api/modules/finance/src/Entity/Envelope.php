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
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\State\CreateEnvelopeProcessor;
use Maggie\Finance\State\DeleteEnvelopeProcessor;
use Maggie\Finance\State\UpdateEnvelopeProcessor;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

#[ORM\Entity(repositoryClass: EnvelopeRepository::class)]
#[Indexed(index: 'envelopes', module: 'finance')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateEnvelopeProcessor::class),
    new Patch(processor: UpdateEnvelopeProcessor::class),
    new Delete(processor: DeleteEnvelopeProcessor::class),
])]
class Envelope implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Category::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Assert\NotNull]
    #[IndexedRelation(targetEntity: Category::class, sourceField: 'categoryId')]
    private Category $category;

    #[ORM\Column(length: 20, enumType: BudgetMode::class)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private BudgetMode $mode = BudgetMode::Monthly;

    /** Budgeted amount in positive integer cents. */
    #[ORM\Column(type: 'integer')]
    #[Assert\PositiveOrZero]
    #[IndexedField(type: 'long')]
    private int $amountCents = 0;

    #[ORM\Column(length: 3)]
    #[Assert\NotBlank]
    #[Assert\Length(exactly: 3)]
    #[Assert\Regex(pattern: '/^[A-Z]{3}$/', message: 'The currency must be a 3-letter ISO 4217 code.')]
    #[IndexedField(type: 'keyword')]
    private string $currency = 'EUR';

    #[ORM\Column(type: 'integer')]
    #[Assert\Range(min: 2000, max: 2100)]
    #[IndexedField(type: 'integer')]
    private int $year;

    /** Month 1-12 for a monthly envelope, null for an annual one. */
    #[ORM\Column(type: 'integer', nullable: true)]
    #[Assert\Range(min: 1, max: 12)]
    #[IndexedField(type: 'integer')]
    private ?int $month = null;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->year = (int) (new \DateTimeImmutable())->format('Y');
    }

    #[Assert\Callback]
    public function validatePeriod(ExecutionContextInterface $context): void
    {
        if (BudgetMode::Monthly === $this->mode && null === $this->month) {
            $context->buildViolation('A monthly envelope requires a month.')
                ->atPath('month')
                ->addViolation();
        }

        if (BudgetMode::Annual === $this->mode && null !== $this->month) {
            $context->buildViolation('An annual envelope must not carry a month.')
                ->atPath('month')
                ->addViolation();
        }
    }

    public function getId(): Ulid
    {
        return $this->id;
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

    public function getMode(): BudgetMode
    {
        return $this->mode;
    }

    public function setMode(BudgetMode $mode): static
    {
        $this->mode = $mode;

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

    public function getYear(): int
    {
        return $this->year;
    }

    public function setYear(int $year): static
    {
        $this->year = $year;

        return $this;
    }

    public function getMonth(): ?int
    {
        return $this->month;
    }

    public function setMonth(?int $month): static
    {
        $this->month = $month;

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

    /** First day of the period this envelope budgets. */
    public function getPeriodStart(): \DateTimeImmutable
    {
        return new \DateTimeImmutable(sprintf('%04d-%02d-01', $this->year, $this->month ?? 1));
    }

    /** First day after the period this envelope budgets. */
    public function getPeriodEnd(): \DateTimeImmutable
    {
        return $this->getPeriodStart()->modify(BudgetMode::Monthly === $this->mode ? '+1 month' : '+1 year');
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'mode' => $this->mode->value,
            'amountCents' => $this->amountCents,
            'currency' => $this->currency,
            'year' => $this->year,
            'month' => $this->month,
            'categoryId' => (string) $this->category->getId(),
            'userId' => (string) $this->user->getId(),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'mode' => $this->mode->value,
            'amountCents' => $this->amountCents,
            'currency' => $this->currency,
            'year' => $this->year,
            'month' => $this->month,
            'categoryId' => (string) $this->category->getId(),
        ], $changedProperties, ['category' => 'categoryId']);
    }
}
