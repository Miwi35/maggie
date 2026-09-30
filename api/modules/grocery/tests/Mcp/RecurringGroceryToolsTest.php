<?php

namespace Maggie\Grocery\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Mcp\Tool\ManageRecurringGroceriesTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class RecurringGroceryToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function tool(): ManageRecurringGroceriesTool
    {
        return self::getContainer()->get(ManageRecurringGroceriesTool::class);
    }

    public function testCreateWithoutUserIsRefused(): void
    {
        $this->loadFixtures('user.yaml');

        $data = json_decode(($this->tool())('create', frequency: 'weekly', customLabel: 'Pain de mie'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('No user bound', $data['error']);
    }

    public function testCreateRequiresAFrequency(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('create', customLabel: 'Pain de mie'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testUnknownActionIsRejected(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('pause'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('Unknown action', $data['error']);
    }

    public function testCreatePersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $result = ($this->tool())('create', frequency: 'weekly', customLabel: 'Pain de mie', quantity: 1, unit: 'pack');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Pain de mie', $data['recurringItem']['label']);
        self::assertSame('weekly', $data['recurringItem']['frequency']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $items = $em->getRepository(RecurringGroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame('Pain de mie', $items[0]->getLabel());

        $this->assertMercureUpdatePublished('/recurring_grocery_items/');
        $this->assertElasticsearchIndexDispatched(RecurringGroceryItem::class);
    }

    public function testListReturnsTheCurrentUserItems(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        ($this->tool())('create', frequency: 'weekly', customLabel: 'Pain de mie');
        ($this->tool())('create', frequency: 'monthly', customLabel: 'Lessive');

        $data = json_decode(($this->tool())('list'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(2, $data['count']);
        self::assertSame(['Pain de mie', 'Lessive'], array_column($data['recurringItems'], 'label'));
    }

    public function testUpdateChangesFrequencyAndPublishes(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $created = json_decode(($this->tool())('create', frequency: 'weekly', customLabel: 'Pain de mie'), true, 512, JSON_THROW_ON_ERROR);
        $this->resetMercure();

        $data = json_decode(($this->tool())('update', recurringItemId: $created['recurringItem']['id'], frequency: 'biweekly'), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame('biweekly', $data['recurringItem']['frequency']);

        $this->assertMercureUpdatePublished('/recurring_grocery_items/');
    }

    public function testDeleteRemovesAndPublishes(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $created = json_decode(($this->tool())('create', frequency: 'weekly', customLabel: 'Pain de mie'), true, 512, JSON_THROW_ON_ERROR);
        $this->resetMercure();

        $data = json_decode(($this->tool())('delete', recurringItemId: $created['recurringItem']['id']), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertCount(0, $em->getRepository(RecurringGroceryItem::class)->findAll());

        $this->assertMercureUpdatePublished('/recurring_grocery_items/');
    }

    public function testUpdateClearEmptiesQuantityUnitAndProduct(): void
    {
        $this->loadFixtures('product.yaml');
        $this->loginFixtureUser();

        $created = json_decode(($this->tool())(
            'create',
            frequency: 'weekly',
            productId: (string) $this->getFixture('product_bananes')->getId(),
            customLabel: 'Bananes bio',
            quantity: 2,
            unit: 'kg',
        ), true, 512, JSON_THROW_ON_ERROR);
        $id = $created['recurringItem']['id'];
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = json_decode(($this->tool())('update', recurringItemId: $id, clear: ['quantity', 'unit', 'productId', 'frequency']), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertNull($data['recurringItem']['quantity']);
        self::assertNull($data['recurringItem']['unit']);
        self::assertNull($data['recurringItem']['productId']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $item = $em->getRepository(RecurringGroceryItem::class)->find($id);
        self::assertNull($item->getQuantity());
        self::assertNull($item->getUnit());
        self::assertNull($item->getProduct());
        self::assertSame('Bananes bio', $item->getCustomLabel());
        self::assertSame('weekly', $item->getFrequency()->value, 'Required fields cannot be cleared');

        $this->assertMercureUpdatePublished('/recurring_grocery_items/');
        $this->assertElasticsearchIndexDispatched(RecurringGroceryItem::class);
    }

    public function testUpdateClearCannotLeaveTheItemWithoutProductNorLabel(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $created = json_decode(($this->tool())('create', frequency: 'weekly', customLabel: 'Pain de mie', quantity: 1), true, 512, JSON_THROW_ON_ERROR);
        $id = $created['recurringItem']['id'];

        $data = json_decode(($this->tool())('update', recurringItemId: $id, clear: ['customLabel', 'quantity']), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $item = $em->getRepository(RecurringGroceryItem::class)->find($id);
        self::assertSame('Pain de mie', $item->getCustomLabel());
        self::assertSame(1.0, $item->getQuantity());
    }

    public function testUpdateUnknownItemReturnsAnError(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('update', recurringItemId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['quantity']), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }
}
