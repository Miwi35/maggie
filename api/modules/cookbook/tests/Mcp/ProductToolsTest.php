<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Mcp\Tool\ManageIngredientsTool;
use Maggie\Cookbook\Mcp\Tool\SearchIngredientsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class ProductToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function manageIngredients(): ManageIngredientsTool
    {
        return self::getContainer()->get(ManageIngredientsTool::class);
    }

    public function testCreateIngredientWithoutUserIsRefused(): void
    {
        $this->loadFixtures('user.yaml');

        $data = json_decode(($this->manageIngredients())('create', name: 'Carotte', category: 'produce'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('No user bound', $data['error']);
    }

    public function testCreateIngredientRequiresNameAndCategory(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->manageIngredients())('create', name: 'Carotte'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testUnknownActionIsRejected(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->manageIngredients())('taste'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('Unknown action', $data['error']);
    }

    public function testUpdateIngredientRenamesAndPublishes(): void
    {
        $this->loadFixtures('ingredient.yaml');
        $this->loginFixtureUser();

        $tomato = $this->getFixture('tomato');

        $data = json_decode(($this->manageIngredients())('update', ingredientId: (string) $tomato->getId(), name: 'Tomate cerise', kcalPer100g: 18.0), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame('Tomate cerise', $data['ingredient']['name']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(Ingredient::class, $tomato->getId());
        self::assertSame('Tomate cerise', $stored->getName());
        self::assertSame(18.0, $stored->getKcalPer100g());

        $this->assertMercureUpdatePublished('/ingredients/');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);
    }

    public function testDeleteIngredientRemovesAndPublishes(): void
    {
        $this->loadFixtures('ingredient.yaml');
        $this->loginFixtureUser();

        $tomato = $this->getFixture('tomato');

        $data = json_decode(($this->manageIngredients())('delete', ingredientId: (string) $tomato->getId()), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Ingredient::class, $tomato->getId()));

        $this->assertMercureUpdatePublished('/ingredients/');
    }

    public function testCreateIngredientPersistsAndPublishes(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $result = ($this->manageIngredients())('create', name: 'Carotte', category: 'produce', defaultUnit: 'g');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Carotte', $data['ingredient']['name']);
        self::assertSame('produce', $data['ingredient']['category']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $ingredients = $em->getRepository(Ingredient::class)->findAll();
        self::assertCount(1, $ingredients);
        self::assertSame('Carotte', $ingredients[0]->getName());

        $this->assertMercureUpdatePublished('/ingredients/');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);
    }

    public function testSearchIngredientsReturnsResults(): void
    {
        $this->loadFixtures('ingredient.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(SearchIngredientsTool::class);
        $result = $tool('Tomate');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(1, $data['ingredients']);
        self::assertSame('Tomate', $data['ingredients'][0]['name']);
    }

    public function testUpdateIngredientClearEmptiesOptionalFields(): void
    {
        $this->loadFixtures('ingredient_nutrition.yaml');
        $this->loginFixtureUser();

        $tomato = $this->getFixture('tomato');

        $data = json_decode(
            ($this->manageIngredients())('update', ingredientId: (string) $tomato->getId(), clear: ['ciqualAlimCode', 'kcalPer100g', 'defaultUnit', 'name', 'category']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(Ingredient::class, $tomato->getId());
        self::assertNull($stored->getCiqualAlimCode());
        self::assertNull($stored->getKcalPer100g());
        self::assertNull($stored->getDefaultUnit());
        self::assertSame(0.9, $stored->getProteinPer100g(), 'Fields not listed in clear are untouched');
        self::assertSame('Tomate', $stored->getName(), 'Required fields cannot be cleared');
        self::assertSame('produce', $stored->getCategory()->value, 'Required fields cannot be cleared');

        $this->assertMercureUpdatePublished('/ingredients/');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);
    }

    public function testUpdateUnknownIngredientReturnsAnError(): void
    {
        $this->loadFixtures('ingredient_nutrition.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->manageIngredients())('update', ingredientId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['kcalPer100g']), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testCreateIngredientWithItsPackaging(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->manageIngredients())('create', name: 'Riz', category: 'grain', defaultUnit: 'g', packagingUnit: 'pack', packagingSize: 500, packagingSizeUnit: 'g'), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame('pack', $data['ingredient']['packagingUnit']);
        self::assertEquals(500, $data['ingredient']['packagingSize']);
        self::assertSame('g', $data['ingredient']['packagingSizeUnit']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(Ingredient::class, $data['ingredient']['id']);
        self::assertSame('pack', $stored->getPackagingUnit()?->value);
        self::assertSame(500.0, $stored->getPackagingSize());
        self::assertSame('g', $stored->getPackagingSizeUnit()?->value);

        $this->assertMercureUpdatePublished('/ingredients/');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);
    }

    public function testCreateIngredientRefusesASizeWithoutItsUnit(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->manageIngredients())('create', name: 'Riz', category: 'grain', packagingUnit: 'pack', packagingSize: 500), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertSame([], self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(Ingredient::class)->findAll());
    }

    public function testUpdateIngredientSetsThenClearsThePackaging(): void
    {
        $this->loadFixtures('ingredient_nutrition.yaml');
        $this->loginFixtureUser();
        $id = (string) $this->getFixture('tomato')->getId();

        $data = json_decode(($this->manageIngredients())('update', ingredientId: $id, packagingUnit: 'can', packagingSize: 400, packagingSizeUnit: 'g'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('can', $data['ingredient']['packagingUnit']);
        self::assertEquals(400, $data['ingredient']['packagingSize']);
        $this->assertMercureUpdatePublished('/ingredients/');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);

        $data = json_decode(($this->manageIngredients())('update', ingredientId: $id, clear: ['packagingUnit', 'packagingSize', 'packagingSizeUnit']), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(Ingredient::class, $id);
        self::assertNull($stored->getPackagingUnit());
        self::assertNull($stored->getPackagingSize());
        self::assertNull($stored->getPackagingSizeUnit());
    }

    public function testCreateIngredientIsInStockByDefault(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->manageIngredients())('create', name: 'Carotte', category: 'produce'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(['in_stock', null, false], [$data['ingredient']['stockState'], $data['ingredient']['restockQuantity'], $data['ingredient']['autoRestock']]);
    }

    public function testIngredientStockIsSetMovedThenClearedAndPublished(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $created = json_decode(($this->manageIngredients())('create', name: 'Riz', category: 'grain', stockState: 'low', restockQuantity: 2, autoRestock: true), true, 512, JSON_THROW_ON_ERROR);
        $id = $created['ingredient']['id'];
        self::assertSame(['low', 2, true], [$created['ingredient']['stockState'], $created['ingredient']['restockQuantity'], $created['ingredient']['autoRestock']]);

        $data = json_decode(($this->manageIngredients())('update', ingredientId: $id, stockState: 'out'), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(['out', 2, true], [$data['ingredient']['stockState'], $data['ingredient']['restockQuantity'], $data['ingredient']['autoRestock']]);
        $this->assertMercureUpdatePublished('/ingredients/');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);

        $data = json_decode(($this->manageIngredients())('update', ingredientId: $id, autoRestock: false, clear: ['restockQuantity']), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(Ingredient::class, $id);
        self::assertSame(['out', null, false], [$stored->getStockState()->value, $stored->getRestockQuantity(), $stored->isAutoRestock()]);
    }

    public function testIngredientGoingOutRestocksItWhenAutomaticRestockIsOn(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();
        $created = json_decode(($this->manageIngredients())('create', name: 'Riz', category: 'grain', restockQuantity: 2, autoRestock: true), true, 512, JSON_THROW_ON_ERROR);
        $id = $created['ingredient']['id'];

        json_decode(($this->manageIngredients())('update', ingredientId: $id, stockState: 'out'), true, 512, JSON_THROW_ON_ERROR);
        json_decode(($this->manageIngredients())('update', ingredientId: $id, stockState: 'out'), true, 512, JSON_THROW_ON_ERROR);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $items = $em->getRepository(\Maggie\Grocery\Entity\GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame(2.0, $items[0]->getQuantity());
        self::assertSame(\Maggie\Grocery\Enum\GroceryItemSource::Restock, $items[0]->getSource());
        $this->assertMercureUpdatePublished('/grocery_lists/');
    }

    public function testIngredientCreatedOutIsRestockedByTheCreation(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        json_decode(($this->manageIngredients())('create', name: 'Riz', category: 'grain', stockState: 'out', restockQuantity: 2, autoRestock: true), true, 512, JSON_THROW_ON_ERROR);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $items = $em->getRepository(\Maggie\Grocery\Entity\GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame(2.0, $items[0]->getQuantity());
        self::assertSame(\Maggie\Grocery\Enum\GroceryItemSource::Restock, $items[0]->getSource());
    }

    public function testIngredientRefusesAnUnknownStateAndANegativeRestockQuantity(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $unknown = json_decode(($this->manageIngredients())('create', name: 'Riz', category: 'grain', stockState: 'plenty'), true, 512, JSON_THROW_ON_ERROR);
        $negative = json_decode(($this->manageIngredients())('create', name: 'Riz', category: 'grain', restockQuantity: -1), true, 512, JSON_THROW_ON_ERROR);

        self::assertStringContainsString('stock state', $unknown['error']);
        self::assertStringContainsString('restockQuantity', $negative['error']);
        self::assertSame([], self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(Ingredient::class)->findAll());
    }
}
