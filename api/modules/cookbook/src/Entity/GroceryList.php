<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Maggie\Calendar\Contract\MercurePublishable;
use Maggie\Cookbook\Enum\GroceryListStatus;
use Maggie\Cookbook\Repository\GroceryListRepository;
use Maggie\Cookbook\State\CreateGroceryListProcessor;
use Maggie\Cookbook\State\DeleteGroceryListProcessor;
use Maggie\Cookbook\State\UpdateGroceryListProcessor;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Uid\Ulid;

#[ORM\Entity(repositoryClass: GroceryListRepository::class)]
#[ApiResource(operations: [
    new GetCollection(),
    new Get(),
    new Post(processor: CreateGroceryListProcessor::class),
    new Patch(processor: UpdateGroceryListProcessor::class),
    new Delete(processor: DeleteGroceryListProcessor::class),
])]
class GroceryList implements MercurePublishable, OwnedByUserInterface
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(type: Types::DATE_IMMUTABLE)]
    private \DateTimeImmutable $weekStart;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    #[ORM\Column(length: 20, enumType: GroceryListStatus::class, options: ['default' => 'draft'])]
    private GroceryListStatus $status = GroceryListStatus::Draft;

    /** @var Collection<int, GroceryItem> */
    #[ORM\OneToMany(targetEntity: GroceryItem::class, mappedBy: 'groceryList', cascade: ['persist', 'remove'], orphanRemoval: true)]
    private Collection $items;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $createdAt;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $updatedAt;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->items = new ArrayCollection();
        $this->createdAt = new \DateTimeImmutable();
        $this->updatedAt = new \DateTimeImmutable();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getWeekStart(): \DateTimeImmutable
    {
        return $this->weekStart;
    }

    public function setWeekStart(\DateTimeImmutable $weekStart): static
    {
        $this->weekStart = $weekStart;

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

    public function getStatus(): GroceryListStatus
    {
        return $this->status;
    }

    public function setStatus(GroceryListStatus $status): static
    {
        $this->status = $status;

        return $this;
    }

    /** @return Collection<int, GroceryItem> */
    public function getItems(): Collection
    {
        return $this->items;
    }

    public function addItem(GroceryItem $item): static
    {
        if (!$this->items->contains($item)) {
            $this->items->add($item);
            $item->setGroceryList($this);
        }

        return $this;
    }

    public function removeItem(GroceryItem $item): static
    {
        $this->items->removeElement($item);

        return $this;
    }

    public function getCreatedAt(): \DateTimeImmutable
    {
        return $this->createdAt;
    }

    public function getUpdatedAt(): \DateTimeImmutable
    {
        return $this->updatedAt;
    }

    public function setUpdatedAt(\DateTimeImmutable $updatedAt): static
    {
        $this->updatedAt = $updatedAt;

        return $this;
    }

    public function toMercurePayload(): array
    {
        return [
            'weekStart' => $this->weekStart->format('Y-m-d'),
            'status' => $this->status->value,
            'itemCount' => $this->items->count(),
        ];
    }
}
