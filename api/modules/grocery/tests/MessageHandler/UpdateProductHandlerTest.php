<?php

namespace Maggie\Grocery\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Message\UpdateProductCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateProductHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('UpdateProductHandlerTest.yaml');
    }

    private function dispatch(UpdateProductCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function reload(string $ref = 'product'): Product
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Product::class)->find($this->getFixture($ref)->getId());
    }

    private function id(string $ref = 'product'): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    public function testClearingTheDefaultUnitEmptiesItAndLeavesTheRestUntouched(): void
    {
        $this->dispatch(new UpdateProductCommand(productId: $this->id(), clearFields: ['defaultUnit']));

        $product = $this->reload();
        self::assertNull($product->getDefaultUnit());
        self::assertSame('Riz', $product->getName());
        self::assertSame('other', $product->getCategory()->value);
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testNullFieldsWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateProductCommand(productId: $this->id(), name: 'Riz basmati'));

        $product = $this->reload();
        self::assertSame('Riz basmati', $product->getName());
        self::assertSame('kg', $product->getDefaultUnit()?->value);
    }

    public function testStoresAndShelfLifeAreSaved(): void
    {
        $this->dispatch(new UpdateProductCommand(
            productId: $this->id(),
            preferredStoreId: $this->id('halles'),
            fallbackStoreId: $this->id('bio'),
            shelfLifeDays: 14,
        ));

        $product = $this->reload();
        self::assertSame($this->id('halles'), (string) $product->getPreferredStore()?->getId());
        self::assertSame($this->id('bio'), (string) $product->getFallbackStore()?->getId());
        self::assertSame(14, $product->getShelfLifeDays());
        self::assertSame('Riz', $product->getName());
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testClearingTheStoresAndTheShelfLifeEmptiesThemOnly(): void
    {
        $this->dispatch(new UpdateProductCommand(
            productId: $this->id('product_with_stores'),
            clearFields: ['preferredStore', 'shelfLifeDays'],
        ));

        $product = $this->reload('product_with_stores');
        self::assertNull($product->getPreferredStore());
        self::assertNull($product->getShelfLifeDays());
        self::assertSame($this->id('bio'), (string) $product->getFallbackStore()?->getId());
        self::assertSame('Câpres', $product->getName());
    }

    public function testStoresLeftOutAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateProductCommand(productId: $this->id('product_with_stores'), name: 'Câpres au sel'));

        $product = $this->reload('product_with_stores');
        self::assertSame($this->id('halles'), (string) $product->getPreferredStore()?->getId());
        self::assertSame($this->id('bio'), (string) $product->getFallbackStore()?->getId());
        self::assertSame(30, $product->getShelfLifeDays());
    }

    public function testAStoreOfAnotherUserIsRefused(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Store not found');

        $this->dispatch(new UpdateProductCommand(productId: $this->id(), preferredStoreId: $this->id('other_user_store')));
    }

    public function testUnknownProductFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Product not found');

        $this->dispatch(new UpdateProductCommand(productId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clearFields: ['defaultUnit']));
    }
}
