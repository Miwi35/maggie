<?php

namespace Maggie\Grocery\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Message\CreateProductCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class CreateProductHandlerTest extends KernelTestCase
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

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    private function dispatch(CreateProductCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    public function testStoresAndShelfLifeAreSavedWithTheProduct(): void
    {
        $this->dispatch(new CreateProductCommand(
            userId: $this->id('test_user'),
            name: 'Sel de Guérande',
            category: 'other',
            preferredStoreId: $this->id('halles'),
            fallbackStoreId: $this->id('bio'),
            shelfLifeDays: 365,
        ));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $created = $em->getRepository(Product::class)->findOneBy(['name' => 'Sel de Guérande']);
        self::assertSame($this->id('halles'), (string) $created?->getPreferredStore()?->getId());
        self::assertSame($this->id('bio'), (string) $created?->getFallbackStore()?->getId());
        self::assertSame(365, $created?->getShelfLifeDays());
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testAStoreOfAnotherUserIsRefused(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Store not found');

        $this->dispatch(new CreateProductCommand(
            userId: $this->id('test_user'),
            name: 'Sel',
            category: 'other',
            preferredStoreId: $this->id('other_user_store'),
        ));
    }
}
