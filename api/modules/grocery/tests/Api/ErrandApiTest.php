<?php

namespace Maggie\Grocery\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Errand;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\PurchasedItem;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\Unit;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The « Courses passées » read side (MAG-488): the errands are written by EndErrand only. */
class ErrandApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('ErrandApiTest.yaml');
    }

    /** @param list<string> $labels */
    private function errand(string $userRef, string $storeRef, string $endedAt, array $labels): Errand
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        /** @var User $user */
        $user = $em->find(User::class, $this->getFixture($userRef)->getId());
        /** @var Store $store */
        $store = $em->find(Store::class, $this->getFixture($storeRef)->getId());

        $errand = (new Errand())->setUser($user)->setStore($store)->setEndedAt(new \DateTimeImmutable($endedAt));
        foreach ($labels as $label) {
            $errand->addItem(PurchasedItem::fromGroceryItem((new GroceryItem())->setCustomLabel($label)->setQuantity(2)->setUnit(Unit::Piece)));
        }
        $em->persist($errand);
        $em->flush();

        return $errand;
    }

    /** @return array<string, mixed> */
    private function get(string $uri): array
    {
        $this->client->request('GET', $uri, [], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testListingRequiresAuthentication(): void
    {
        $this->client->request('GET', '/api/errands');

        self::assertResponseStatusCodeSame(401);
    }

    public function testDetailRequiresAuthentication(): void
    {
        $errand = $this->errand('test_user', 'store_lidl', '2026-10-01 10:00', ['Tomates']);

        $this->client->request('GET', '/api/errands/'.$errand->getId());

        self::assertResponseStatusCodeSame(401);
    }

    public function testListsTheUsersErrandsMostRecentFirst(): void
    {
        $this->errand('test_user', 'store_lidl', '2026-09-20 10:00', ['Tomates']);
        $this->errand('test_user', 'store_carrefour', '2026-10-02 18:00', ['Lait', 'Pain']);
        $this->errand('other_user', 'other_store', '2026-10-03 09:00', ['Café']);
        $this->authenticateAsUser($this->getFixture('test_user'));

        $body = $this->get('/api/errands');

        self::assertResponseIsSuccessful();
        self::assertSame(['Carrefour', 'Lidl'], array_column($body['member'], 'storeName'));
        self::assertSame([2, 1], array_column($body['member'], 'itemCount'));
        self::assertSame('2026-10-02', substr($body['member'][0]['endedAt'], 0, 10));
    }

    public function testFiltersByStore(): void
    {
        $this->errand('test_user', 'store_lidl', '2026-09-20 10:00', ['Tomates']);
        $this->errand('test_user', 'store_carrefour', '2026-10-02 18:00', ['Lait']);
        $this->authenticateAsUser($this->getFixture('test_user'));

        $body = $this->get('/api/errands?store=/api/stores/'.$this->getFixture('store_lidl')->getId());

        self::assertResponseIsSuccessful();
        self::assertSame(['Lidl'], array_column($body['member'], 'storeName'));
    }

    public function testAStoreThatIsNotAnIdMatchesNothing(): void
    {
        $this->errand('test_user', 'store_lidl', '2026-09-20 10:00', ['Tomates']);
        $this->authenticateAsUser($this->getFixture('test_user'));

        $body = $this->get('/api/errands?store=not-an-id');

        self::assertResponseIsSuccessful();
        self::assertSame([], $body['member']);
    }

    public function testFiltersByMonth(): void
    {
        $this->errand('test_user', 'store_lidl', '2026-09-20 10:00', ['Tomates']);
        $this->errand('test_user', 'store_carrefour', '2026-10-02 18:00', ['Lait']);
        $this->authenticateAsUser($this->getFixture('test_user'));

        $body = $this->get('/api/errands?endedAt[after]=2026-10-01&endedAt[strictly_before]=2026-11-01');

        self::assertResponseIsSuccessful();
        self::assertSame(['Carrefour'], array_column($body['member'], 'storeName'));
    }

    public function testDetailCarriesTheBoughtLines(): void
    {
        $errand = $this->errand('test_user', 'store_carrefour', '2026-10-02 18:00', ['Lait', 'Pain']);
        $this->authenticateAsUser($this->getFixture('test_user'));

        $body = $this->get('/api/errands/'.$errand->getId());

        self::assertResponseIsSuccessful();
        self::assertSame('Carrefour', $body['storeName']);
        self::assertSame(['Lait', 'Pain'], array_column($body['items'], 'label'));
        self::assertSame([2, 2], array_map('intval', array_column($body['items'], 'quantity')));
        self::assertSame(['piece', 'piece'], array_column($body['items'], 'unit'));
    }

    public function testAnotherUsersErrandIsNotFound(): void
    {
        $errand = $this->errand('other_user', 'other_store', '2026-10-03 09:00', ['Café']);
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->get('/api/errands/'.$errand->getId());

        self::assertResponseStatusCodeSame(404);
    }

    public function testAnErrandCannotBeWrittenOverRest(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('POST', '/api/errands', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['storeName' => 'Lidl'], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(405);
    }
}
