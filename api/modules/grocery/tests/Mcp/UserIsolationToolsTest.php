<?php

namespace Maggie\Grocery\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Mcp\Tool\AddGroceryItemTool;
use Maggie\Grocery\Mcp\Tool\CheckGroceryItemTool;
use Maggie\Grocery\Mcp\Tool\MoveToFallbackTool;
use Maggie\Grocery\Mcp\Tool\RemoveGroceryItemTool;
use Maggie\Grocery\Mcp\Tool\ReorderGroceryItemsTool;
use Maggie\Grocery\Mcp\Tool\SearchProductsTool;
use Maggie\Grocery\Message\EditGroceryItemCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Two users share the database: product lookups must never cross the user boundary.
 */
class UserIsolationToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    /** @var array<string, string> */
    private array $ids = [];

    protected function setUp(): void
    {
        self::bootKernel();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function load(): void
    {
        $this->loadFixtures('isolation.yaml');
        foreach (['test_user', 'other_user', 'other_bananes', 'own_item_without_product', 'other_item', 'other_store'] as $ref) {
            $this->ids[$ref] = (string) $this->getFixture($ref)->getId();
        }
        $this->em()->clear();
    }

    private function loadAndLogin(): void
    {
        $this->load();
        $this->loginUser($this->em()->find(User::class, $this->ids['test_user']));
    }

    /** @return array<string, mixed> */
    private function decode(string $json): array
    {
        return json_decode($json, true, 512, JSON_THROW_ON_ERROR);
    }

    public function testSearchProductsOnlyReturnsTheCallersProducts(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(SearchProductsTool::class))('Banane'));

        self::assertSame(['Bananes plantain'], array_column($data['products'], 'name'));
    }

    public function testSearchProductsWithoutUserIsRefused(): void
    {
        $this->load();

        $data = $this->decode((self::getContainer()->get(SearchProductsTool::class))('Banane'));

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertArrayNotHasKey('products', $data);
    }

    public function testAddGroceryItemNeverMatchesAnotherUsersProduct(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(AddGroceryItemTool::class))('Bananes', 6, 'piece'));

        self::assertTrue($data['success']);

        $this->em()->clear();
        $added = array_values(array_filter(
            $this->em()->getRepository(GroceryItem::class)->findAll(),
            fn (GroceryItem $item) => null !== $item->getProduct(),
        ));
        self::assertCount(1, $added);
        $product = $added[0]->getProduct();
        self::assertNotSame($this->ids['other_bananes'], (string) $product->getId());
        self::assertSame($this->ids['test_user'], (string) $product->getUser()->getId());

        $other = $this->em()->find(Product::class, $this->ids['other_bananes']);
        self::assertSame('dairy', $other->getCategory()->value, "The other user's product is left untouched");
        self::assertSame('Magasin de l\'autre', $other->getPreferredStore()->getName());
    }

    public function testEditingALabelNeverLinksAnotherUsersProduct(): void
    {
        $this->loadAndLogin();

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new EditGroceryItemCommand(
            groceryItemId: $this->ids['own_item_without_product'],
            userId: $this->ids['test_user'],
            label: 'Bananes',
        ));

        $this->em()->clear();
        $item = $this->em()->find(GroceryItem::class, $this->ids['own_item_without_product']);
        $product = $item->getProduct();
        self::assertNotNull($product);
        self::assertNotSame($this->ids['other_bananes'], (string) $product->getId());
        self::assertSame($this->ids['test_user'], (string) $product->getUser()->getId());
    }

    public function testCheckingAnotherUsersItemIsRefusedAndChangesNothing(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(CheckGroceryItemTool::class))($this->ids['other_item'], true));

        self::assertArrayHasKey('error', $data);
        $this->em()->clear();
        self::assertFalse($this->em()->find(GroceryItem::class, $this->ids['other_item'])->isChecked());
    }

    public function testRemovingAnotherUsersItemIsRefusedAndKeepsIt(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(RemoveGroceryItemTool::class))($this->ids['other_item']));

        self::assertArrayHasKey('error', $data);
        $this->em()->clear();
        self::assertNotNull($this->em()->find(GroceryItem::class, $this->ids['other_item']));
    }

    public function testReorderingAnotherUsersItemIsRefusedAndMovesNothing(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(ReorderGroceryItemsTool::class))([
            ['id' => $this->ids['own_item_without_product'], 'position' => 9],
            ['id' => $this->ids['other_item'], 'position' => 0],
        ]));

        self::assertArrayHasKey('error', $data);
        $this->em()->clear();
        self::assertSame(7, $this->em()->find(GroceryItem::class, $this->ids['other_item'])->getPosition());
        self::assertSame(1, $this->em()->find(GroceryItem::class, $this->ids['own_item_without_product'])->getPosition());
    }

    public function testCheckAndRemoveWithoutUserAreRefused(): void
    {
        $this->load();

        $check = $this->decode((self::getContainer()->get(CheckGroceryItemTool::class))($this->ids['other_item'], true));
        $remove = $this->decode((self::getContainer()->get(RemoveGroceryItemTool::class))($this->ids['other_item']));

        self::assertSame(MissingMcpUserException::MESSAGE, $check['error']);
        self::assertSame(MissingMcpUserException::MESSAGE, $remove['error']);
        $this->em()->clear();
        self::assertNotNull($this->em()->find(GroceryItem::class, $this->ids['other_item']));
    }

    public function testMoveToFallbackRefusesAnotherUsersStore(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(MoveToFallbackTool::class))($this->ids['other_store']));

        self::assertArrayHasKey('error', $data);
    }

    public function testAddingAnItemNeverAttachesAnotherUsersStore(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(AddGroceryItemTool::class))('Pommes', 2, 'piece', storeId: $this->ids['other_store']));

        self::assertTrue($data['success']);
        $this->em()->clear();
        $added = array_values(array_filter(
            $this->em()->getRepository(GroceryItem::class)->findAll(),
            fn (GroceryItem $item) => 'Pommes' === $item->getProduct()?->getName(),
        ));
        self::assertCount(1, $added);
        self::assertNull($added[0]->getStore());
    }

    public function testOwnerKeepsCheckingAndRemovingTheirItem(): void
    {
        $this->loadAndLogin();
        $id = $this->ids['own_item_without_product'];

        $checked = $this->decode((self::getContainer()->get(CheckGroceryItemTool::class))($id, true));
        self::assertTrue($checked['success']);
        $this->em()->clear();
        self::assertTrue($this->em()->find(GroceryItem::class, $id)->isChecked());

        $removed = $this->decode((self::getContainer()->get(RemoveGroceryItemTool::class))($id));
        self::assertTrue($removed['success']);
        $this->em()->clear();
        self::assertNull($this->em()->find(GroceryItem::class, $id));
    }
}
