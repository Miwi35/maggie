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
use Maggie\Cookbook\Enum\ProductCategory;
use Maggie\Cookbook\Enum\Unit;
use Maggie\Cookbook\Repository\ProductRepository;
use Maggie\Cookbook\State\CreateProductProcessor;
use Maggie\Cookbook\State\DeleteProductProcessor;
use Maggie\Cookbook\State\UpdateProductProcessor;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: ProductRepository::class)]
#[ORM\InheritanceType("SINGLE_TABLE")]
#[ORM\DiscriminatorColumn(name: "dtype", type: "string", length: 20)]
#[ORM\DiscriminatorMap(["product" => Product::class, "ingredient" => Ingredient::class])]
#[ApiResource(operations: [
    new GetCollection(),
    new Get(),
    new Post(processor: CreateProductProcessor::class),
    new Patch(processor: UpdateProductProcessor::class),
    new Delete(processor: DeleteProductProcessor::class),
])]
class Product implements MercurePublishable, OwnedByUserInterface
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    private string $name;

    #[ORM\Column(length: 20, nullable: true, enumType: Unit::class)]
    private ?Unit $defaultUnit = null;

    #[ORM\Column(length: 20, enumType: ProductCategory::class)]
    #[Assert\NotNull]
    private ProductCategory $category;

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

    public function getName(): string
    {
        return $this->name;
    }

    public function setName(string $name): static
    {
        $this->name = $name;

        return $this;
    }

    public function getDefaultUnit(): ?Unit
    {
        return $this->defaultUnit;
    }

    public function setDefaultUnit(?Unit $defaultUnit): static
    {
        $this->defaultUnit = $defaultUnit;

        return $this;
    }

    public function getCategory(): ProductCategory
    {
        return $this->category;
    }

    public function setCategory(ProductCategory $category): static
    {
        $this->category = $category;

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

    public function toMercurePayload(): array
    {
        return [
            'name' => $this->name,
            'category' => $this->category->value,
        ];
    }
}
