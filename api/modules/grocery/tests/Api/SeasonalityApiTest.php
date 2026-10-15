<?php

namespace Maggie\Grocery\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** The season months of a product and the `inSeason` of the product and the grocery line (MAG-494). */
class SeasonalityApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const OCTOBER = '2026-10-16 12:00:00 UTC';
    private const JANUARY = '2027-01-15 12:00:00 UTC';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        Clock::set(new MockClock(self::OCTOBER));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function login(): void
    {
        $this->loadFixtures('SeasonalityApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array<string, mixed>
     */
    private function post(array $body, bool $authenticated = true): array
    {
        $this->client->request('POST', '/api/products', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $authenticated ? $this->authHeaders() : []), json_encode($body, JSON_THROW_ON_ERROR));

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function get(string $uri): array
    {
        $this->client->request('GET', $uri, [], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function reload(string $id): Product
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Product::class)->find($id);
    }

    public function testCreateRequiresAuthentication(): void
    {
        $this->loadFixtures('SeasonalityApiTest.yaml');

        $this->post(['name' => 'Potiron', 'category' => 'produce'], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testAPotironCreatedInOctoberHasItsSeasonAndIsInSeason(): void
    {
        $this->login();

        $record = $this->post(['name' => 'Potiron', 'category' => 'produce']);

        self::assertResponseStatusCodeSame(201);
        self::assertSame([1, 9, 10, 11, 12], $record['seasonMonths']);
        self::assertTrue($record['inSeason']);
        self::assertSame([1, 9, 10, 11, 12], $this->reload($record['id'])->getSeasonMonths());

        $this->assertMercureUpdatePublished('/products/');
        $payload = $this->mercurePayloadsOn('/api/products/'.$record['id'])[0];
        self::assertSame([1, 9, 10, 11, 12], $payload['seasonMonths']);
        self::assertTrue($payload['inSeason']);
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testATomatoIsOutOfSeasonInJanuary(): void
    {
        Clock::set(new MockClock(self::JANUARY));
        $this->login();

        $record = $this->get('/api/products/'.$this->getFixture('tomates')->getId());

        self::assertSame([6, 7, 8, 9], $record['seasonMonths']);
        self::assertFalse($record['inSeason']);
    }

    public function testAProductThatIsNotProduceHasNoSeason(): void
    {
        $this->login();

        $record = $this->post(['name' => 'Lessive', 'category' => 'cleaning']);

        self::assertResponseStatusCodeSame(201);
        self::assertNull($record['seasonMonths'] ?? null);
        self::assertNull($record['inSeason'] ?? null);
        self::assertNull($this->reload($record['id'])->getSeasonMonths());
    }

    public function testMonthsGivenOnCreateWinOverTheCalendar(): void
    {
        $this->login();

        $record = $this->post(['name' => 'Potiron', 'category' => 'produce', 'seasonMonths' => [11, 10, 10]]);

        self::assertResponseStatusCodeSame(201);
        self::assertSame([10, 11], $record['seasonMonths'], 'Sorted, without duplicates');
        self::assertSame([10, 11], $this->reload($record['id'])->getSeasonMonths());
    }

    public function testAMonthOutsideOneToTwelveIsRefused(): void
    {
        $this->login();

        $this->post(['name' => 'Potiron', 'category' => 'produce', 'seasonMonths' => [13]]);

        self::assertResponseStatusCodeSame(400);
        $this->assertMercureUpdateCount(0);
    }

    public function testPatchCorrectsTheSeasonMonths(): void
    {
        $this->login();
        $tomates = $this->getFixture('tomates');

        $this->client->request('PATCH', '/api/products/'.$tomates->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['seasonMonths' => [7, 8, 9, 10]], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        $record = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($record['inSeason']);
        self::assertSame([7, 8, 9, 10], $this->reload((string) $tomates->getId())->getSeasonMonths());

        $payload = $this->mercurePayloadsOn('/api/products/'.$tomates->getId())[0];
        self::assertSame([7, 8, 9, 10], $payload['seasonMonths']);
        self::assertTrue($payload['inSeason'], 'A differential update of the months carries inSeason');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testPatchWithAnEmptyListClearsTheSeason(): void
    {
        $this->login();
        $tomates = $this->getFixture('tomates');

        $this->client->request('PATCH', '/api/products/'.$tomates->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['seasonMonths' => []], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        self::assertNull($this->reload((string) $tomates->getId())->getSeasonMonths());
    }

    public function testPatchWithAMonthOutsideOneToTwelveIsRefused(): void
    {
        $this->login();
        $tomates = $this->getFixture('tomates');

        $this->client->request('PATCH', '/api/products/'.$tomates->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['seasonMonths' => [0, 5]], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(400);
        self::assertSame([6, 7, 8, 9], $this->reload((string) $tomates->getId())->getSeasonMonths());
    }

    public function testTheGroceryLinesSayWhetherTheirProductIsInSeason(): void
    {
        $this->login();
        // The fixtures leave the list's inverse side empty in the identity map.
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();

        $list = $this->get('/api/grocery_lists/'.$this->getFixture('grocery_list')->getId());

        $byLabel = array_column($list['items'], 'inSeason', 'label');
        self::assertTrue($byLabel['Potiron']);
        self::assertFalse($byLabel['Tomates']);
        self::assertArrayHasKey('Lessive', array_column($list['items'], 'label', 'label'));
        self::assertNull($byLabel['Lessive'] ?? null, 'No season, no badge');
        self::assertNull($byLabel['Bougies'] ?? null);
    }

    public function testTheGroceryListMercurePayloadCarriesInSeason(): void
    {
        $this->login();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $list = $em->find(GroceryList::class, $this->getFixture('grocery_list')->getId());

        $payload = $list->toMercurePayload();

        $byLabel = array_column($payload['items'], 'inSeason', 'label');
        self::assertTrue($byLabel['Potiron']);
        self::assertFalse($byLabel['Tomates']);
        self::assertNull($byLabel['Lessive']);
        self::assertNull($byLabel['Bougies']);
    }
}
