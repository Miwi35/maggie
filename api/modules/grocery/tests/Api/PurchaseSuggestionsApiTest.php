<?php

namespace Maggie\Grocery\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * « Tu en prends souvent » (MAG-495): `GET /api/grocery-suggestions` and
 * « Pas besoin ». Reading publishes and indexes nothing; the snooze writes the
 * product, so it is published and reindexed.
 */
class PurchaseSuggestionsApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        self::at('2026-10-13 09:00:00');
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('PurchaseSuggestions.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private static function at(string $parisTime): void
    {
        Clock::set(new MockClock(new \DateTimeImmutable($parisTime, new \DateTimeZone('Europe/Paris'))));
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function signIn(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    /** @return list<array<string, mixed>> */
    private function suggestions(): array
    {
        $this->client->request('GET', '/api/grocery-suggestions', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function snooze(string $productId): array
    {
        $this->client->request('POST', '/api/grocery-suggestions/'.$productId.'/snooze', [], [], $this->authHeaders());

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    public function testReadingRequiresAuthentication(): void
    {
        $this->client->request('GET', '/api/grocery-suggestions');

        self::assertResponseStatusCodeSame(401);
    }

    public function testSnoozingRequiresAuthentication(): void
    {
        $this->client->request('POST', '/api/grocery-suggestions/'.$this->id('product_coffee').'/snooze');

        self::assertResponseStatusCodeSame(401);
        self::assertNull($this->em()->find(Product::class, $this->id('product_coffee'))?->getSuggestionSnoozedUntil());
    }

    public function testTheCoffeeBoughtEveryTwoWeeksIsDueOnTheThirteenth(): void
    {
        $this->signIn();

        $suggestions = $this->suggestions();

        // Not the tea (twice), the milk (on the list), the bread (recurring), nor the other user's coffee.
        self::assertSame([[
            'product' => ['id' => $this->id('product_coffee'), 'name' => 'Café'],
            'everyDays' => 14,
            'lastBoughtOn' => '2026-09-29',
            'dueOn' => '2026-10-13',
            'usualQuantity' => 1,
            'usualUnit' => 'pack',
        ]], $suggestions);
        $this->assertMercureUpdateCount(0);
        self::assertSame([], $this->getAsyncTransport()->getSent());
    }

    public function testNothingIsDueTheDayBefore(): void
    {
        self::at('2026-10-12 23:30:00');
        $this->signIn();

        self::assertSame([], $this->suggestions());
    }

    public function testACoffeeAlreadyOnTheListIsNotSuggested(): void
    {
        $em = $this->em();
        $list = $this->getFixture('grocery_list');
        $item = (new GroceryItem())->setProduct($em->find(Product::class, $this->id('product_coffee')))->setQuantity(1);
        $item->setSource(\Maggie\Grocery\Enum\GroceryItemSource::Manual);
        $em->find($list::class, $list->getId())->addItem($item);
        $em->flush();
        $this->signIn();

        self::assertSame([], $this->suggestions());
    }

    public function testNotNeededPutsTheCoffeeOffUntilTheTwentySeventh(): void
    {
        $this->signIn();

        $body = $this->snooze($this->id('product_coffee'));

        self::assertResponseIsSuccessful();
        self::assertSame('2026-10-27', $body['suggestionSnoozedUntil']);
        $this->em()->clear();
        self::assertSame('2026-10-27', $this->em()->find(Product::class, $this->id('product_coffee'))?->getSuggestionSnoozedUntil()?->format('Y-m-d'));
        $this->assertMercureUpdatePublished('/api/products/'.$this->id('product_coffee'));
        self::assertSame('2026-10-27', $this->mercurePayloadsOn('/api/products/'.$this->id('product_coffee'))[0]['suggestionSnoozedUntil'] ?? null);
        $this->assertElasticsearchIndexDispatchedFor(Product::class, $this->id('product_coffee'));

        // The same day over REST as on Mercure, not a midnight instant.
        $this->client->request('GET', '/api/products/'.$this->id('product_coffee'), [], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));
        self::assertSame('2026-10-27', json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['suggestionSnoozedUntil'] ?? null);

        self::assertSame([], $this->suggestions());

        self::at('2026-10-26 20:00:00');
        self::assertSame([], $this->suggestions(), 'Still put off the day before.');

        self::at('2026-10-27 08:00:00');
        self::assertSame(['Café'], array_column(array_column($this->suggestions(), 'product'), 'name'), 'Back at the next cycle.');
    }

    public function testAnUnknownProductCannotBeSnoozed(): void
    {
        $this->signIn();

        $this->snooze('01JAAAAAAAAAAAAAAAAAAAAAAA');
        self::assertResponseStatusCodeSame(400);

        $this->snooze('not-an-id');
        self::assertResponseStatusCodeSame(400);
    }

    public function testAnotherUsersProductCannotBeSnoozed(): void
    {
        $this->signIn();

        $this->snooze($this->id('other_coffee'));

        self::assertResponseStatusCodeSame(400);
        $this->em()->clear();
        self::assertNull($this->em()->find(Product::class, $this->id('other_coffee'))?->getSuggestionSnoozedUntil());
        $this->assertMercureUpdateCount(0);
    }

    public function testAProductBoughtTwiceHasNoRhythmToSnooze(): void
    {
        $this->signIn();

        $body = $this->snooze($this->id('product_tea'));

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('Thé', $body['error']);
    }

    public function testTheSnoozeCannotBeWrittenThroughTheProduct(): void
    {
        $this->signIn();

        $this->client->request('PATCH', '/api/products/'.$this->id('product_coffee'), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['suggestionSnoozedUntil' => '2099-01-01'], JSON_THROW_ON_ERROR));

        $this->em()->clear();
        self::assertNull($this->em()->find(Product::class, $this->id('product_coffee'))?->getSuggestionSnoozedUntil());
    }
}
