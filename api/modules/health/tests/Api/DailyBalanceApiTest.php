<?php

namespace Maggie\Health\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** GET /api/health/daily-balance (MAG-748): eaten + planned − expended, one user's. */
class DailyBalanceApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('food_intake.yaml');
        // The fixtures' recipes do not know their lines until they are read back.
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();
    }

    private function login(): void
    {
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);
        $this->authenticateAsUser($user);
    }

    /** @return array<string, mixed> */
    private function get(string $query, bool $authenticated = true): array
    {
        $this->client->request('GET', '/api/health/daily-balance'.$query, server: $authenticated ? $this->authHeaders() : []);

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testItRequiresAuthentication(): void
    {
        $this->get('?date=2026-11-05', authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testAMissingOrMalformedDateIsA400(): void
    {
        $this->login();

        self::assertArrayHasKey('error', $this->get(''));
        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('date', $this->get('?date=05/11/2026')['error']);
        self::assertResponseStatusCodeSame(400);
        $this->get('?from=2026-11-01&to=2026-12-15');
        self::assertResponseStatusCodeSame(400);
    }

    /** balanceKcal = eatenKcal + plannedKcal − expendedKcal; the neighbour's meal and snack count for nothing. */
    public function testTheDayAddsThePlannedMealsAndTakesOffTheExpenditure(): void
    {
        $this->login();

        // JSON writes 20.0 as 20: compared by value.
        self::assertEquals([
            'date' => '2026-11-05',
            'eatenKcal' => 0,
            'plannedKcal' => 515,
            'expendedKcal' => 2048,
            'balanceKcal' => 515 - 2048,
            'proteinG' => 20.0,
            'carbsG' => 50.0,
            'fatG' => 25.0,
            'plannedMeals' => [
                ['mealId' => (string) $this->getFixture('lunch_5')->getId(), 'slot' => 'lunch', 'label' => 'Bol du midi', 'kcal' => 515, 'proteinG' => 20.0, 'carbsG' => 50.0, 'fatG' => 25.0],
                ['mealId' => (string) $this->getFixture('dinner_5')->getId(), 'slot' => 'dinner', 'label' => 'Plat mystère', 'kcal' => null, 'proteinG' => 0.0, 'carbsG' => 0.0, 'fatG' => 0.0],
            ],
        ], $this->get('?date=2026-11-05'));
        self::assertResponseIsSuccessful();
    }

    public function testADayWithOnlyAnIntakeCountsIt(): void
    {
        $this->login();

        $day = $this->get('?date=2026-11-04');

        self::assertSame(78, $day['eatenKcal']);
        self::assertSame(0, $day['plannedKcal']);
        self::assertSame(1648, $day['expendedKcal']);
        self::assertSame(78 - 1648, $day['balanceKcal']);
        self::assertEquals(18.0, $day['carbsG']);
    }

    public function testARangeGivesOneRowPerDay(): void
    {
        $this->login();

        $days = $this->get('?from=2026-11-01&to=2026-11-07')['days'];

        self::assertSame(['2026-11-01', '2026-11-02', '2026-11-03', '2026-11-04', '2026-11-05', '2026-11-06', '2026-11-07'], array_column($days, 'date'));
        self::assertSame([0, 0, 0, 78, 0, 0, 0], array_column($days, 'eatenKcal'));
        self::assertSame(515, $days[4]['plannedKcal']);
        self::assertArrayNotHasKey('plannedMeals', $days[4]);
    }
}
