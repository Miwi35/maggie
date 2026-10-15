<?php

declare(strict_types=1);

namespace Maggie\Grocery\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\AggregateRoot;
use Symfony\Component\Uid\Ulid;

/**
 * Where a product is in one store: « Crèmerie » at Carrefour, « Frais » at
 * Lidl. One row at most per product and store; no row is « Sans rayon ».
 * The store is the aisle's, kept here for the unique key.
 */
#[ORM\Entity]
#[ORM\UniqueConstraint(name: 'uniq_product_aisle_store', columns: ['product_id', 'store_id'])]
#[AggregateRoot('product')]
class ProductAisle
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Product::class, inversedBy: 'aisleAssignments')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Product $product;

    #[ORM\ManyToOne(targetEntity: Store::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Store $store;

    #[ORM\ManyToOne(targetEntity: Aisle::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Aisle $aisle;

    public function __construct(Product $product, Aisle $aisle)
    {
        $this->id = new Ulid();
        $this->product = $product;
        $this->store = $aisle->getStore();
        $this->aisle = $aisle;
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getProduct(): Product
    {
        return $this->product;
    }

    public function getStore(): Store
    {
        return $this->store;
    }

    public function getAisle(): Aisle
    {
        return $this->aisle;
    }

    /** Moved in place: a delete and an insert in one flush would break the unique key (inserts run first). */
    public function setAisle(Aisle $aisle): static
    {
        $this->aisle = $aisle;

        return $this;
    }
}
