<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Maggie\Cookbook\Enum\RecurringFrequency;
use Maggie\Cookbook\Enum\Unit;
use Maggie\Cookbook\Repository\RecurringGroceryItemRepository;
use Maggie\Cookbook\State\CreateRecurringGroceryItemProcessor;
use Maggie\Cookbook\State\DeleteRecurringGroceryItemProcessor;
use Maggie\Cookbook\State\UpdateRecurringGroceryItemProcessor;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Uid\Ulid;

#[ORM\Entity(repositoryClass: RecurringGroceryItemRepository::class)]
#[ApiResource(operations: [
    new GetCollection(),
    new Get(),
    new Post(processor: CreateRecurringGroceryItemProcessor::class),
    new Patch(processor: UpdateRecurringGroceryItemProcessor::class),
    new Delete(processor: DeleteRecurringGroceryItemProcessor::class),
])]
class RecurringGroceryItem implements OwnedByUserInterface
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Product::class)]
    #[ORM\JoinColumn(nullable: true)]
    private ?Product $product = null;

    #[ORM\Column(length: 255, nullable: true)]
    private ?string $customLabel = null;

    #[ORM\Column(type: 'float', nullable: true)]
    private ?float $quantity = null;

    #[ORM\Column(length: 20, nullable: true, enumType: Unit::class)]
    private ?Unit $unit = null;

    #[ORM\Column(length: 20, enumType: RecurringFrequency::class)]
    private RecurringFrequency $frequency;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getProduct(): ?Product
    {
        return $this->product;
    }

    public function setProduct(?Product $product): static
    {
        $this->product = $product;

        return $this;
    }

    public function getCustomLabel(): ?string
    {
        return $this->customLabel;
    }

    public function setCustomLabel(?string $customLabel): static
    {
        $this->customLabel = $customLabel;

        return $this;
    }

    public function getLabel(): string
    {
        return $this->customLabel ?? $this->product?->getName() ?? 'Unknown';
    }

    public function getQuantity(): ?float
    {
        return $this->quantity;
    }

    public function setQuantity(?float $quantity): static
    {
        $this->quantity = $quantity;

        return $this;
    }

    public function getUnit(): ?Unit
    {
        return $this->unit;
    }

    public function setUnit(?Unit $unit): static
    {
        $this->unit = $unit;

        return $this;
    }

    public function getFrequency(): RecurringFrequency
    {
        return $this->frequency;
    }

    public function setFrequency(RecurringFrequency $frequency): static
    {
        $this->frequency = $frequency;

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
}
