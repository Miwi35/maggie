<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Home;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** GET /api/home/nutrition (MAG-564): the meals of the day, lunch then dinner. */
class NutritionHomeCardTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        // 00:30 on the 14th in Paris is still the 13th in UTC: « today » follows the owner's zone.
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-14 00:30:00', new \DateTimeZone('Europe/Paris'))));
        $this->loadFixtures('NutritionHomeCardTest.yaml');
        // The loading kernel memoised the guests default and holds the fixtures' half-built collections: read from a fresh one.
        self::ensureKernelShutdown();
        $this->client = self::createClient();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testUnauthenticatedIs401(): void
    {
        $this->client->request('GET', '/api/home/nutrition');

        self::assertResponseStatusCodeSame(401);
    }

    public function testInvalidDayIs400(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/home/nutrition?day=yesterday', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testTodayCarriesBothSlotsWithEveryRecipeOfAMeal(): void
    {
        $body = $this->state('today');

        self::assertSame('nutrition', $body['module']);
        self::assertSame('today', $body['day']);
        self::assertSame('2026-10-14', $body['date']);
        self::assertSame(['lunch', 'dinner'], array_column($body['slots'], 'slot'));

        [$lunch, $dinner] = $body['slots'];
        self::assertCount(1, $lunch['meals']);
        self::assertSame(['Salade verte'], array_column($lunch['meals'][0]['recipes'], 'name'));

        self::assertCount(1, $dinner['meals']);
        self::assertSame(['Pâtes au basilic', 'Salade verte', 'Gratin'], array_column($dinner['meals'][0]['recipes'], 'name'));
        self::assertSame('Pâtes au basilic, Salade verte, Gratin', $dinner['meals'][0]['title']);
        self::assertSame((string) $this->getFixture('dinner_today')->getId(), $dinner['meals'][0]['id']);
    }

    public function testTomorrowCarriesTomorrowsMealsAndAnEmptySlot(): void
    {
        $body = $this->state('tomorrow');

        self::assertSame('tomorrow', $body['day']);
        self::assertSame('2026-10-15', $body['date']);
        [$lunch, $dinner] = $body['slots'];
        self::assertSame([], $lunch['meals']);
        self::assertSame(['Salade verte'], array_column($dinner['meals'][0]['recipes'], 'name'));
    }

    public function testDefaultsToToday(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/home/nutrition', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        self::assertSame('today', $this->body()['day']);
    }

    public function testGuestsAreTheMealsOwnOrTheDefault(): void
    {
        $body = $this->state('today');

        self::assertSame(4, $body['slots'][0]['meals'][0]['guests'], 'the lunch follows the default of the meal settings');
        self::assertSame(3, $body['slots'][1]['meals'][0]['guests'], 'the dinner names its own');
    }

    public function testMissingIngredientsAreThoseOutOrLowAndCountedOncePerMeal(): void
    {
        $body = $this->state('today');

        self::assertSame([], $body['slots'][0]['meals'][0]['missing'], 'a meal of ingredients in stock lacks nothing');
        // Parmesan is out, basil low (asked by two recipes); pasta is in stock.
        self::assertEqualsCanonicalizing(['Basilic', 'Parmesan'], array_column($body['slots'][1]['meals'][0]['missing'], 'name'));
    }

    public function testAnotherUsersMealsAreNotReturned(): void
    {
        $body = $this->state('today');

        $ids = array_map(static fn (array $meal): string => $meal['id'], [...$body['slots'][0]['meals'], ...$body['slots'][1]['meals']]);
        self::assertNotContains((string) $this->getFixture('other_lunch_today')->getId(), $ids);
        self::assertSame(['Salade verte'], array_column($body['slots'][0]['meals'][0]['recipes'], 'name'));

        $this->authenticateAsUser($this->getFixture('other_user'));
        $this->client->request('GET', '/api/home/nutrition', [], [], $this->authHeaders());
        $other = $this->body();
        self::assertSame(['Velouté du voisin'], array_column($other['slots'][0]['meals'][0]['recipes'], 'name'));
        self::assertSame(['Poireau du voisin'], array_column($other['slots'][0]['meals'][0]['missing'], 'name'));
        self::assertSame([], $other['slots'][1]['meals']);
    }

    /** @return array<string, mixed> */
    private function state(string $day): array
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->client->request('GET', '/api/home/nutrition?day='.$day, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return $this->body();
    }

    /** @return array<string, mixed> */
    private function body(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }
}
