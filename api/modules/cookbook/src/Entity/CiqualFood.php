<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use ApiPlatform\Doctrine\Orm\Filter\SearchFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Cookbook\Repository\CiqualFoodRepository;
use Symfony\Component\Serializer\Attribute\Groups;
use Symfony\Component\Uid\Ulid;

#[ORM\Entity(repositoryClass: CiqualFoodRepository::class)]
#[ORM\Table(name: 'ciqual_food')]
#[ORM\Index(columns: ['alim_code'], name: 'idx_ciqual_food_alim_code')]
#[ApiResource(
    operations: [
        new GetCollection(normalizationContext: ['groups' => ['ciqual_food:list']]),
        new Get(normalizationContext: ['groups' => ['ciqual_food:read']]),
    ],
)]
#[ApiFilter(SearchFilter::class, properties: ['alimNameFr' => 'ipartial'])]
class CiqualFood
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[Groups(['ciqual_food:list', 'ciqual_food:read'])]
    private Ulid $id;

    #[ORM\Column(length: 10, unique: true)]
    #[Groups(['ciqual_food:list', 'ciqual_food:read'])]
    private string $alimCode;

    #[ORM\Column(length: 255)]
    #[Groups(['ciqual_food:list', 'ciqual_food:read'])]
    private string $alimNameFr;

    #[ORM\Column(length: 10, nullable: true)]
    #[Groups(['ciqual_food:list', 'ciqual_food:read'])]
    private ?string $alimGroupCode = null;

    #[ORM\Column(length: 255, nullable: true)]
    #[Groups(['ciqual_food:list', 'ciqual_food:read'])]
    private ?string $alimGroupNameFr = null;

    #[ORM\Column(length: 10, nullable: true)]
    #[Groups(['ciqual_food:list', 'ciqual_food:read'])]
    private ?string $alimSsgroupCode = null;

    #[ORM\Column(length: 255, nullable: true)]
    #[Groups(['ciqual_food:list', 'ciqual_food:read'])]
    private ?string $alimSsgroupNameFr = null;

    /** @var Collection<int, CiqualFoodNutrient> */
    #[ORM\OneToMany(targetEntity: CiqualFoodNutrient::class, mappedBy: 'food', cascade: ['persist', 'remove'], orphanRemoval: true)]
    #[Groups(['ciqual_food:read'])]
    private Collection $nutrients;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->nutrients = new ArrayCollection();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getAlimCode(): string
    {
        return $this->alimCode;
    }

    public function setAlimCode(string $alimCode): static
    {
        $this->alimCode = $alimCode;

        return $this;
    }

    public function getAlimNameFr(): string
    {
        return $this->alimNameFr;
    }

    public function setAlimNameFr(string $alimNameFr): static
    {
        $this->alimNameFr = $alimNameFr;

        return $this;
    }

    public function getAlimGroupCode(): ?string
    {
        return $this->alimGroupCode;
    }

    public function setAlimGroupCode(?string $alimGroupCode): static
    {
        $this->alimGroupCode = $alimGroupCode;

        return $this;
    }

    public function getAlimGroupNameFr(): ?string
    {
        return $this->alimGroupNameFr;
    }

    public function setAlimGroupNameFr(?string $alimGroupNameFr): static
    {
        $this->alimGroupNameFr = $alimGroupNameFr;

        return $this;
    }

    public function getAlimSsgroupCode(): ?string
    {
        return $this->alimSsgroupCode;
    }

    public function setAlimSsgroupCode(?string $alimSsgroupCode): static
    {
        $this->alimSsgroupCode = $alimSsgroupCode;

        return $this;
    }

    public function getAlimSsgroupNameFr(): ?string
    {
        return $this->alimSsgroupNameFr;
    }

    public function setAlimSsgroupNameFr(?string $alimSsgroupNameFr): static
    {
        $this->alimSsgroupNameFr = $alimSsgroupNameFr;

        return $this;
    }

    /** @return Collection<int, CiqualFoodNutrient> */
    public function getNutrients(): Collection
    {
        return $this->nutrients;
    }

    public function addNutrient(CiqualFoodNutrient $nutrient): static
    {
        if (!$this->nutrients->contains($nutrient)) {
            $this->nutrients->add($nutrient);
            $nutrient->setFood($this);
        }

        return $this;
    }
}
