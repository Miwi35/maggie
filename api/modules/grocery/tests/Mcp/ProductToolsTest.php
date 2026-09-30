<?php

namespace Maggie\Grocery\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Mcp\Tool\AssignProductStoreTool;
use Maggie\Grocery\Mcp\Tool\ManageProductsTool;
use Maggie\Grocery\Mcp\Tool\SearchProductsTool;
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

    private function manageProducts(): ManageProductsTool
    {
        return self::getContainer()->get(ManageProductsTool::class);
    }

    public function testCreateProductWithoutUserIsRefused(): void
    {
        $this->loadFixtures('user.yaml');

        $data = json_decode(($this->manageProducts())('create', name: 'Papier toilette', category: 'household'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('No user bound', $data['error']);
    }

    public function testCreateProductRequiresNameAndCategory(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->manageProducts())('create', name: 'Papier toilette'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testListProductsReturnsTheCurrentUserProducts(): void
    {
        $this->loadFixtures('product.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->manageProducts())('list'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(1, $data['count']);
        self::assertSame('Bananes', $data['products'][0]['name']);
    }

    public function testUpdateProductRenamesAndPublishes(): void
    {
        $this->loadFixtures('product.yaml');
        $this->loginFixtureUser();

        $product = $this->getFixture('product_bananes');

        $data = json_decode(($this->manageProducts())('update', productId: (string) $product->getId(), name: 'Bananes bio'), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Bananes bio', $data['product']['name']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame('Bananes bio', $em->find(Product::class, $product->getId())->getName());

        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testDeleteProductRemovesAndPublishes(): void
    {
        $this->loadFixtures('product.yaml');
        $this->loginFixtureUser();

        $product = $this->getFixture('product_bananes');

        $data = json_decode(($this->manageProducts())('delete', productId: (string) $product->getId()), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Product::class, $product->getId()));

        $this->assertMercureUpdatePublished('/products/');
    }

    public function testCreateProductPersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $result = ($this->manageProducts())('create', name: 'Papier toilette', category: 'household');

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
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(SearchProductsTool::class);
        $result = $tool('Tomate');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(1, $data['products']);
        self::assertSame('ingredient', $data['products'][0]['type']);
    }

    public function testAssignProductStoreUpdates(): void
    {
        $this->loadFixtures('ingredient.yaml');
        $this->loginFixtureUser();

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

    public function testUpdateProductClearEmptiesTheDefaultUnit(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $created = json_decode(($this->manageProducts())('create', name: 'Papier toilette', category: 'household', defaultUnit: 'pack'), true, 512, JSON_THROW_ON_ERROR);
        $id = $created['product']['id'];
        self::assertSame('pack', $created['product']['defaultUnit']);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = json_decode(($this->manageProducts())('update', productId: $id, clear: ['defaultUnit', 'name', 'category']), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertNull($data['product']['defaultUnit']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $product = $em->getRepository(Product::class)->find($id);
        self::assertNull($product->getDefaultUnit());
        self::assertSame('Papier toilette', $product->getName(), 'Required fields cannot be cleared');
        self::assertSame('household', $product->getCategory()->value);

        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testUpdateProductWithoutClearKeepsTheDefaultUnit(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $created = json_decode(($this->manageProducts())('create', name: 'Papier toilette', category: 'household', defaultUnit: 'pack'), true, 512, JSON_THROW_ON_ERROR);

        $data = json_decode(($this->manageProducts())('update', productId: $created['product']['id'], name: 'Essuie-tout'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('Essuie-tout', $data['product']['name']);
        self::assertSame('pack', $data['product']['defaultUnit']);
    }

    public function testUpdateUnknownProductReturnsAnError(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->manageProducts())('update', productId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['defaultUnit']), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }
}
