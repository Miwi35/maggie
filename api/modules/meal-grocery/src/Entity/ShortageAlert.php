<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Maggie\MealGrocery\Repository\ShortageAlertRepository;
use Symfony\Component\Uid\Ulid;

/**
 * Maggie told the owner a product is missing for a meal (MAG-570): once per
 * product and meal, whatever became of the notification. The row is what keeps
 * a notification deleted by hand, or resolved, from coming back at the next
 * check.
 *
 * Bookkeeping: what the owner sees is the notification, which points back here
 * through its `relatedEntityIri` (`/api/shortage-alerts/{id}`). Answered through
 * `/api/shortage-alerts/{id}/action`; not published, not indexed.
 */
#[ORM\Entity(repositoryClass: ShortageAlertRepository::class)]
#[ORM\Table(name: 'shortage_alert')]
#[ORM\UniqueConstraint(name: 'uniq_shortage_alert_meal_product', columns: ['meal_id', 'product_id'])]
class ShortageAlert implements OwnedByUserInterface
{
    public const ACTION_ADD = 'add';
    public const ACTIONS = [self::ACTION_ADD];

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    #[ORM\ManyToOne(targetEntity: Meal::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Meal $meal;

    #[ORM\ManyToOne(targetEntity: Product::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Product $product;

    /** Bought, added to the list, or no longer needed by the meal: nothing left to say. */
    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $resolvedAt = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $createdAt;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->createdAt = new \DateTimeImmutable();
    }

    public function getId(): Ulid
    {
        return $this->id;
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

    public function getMeal(): Meal
    {
        return $this->meal;
    }

    public function setMeal(Meal $meal): static
    {
        $this->meal = $meal;

        return $this;
    }

    public function getProduct(): Product
    {
        return $this->product;
    }

    public function setProduct(Product $product): static
    {
        $this->product = $product;

        return $this;
    }

    public function getResolvedAt(): ?\DateTimeImmutable
    {
        return $this->resolvedAt;
    }

    public function setResolvedAt(?\DateTimeImmutable $resolvedAt): static
    {
        $this->resolvedAt = $resolvedAt;

        return $this;
    }

    public function isResolved(): bool
    {
        return null !== $this->resolvedAt;
    }

    public function getCreatedAt(): \DateTimeImmutable
    {
        return $this->createdAt;
    }

    /** @return array{id: string, productId: string, name: string, mealId: string, resolved: bool} */
    public function describe(): array
    {
        return [
            'id' => (string) $this->id,
            'productId' => (string) $this->product->getId(),
            'name' => $this->product->getName(),
            'mealId' => (string) $this->meal->getId(),
            'resolved' => $this->isResolved(),
        ];
    }
}
