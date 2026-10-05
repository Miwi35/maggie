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
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\State\CreateCategoryProcessor;
use Maggie\Finance\State\DeleteCategoryProcessor;
use Maggie\Finance\State\UpdateCategoryProcessor;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

#[ORM\Entity(repositoryClass: CategoryRepository::class)]
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

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    #[IndexedField(type: 'text', boost: 3.0, keyword: true)]
    private string $name;

    #[ORM\ManyToOne(targetEntity: Category::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'CASCADE')]
    private ?Category $parent = null;

    #[ORM\Column(length: 20, enumType: ObligationFlag::class)]
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
            'parentId' => null !== $this->parent ? (string) $this->parent->getId() : null,
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
            'parentId' => null !== $this->parent ? (string) $this->parent->getId() : null,
            'color' => $this->color,
            'icon' => $this->icon,
        ], $changedProperties, ['parent' => 'parentId']);
    }
}
