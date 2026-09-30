<?php

namespace Maggie\Grocery\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Message\UpdateRecurringGroceryItemCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateRecurringGroceryItemHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('UpdateRecurringGroceryItemHandlerTest.yaml');
    }

    private function dispatch(UpdateRecurringGroceryItemCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function reload(string $ref = 'item_full'): RecurringGroceryItem
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(RecurringGroceryItem::class)->find($this->getFixture($ref)->getId());
    }

    private function id(string $ref = 'item_full'): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    public function testClearingQuantityAndUnitLeavesTheRestUntouched(): void
    {
        $this->dispatch(new UpdateRecurringGroceryItemCommand(
            recurringGroceryItemId: $this->id(),
            clearFields: ['quantity', 'unit'],
        ));

        $item = $this->reload();
        self::assertNull($item->getQuantity());
        self::assertNull($item->getUnit());
        self::assertSame('Bananes bio', $item->getCustomLabel());
        self::assertNotNull($item->getProduct());
        self::assertSame('weekly', $item->getFrequency()->value);
        $this->assertMercureUpdatePublished('/recurring_grocery_items/');
        $this->assertElasticsearchIndexDispatched(RecurringGroceryItem::class);
    }

    public function testClearingTheProductKeepsTheCustomLabel(): void
    {
        $this->dispatch(new UpdateRecurringGroceryItemCommand(
            recurringGroceryItemId: $this->id(),
            clearFields: ['productId'],
        ));

        $item = $this->reload();
        self::assertNull($item->getProduct());
        self::assertSame('Bananes bio', $item->getCustomLabel());
        self::assertSame(2.5, $item->getQuantity());
    }

    public function testClearingTheCustomLabelKeepsTheProduct(): void
    {
        $this->dispatch(new UpdateRecurringGroceryItemCommand(
            recurringGroceryItemId: $this->id(),
            clearFields: ['customLabel'],
        ));

        $item = $this->reload();
        self::assertNull($item->getCustomLabel());
        self::assertSame('Bananes', $item->getLabel());
    }

    public function testClearingBothProductAndLabelIsRefusedAndChangesNothing(): void
    {
        try {
            $this->dispatch(new UpdateRecurringGroceryItemCommand(
                recurringGroceryItemId: $this->id(),
                clearFields: ['productId', 'customLabel', 'quantity'],
            ));
            self::fail('Expected the update to be refused.');
        } catch (\Throwable $e) {
            self::assertStringContainsString('needs a product or a custom label', $e->getMessage());
        }

        $item = $this->reload();
        self::assertNotNull($item->getProduct());
        self::assertSame('Bananes bio', $item->getCustomLabel());
        self::assertSame(2.5, $item->getQuantity());
    }

    public function testClearingTheOnlyLabelOfAProductlessItemIsRefused(): void
    {
        try {
            $this->dispatch(new UpdateRecurringGroceryItemCommand(
                recurringGroceryItemId: $this->id('item_label_only'),
                clearFields: ['customLabel'],
            ));
            self::fail('Expected the update to be refused.');
        } catch (\Throwable $e) {
            self::assertStringContainsString('needs a product or a custom label', $e->getMessage());
        }

        self::assertSame('Pain de mie', $this->reload('item_label_only')->getCustomLabel());
    }

    public function testNullFieldsWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateRecurringGroceryItemCommand(
            recurringGroceryItemId: $this->id(),
            frequency: 'monthly',
        ));

        $item = $this->reload();
        self::assertSame('monthly', $item->getFrequency()->value);
        self::assertSame(2.5, $item->getQuantity());
        self::assertSame('kg', $item->getUnit()?->value);
        self::assertSame('Bananes bio', $item->getCustomLabel());
        self::assertNotNull($item->getProduct());
    }

    public function testUnknownItemFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Recurring grocery item not found');

        $this->dispatch(new UpdateRecurringGroceryItemCommand(
            recurringGroceryItemId: '01ARZ3NDEKTSV4RRFFQ69G5FAV',
            clearFields: ['quantity'],
        ));
    }
}
