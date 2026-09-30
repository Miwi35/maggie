<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use ApiPlatform\Doctrine\Orm\Filter\DateFilter;
use ApiPlatform\Doctrine\Orm\Filter\OrderFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Entity\Event;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\State\CreateMealProcessor;
use Maggie\Cookbook\State\DeleteMealProcessor;
use Maggie\Cookbook\State\UpdateMealProcessor;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;

#[ORM\Entity(repositoryClass: MealRepository::class)]
#[ApiFilter(DateFilter::class, properties: ['startAt'])]
#[ApiFilter(OrderFilter::class, properties: ['startAt'])]
#[Indexed(index: 'meals', module: 'cookbook')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateMealProcessor::class),
    new Patch(processor: UpdateMealProcessor::class),
    new Delete(processor: DeleteMealProcessor::class),
])]
class Meal extends Event implements MercurePublishable
{
    #[ORM\Column(length: 10, enumType: MealSlot::class)]
    #[IndexedField(type: 'keyword')]
    private MealSlot $slot;

    /** @var Collection<int, Recipe> */
    #[ORM\ManyToMany(targetEntity: Recipe::class)]
    #[ORM\JoinTable(name: 'meal_recipe')]
    private Collection $recipes;

    public function __construct()
    {
        parent::__construct();
        $this->recipes = new ArrayCollection();
    }

    public function getSlot(): MealSlot
    {
        return $this->slot;
    }

    public function setSlot(MealSlot $slot): static
    {
        $this->slot = $slot;

        return $this;
    }

    /** @return Collection<int, Recipe> */
    public function getRecipes(): Collection
    {
        return $this->recipes;
    }

    public function addRecipe(Recipe $recipe): static
    {
        if (!$this->recipes->contains($recipe)) {
            $this->recipes->add($recipe);
        }

        return $this;
    }

    public function removeRecipe(Recipe $recipe): static
    {
        $this->recipes->removeElement($recipe);

        return $this;
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        $doc = parent::toSearchDocument();
        $doc['slot'] = $this->slot->value;
        $doc['recipeIds'] = $this->recipes->map(fn (Recipe $r) => (string) $r->getId())->toArray();

        return $doc;
    }

    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'summary' => $this->getSummary(),
            'startAt' => $this->getStartAt()->format('c'),
            'endAt' => $this->getEndAt()->format('c'),
            'slot' => $this->slot->value,
            'recipes' => $this->recipes->map(fn (Recipe $r) => [
                'id' => (string) $r->getId(),
                'name' => $r->getName(),
            ])->toArray(),
        ], $changedProperties);
    }
}
