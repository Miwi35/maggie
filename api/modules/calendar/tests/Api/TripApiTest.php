<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Routing\RouteLeg;
use Maggie\Calendar\Service\TripEstimate;
use Maggie\Calendar\Service\TripOrigin;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * Kept trips over REST (MAG-654): `/api/trips?date=` and `/api/trips/{id}`, read
 * only, the owner's only, served by Doctrine. The trips are seeded as set_trip
 * keeps them; computing them is {@see \Maggie\Calendar\Tests\Mcp\TripToolsTest}'s.
 */
class TripApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        $this->loadFixtures(\dirname(__DIR__).'/Mcp/fixtures/EstimateTripToolTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** A trip as set_trip keeps it, leaving at $departAt (Paris). */
    private function seed(string $eventRef, string $departAt, TripMode $mode = TripMode::Walk): string
    {
        $event = $this->em()->find(Event::class, $this->id($eventRef));
        self::assertNotNull($event);
        $steps = TripMode::Drive === $mode
            ? [new RouteLeg(TripMode::Walk, 240), new RouteLeg(TripMode::Drive, 840), new RouteLeg(TripMode::Walk, 180)]
            : [new RouteLeg($mode, 240)];
        $trip = (new Trip($event))->apply(new TripEstimate(
            mode: $mode,
            origin: new TripOrigin(TripOrigin::POSITION, null, 'ma position', 48.11, -1.68),
            steps: $steps,
            durationMinutes: TripMode::Drive === $mode ? 21 : 4,
            departAt: new \DateTimeImmutable($departAt, new \DateTimeZone('Europe/Paris')),
            navigationUrl: 'https://waze.com/ul?ll=48.119,-1.6672&navigate=yes',
            parkingKnown: true,
            carLatitude: TripMode::Drive === $mode ? 48.1127 : null,
            carLongitude: TripMode::Drive === $mode ? -1.68 : null,
        ), TripDecider::Maggie, new \DateTimeImmutable('2026-11-03 10:00:00 UTC'));
        $this->em()->persist($trip);
        $this->em()->flush();

        return (string) $trip->getId();
    }

    /** @return array<string, mixed> */
    private function get(string $endpoint): array
    {
        $this->client->request('GET', $endpoint, [], [], $this->authHeaders() + ['HTTP_ACCEPT' => 'application/ld+json']);
        $content = (string) $this->client->getResponse()->getContent();

        return '' === $content ? [] : json_decode($content, true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $trip = $this->seed('dentist', '2026-11-03 13:34');

        foreach (['/api/trips', '/api/trips?date=2026-11-03', '/api/trips/'.$trip] as $endpoint) {
            $this->client->request('GET', $endpoint);
            self::assertResponseStatusCodeSame(401, $endpoint);
        }
    }

    public function testTheDayListsTheUsersTripsLeavingThatParisDayByDeparture(): void
    {
        $dentist = $this->seed('dentist', '2026-11-03 13:34', TripMode::Drive);
        $office = $this->seed('office', '2026-11-03 08:51');
        // 00:21 in Paris on the 4th, 23:21 UTC on the 3rd.
        $swim = $this->seed('swim', '2026-11-04 00:21');
        $this->seed('other_dentist', '2026-11-03 12:00');

        $today = $this->get('/api/trips?date=2026-11-03');

        self::assertResponseIsSuccessful();
        self::assertSame([$office, $dentist], array_column($today['member'], 'id'));
        $trip = $today['member'][1];
        self::assertSame('/api/trips/'.$dentist, $trip['@id']);
        self::assertSame('/api/events/'.$this->id('dentist'), $trip['event']);
        self::assertSame('drive', $trip['mode']);
        self::assertSame([
            ['kind' => 'walk', 'minutes' => 4],
            ['kind' => 'drive', 'minutes' => 14],
            ['kind' => 'walk', 'minutes' => 3],
        ], $trip['steps']);
        self::assertSame(21, $trip['durationMinutes']);
        self::assertEquals(new \DateTimeImmutable('2026-11-03T13:34:00+01:00'), new \DateTimeImmutable($trip['departAt']));
        self::assertSame('https://waze.com/ul?ll=48.119,-1.6672&navigate=yes', $trip['navigationUrl']);
        self::assertSame(['position', null, 'ma position', 48.11, -1.68], [$trip['originKind'], $trip['originRef'] ?? null, $trip['originLabel'], $trip['originLatitude'], $trip['originLongitude']]);
        self::assertSame([48.1127, -1.68], [$trip['carLatitude'], $trip['carLongitude']]);
        self::assertSame('maggie', $trip['originDecidedBy']);

        self::assertSame([$swim], array_column($this->get('/api/trips?date=2026-11-04')['member'], 'id'));
    }

    public function testWithoutADateTheListHasAllTheUsersTripsOnly(): void
    {
        $dentist = $this->seed('dentist', '2026-11-03 13:34');
        $this->seed('other_dentist', '2026-11-03 12:00');

        self::assertSame([$dentist], array_column($this->get('/api/trips')['member'], 'id'));
    }

    /** @return iterable<string, array{string}> */
    public static function malformedDates(): iterable
    {
        yield 'words' => ['demain'];
        yield 'no such day' => ['2026-02-30'];
        yield 'with a time' => ['2026-11-03T10:00'];
        yield 'empty' => [''];
    }

    #[DataProvider('malformedDates')]
    public function testADateThatIsNotADayIs400(string $date): void
    {
        $this->seed('dentist', '2026-11-03 13:34');

        $this->get('/api/trips?date='.urlencode($date));

        self::assertResponseStatusCodeSame(400);
    }

    public function testATripIsReadByItsOwnerOnly(): void
    {
        $own = $this->seed('dentist', '2026-11-03 13:34');
        $other = $this->seed('other_dentist', '2026-11-03 12:00');

        self::assertSame('Dentiste', $this->em()->find(Trip::class, $own)?->getEvent()->getSummary());
        self::assertSame($own, $this->get('/api/trips/'.$own)['id']);
        self::assertResponseIsSuccessful();

        $this->get('/api/trips/'.$other);
        self::assertResponseStatusCodeSame(404);
    }
}
