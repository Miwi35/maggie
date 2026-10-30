<?php

namespace Maggie\Health\Tests\MessageHandler;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Exception\DuplicateWorkoutException;
use Maggie\Health\Exception\InvalidWorkoutException;
use Maggie\Health\Message\CreateWorkoutCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

class CreateWorkoutHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/workout.yaml');
        $this->resetMercure();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function command(?string $userId = null, string $type = 'run', string $startedAt = '2026-10-22T07:00:00+02:00', int $duration = 30, ?string $externalId = null): CreateWorkoutCommand
    {
        return new CreateWorkoutCommand(
            userId: $userId ?? (string) $this->getFixture('test_user')->getId(),
            type: $type,
            title: 'Footing',
            startedAt: $startedAt,
            durationMinutes: $duration,
            distanceMeters: 5000,
            externalId: $externalId,
        );
    }

    public function testItSavesAndPublishes(): void
    {
        $envelope = self::getContainer()->get(MessageBusInterface::class)->dispatch($this->command());

        $workout = $envelope->last(HandledStamp::class)?->getResult();
        self::assertInstanceOf(Workout::class, $workout);
        $reloaded = $this->em()->find(Workout::class, (string) $workout->getId());
        self::assertInstanceOf(Workout::class, $reloaded);
        self::assertSame(WorkoutType::Run, $reloaded->getType());
        self::assertSame(WorkoutSource::Manual, $reloaded->getSource());
        self::assertSame(5000, $reloaded->getDistanceMeters());
        $this->assertMercureUpdatePublished('/api/workouts/'.$workout->getId());
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function refusals(): iterable
    {
        yield 'unknown type' => [['type' => 'yoga']];
        yield 'date only' => [['startedAt' => '2026-10-22']];
        yield 'no offset' => [['startedAt' => '2026-10-22T07:00:00']];
        yield 'no duration' => [['duration' => 0]];
    }

    /** @param array<string, mixed> $override */
    #[\PHPUnit\Framework\Attributes\DataProvider('refusals')]
    public function testARefusalWritesAndPublishesNothing(array $override): void
    {
        try {
            self::getContainer()->get(MessageBusInterface::class)->dispatch($this->command(...$override));
            self::fail('The command should be refused.');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(InvalidWorkoutException::class, $e->getPrevious());
        }

        self::assertSame(4, $this->em()->getRepository(Workout::class)->count([]));
        $this->assertMercureUpdateCount(0);
    }

    public function testADuplicateExternalIdOfTheSameUserIsRefused(): void
    {
        try {
            self::getContainer()->get(MessageBusInterface::class)->dispatch($this->command(externalId: 'watch-4711'));
            self::fail('The command should be refused.');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(DuplicateWorkoutException::class, $e->getPrevious());
            self::assertSame('externalId', $e->getPrevious()->field);
        }

        self::assertSame(4, $this->em()->getRepository(Workout::class)->count([]));
        $this->assertMercureUpdateCount(0);
    }

    public function testTheSameExternalIdForAnotherUserIsKept(): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(
            $this->command(userId: (string) $this->getFixture('other_user')->getId(), externalId: 'watch-new'),
        );

        self::assertSame(5, $this->em()->getRepository(Workout::class)->count([]));
    }
}
