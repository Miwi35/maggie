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
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\Service\MatchCriteria;
use Maggie\Finance\State\CreateCategorizationRuleProcessor;
use Maggie\Finance\State\DeleteCategorizationRuleProcessor;
use Maggie\Finance\State\UpdateCategorizationRuleProcessor;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

/**
 * "When a transaction label matches X (and, optionally, its amount falls in a
 * range and goes in a direction), file it under category Y."
 */
#[ORM\Entity(repositoryClass: CategorizationRuleRepository::class)]
#[Indexed(index: 'categorization_rules', module: 'finance')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateCategorizationRuleProcessor::class),
    new Patch(processor: UpdateCategorizationRuleProcessor::class),
    new Delete(processor: DeleteCategorizationRuleProcessor::class),
])]
class CategorizationRule implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    #[IndexedField(type: 'text', boost: 2.0, keyword: true)]
    private string $labelPattern;

    #[ORM\Column(length: 20, enumType: MatchType::class)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private MatchType $matchType = MatchType::Contains;

    #[ORM\ManyToOne(targetEntity: Category::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Assert\NotNull]
    #[IndexedRelation(targetEntity: Category::class, sourceField: 'categoryId')]
    private Category $category;

    #[ORM\Column(length: 20, enumType: AmountDirection::class)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private AmountDirection $direction = AmountDirection::Any;

    /** Absolute amount bounds in cents: a debit of 15,99 € matches 1000..2000. */
    #[ORM\Column(type: 'integer', nullable: true)]
    #[Assert\PositiveOrZero]
    #[IndexedField(type: 'long')]
    private ?int $minAmountCents = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[Assert\PositiveOrZero]
    #[IndexedField(type: 'long')]
    private ?int $maxAmountCents = null;

    /** Higher priority wins; ties are broken by creation order. */
    #[ORM\Column(type: 'integer')]
    #[IndexedField(type: 'integer')]
    private int $priority = 0;

    #[ORM\Column(type: 'boolean')]
    #[IndexedField(type: 'boolean')]
    private bool $isActive = true;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    /** Asked on save, never stored: file the existing history under this rule too. */
    #[ApiProperty(readable: false, writable: true)]
    private bool $applyToExisting = false;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    #[Assert\Callback]
    public function validateAmountRange(ExecutionContextInterface $context): void
    {
        if (null !== $this->minAmountCents
            && null !== $this->maxAmountCents
            && $this->minAmountCents > $this->maxAmountCents
        ) {
            $context->buildViolation('The minimum amount must not exceed the maximum amount.')
                ->atPath('minAmountCents')
                ->addViolation();
        }
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getLabelPattern(): string
    {
        return $this->labelPattern;
    }

    public function setLabelPattern(string $labelPattern): static
    {
        $this->labelPattern = $labelPattern;

        return $this;
    }

    public function getMatchType(): MatchType
    {
        return $this->matchType;
    }

    public function setMatchType(MatchType $matchType): static
    {
        $this->matchType = $matchType;

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

    public function getDirection(): AmountDirection
    {
        return $this->direction;
    }

    public function setDirection(AmountDirection $direction): static
    {
        $this->direction = $direction;

        return $this;
    }

    public function getMinAmountCents(): ?int
    {
        return $this->minAmountCents;
    }

    public function setMinAmountCents(?int $minAmountCents): static
    {
        $this->minAmountCents = $minAmountCents;

        return $this;
    }

    public function getMaxAmountCents(): ?int
    {
        return $this->maxAmountCents;
    }

    public function setMaxAmountCents(?int $maxAmountCents): static
    {
        $this->maxAmountCents = $maxAmountCents;

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

    public function isActive(): bool
    {
        return $this->isActive;
    }

    public function setIsActive(bool $isActive): static
    {
        $this->isActive = $isActive;

        return $this;
    }

    public function shouldApplyToExisting(): bool
    {
        return $this->applyToExisting;
    }

    public function setApplyToExisting(bool $applyToExisting): static
    {
        $this->applyToExisting = $applyToExisting;

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
     * What the rule recognises a transaction by, for the engine it shares with
     * the recurring operations. Whether the rule is active is the caller's
     * question.
     */
    public function matchCriteria(): MatchCriteria
    {
        return new MatchCriteria(
            labelPattern: $this->labelPattern,
            matchType: $this->matchType,
            direction: $this->direction,
            minAmountCents: $this->minAmountCents,
            maxAmountCents: $this->maxAmountCents,
        );
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'labelPattern' => $this->labelPattern,
            'matchType' => $this->matchType->value,
            'direction' => $this->direction->value,
            'minAmountCents' => $this->minAmountCents,
            'maxAmountCents' => $this->maxAmountCents,
            'priority' => $this->priority,
            'isActive' => $this->isActive,
            'categoryId' => (string) $this->category->getId(),
            'userId' => (string) $this->user->getId(),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'labelPattern' => $this->labelPattern,
            'matchType' => $this->matchType->value,
            'direction' => $this->direction->value,
            'minAmountCents' => $this->minAmountCents,
            'maxAmountCents' => $this->maxAmountCents,
            'priority' => $this->priority,
            'isActive' => $this->isActive,
            'categoryId' => (string) $this->category->getId(),
        ], $changedProperties, ['category' => 'categoryId']);
    }
}
