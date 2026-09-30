<?php

namespace Maggie\Grocery\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Message\UpdateStoreCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateStoreHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('UpdateStoreHandlerTest.yaml');
    }

    private function dispatch(UpdateStoreCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function reload(string $ref = 'store'): Store
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Store::class)->find($this->getFixture($ref)->getId());
    }

    private function id(string $ref = 'store'): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    public function testClearingTheDescriptionEmptiesItAndLeavesTheRestUntouched(): void
    {
        $this->dispatch(new UpdateStoreCommand(storeId: $this->id(), clearFields: ['description']));

        $store = $this->reload();
        self::assertNull($store->getDescription());
        self::assertSame('Supermarché', $store->getName());
        self::assertSame(4, $store->getVisitOrder());
        $this->assertMercureUpdatePublished('/stores/');
        $this->assertElasticsearchIndexDispatched(Store::class);
    }

    public function testNullFieldsWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateStoreCommand(storeId: $this->id(), name: 'Hypermarché'));

        $store = $this->reload();
        self::assertSame('Hypermarché', $store->getName());
        self::assertSame('Supermarket for general groceries', $store->getDescription());
        self::assertSame(4, $store->getVisitOrder());
    }

    public function testAGivenValueWinsOverClear(): void
    {
        $this->dispatch(new UpdateStoreCommand(storeId: $this->id(), description: 'Bulk shop', clearFields: ['description']));

        self::assertSame('Bulk shop', $this->reload()->getDescription());
    }

    public function testUnknownStoreFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Store not found');

        $this->dispatch(new UpdateStoreCommand(storeId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clearFields: ['description']));
    }
}
