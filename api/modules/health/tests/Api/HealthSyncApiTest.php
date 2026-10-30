<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Entity\DailyActivity;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\DailyActivitySource;
use Maggie\Health\Enum\MeasurementSource;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The Health Connect batch (MAG-708): an idempotent upsert of workouts, measurements and days. */
class HealthSyncApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;

    private const string ENDPOINT = '/api/health/sync';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('health_sync.yaml');
        $this->authenticateAsUser($this->user('test_user'));
        $this->resetMercure();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', self::ENDPOINT, [], [], ['CONTENT_TYPE' => 'application/json'], json_encode($this->batch(), JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
        self::assertNull($this->em()->getRepository(Workout::class)->findOneBy(['externalId' => 'hc-swim-1']));
    }

    public function testABatchIsCreatedThenReplayedWithoutCreatingAnything(): void
    {
        $this->sync($this->batch());

        self::assertResponseIsSuccessful();
        self::assertSame([
            'workouts' => ['created' => 1, 'updated' => 0, 'unchanged' => 0],
            'measurements' => ['created' => 2, 'updated' => 0, 'unchanged' => 0],
            'days' => ['created' => 1, 'updated' => 0, 'unchanged' => 0],
        ], $this->response());

        $swim = $this->workout('hc-swim-1');
        self::assertSame(WorkoutType::Swim, $swim->getType());
        // No title in the batch: the type's.
        self::assertSame('Natation', $swim->getTitle());
        self::assertSame(WorkoutSource::Watch, $swim->getSource());
        self::assertSame('2026-10-22T06:30:00+00:00', $swim->getStartedAt()->setTimezone(new \DateTimeZone('UTC'))->format(\DATE_ATOM));
        self::assertSame(40, $swim->getDurationMinutes());
        self::assertSame(1500, $swim->getDistanceMeters());
        self::assertSame(320, $swim->getKcal());
        self::assertSame(128, $swim->getAvgHeartRate());

        $weight = $this->measurement('hc-weight-2');
        self::assertSame(71.9, $weight->getWeightKg());
        self::assertNull($weight->getBodyFatPercent());
        self::assertSame(MeasurementSource::HealthConnect, $weight->getSource());
        self::assertSame(20.8, $this->measurement('hc-fat-2')->getBodyFatPercent());

        $day = $this->day('2026-10-22');
        self::assertSame(10250, $day->getSteps());
        self::assertSame(530, $day->getActiveKcal());
        self::assertSame(DailyActivitySource::HealthConnect, $day->getSource());

        $this->assertMercurePublishedOnce('/api/workouts/'.$swim->getId());
        $this->assertMercurePublishedOnce('/api/body-measurements/'.$weight->getId());
        $this->assertMercurePublishedOnce('/api/daily-activities/'.$day->getId());

        $this->resetMercure();
        $this->sync($this->batch());

        self::assertResponseIsSuccessful();
        self::assertSame([
            'workouts' => ['created' => 0, 'updated' => 0, 'unchanged' => 1],
            'measurements' => ['created' => 0, 'updated' => 0, 'unchanged' => 2],
            'days' => ['created' => 0, 'updated' => 0, 'unchanged' => 1],
        ], $this->response());
        self::assertSame(2, $this->rows(Workout::class, 'test_user'));
        self::assertSame(3, $this->rows(BodyMeasurement::class, 'test_user'));
        self::assertSame(2, $this->rows(DailyActivity::class, 'test_user'));
        $this->assertMercureUpdateCount(0);
    }

    public function testAKnownExternalIdOrDateIsUpdatedAndOnlyTheUsersOwn(): void
    {
        $this->sync([
            'workouts' => [
                ['externalId' => 'hc-run-1', 'type' => 'run', 'startedAt' => '2026-10-20T07:10:00.700+02:00', 'durationMinutes' => 35, 'source' => 'watch'],
                // The other user's externalId: a new workout of this user's.
                ['externalId' => 'hc-run-2', 'type' => 'run', 'startedAt' => '2026-10-20T07:10:00+02:00', 'durationMinutes' => 50, 'source' => 'watch'],
            ],
            'measurements' => [
                ['externalId' => 'hc-weight-1', 'measuredAt' => '2026-10-21T07:12:00+02:00', 'weightKg' => 72.6, 'source' => 'health_connect'],
            ],
            'days' => [
                ['date' => '2026-10-20', 'steps' => 9000, 'activeKcal' => 450],
                ['date' => '2026-10-21', 'steps' => 3000, 'activeKcal' => 120],
            ],
        ]);

        self::assertResponseIsSuccessful();
        self::assertSame([
            'workouts' => ['created' => 1, 'updated' => 1, 'unchanged' => 0],
            'measurements' => ['created' => 0, 'updated' => 1, 'unchanged' => 0],
            'days' => ['created' => 1, 'updated' => 1, 'unchanged' => 0],
        ], $this->response());

        $run = $this->workout('hc-run-1');
        self::assertSame(35, $run->getDurationMinutes());
        // The title renamed in Maggie stays; what the watch no longer sends is cleared.
        self::assertSame('Course du matin', $run->getTitle());
        self::assertNull($run->getDistanceMeters());
        $this->assertMercurePublishedOnce('/api/workouts/'.$run->getId());

        $weighing = $this->measurement('hc-weight-1');
        self::assertSame(72.6, $weighing->getWeightKg());
        // The waist size and « écarter » are the user's: kept.
        self::assertSame(84.5, $weighing->getWaistCm());
        self::assertTrue($weighing->isExcluded());

        self::assertSame(9000, $this->day('2026-10-20')->getSteps());
        $this->assertMercurePublishedOnce('/api/daily-activities/'.$this->day('2026-10-20')->getId());
        self::assertSame(3000, $this->day('2026-10-21')->getSteps());

        $other = $this->user('other_user');
        self::assertSame(50, $this->em()->getRepository(Workout::class)->findOneBy(['user' => $other, 'externalId' => 'hc-run-2'])?->getDurationMinutes());
        self::assertSame(999, $this->em()->getRepository(DailyActivity::class)->findOneBy(['user' => $other])?->getSteps());

        // The fraction of a second was dropped: the same batch again changes nothing.
        $this->sync(['workouts' => [['externalId' => 'hc-run-1', 'type' => 'run', 'startedAt' => '2026-10-20T07:10:00.700+02:00', 'durationMinutes' => 35, 'source' => 'watch']]]);
        self::assertSame(['created' => 0, 'updated' => 0, 'unchanged' => 1], $this->response()['workouts']);
    }

    /** @return iterable<string, array{array<string, mixed>, string}> */
    public static function invalidBatches(): iterable
    {
        $workout = ['externalId' => 'hc-x', 'type' => 'run', 'startedAt' => '2026-10-22T08:30:00+02:00', 'durationMinutes' => 30, 'source' => 'watch'];
        $measurement = ['externalId' => 'hc-y', 'measuredAt' => '2026-10-22T07:00:00+02:00', 'weightKg' => 72.0];
        $day = ['date' => '2026-10-22', 'steps' => 100, 'activeKcal' => 10];

        yield 'unknown type' => [['workouts' => [$workout, ['externalId' => 'hc-yoga', 'type' => 'yoga'] + $workout]], 'workouts[1].type'];
        yield 'start without offset' => [['workouts' => [['startedAt' => '2026-10-22T08:30:00'] + $workout]], 'workouts[0].startedAt'];
        yield 'duration as a string' => [['workouts' => [['durationMinutes' => '30'] + $workout]], 'workouts[0].durationMinutes'];
        yield 'missing externalId' => [['workouts' => [['externalId' => ''] + $workout]], 'workouts[0].externalId'];
        yield 'a source the sync does not write' => [['workouts' => [['source' => 'manual'] + $workout]], 'workouts[0].source'];
        yield 'same externalId twice' => [['workouts' => [$workout, $workout]], 'workouts[1].externalId'];
        yield 'measurement without a figure' => [['measurements' => [$measurement, ['externalId' => 'hc-z', 'measuredAt' => '2026-10-22T07:00:00+02:00']]], 'measurements[1].weightKg'];
        yield 'weight out of range' => [['measurements' => [['weightKg' => 900] + $measurement]], 'measurements[0].weightKg'];
        yield 'not a date' => [['days' => [$day, ['date' => '2026-02-30'] + $day]], 'days[1].date'];
        yield 'negative steps' => [['days' => [['steps' => -1] + $day]], 'days[0].steps'];
        yield 'same day twice' => [['days' => [$day, $day]], 'days[1].date'];
    }

    /** @param array<string, mixed> $batch */
    #[DataProvider('invalidBatches')]
    public function testAnInvalidItemRefusesTheWholeBatchWithItsPath(array $batch, string $path): void
    {
        // A valid workout first: it must not be written either.
        $batch['workouts'] = [['externalId' => 'hc-first', 'type' => 'walk', 'startedAt' => '2026-10-22T12:00:00+02:00', 'durationMinutes' => 20, 'source' => 'watch'], ...$batch['workouts'] ?? []];
        $batch['workouts'] = array_values($batch['workouts']);
        // The prepended workout shifts the workouts' indexes by one.
        $path = (string) preg_replace_callback('/^workouts\[(\d+)\]/', static fn (array $m): string => 'workouts['.((int) $m[1] + 1).']', $path);

        $this->sync($batch);

        self::assertResponseStatusCodeSame(400);
        self::assertSame($path, $this->response()['path']);
        self::assertStringStartsWith($path.': ', $this->response()['error']);
        self::assertNull($this->em()->getRepository(Workout::class)->findOneBy(['externalId' => 'hc-first']));
        $this->assertMercureUpdateCount(0);
    }

    public function testABodyThatIsNotAnObjectOfListsIsRefused(): void
    {
        $this->sync(['workouts' => 'nope']);
        self::assertResponseStatusCodeSame(400);
        self::assertSame('workouts: must be a list.', $this->response()['error']);

        $this->sync(['days' => [42]]);
        self::assertResponseStatusCodeSame(400);
        self::assertSame('days[0]: must be an object.', $this->response()['error']);

        $this->sync(['sleep' => []]);
        self::assertResponseStatusCodeSame(400);
    }

    /** @return array<string, mixed> */
    private function batch(): array
    {
        return [
            'workouts' => [
                ['externalId' => 'hc-swim-1', 'type' => 'swim', 'startedAt' => '2026-10-22T08:30:00+02:00', 'durationMinutes' => 40, 'distanceMeters' => 1500, 'kcal' => 320, 'avgHeartRate' => 128, 'source' => 'watch'],
            ],
            'measurements' => [
                ['externalId' => 'hc-weight-2', 'measuredAt' => '2026-10-22T07:05:00+02:00', 'weightKg' => 71.9, 'source' => 'health_connect'],
                ['externalId' => 'hc-fat-2', 'measuredAt' => '2026-10-22T07:05:00+02:00', 'bodyFatPercent' => 20.8, 'source' => 'health_connect'],
            ],
            'days' => [
                ['date' => '2026-10-22', 'steps' => 10250, 'activeKcal' => 530],
            ],
        ];
    }

    /** @param array<string, mixed> $body */
    private function sync(array $body): void
    {
        $this->client->request('POST', self::ENDPOINT, [], [], ['CONTENT_TYPE' => 'application/json'] + $this->authHeaders(), json_encode($body, JSON_THROW_ON_ERROR));
    }

    /** @return array<string, mixed> */
    private function response(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function user(string $fixture): User
    {
        $user = $this->getFixture($fixture);
        \assert($user instanceof User);

        return $user;
    }

    private function workout(string $externalId): Workout
    {
        $workout = $this->em()->getRepository(Workout::class)->findOneBy(['user' => $this->user('test_user'), 'externalId' => $externalId]);
        self::assertInstanceOf(Workout::class, $workout);

        return $workout;
    }

    private function measurement(string $externalId): BodyMeasurement
    {
        $measurement = $this->em()->getRepository(BodyMeasurement::class)->findOneBy(['user' => $this->user('test_user'), 'externalId' => $externalId]);
        self::assertInstanceOf(BodyMeasurement::class, $measurement);

        return $measurement;
    }

    private function day(string $date): DailyActivity
    {
        $day = $this->em()->getRepository(DailyActivity::class)->findOneBy(['user' => $this->user('test_user'), 'date' => new \DateTimeImmutable($date)]);
        self::assertInstanceOf(DailyActivity::class, $day);

        return $day;
    }

    /** @param class-string<Workout|BodyMeasurement|DailyActivity> $class */
    private function rows(string $class, string $userFixture): int
    {
        return $this->em()->getRepository($class)->count(['user' => $this->user($userFixture)]);
    }
}
