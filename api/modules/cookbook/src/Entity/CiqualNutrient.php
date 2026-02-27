<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Cookbook\Repository\CiqualNutrientRepository;
use Symfony\Component\Uid\Ulid;

#[ORM\Entity(repositoryClass: CiqualNutrientRepository::class)]
#[ORM\Table(name: 'ciqual_nutrient')]
#[ApiResource(operations: [
    new GetCollection(),
    new Get(),
])]
class CiqualNutrient
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(length: 10, unique: true)]
    private string $constCode;

    #[ORM\Column(length: 255)]
    private string $constNameFr;

    #[ORM\Column(length: 20, nullable: true)]
    private ?string $constUnit = null;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getConstCode(): string
    {
        return $this->constCode;
    }

    public function setConstCode(string $constCode): static
    {
        $this->constCode = $constCode;

        return $this;
    }

    public function getConstNameFr(): string
    {
        return $this->constNameFr;
    }

    public function setConstNameFr(string $constNameFr): static
    {
        $this->constNameFr = $constNameFr;

        return $this;
    }

    public function getConstUnit(): ?string
    {
        return $this->constUnit;
    }

    public function setConstUnit(?string $constUnit): static
    {
        $this->constUnit = $constUnit;

        return $this;
    }
}
