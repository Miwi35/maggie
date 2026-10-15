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
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\State\CreateCategoryProcessor;
use Maggie\Finance\State\DeleteCategoryProcessor;
use Maggie\Finance\State\UpdateCategoryProcessor;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

#[ORM\Entity(repositoryClass: CategoryRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_category_user_standard_key', columns: ['user_id', 'standard_key'])]
#[Indexed(index: 'categories', module: 'finance')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateCategoryProcessor::class),
    new Patch(processor: UpdateCategoryProcessor::class),
    new Delete(processor: DeleteCategoryProcessor::class),
])]
class Category implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use MercurePayloadFilterTrait;

    public const RENTE_WITHOUT_INCOME = 'Only an income category can be a rente.';

    public const MAX_DEPTH = 3;

    public const PATH_SEPARATOR = ' › ';

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    #[IndexedField(type: 'text', boost: 3.0, keyword: true)]
    private string $name;

    #[ORM\ManyToOne(targetEntity: Category::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'CASCADE')]
    #[IndexedRelation(targetEntity: Category::class, sourceField: 'parentId')]
    private ?Category $parent = null;

    // depth and path are not stored: they come from the parent chain. Only a
    // category rebuilt from its Elasticsearch document carries them.

    /** Level in the tree, from 1 (top-level) to 3. */
    #[ApiProperty(writable: false)]
    #[IndexedField(type: 'integer')]
    private ?int $depth = null; // @phpstan-ignore property.unusedType (set by the Elasticsearch hydrator)

    /** Names from the top-level category down to this one, e.g. « Loisirs › Bars › Obsydian ». */
    #[ApiProperty(writable: false)]
    #[IndexedField(type: 'text', keyword: true)]
    private ?string $path = null; // @phpstan-ignore property.unusedType (set by the Elasticsearch hydrator)

    /**
     * Deprecated (MAG-476): a category has no nature any more, the transaction
     * carries its own `qualification`. Still read by the calculations until
     * MAG-477 and still accepted from the clients until MAG-479 removes it;
     * no qualification path writes it.
     */
    #[ORM\Column(length: 20, enumType: ObligationFlag::class)]
    #[ApiProperty(deprecationReason: 'A category has no nature: read the qualification of the transaction, the series or the rule.')]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private ObligationFlag $obligation = ObligationFlag::Optional;

    /**
     * Whether what this category brings in is a rente: income the user does
     * not work for. It is what the independence counter counts, and the one
     * thing that tells a rent received from a salary — both are income.
     *
     * Named without the `is` on purpose: Symfony serialises `isFoo()` as
     * `foo`, so a field declared `isPassiveIncome` would be `passiveIncome`
     * over REST and `isPassiveIncome` on Mercure — the disagreement
     * `isCushion` already pays for. One spelling, every channel.
     */
    #[ORM\Column(name: 'is_passive_income', type: 'boolean', options: ['default' => false])]
    #[IndexedField(type: 'boolean')]
    private bool $passiveIncome = false;

    /**
     * Which standard category this is, if it is one (`StandardCategories::SALARY`,
     * `StandardCategories::BONUS`). Laid down with the category, never written
     * over REST, and kept whatever the owner renames it to: what reads "the
     * salary" asks the key, never the name.
     */
    #[ORM\Column(length: 40, nullable: true)]
    #[ApiProperty(writable: false)]
    #[IndexedField(type: 'keyword')]
    private ?string $standardKey = null;

    #[ORM\Column(length: 20, nullable: true)]
    private ?string $color = null;

    #[ORM\Column(length: 40, nullable: true)]
    private ?string $icon = null;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    /**
     * A rente is income. A spending category flagged as one would feed the
     * independence counter a number it reads as money coming in, so the
     * combination is refused rather than silently ignored.
     */
    #[Assert\Callback]
    public function validateARenteIsIncome(ExecutionContextInterface $context): void
    {
        if ($this->declaresARenteWithoutIncome()) {
            $context->buildViolation(self::RENTE_WITHOUT_INCOME)
                ->atPath('passiveIncome')
                ->addViolation();
        }
    }

    /** Whether the rente flag contradicts the kind of line this category is. */
    public function declaresARenteWithoutIncome(): bool
    {
        return $this->passiveIncome && ObligationFlag::Income !== $this->obligation;
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

    public function getParent(): ?Category
    {
        return $this->parent;
    }

    public function setParent(?Category $parent): static
    {
        $this->parent = $parent;

        return $this;
    }

    public function getDepth(): int
    {
        return $this->depth ?? (null === $this->parent ? 1 : $this->parent->getDepth() + 1);
    }

    public function getPath(): string
    {
        return $this->path ?? (null === $this->parent ? $this->name : $this->parent->getPath().self::PATH_SEPARATOR.$this->name);
    }

    public function getObligation(): ObligationFlag
    {
        return $this->obligation;
    }

    public function setObligation(ObligationFlag $obligation): static
    {
        $this->obligation = $obligation;

        return $this;
    }

    public function isPassiveIncome(): bool
    {
        return $this->passiveIncome;
    }

    public function setPassiveIncome(bool $passiveIncome): static
    {
        $this->passiveIncome = $passiveIncome;

        return $this;
    }

    public function getStandardKey(): ?string
    {
        return $this->standardKey;
    }

    public function setStandardKey(?string $standardKey): static
    {
        $this->standardKey = $standardKey;

        return $this;
    }

    /** Whether this category, or one it sits under, is the standard one with this key. */
    public function isStandard(string $key): bool
    {
        return $key === $this->standardKey || (null !== $this->parent && $this->parent->isStandard($key));
    }

    public function getColor(): ?string
    {
        return $this->color;
    }

    public function setColor(?string $color): static
    {
        $this->color = $color;

        return $this;
    }

    public function getIcon(): ?string
    {
        return $this->icon;
    }

    public function setIcon(?string $icon): static
    {
        $this->icon = $icon;

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
            'obligation' => $this->obligation->value,
            'passiveIncome' => $this->passiveIncome,
            'standardKey' => $this->standardKey,
            'parentId' => null !== $this->parent ? (string) $this->parent->getId() : null,
            'depth' => $this->getDepth(),
            'path' => $this->getPath(),
            'color' => $this->color,
            'icon' => $this->icon,
            'userId' => (string) $this->user->getId(),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'name' => $this->name,
            'obligation' => $this->obligation->value,
            'passiveIncome' => $this->passiveIncome,
            'standardKey' => $this->standardKey,
            'parentId' => null !== $this->parent ? (string) $this->parent->getId() : null,
            'depth' => $this->getDepth(),
            'path' => $this->getPath(),
            'color' => $this->color,
            'icon' => $this->icon,
        ], self::withDerivedProperties($changedProperties), ['parent' => 'parentId']);
    }

    /**
     * The depth and the path follow the name and the parent: a differential
     * update that renames or moves a category must carry them too.
     *
     * @param string[]|null $changedProperties
     *
     * @return string[]|null
     */
    private static function withDerivedProperties(?array $changedProperties): ?array
    {
        if (null === $changedProperties || [] === array_intersect($changedProperties, ['name', 'parent'])) {
            return $changedProperties;
        }

        return [...$changedProperties, 'depth', 'path'];
    }
}
