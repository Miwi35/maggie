<?php

declare(strict_types=1);

namespace Maggie\Grocery\Entity;

use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Doctrine\Filter\UlidRelationFilter;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Grocery\Repository\AisleRepository;
use Maggie\Grocery\State\CreateAisleProcessor;
use Maggie\Grocery\State\DeleteAisleProcessor;
use Maggie\Grocery\State\UpdateAisleProcessor;
use Symfony\Component\Uid\Ulid;

/**
 * A shelf of one store, where its products are picked up, in the order the
 * owner walks through the shop (MAG-493). The same product may be in a
 * different aisle in each store: {@see ProductAisle}.
 *
 * The name is checked by the use cases (not blank, unique per store whatever
 * the case), so that the API and the MCP tools answer the same 400.
 */
#[ORM\Entity(repositoryClass: AisleRepository::class)]
#[ORM\Index(name: 'idx_aisle_store_position', columns: ['store_id', 'position'])]
#[ApiFilter(UlidRelationFilter::class, properties: ['store'])]
#[ApiResource(
    operations: [
        new GetCollection(),
        new Get(),
        new Post(processor: CreateAisleProcessor::class),
        new Patch(processor: UpdateAisleProcessor::class),
        new Delete(processor: DeleteAisleProcessor::class),
    ],
    order: ['position' => 'ASC'],
    paginationEnabled: false,
)]
class Aisle implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    // Set on creation, never moved to another store. Null only on a POST that left it out.
    #[ORM\ManyToOne(targetEntity: Store::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private ?Store $store = null;

    #[ORM\Column(length: 255)]
    private string $name = '';

    // 1 is the first aisle on the way through the store.
    #[ORM\Column(type: 'integer')]
    private int $position = 0;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[ApiProperty(writable: false)]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getStore(): Store
    {
        return $this->store ?? throw new \LogicException('The aisle has no store yet.');
    }

    /** The store a POST sent, null when it sent none (not an accessor: `store` is always set once saved). */
    public function sentStore(): ?Store
    {
        return $this->store;
    }

    public function setStore(Store $store): static
    {
        $this->store = $store;

        return $this;
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

    public function getPosition(): int
    {
        return $this->position;
    }

    public function setPosition(int $position): static
    {
        $this->position = $position;

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

    /** @return array{id: string, name: string, position: int} what a grocery line says of its aisle */
    public function toSummary(): array
    {
        return [
            'id' => (string) $this->id,
            'name' => $this->name,
            'position' => $this->position,
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'name' => $this->name,
            'position' => $this->position,
            'storeId' => (string) $this->getStore()->getId(),
        ], $changedProperties, ['store' => 'storeId']);
    }
}
