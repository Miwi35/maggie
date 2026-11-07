<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** The consolidated daily energy over REST (MAG-746). */
class DailyEnergyApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private const string ENDPOINT = '/api/health/daily-energy';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-25T09:00:00+00:00')));
        $this->loadFixtures('daily_energy.yaml');
        $this->authenticateAsUser($this->user('test_user'));
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function user(string $ref): User
    {
        $user = $this->getFixture($ref);
        \assert($user instanceof User);

        return $user;
    }

    /** @return list<array<string, mixed>> */
    private function days(string $query): array
    {
        $this->client->request('GET', self::ENDPOINT.'?'.$query, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT.'?from=2026-10-20&to=2026-10-21');

        self::assertResponseStatusCodeSame(401);
    }

    /** @return iterable<string, array{string}> */
    public static function badQueries(): iterable
    {
        yield 'no dates' => [''];
        yield 'no to' => ['from=2026-10-20'];
        yield 'invalid date' => ['from=2026-02-30&to=2026-03-01'];
        yield 'not a date' => ['from=yesterday&to=2026-03-01'];
        yield 'from after to' => ['from=2026-10-21&to=2026-10-20'];
        yield 'too long' => ['from=2025-01-01&to=2026-10-20'];
    }

    #[DataProvider('badQueries')]
    public function testABadQueryReturns400(string $query): void
    {
        $this->client->request('GET', self::ENDPOINT.'?'.$query, [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
        self::assertArrayHasKey('error', json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR));
    }

    public function testASessionSeenByHealthConnectIsInsideTheActiveEnergy(): void
    {
        $days = $this->days('from=2026-10-20&to=2026-10-20');

        self::assertSame([
            ['date' => '2026-10-20', 'steps' => 8000, 'activeKcal' => 400, 'bmrKcal' => 1648, 'workoutKcal' => 300, 'stepsKcal' => 100, 'expendedKcal' => 2048],
        ], $days);
    }

    public function testATypedSessionIsAddedOnceAndAPlannedOneNever(): void
    {
        $days = $this->days('from=2026-10-21&to=2026-10-22');

        self::assertSame(
            ['date' => '2026-10-21', 'steps' => null, 'activeKcal' => null, 'bmrKcal' => 1648, 'workoutKcal' => 200, 'stepsKcal' => null, 'expendedKcal' => 1848],
            $days[0],
        );
        self::assertSame(
            ['date' => '2026-10-22', 'steps' => null, 'activeKcal' => null, 'bmrKcal' => 1648, 'workoutKcal' => 0, 'stepsKcal' => null, 'expendedKcal' => 1648],
            $days[1],
        );
    }

    public function testASessionWithoutKcalIsEstimatedAndBelongsToItsLocalDay(): void
    {
        $days = $this->days('from=2026-10-23&to=2026-10-24');

        // Swim, 60 min: 8 × 71.8 = 574.4, on the 23rd (21:30 UTC) — not the 24th.
        self::assertSame(574, $days[0]['workoutKcal']);
        self::assertSame(1648 + 574, $days[0]['expendedKcal']);
        self::assertSame(0, $days[1]['workoutKcal']);
    }

    public function testOneRowPerDayAndNothingOfAnotherUser(): void
    {
        $days = $this->days('from=2026-10-19&to=2026-10-25');

        self::assertCount(7, $days);
        self::assertSame(8000, $days[1]['steps']);
        self::assertSame(200, $days[2]['workoutKcal']);
    }

    public function testWithoutAProfileTheBaseAndTheTotalAreNullAndThereIsNoError(): void
    {
        $this->authenticateAsUser($this->user('other_user'));

        $days = $this->days('from=2026-10-20&to=2026-10-21');

        self::assertSame(
            ['date' => '2026-10-20', 'steps' => 999, 'activeKcal' => 99, 'bmrKcal' => null, 'workoutKcal' => 0, 'stepsKcal' => 99, 'expendedKcal' => null],
            $days[0],
        );
        self::assertSame(500, $days[1]['workoutKcal']);
        self::assertNull($days[1]['expendedKcal']);
    }
}
