<?php

namespace Maggie\Grocery\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Mcp\Tool\UpdateStockTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateStockToolTest extends KernelTestCase
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

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** @return array<string, mixed> */
    private function call(string $product, string $state): array
    {
        $tool = self::getContainer()->get(UpdateStockTool::class);

        return json_decode($tool($product, $state), true, 512, JSON_THROW_ON_ERROR);
    }

    private function loadAndLogin(): void
    {
        $this->loadFixtures('update_stock.yaml');
        $this->loginFixtureUser();
    }

    private function stateOf(string $ref): ProductStockState
    {
        $this->em()->clear();

        return $this->em()->find(Product::class, $this->getFixture($ref)->getId())->getStockState();
    }

    /** @return GroceryItem[] */
    private function listItems(): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(GroceryItem::class)->findAll();
    }

    private function putRiceOnTheList(bool $checked, GroceryItemSource $source = GroceryItemSource::Manual, Unit $unit = Unit::Pack): void
    {
        $em = $this->em();
        $list = new GroceryList();
        $list->setUser($em->find(\Maggie\Core\Entity\User::class, $this->getFixture('test_user')->getId()));
        $em->persist($list);

        $item = new GroceryItem();
        $item->setProduct($em->find(Product::class, $this->getFixture('rice')->getId()));
        $item->setQuantity(1);
        $item->setUnit($unit);
        $item->setChecked($checked);
        $item->setSource($source);
        $item->setPosition(1);
        $list->addItem($item);
        $em->flush();
        $em->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function planCouscous(string $when): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateMealCommand(
            date: (new \DateTimeImmutable($when, new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture('couscous')->getId()],
            userId: (string) $this->getFixture('test_user')->getId(),
        ));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testWithoutAUserTheCallIsRefused(): void
    {
        $this->loadFixtures('update_stock.yaml');

        $data = $this->call('Riz', 'low');

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertSame(ProductStockState::InStock, $this->stateOf('rice'));
    }

    public function testUnknownProductIsAReadableError(): void
    {
        $this->loadAndLogin();

        $data = $this->call('Quinoa', 'low');

        self::assertStringContainsString('Product not found: Quinoa', $data['error']);
        self::assertSame([], $this->listItems());
    }

    public function testAnEmptyProductNeverMatchesEverything(): void
    {
        $this->loadAndLogin();

        $data = $this->call('  ', 'low');

        self::assertStringContainsString('Which product?', $data['error']);
        self::assertSame(ProductStockState::InStock, $this->stateOf('rice'));
        self::assertSame([], $this->listItems());
    }

    public function testUnknownStateIsAReadableErrorAndChangesNothing(): void
    {
        $this->loadAndLogin();

        $data = $this->call('Riz', 'empty');

        self::assertStringContainsString('Unknown stock state "empty"', $data['error']);
        self::assertSame(ProductStockState::InStock, $this->stateOf('rice'));
        self::assertSame([], $this->listItems());
    }

    public function testNearlyOutRiceIsLowAndTwoPacksGoOnTheList(): void
    {
        $this->loadAndLogin();

        $data = $this->call('Riz', 'low');

        self::assertTrue($data['success']);
        self::assertSame('low', $data['stockState']);
        self::assertSame(['added' => true, 'quantity' => 2, 'unit' => 'pack', 'lineQuantity' => 2], $data['restock']);

        self::assertSame(ProductStockState::Low, $this->stateOf('rice'));
        $items = $this->listItems();
        self::assertCount(1, $items);
        self::assertSame('Riz', $items[0]->getLabel());
        self::assertSame(2.0, $items[0]->getQuantity());
        self::assertSame(Unit::Pack, $items[0]->getUnit());
        self::assertSame(GroceryItemSource::Restock, $items[0]->getSource());
        self::assertFalse($items[0]->isChecked());
        self::assertSame('Supermarché', $items[0]->getStore()->getName());

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testSayingAgainThatItIsOutAddsNothingMore(): void
    {
        $this->loadAndLogin();
        $this->call('Riz', 'low');

        $data = $this->call('Riz', 'out');

        self::assertTrue($data['success']);
        self::assertFalse($data['restock']['added']);
        self::assertStringContainsString('already', $data['restock']['reason']);
        $items = $this->listItems();
        self::assertCount(1, $items);
        self::assertSame(2.0, $items[0]->getQuantity());
    }

    public function testARestockInAnotherUnitThanTheOpenLineIsStillReportedAsAdded(): void
    {
        $this->loadAndLogin();
        $this->putRiceOnTheList(false, unit: Unit::Kilogram);

        $data = $this->call('Riz', 'out');

        self::assertSame(['added' => true, 'quantity' => 2, 'unit' => 'pack', 'lineQuantity' => 2], $data['restock']);
        self::assertCount(2, $this->listItems(), 'the kilos line is kept, the packs go on their own line');
    }

    public function testTheProductCanBeGivenByItsId(): void
    {
        $this->loadAndLogin();

        $data = $this->call((string) $this->getFixture('rice')->getId(), 'out');

        self::assertTrue($data['success']);
        self::assertSame(ProductStockState::Out, $this->stateOf('rice'));
        self::assertCount(1, $this->listItems());
    }

    public function testTheNameIsMatchedWithoutCaseAndExactNameBeatsPartialMatches(): void
    {
        $this->loadAndLogin();

        // « Nouilles de riz » also contains "riz": the exact name wins.
        $data = $this->call('riz', 'low');

        self::assertSame('Riz', $data['product']['name']);
        self::assertSame(ProductStockState::InStock, $this->stateOf('rice_noodles'));
    }

    public function testASeveralWayPartialMatchAsksWhichOne(): void
    {
        $this->loadAndLogin();

        $data = $this->call('Ri', 'low');

        self::assertStringContainsString('Several products match', $data['error']);
        self::assertStringContainsString('Nouilles de riz', $data['error']);
        self::assertSame(ProductStockState::InStock, $this->stateOf('rice'));
    }

    public function testOutWithoutAutomaticRestockAddsNothingAndNamesThePlannedMeal(): void
    {
        $this->loadAndLogin();
        $this->planCouscous('+4 days');
        $this->em()->clear();
        // The meal brought its own ingredients onto the list: only what Maggie adds counts here.
        $before = count($this->listItems());

        $data = $this->call('Légumes pour couscous', 'out');

        self::assertTrue($data['success']);
        self::assertSame('out', $data['stockState']);
        self::assertFalse($data['restock']['added']);
        self::assertStringContainsString('Automatic restock is off', $data['restock']['reason']);
        self::assertSame(ProductStockState::Out, $this->stateOf('couscous_vegetables'));
        self::assertCount($before, $this->listItems());
        foreach ($this->listItems() as $item) {
            self::assertNotSame(GroceryItemSource::Restock, $item->getSource());
        }

        self::assertCount(1, $data['plannedMeals']);
        self::assertSame((new \DateTimeImmutable('+4 days', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'), $data['plannedMeals'][0]['date']);
        self::assertSame('dinner', $data['plannedMeals'][0]['slot']);
        self::assertSame(['Couscous'], $data['plannedMeals'][0]['recipes']);
    }

    public function testAMealBeyondTheNextSevenDaysIsNotReported(): void
    {
        $this->loadAndLogin();
        $this->planCouscous('+7 days');

        $data = $this->call('Légumes pour couscous', 'low');

        self::assertSame([], $data['plannedMeals']);
    }

    public function testBackInStockAddsNothing(): void
    {
        $this->loadAndLogin();

        $data = $this->call('Riz', 'in_stock');

        self::assertTrue($data['success']);
        self::assertFalse($data['restock']['added']);
        self::assertSame([], $this->listItems());
    }

    public function testNoRestockQuantityAddsNothing(): void
    {
        $this->loadAndLogin();

        $data = $this->call('Sel', 'out');

        self::assertFalse($data['restock']['added']);
        self::assertStringContainsString('no restock quantity', $data['restock']['reason']);
        self::assertSame(ProductStockState::Out, $this->stateOf('salt'));
        self::assertSame([], $this->listItems());
    }

    public function testAnUntickedLineIsRaisedInsteadOfDuplicated(): void
    {
        $this->loadAndLogin();
        $this->putRiceOnTheList(checked: false);

        $data = $this->call('Riz', 'low');

        self::assertSame(3, $data['restock']['lineQuantity']);
        $items = $this->listItems();
        self::assertCount(1, $items);
        self::assertSame(3.0, $items[0]->getQuantity());
        self::assertSame(GroceryItemSource::Manual, $items[0]->getSource(), 'the line keeps the origin the owner gave it');
        $this->assertMercureUpdatePublished('/grocery_lists/');
    }

    public function testATickedLineIsNotMerged(): void
    {
        $this->loadAndLogin();
        $this->putRiceOnTheList(checked: true);

        $this->call('Riz', 'low');

        $items = $this->listItems();
        self::assertCount(2, $items);
        $byChecked = [];
        foreach ($items as $item) {
            $byChecked[(int) $item->isChecked()] = $item;
        }
        self::assertSame(1.0, $byChecked[1]->getQuantity());
        self::assertSame(2.0, $byChecked[0]->getQuantity());
        self::assertSame(GroceryItemSource::Restock, $byChecked[0]->getSource());
    }

    public function testAnotherUsersProductIsInvisible(): void
    {
        $this->loadAndLogin();
        $this->loginFixtureUser('other_user');

        // « Légumes pour couscous » is the first user's; « Riz » exists for both.
        $data = $this->call('Légumes pour couscous', 'out');
        self::assertStringContainsString('Product not found', $data['error']);

        $data = $this->call((string) $this->getFixture('rice')->getId(), 'low');
        self::assertStringContainsString('Product not found', $data['error']);
        self::assertSame(ProductStockState::InStock, $this->stateOf('rice'));

        // By name, the other user reaches only their own « Riz ».
        $data = $this->call('Riz', 'low');
        self::assertSame((string) $this->getFixture('other_rice')->getId(), $data['product']['id']);
        self::assertSame(ProductStockState::InStock, $this->stateOf('rice'));
    }
}
