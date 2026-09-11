<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Mcp\Tool\CreateIngredientTool;
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

    public function testCreateIngredientPersistsAndPublishes(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(CreateIngredientTool::class);
        $result = $tool('Carotte', 'produce', 'g');

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
