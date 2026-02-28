<?php

namespace Maggie\Grocery\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Mcp\Tool\AssignProductStoreTool;
use Maggie\Grocery\Mcp\Tool\CreateProductTool;
use Maggie\Grocery\Mcp\Tool\SearchProductsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class ProductToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testCreateProductPersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('user.yaml');

        $tool = self::getContainer()->get(CreateProductTool::class);
        $result = $tool('Papier toilette', 'household');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Papier toilette', $data['product']['name']);
        self::assertSame('household', $data['product']['category']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $products = $em->getRepository(Product::class)->findAll();
        self::assertCount(1, $products);

        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testSearchProductsReturnsResults(): void
    {
        $this->loadFixtures('ingredient.yaml');

        $tool = self::getContainer()->get(SearchProductsTool::class);
        $result = $tool('Tomate');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(1, $data['products']);
        self::assertSame('ingredient', $data['products'][0]['type']);
    }

    public function testAssignProductStoreUpdates(): void
    {
        $this->loadFixtures('ingredient.yaml');

        $tomato = $this->getFixture('tomato');
        $supermarket = $this->getFixture('supermarket');

        $tool = self::getContainer()->get(AssignProductStoreTool::class);
        $result = $tool(
            (string) $tomato->getId(),
            preferredStoreId: (string) $supermarket->getId(),
            shelfLifeDays: 7,
        );

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame(7, $data['product']['shelfLifeDays']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Product::class, $tomato->getId());
        self::assertSame(7, $refreshed->getShelfLifeDays());
    }
}
