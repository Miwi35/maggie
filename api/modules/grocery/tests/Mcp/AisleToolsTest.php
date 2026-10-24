<?php

namespace Maggie\Grocery\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\ProductAisle;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Mcp\Tool\AssignProductStoreTool;
use Maggie\Grocery\Mcp\Tool\GetGroceryListTool;
use Maggie\Grocery\Mcp\Tool\ManageAislesTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The aisles of a store, in the order of the way through it, and the grocery
 * list read aisle by aisle (MAG-493).
 */
class AisleToolsTest extends KernelTestCase
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

    private function load(): void
    {
        $this->loadFixtures('aisles.yaml');
        $this->loginFixtureUser();
        $this->em()->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return array<string, mixed> */
    private function aisles(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageAislesTool::class);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function assign(string $productRef, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(AssignProductStoreTool::class);

        return json_decode($tool($this->id($productRef), ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, list<string>> labels of each store group, store name => labels */
    private function listByStore(): array
    {
        $tool = self::getContainer()->get(GetGroceryListTool::class);
        $data = json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);

        $groups = [];
        foreach ($data['groceryList']['storeGroups'] as $group) {
            $groups[$group['storeName']] = array_map(fn (array $item) => $item['label'], $group['items']);
        }

        return $groups;
    }

    /** @return array<string, int> name => position, of the store's aisles in the database */
    private function positionsIn(string $storeRef): array
    {
        $this->em()->clear();
        $aisles = $this->em()->getRepository(Aisle::class)->findBy(['store' => $this->id($storeRef)], ['position' => 'ASC']);

        $positions = [];
        foreach ($aisles as $aisle) {
            $positions[$aisle->getName()] = $aisle->getPosition();
        }

        return $positions;
    }

    public function testWithoutAUserEveryActionIsRefused(): void
    {
        $this->loadFixtures('aisles.yaml');

        $data = $this->aisles('list', storeId: $this->id('carrefour'));

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('No user bound', $data['error']);
    }

    public function testTheListFollowsTheWayThroughTheStoreAndTheUnassignedComeLast(): void
    {
        $this->load();

        $groups = $this->listByStore();

        self::assertSame(['Pommes', 'Lait', 'Pain', 'Éponges'], $groups['Carrefour']);
    }

    public function testSwappingCremerieAndBoulangerieSwapsMilkAndBread(): void
    {
        $this->load();

        $data = $this->aisles('reorder', storeId: $this->id('carrefour'), aisleIds: [$this->id('fruits'), $this->id('boulangerie'), $this->id('cremerie')]);

        self::assertTrue($data['success']);
        self::assertSame(['Fruits et légumes' => 1, 'Boulangerie' => 2, 'Crèmerie' => 3], $this->positionsIn('carrefour'));
        self::assertSame(['Pommes', 'Pain', 'Lait', 'Éponges'], $this->listByStore()['Carrefour']);
        $this->assertMercureUpdatePublished('/aisles/'.$this->id('boulangerie'));
        $this->assertMercurePublishedOnce('/api/grocery_lists/'.$this->id('grocery_list'));
    }

    public function testReorderingReindexesTheProductsOfTheStoreForTheirAislePosition(): void
    {
        $this->load();

        $this->aisles('reorder', storeId: $this->id('carrefour'), aisleIds: [$this->id('fruits'), $this->id('boulangerie'), $this->id('cremerie')]);

        foreach (['pommes', 'pain', 'lait'] as $ref) {
            $this->assertElasticsearchIndexDispatchedFor(Product::class, $this->id($ref));
        }
        self::assertNotContains($this->id('eponges'), $this->reindexedIdsOf(Product::class), 'No aisle in this store: its document has nothing to change');
    }

    public function testTheSameProductIsUnderItsOwnAisleInEachStore(): void
    {
        $this->load();

        $tool = self::getContainer()->get(GetGroceryListTool::class);
        $data = json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);

        $aisleOfMilk = [];
        foreach ($data['groceryList']['storeGroups'] as $group) {
            foreach ($group['items'] as $item) {
                if ('Lait' === $item['label']) {
                    $aisleOfMilk[$group['storeName']] = $item['aisle']['name'];
                }
                if ('Éponges' === $item['label']) {
                    self::assertNull($item['aisle']);
                }
            }
        }
        self::assertSame(['Carrefour' => 'Crèmerie', 'Lidl' => 'Frais'], $aisleOfMilk);
    }

    public function testListGivesTheStoreAislesInOrder(): void
    {
        $this->load();

        $data = $this->aisles('list', storeId: $this->id('carrefour'));

        self::assertSame(['Fruits et légumes', 'Crèmerie', 'Boulangerie'], array_column($data['aisles'], 'name'));
        self::assertSame([1, 2, 3], array_column($data['aisles'], 'position'));
    }

    public function testCreatePutsTheAisleLastAndPublishesIt(): void
    {
        $this->load();

        $data = $this->aisles('create', storeId: $this->id('carrefour'), name: '  Surgelés ');

        self::assertTrue($data['success']);
        self::assertSame('Surgelés', $data['aisle']['name']);
        self::assertSame(4, $data['aisle']['position']);
        self::assertSame(['Fruits et légumes' => 1, 'Crèmerie' => 2, 'Boulangerie' => 3, 'Surgelés' => 4], $this->positionsIn('carrefour'));
        $this->assertMercureUpdatePublished('/aisles/'.$data['aisle']['id']);
    }

    public function testCreateAtAPositionMovesTheOthersAlong(): void
    {
        $this->load();

        $this->aisles('create', storeId: $this->id('carrefour'), name: 'Entrée', position: 1);

        self::assertSame(['Entrée' => 1, 'Fruits et légumes' => 2, 'Crèmerie' => 3, 'Boulangerie' => 4], $this->positionsIn('carrefour'));
    }

    public function testCreateRefusesABlankName(): void
    {
        $this->load();

        $data = $this->aisles('create', storeId: $this->id('carrefour'), name: '   ');

        self::assertArrayHasKey('error', $data);
        self::assertCount(3, $this->positionsIn('carrefour'));
    }

    public function testCreateRefusesANameTheStoreHasInAnotherCase(): void
    {
        $this->load();

        $data = $this->aisles('create', storeId: $this->id('carrefour'), name: 'CRÈMERIE');

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('already has an aisle', $data['error']);
        self::assertCount(3, $this->positionsIn('carrefour'));
    }

    public function testTheSameNameInAnotherStoreIsFine(): void
    {
        $this->load();

        $data = $this->aisles('create', storeId: $this->id('lidl'), name: 'Crèmerie');

        self::assertTrue($data['success']);
    }

    public function testUpdateRenamesAndMoves(): void
    {
        $this->load();

        $data = $this->aisles('update', aisleId: $this->id('boulangerie'), name: 'Pains et viennoiseries', position: 1);

        self::assertTrue($data['success']);
        self::assertSame(['Pains et viennoiseries' => 1, 'Fruits et légumes' => 2, 'Crèmerie' => 3], $this->positionsIn('carrefour'));
        self::assertSame(['Pain', 'Pommes', 'Lait', 'Éponges'], $this->listByStore()['Carrefour']);
        $this->assertMercurePublishedOnce('/api/grocery_lists/'.$this->id('grocery_list'));
    }

    public function testRenamingToTheSameNameInAnotherCaseIsFine(): void
    {
        $this->load();

        $data = $this->aisles('update', aisleId: $this->id('cremerie'), name: 'crèmerie');

        self::assertTrue($data['success']);
        self::assertSame('crèmerie', $data['aisle']['name']);
    }

    public function testRenamingToAnotherAisleNameIsRefused(): void
    {
        $this->load();

        $data = $this->aisles('update', aisleId: $this->id('cremerie'), name: 'boulangerie');

        self::assertArrayHasKey('error', $data);
    }

    public function testReorderNeedsEveryAisleOfTheStoreOnce(): void
    {
        $this->load();

        $missing = $this->aisles('reorder', storeId: $this->id('carrefour'), aisleIds: [$this->id('boulangerie'), $this->id('cremerie')]);
        $foreign = $this->aisles('reorder', storeId: $this->id('carrefour'), aisleIds: [$this->id('fruits'), $this->id('cremerie'), $this->id('frais')]);

        self::assertArrayHasKey('error', $missing);
        self::assertArrayHasKey('error', $foreign);
        self::assertSame(['Fruits et légumes' => 1, 'Crèmerie' => 2, 'Boulangerie' => 3], $this->positionsIn('carrefour'));
    }

    public function testDeleteLeavesItsProductsWithoutAisleAndKeepsThem(): void
    {
        $this->load();
        $cremerieId = $this->id('cremerie');

        $data = $this->aisles('delete', aisleId: $cremerieId);

        self::assertTrue($data['success']);
        self::assertSame(['Fruits et légumes' => 1, 'Boulangerie' => 2], $this->positionsIn('carrefour'));
        $lait = $this->em()->find(Product::class, $this->id('lait'));
        self::assertNotNull($lait);
        self::assertNull($lait->aisleIn($this->em()->find(Store::class, $this->id('carrefour'))));
        self::assertSame('Frais', $lait->aisleIn($this->em()->find(Store::class, $this->id('lidl')))?->getName(), 'Its aisle in the other store stays');
        self::assertSame(['Pommes', 'Pain', 'Éponges', 'Lait'], $this->listByStore()['Carrefour']);
        $this->assertMercureDeletePublished('/api/aisles/'.$cremerieId);
        $this->assertMercureUpdatePublished('/api/products/'.$this->id('lait'));
        $this->assertMercureUpdatePublished('/api/grocery_lists/'.$this->id('grocery_list'));
        $this->assertElasticsearchIndexDispatchedFor(Product::class, $this->id('lait'));
    }

    public function testAnotherUsersAisleAndStoreAreNotFound(): void
    {
        $this->load();

        $listing = $this->aisles('list', storeId: $this->id('other_store'));
        $creation = $this->aisles('create', storeId: $this->id('other_store'), name: 'Intrus');
        $renaming = $this->aisles('update', aisleId: $this->id('other_aisle'), name: 'Intrus');
        $deletion = $this->aisles('delete', aisleId: $this->id('other_aisle'));

        foreach ([$listing, $creation, $renaming, $deletion] as $data) {
            self::assertArrayHasKey('error', $data);
            self::assertStringContainsString('not found', $data['error']);
        }
        $this->em()->clear();
        self::assertSame("Rayon de l'autre", $this->em()->find(Aisle::class, $this->id('other_aisle'))?->getName());
        self::assertCount(1, $this->em()->getRepository(Aisle::class)->findBy(['store' => $this->id('other_store')]));
    }

    public function testAssignByIdPutsTheProductInTheAisleOfItsPreferredStore(): void
    {
        $this->load();

        $data = $this->assign('eponges', aisle: $this->id('boulangerie'));

        // In the same aisle as the bread, before it: added first (position).
        self::assertTrue($data['success'], json_encode($data, JSON_THROW_ON_ERROR));
        self::assertSame(['Pommes', 'Lait', 'Éponges', 'Pain'], $this->listByStore()['Carrefour']);
        $this->em()->clear();
        $assignments = $this->em()->getRepository(ProductAisle::class)->findBy(['product' => $this->id('eponges')]);
        self::assertCount(1, $assignments);
        self::assertSame($this->id('boulangerie'), (string) $assignments[0]->getAisle()->getId());
        $this->assertMercureUpdatePublished('/api/products/'.$this->id('eponges'));
        $this->assertMercurePublishedOnce('/api/grocery_lists/'.$this->id('grocery_list'));
        $this->assertElasticsearchIndexDispatchedFor(Product::class, $this->id('eponges'));
    }

    public function testAssignByAnUnknownNameCreatesTheAisleLast(): void
    {
        $this->load();

        $data = $this->assign('eponges', aisle: 'Entretien');

        self::assertTrue($data['success']);
        self::assertSame(['Fruits et légumes' => 1, 'Crèmerie' => 2, 'Boulangerie' => 3, 'Entretien' => 4], $this->positionsIn('carrefour'));
        self::assertSame('Entretien', $data['product']['aisles'][0]['aisle']['name']);
    }

    public function testAssignByAKnownNameInAnotherCaseReusesTheAisle(): void
    {
        $this->load();

        $this->assign('eponges', aisle: 'boulangerie');

        self::assertCount(3, $this->positionsIn('carrefour'));
        self::assertSame(['Pommes', 'Lait', 'Éponges', 'Pain'], $this->listByStore()['Carrefour']);
    }

    public function testAssignInAnotherStoreKeepsTheAisleOfTheFirst(): void
    {
        $this->load();

        $this->assign('pommes', aisle: 'Fruits', aisleStoreId: $this->id('lidl'));

        $this->em()->clear();
        $pommes = $this->em()->find(Product::class, $this->id('pommes'));
        self::assertSame('Fruits et légumes', $pommes?->aisleIn($this->em()->find(Store::class, $this->id('carrefour')))?->getName());
        self::assertSame('Fruits', $pommes?->aisleIn($this->em()->find(Store::class, $this->id('lidl')))?->getName());
    }

    public function testAssignAnEmptyAisleLeavesTheProductWithoutAisle(): void
    {
        $this->load();

        $this->assign('lait', aisle: '');

        self::assertSame(['Pommes', 'Pain', 'Éponges', 'Lait'], $this->listByStore()['Carrefour']);
        self::assertSame(['Lait'], $this->listByStore()['Lidl'], 'Lidl keeps its own aisle');
    }

    public function testAssignAnAisleOfAnotherStoreIsRefused(): void
    {
        $this->load();

        $data = $this->assign('eponges', aisle: $this->id('frais'));

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('is in the store "Lidl"', $data['error']);
        self::assertCount(0, $this->em()->getRepository(ProductAisle::class)->findBy(['product' => $this->id('eponges')]));
    }

    public function testAssignAnotherUsersAisleOrProductIsRefused(): void
    {
        $this->load();

        $foreignAisle = $this->assign('eponges', aisle: $this->id('other_aisle'));
        $foreignProduct = $this->assign('other_product', aisle: 'Crèmerie');

        self::assertStringContainsString('not found', $foreignAisle['error']);
        self::assertStringContainsString('not found', $foreignProduct['error']);
        self::assertCount(0, $this->em()->getRepository(ProductAisle::class)->findBy(['product' => $this->id('other_product')]));
    }
}
