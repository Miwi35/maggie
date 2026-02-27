<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Serializer\Attribute\Groups;
use Symfony\Component\Uid\Ulid;

#[ORM\Entity]
#[ORM\Table(name: 'ciqual_food_nutrient')]
#[ORM\UniqueConstraint(columns: ['food_id', 'nutrient_id'])]
class CiqualFoodNutrient
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: CiqualFood::class, inversedBy: 'nutrients')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private CiqualFood $food;

    #[ORM\ManyToOne(targetEntity: CiqualNutrient::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Groups(['ciqual_food:read'])]
    private CiqualNutrient $nutrient;

    #[ORM\Column(type: 'float', nullable: true)]
    #[Groups(['ciqual_food:read'])]
    private ?float $value = null;

    #[ORM\Column(length: 5, nullable: true)]
    #[Groups(['ciqual_food:read'])]
    private ?string $confidenceCode = null;

    #[ORM\Column(length: 50, nullable: true)]
    private ?string $rawValue = null;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getFood(): CiqualFood
    {
        return $this->food;
    }

    public function setFood(CiqualFood $food): static
    {
        $this->food = $food;

        return $this;
    }

    public function getNutrient(): CiqualNutrient
    {
        return $this->nutrient;
    }

    public function setNutrient(CiqualNutrient $nutrient): static
    {
        $this->nutrient = $nutrient;

        return $this;
    }

    public function getValue(): ?float
    {
        return $this->value;
    }

    public function setValue(?float $value): static
    {
        $this->value = $value;

        return $this;
    }

    public function getConfidenceCode(): ?string
    {
        return $this->confidenceCode;
    }

    public function setConfidenceCode(?string $confidenceCode): static
    {
        $this->confidenceCode = $confidenceCode;

        return $this;
    }

    public function getRawValue(): ?string
    {
        return $this->rawValue;
    }

    public function setRawValue(?string $rawValue): static
    {
        $this->rawValue = $rawValue;

        return $this;
    }
}
