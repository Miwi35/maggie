<?php

namespace Maggie\Cookbook\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Message\UpdateIngredientCommand;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateIngredientHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('UpdateIngredientHandlerTest.yaml');
    }

    private function dispatch(UpdateIngredientCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function id(): string
    {
        return (string) $this->getFixture('tomato')->getId();
    }

    private function reload(): Ingredient
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Ingredient::class)->find($this->getFixture('tomato')->getId());
    }

    public function testClearingNutritionAndCiqualCodeLeavesTheRestUntouched(): void
    {
        $this->dispatch(new UpdateIngredientCommand(
            ingredientId: $this->id(),
            clearFields: ['ciqualAlimCode', 'kcalPer100g', 'proteinPer100g', 'carbsPer100g', 'fatPer100g'],
        ));

        $ingredient = $this->reload();
        self::assertNull($ingredient->getCiqualAlimCode());
        self::assertNull($ingredient->getKcalPer100g());
        self::assertNull($ingredient->getProteinPer100g());
        self::assertNull($ingredient->getCarbsPer100g());
        self::assertNull($ingredient->getFatPer100g());
        self::assertSame('Tomate', $ingredient->getName());
        self::assertSame(ProductCategory::Produce, $ingredient->getCategory());
        self::assertSame(Unit::Gram, $ingredient->getDefaultUnit());
        $this->assertMercureUpdatePublished('/ingredients/');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);
    }

    public function testClearingDefaultUnit(): void
    {
        $this->dispatch(new UpdateIngredientCommand(ingredientId: $this->id(), clearFields: ['defaultUnit']));

        $ingredient = $this->reload();
        self::assertNull($ingredient->getDefaultUnit());
        self::assertSame('20047', $ingredient->getCiqualAlimCode());
        self::assertSame(18.0, $ingredient->getKcalPer100g());
    }

    public function testNullFieldsWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateIngredientCommand(ingredientId: $this->id(), name: 'Tomate cerise'));

        $ingredient = $this->reload();
        self::assertSame('Tomate cerise', $ingredient->getName());
        self::assertSame('20047', $ingredient->getCiqualAlimCode());
        self::assertSame(18.0, $ingredient->getKcalPer100g());
        self::assertSame(Unit::Gram, $ingredient->getDefaultUnit());
    }

    public function testAValueWinsOverAClearOfTheSameField(): void
    {
        $this->dispatch(new UpdateIngredientCommand(ingredientId: $this->id(), kcalPer100g: 20.0, clearFields: ['kcalPer100g']));

        self::assertSame(20.0, $this->reload()->getKcalPer100g());
    }

    public function testUnknownIngredientFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Ingredient not found');

        $this->dispatch(new UpdateIngredientCommand(ingredientId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clearFields: ['kcalPer100g']));
    }
}
