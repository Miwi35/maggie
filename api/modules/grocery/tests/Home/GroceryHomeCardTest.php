<?php

declare(strict_types=1);

namespace Maggie\Grocery\Tests\Home;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Message\AddGroceryItemCommand;
use Maggie\Grocery\Message\CheckGroceryItemCommand;
use Maggie\Grocery\Message\RemoveGroceryItemCommand;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Messenger\MessageBusInterface;

/** GET /api/home/grocery and its Mercure refresh (MAG-563). */
class GroceryHomeCardTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-14 12:00:00', new \DateTimeZone('Europe/Paris'))));
        $this->loadFixtures('GroceryHomeCardTest.yaml');
        // The fixtures' own objects do not hold the aisle assignments a request loads.
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function card(string $query = ''): array
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->client->request('GET', '/api/home/grocery'.$query, [], [], $this->authHeaders());

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function dispatch(object $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function topic(): string
    {
        return '/users/'.$this->getFixture('test_user')->getId().'/home/grocery';
    }

    public function testUnauthenticatedIs401(): void
    {
        $this->client->request('GET', '/api/home/grocery');

        self::assertResponseStatusCodeSame(401);
    }

    public function testInvalidDayIs400(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/home/grocery?day=yesterday', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testGroupsOpenItemsByStoreInVisitOrderAndCutsAtFive(): void
    {
        $card = $this->card();

        self::assertResponseIsSuccessful();
        self::assertSame('grocery', $card['module']);
        // Open lines of the user: 6 at the supermarket, 1 at the greengrocer, 1 without a store.
        self::assertSame(8, $card['count']);
        self::assertSame(['Supermarché', 'Primeur', null], array_column($card['stores'], 'name'));

        $supermarket = $card['stores'][0];
        self::assertSame(6, $supermarket['count']);
        self::assertSame(1, $supermarket['more']);
        // Aisle by aisle (fruits, then dairy), then the lines with no aisle in their own order.
        self::assertSame(['Pommes', 'Beurre', 'Pain', 'Sel', 'Sucre'], array_column($supermarket['items'], 'name'));

        self::assertSame(['Radis'], array_column($card['stores'][1]['items'], 'name'));
        self::assertSame(0, $card['stores'][1]['more']);
        self::assertSame(['Piles'], array_column($card['stores'][2]['items'], 'name'));
    }

    public function testItemsCarryTheirQuantityAndNeed(): void
    {
        $items = array_column($this->card()['stores'][0]['items'], null, 'name');

        self::assertEquals(6, $items['Pommes']['quantity']);
        self::assertSame('piece', $items['Pommes']['unit']);
        self::assertEquals([['quantity' => 300, 'unit' => 'g']], $items['Sel']['needed']);
        self::assertNull($items['Pain']['quantity']);
    }

    public function testNeitherCheckedNorDeferredNorOtherUsersItemsAreListed(): void
    {
        $names = array_merge(...array_map(
            static fn (array $store): array => array_column($store['items'], 'name'),
            $this->card()['stores'],
        ));

        self::assertNotContains('Lait', $names);
        self::assertNotContains('Sapin', $names);
        self::assertNotContains('Secret', $names);
    }

    public function testListsWhatTodayAndTomorrowsMealsNeedAndTheOwnerIsLowOn(): void
    {
        self::assertSame(
            [
                ['name' => 'Parmesan', 'day' => 'today', 'slot' => 'dinner'],
                ['name' => 'Crème fraîche', 'day' => 'tomorrow', 'slot' => 'lunch'],
            ],
            $this->card()['forRecipes'],
        );
    }

    public function testTheCardDoesNotMoveWithTheDay(): void
    {
        $today = $this->card('?day=today');
        $tomorrow = $this->card('?day=tomorrow');

        self::assertSame('tomorrow', $tomorrow['day']);
        self::assertSame($today['stores'], $tomorrow['stores']);
        self::assertSame($today['forRecipes'], $tomorrow['forRecipes']);
    }

    public function testAnotherUserSeesOnlyTheirOwnList(): void
    {
        $this->authenticateAsUser($this->getFixture('other_user'));
        $this->client->request('GET', '/api/home/grocery', [], [], $this->authHeaders());
        $card = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(1, $card['count']);
        self::assertSame(['Secret'], array_column($card['stores'][0]['items'], 'name'));
        self::assertSame([], $card['forRecipes']);
    }

    public function testAddingAnItemRefreshesTheCard(): void
    {
        $this->dispatch(new AddGroceryItemCommand(userId: (string) $this->getFixture('test_user')->getId(), label: 'Miel'));

        $this->assertMercureUpdatePublished($this->topic());
    }

    public function testCheckingAnItemRefreshesTheCardOnce(): void
    {
        $this->dispatch(new CheckGroceryItemCommand(
            groceryItemId: (string) $this->getFixture('item_bread')->getId(),
            userId: (string) $this->getFixture('test_user')->getId(),
            checked: true,
        ));

        $this->assertMercurePublishedOnce($this->topic());
    }

    public function testRemovingAnItemRefreshesTheCard(): void
    {
        $this->dispatch(new RemoveGroceryItemCommand(
            groceryItemId: (string) $this->getFixture('item_bread')->getId(),
            userId: (string) $this->getFixture('test_user')->getId(),
        ));

        $this->assertMercureUpdatePublished($this->topic());
    }

    public function testACheckedItemDisappearsFromTheCard(): void
    {
        $this->dispatch(new CheckGroceryItemCommand(
            groceryItemId: (string) $this->getFixture('item_bread')->getId(),
            userId: (string) $this->getFixture('test_user')->getId(),
            checked: true,
        ));

        $supermarket = $this->card()['stores'][0];

        self::assertSame(5, $supermarket['count']);
        self::assertSame(0, $supermarket['more']);
        self::assertSame(['Pommes', 'Beurre', 'Sel', 'Sucre', 'Café'], array_column($supermarket['items'], 'name'));
    }

    public function testAnotherUsersChangeDoesNotRefreshTheCard(): void
    {
        $this->dispatch(new AddGroceryItemCommand(userId: (string) $this->getFixture('other_user')->getId(), label: 'Thé'));

        $this->assertNothingPublishedOn($this->topic());
    }
}
