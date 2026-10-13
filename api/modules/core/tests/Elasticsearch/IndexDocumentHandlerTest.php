<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Core\Elasticsearch\IndexableEntityRegistry;
use Maggie\Core\Elasticsearch\IndexManager;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Elasticsearch\MessageHandler\IndexDocumentHandler;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Enum\ProductCategory;
use PHPUnit\Framework\TestCase;
use Psr\Log\LoggerInterface;

/**
 * The handler used to drop, without a word, the document of an entity whose
 * parent declares the index (Ingredient extends Product): the dispatch was
 * asserted everywhere, the document reaching the index nowhere.
 */
final class IndexDocumentHandlerTest extends TestCase
{
    use RecordingElasticsearchTrait;

    public function testAnIngredientIsIndexedInTheIndexOfItsParent(): void
    {
        $ingredient = (new Ingredient())
            ->setName('Carotte, crue')
            ->setCategory(ProductCategory::Produce)
            ->setUser(new User());
        $em = $this->createStub(EntityManagerInterface::class);
        $em->method('find')->willReturn($ingredient);
        $reader = new IndexMetadataReader();
        $manager = new IndexManager(
            $this->recordingClient(static fn (): array => [200, ['result' => 'created']]),
            $reader,
            new IndexableEntityRegistry($em, $reader),
            $this->createStub(LoggerInterface::class),
        );
        $handler = new IndexDocumentHandler($em, $manager, $reader, $this->createStub(LoggerInterface::class));

        $handler(new IndexDocumentCommand(Ingredient::class, (string) $ingredient->getId()));

        self::assertCount(1, $this->requests, 'The document must reach Elasticsearch.');
        self::assertStringStartsWith('/products/_doc/'.$ingredient->getId(), $this->requests[0]['path']);
        self::assertSame('Carotte, crue', $this->lastRequestBody()['name']);
    }

    /** MAG-323: the `events` index holds events; a meal, which has its own, is not one of its documents. */
    public function testAMealIsIndexedInTheMealsIndexOnly(): void
    {
        $meal = (new Meal())->setSummary('Déjeuner : pâtes')->setSlot(MealSlot::Lunch)->setDate(new \DateTimeImmutable('2030-03-15'));
        $meal->setAgenda((new Agenda())->setUser(new User()));
        $em = $this->createStub(EntityManagerInterface::class);
        $em->method('find')->willReturn($meal);
        $reader = new IndexMetadataReader();
        $manager = new IndexManager(
            $this->recordingClient(static fn (): array => [200, ['result' => 'created']]),
            $reader,
            new IndexableEntityRegistry($em, $reader),
            $this->createStub(LoggerInterface::class),
        );
        $handler = new IndexDocumentHandler($em, $manager, $reader, $this->createStub(LoggerInterface::class));

        $handler(new IndexDocumentCommand(Meal::class, (string) $meal->getId()));

        self::assertCount(1, $this->requests);
        self::assertStringStartsWith('/meals/_doc/'.$meal->getId(), $this->requests[0]['path']);
    }
}
