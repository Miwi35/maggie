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
}
