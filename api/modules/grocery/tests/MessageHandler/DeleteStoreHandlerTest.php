<?php

namespace Maggie\Grocery\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Message\DeleteStoreCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class DeleteStoreHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('UpdateProductHandlerTest.yaml');
        $this->resetAsyncTransport();
    }

    public function testProductsLoseTheDeletedStoreAndAreReindexed(): void
    {
        $halles = (string) $this->getFixture('halles')->getId();
        $bio = (string) $this->getFixture('bio')->getId();

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new DeleteStoreCommand(storeId: $halles));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->getRepository(Store::class)->find($halles));
        $product = $em->getRepository(Product::class)->find($this->getFixture('product_with_stores')->getId());
        self::assertNull($product->getPreferredStore());
        self::assertSame($bio, (string) $product->getFallbackStore()?->getId(), 'The other store is kept');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }
}
