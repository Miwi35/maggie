<?php

namespace Maggie\Cookbook\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\UpdateRecipeCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateRecipeHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use FakesCiqualTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('UpdateRecipeHandlerTest.yaml');
        $this->fakeCiqual();
    }

    private function dispatch(UpdateRecipeCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function id(): string
    {
        return (string) $this->getFixture('pasta')->getId();
    }

    private function reload(): Recipe
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Recipe::class)->find($this->getFixture('pasta')->getId());
    }

    public function testClearingNotesLeavesTheRestUntouched(): void
    {
        $this->dispatch(new UpdateRecipeCommand(recipeId: $this->id(), clearFields: ['notes']));

        $recipe = $this->reload();
        self::assertNull($recipe->getNotes());
        self::assertSame('Pâtes à la tomate', $recipe->getName());
        self::assertSame(4, $recipe->getServings());
        self::assertSame(['pasta', 'italian'], $recipe->getTags());
        $this->assertMercureUpdatePublished('/recipes/');
        $this->assertElasticsearchIndexDispatched(Recipe::class);
    }

    public function testNullNotesWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateRecipeCommand(recipeId: $this->id(), name: 'Pâtes bolognaise'));

        $recipe = $this->reload();
        self::assertSame('Pâtes bolognaise', $recipe->getName());
        self::assertSame('Ajouter du basilic', $recipe->getNotes());
    }

    public function testAnEmptyTagListEmptiesTheTags(): void
    {
        $this->dispatch(new UpdateRecipeCommand(recipeId: $this->id(), tags: []));

        $recipe = $this->reload();
        self::assertSame([], $recipe->getTags());
        self::assertSame('Ajouter du basilic', $recipe->getNotes());
    }

    public function testUnknownRecipeFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Recipe not found');

        $this->dispatch(new UpdateRecipeCommand(recipeId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clearFields: ['notes']));
    }

    public function testAnIngredientCreatedFromACiqualCodeIsIndexedAndPublished(): void
    {
        $this->dispatch(new UpdateRecipeCommand(
            recipeId: $this->id(),
            ingredients: [['ciqualAlimCode' => self::COURGETTE, 'quantity' => 300.0, 'unit' => 'g']],
        ));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $courgette = $em->getRepository(Ingredient::class)->findOneBy(['ciqualAlimCode' => self::COURGETTE]);
        self::assertNotNull($courgette);
        $this->assertElasticsearchIndexDispatched(Ingredient::class);
        $this->assertMercureUpdatePublished('/ingredients/'.$courgette->getId());
    }

    /** @return array<string, mixed> the last update published for the recipe */
    private function lastPublishedRecipe(): array
    {
        $topic = '/api/recipes/'.$this->id();
        foreach (array_reverse($this->getMercureHub()->getUpdates()) as $update) {
            if (str_ends_with($update->getTopics()[0], $topic)) {
                return json_decode($update->getData(), true, 512, JSON_THROW_ON_ERROR);
            }
        }

        self::fail('No Mercure update published for the recipe.');
    }

    public function testChangingAQuantityPublishesTheRecipeWithItsIngredients(): void
    {
        $pastaId = (string) $this->getFixture('pasta_food')->getId();
        $this->dispatch(new UpdateRecipeCommand(
            recipeId: $this->id(),
            ingredients: [['ingredientId' => $pastaId, 'quantity' => 350.0, 'unit' => 'g']],
        ));

        $payload = $this->lastPublishedRecipe();
        self::assertCount(1, $payload['ingredients']);
        $line = $payload['ingredients'][0];
        self::assertSame(350.0, (float) $line['quantity']);
        self::assertSame('g', $line['unit']);
        self::assertSame('Pâtes', $line['ingredientName']);
        self::assertSame('9810', $line['ciqualAlimCode']);
        self::assertSame('/api/ingredients/'.$pastaId, $line['ingredient']['@id']);
        self::assertSame($pastaId, $line['ingredient']['id']);
        self::assertArrayNotHasKey('name', $payload);
    }

    public function testRemovingEveryIngredientPublishesAnEmptyList(): void
    {
        $this->dispatch(new UpdateRecipeCommand(recipeId: $this->id(), ingredients: []));

        self::assertSame([], $this->lastPublishedRecipe()['ingredients']);
    }

    public function testChangingTheNotesPublishesThemAlone(): void
    {
        $this->dispatch(new UpdateRecipeCommand(recipeId: $this->id(), notes: 'Ajouter du parmesan'));

        $payload = $this->lastPublishedRecipe();
        self::assertSame('Ajouter du parmesan', $payload['notes']);
        self::assertArrayNotHasKey('ingredients', $payload);
    }

    public function testClearingTheNotesPublishesANullValue(): void
    {
        $this->dispatch(new UpdateRecipeCommand(recipeId: $this->id(), clearFields: ['notes']));

        $payload = $this->lastPublishedRecipe();
        self::assertArrayHasKey('notes', $payload);
        self::assertNull($payload['notes']);
    }

    public function testChangingTheTagsPublishesThemAlone(): void
    {
        $this->dispatch(new UpdateRecipeCommand(recipeId: $this->id(), tags: ['pasta', 'quick']));

        $payload = $this->lastPublishedRecipe();
        self::assertSame(['pasta', 'quick'], $payload['tags']);
        self::assertArrayNotHasKey('notes', $payload);
    }
}
