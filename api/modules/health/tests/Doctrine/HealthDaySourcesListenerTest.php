<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Doctrine;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Entity\HealthDay;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\HealthDayAuthor;
use Maggie\Health\Message\CreateBodyMeasurementCommand;
use Maggie\Health\Message\CreateFoodIntakeCommand;
use Maggie\Health\Message\DeleteBodyMeasurementCommand;
use Maggie\Health\Message\DeleteWorkoutCommand;
use Maggie\Health\Message\SyncHealthDataCommand;
use Maggie\Health\Message\UpdateBodyMeasurementCommand;
use Maggie\Health\Message\UpdateHealthDayCommand;
use Maggie\Health\Message\UpdateWorkoutCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * The health journal's computed columns follow their sources (MAG-750),
 * whichever door changed them: a weighing, a session, an intake, Health
 * Connect's day — and a late one refreshes its own day.
 */
class HealthDaySourcesListenerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-25T21:42:00+02:00')));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/health_day.yaml');
        $this->resetMercure();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function bus(): MessageBusInterface
    {
        $bus = self::getContainer()->get('messenger.bus.default');
        \assert($bus instanceof MessageBusInterface);

        return $bus;
    }

    private function userId(string $ref = 'test_user'): string
    {
        $user = $this->getFixture($ref);
        \assert($user instanceof User);

        return (string) $user->getId();
    }

    private function fixtureId(string $ref): string
    {
        $entity = $this->getFixture($ref);
        \assert($entity instanceof BodyMeasurement || $entity instanceof Workout);

        return (string) $entity->getId();
    }

    private function day(string $date, string $ref = 'test_user'): ?HealthDay
    {
        $this->em()->clear();

        return $this->em()->getRepository(HealthDay::class)->findOneBy(['user' => $this->userId($ref), 'date' => new \DateTimeImmutable($date)]);
    }

    public function testTheDaysWeightIsItsFirstWeighingAndTheNextOneOnceItIsSetAside(): void
    {
        self::assertSame(72.4, $this->day('2026-10-20')?->getWeightKg());
        self::assertSame(21.3, $this->day('2026-10-20')?->getBodyFatPercent());

        $this->bus()->dispatch(new UpdateBodyMeasurementCommand(userId: $this->userId(), measurementId: $this->fixtureId('first_weighing_20'), excluded: true));

        $day = $this->day('2026-10-20');
        self::assertSame(71.9, $day?->getWeightKg());
        self::assertSame(20.8, $day?->getBodyFatPercent());
        self::assertSame('2026-10-20T06:30:00+00:00', $day?->getWeighedAt()?->setTimezone(new \DateTimeZone('UTC'))->format(\DATE_ATOM));
        $this->assertMercureUpdatePublished('/api/health/days/2026-10-20');
    }

    public function testAnEarlierWeighingRecordedLaterBecomesTheDaysWeight(): void
    {
        $this->bus()->dispatch(new CreateBodyMeasurementCommand(userId: $this->userId(), measuredAt: '2026-10-20T06:15:00+02:00', weightKg: 73.0));

        self::assertSame(73.0, $this->day('2026-10-20')?->getWeightKg());
        self::assertNull($this->day('2026-10-20')?->getBodyFatPercent());
    }

    public function testAnIntakeOnTheDayBeforeRefreshesThatDayNotToday(): void
    {
        $this->bus()->dispatch(new CreateFoodIntakeCommand(userId: $this->userId(), eatenAt: '2026-10-24T20:00:00+02:00', label: 'Soupe', kcal: 250));

        self::assertSame(250, $this->day('2026-10-24')?->getEatenKcal());
        self::assertNull($this->day('2026-10-25'));
        $this->assertMercureUpdatePublished('/api/health/days/2026-10-24');
    }

    public function testLateStepsRefreshTheirOwnDay(): void
    {
        $this->bus()->dispatch(new SyncHealthDataCommand(userId: $this->userId(), days: [['date' => '2026-10-21', 'steps' => 6500, 'activeKcal' => 250]]));

        self::assertSame(6500, $this->day('2026-10-21')?->getSteps());
        self::assertSame(8000, $this->day('2026-10-20')?->getSteps());
    }

    public function testASessionMovedToAnotherDayLeavesTheFirstOne(): void
    {
        $this->bus()->dispatch(new UpdateWorkoutCommand(userId: $this->userId(), workoutId: $this->fixtureId('typed_walk_21'), startedAt: '2026-10-23T18:00:00+02:00'));

        // The 21st had nothing else: its entry is gone; the 23rd has it.
        self::assertNull($this->day('2026-10-21'));
        self::assertSame(40, $this->day('2026-10-23')?->getSportMinutes());
    }

    public function testADayLosingItsLastSourceKeepsWhatWasTyped(): void
    {
        $this->bus()->dispatch(new UpdateHealthDayCommand(userId: $this->userId(), date: '2026-10-21', author: HealthDayAuthor::User, mood: 6));
        $this->bus()->dispatch(new DeleteWorkoutCommand(userId: $this->userId(), workoutId: $this->fixtureId('typed_walk_21')));

        $day = $this->day('2026-10-21');
        self::assertNotNull($day);
        self::assertNull($day->getSportMinutes());
        self::assertSame(6, $day->getMood());
    }

    public function testRemovingTheDaysWeighingsEmptiesItsWeight(): void
    {
        $this->bus()->dispatch(new DeleteBodyMeasurementCommand(userId: $this->userId(), measurementId: $this->fixtureId('first_weighing_20')));
        $this->bus()->dispatch(new DeleteBodyMeasurementCommand(userId: $this->userId(), measurementId: $this->fixtureId('second_weighing_20')));

        $day = $this->day('2026-10-20');
        self::assertNull($day?->getWeightKg());
        self::assertNull($day?->getWeighedAt());
        self::assertSame(8000, $day?->getSteps());
    }

    public function testAnotherUsersSourcesNeverReachTheJournal(): void
    {
        self::assertSame(95.0, $this->day('2026-10-20', 'other_user')?->getWeightKg());
        self::assertSame(400, $this->day('2026-10-20', 'other_user')?->getEatenKcal());
        self::assertSame(78, $this->day('2026-10-20')?->getEatenKcal());
    }
}
