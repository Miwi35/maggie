<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\Common\Collections\Collection;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * An entity rebuilt from its Elasticsearch document must hold a real
 * collection for every one-to-many / many-to-many property: a raw array made
 * the hydrator throw, and the provider silently fell back to Doctrine on
 * every read (MAG-481).
 */
class ElasticsearchEntityHydratorCollectionTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('ElasticsearchEntityHydratorCollectionTest.yaml');
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }

    /**
     * What the provider does with a hit: the indexed document through JSON,
     * on an entity manager that knows nothing about the row.
     */
    private function hydrate(IndexableInterface $entity): object
    {
        $source = json_decode(json_encode($entity->toSearchDocument(), \JSON_THROW_ON_ERROR), true, 512, \JSON_THROW_ON_ERROR);
        $source['id'] = (string) $entity->getId();
        $this->em()->clear();

        return (new ElasticsearchEntityHydrator($this->em()))->hydrate($source, $entity::class);
    }

    /**
     * @param Collection<int, object> $collection
     *
     * @return list<string>
     */
    private function ids(Collection $collection): array
    {
        $ids = array_map(static fn (object $item): string => (string) $item->getId(), $collection->toArray());
        sort($ids);

        return $ids;
    }

    public function testAGroceryListComesBackWithItsItems(): void
    {
        $list = $this->em()->getRepository(GroceryList::class)->findOneBy([]);
        self::assertNotNull($list);
        $this->em()->refresh($list);
        $expected = $this->ids($list->getItems());
        self::assertCount(2, $expected);

        $hydrated = $this->hydrate($list);

        self::assertInstanceOf(GroceryList::class, $hydrated);
        self::assertSame($expected, $this->ids($hydrated->getItems()));
    }

    public function testARecipeComesBackWithItsIngredients(): void
    {
        $recipe = $this->em()->getRepository(Recipe::class)->findOneBy(['name' => 'Pâtes à la tomate']);
        self::assertNotNull($recipe);
        $this->em()->refresh($recipe);
        $expected = $this->ids($recipe->getIngredients());
        self::assertCount(2, $expected);

        $hydrated = $this->hydrate($recipe);

        self::assertInstanceOf(Recipe::class, $hydrated);
        self::assertSame($expected, $this->ids($hydrated->getIngredients()));
    }

    public function testAnAgendaComesBackWithItsEvents(): void
    {
        $agenda = $this->em()->getRepository(Agenda::class)->findOneBy([]);
        self::assertNotNull($agenda);
        $this->em()->refresh($agenda);
        $expected = $this->ids($agenda->getEvents());
        self::assertCount(2, $expected);

        $hydrated = $this->hydrate($agenda);

        self::assertInstanceOf(Agenda::class, $hydrated);
        self::assertSame($expected, $this->ids($hydrated->getEvents()));
    }

    public function testAMealComesBackWithItsRecipes(): void
    {
        $em = $this->em();
        $agenda = $em->getRepository(Agenda::class)->findOneBy([]);
        self::assertNotNull($agenda);
        $meal = (new Meal())
            ->setAgenda($agenda)
            ->setSlot(MealSlot::Lunch)
            ->setDate(new \DateTimeImmutable('2026-10-22'));
        foreach ($em->getRepository(Recipe::class)->findAll() as $recipe) {
            $meal->addRecipe($recipe);
        }
        $em->persist($meal);
        $em->flush();
        $expected = $this->ids($meal->getRecipes());
        self::assertCount(2, $expected);

        $hydrated = $this->hydrate($meal);

        self::assertInstanceOf(Meal::class, $hydrated);
        self::assertSame($expected, $this->ids($hydrated->getRecipes()));
    }
}
