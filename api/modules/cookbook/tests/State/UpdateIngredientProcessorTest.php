<?php

namespace Maggie\Cookbook\Tests\State;

use ApiPlatform\Metadata\Patch;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\State\UpdateIngredientProcessor;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Grocery\Entity\Store;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * An ingredient is served by Doctrine, so the request body is deserialized into the managed
 * entity, while a store named by its IRI comes from Elasticsearch: rebuilt, not managed.
 * Left on the ingredient, that store makes the next flush fail (MAG-602).
 */
class UpdateIngredientProcessorTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('UpdateIngredientProcessorTest.yaml');
    }

    private function rebuilt(string $fixture): Store
    {
        $store = $this->getFixture($fixture);
        $source = ['id' => (string) $store->getId(), 'name' => $store->getName(), 'userId' => (string) $store->getUser()->getId()];

        return self::getContainer()->get(ElasticsearchEntityHydrator::class)->hydrate($source, Store::class);
    }

    private function patch(Ingredient $managed): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $previous = clone $managed;
        $previous->setPreferredStore($managed->getPreferredStore());

        self::getContainer()->get(UpdateIngredientProcessor::class)->process($managed, new Patch(), [], ['previous_data' => $previous]);
        $em->clear();
    }

    private function managedLemon(): Ingredient
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Ingredient::class)->find($this->getFixture('lemon')->getId());
    }

    private function reloaded(): Ingredient
    {
        return self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(Ingredient::class)->find($this->getFixture('lemon')->getId());
    }

    public function testStoreRebuiltFromTheIndexReplacesThePreferredStore(): void
    {
        $lemon = $this->managedLemon();
        $lemon->setPreferredStore($this->rebuilt('bio'));

        $this->patch($lemon);

        self::assertSame('Biocoop', $this->reloaded()->getPreferredStore()?->getName());
    }

    public function testStoreRebuiltFromTheIndexAsFallbackStoreIsSaved(): void
    {
        $lemon = $this->managedLemon();
        $lemon->setFallbackStore($this->rebuilt('bio'));

        $this->patch($lemon);

        $reloaded = $this->reloaded();
        self::assertSame('Biocoop', $reloaded->getFallbackStore()?->getName());
        self::assertSame('Halles du voisin', $reloaded->getPreferredStore()?->getName());
    }

    public function testClearingThePreferredStoreClearsIt(): void
    {
        $lemon = $this->managedLemon();
        $lemon->setPreferredStore(null);

        $this->patch($lemon);

        self::assertNull($this->reloaded()->getPreferredStore());
    }
}
