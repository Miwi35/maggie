<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Doctrine;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Events;
use Maggie\Core\Entity\User;
use Maggie\Health\Doctrine\PlannedWorkoutKeptListener;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Repository\WorkoutRepository;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * « Séance tenue » hangs on the save of a session done, whatever the door
 * (MAG-771): created or moved onto a day with a planned session, it keeps it;
 * any other change keeps nothing; a failing effect never undoes the save.
 */
final class PlannedWorkoutKeptListenerTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/workout_makeup.yaml');
    }

    public function testASessionDoneCreatedOnTheDayOfAPlannedOneKeepsIt(): void
    {
        $done = $this->done('2026-11-12T07:00:00+01:00');

        self::assertSame((string) $this->getFixture('thursday')->getId(), (string) $this->reload($done)->getKeeps()?->getId());
    }

    public function testASessionDoneMovedOntoTheDayOfAPlannedOneKeepsIt(): void
    {
        $done = $this->done('2026-11-11T07:00:00+01:00');
        self::assertNull($this->reload($done)->getKeeps());

        $this->reload($done)->setStartedAt(new \DateTimeImmutable('2026-11-12T07:00:00+01:00'));
        $this->em()->flush();

        self::assertSame((string) $this->getFixture('thursday')->getId(), (string) $this->reload($done)->getKeeps()?->getId());
    }

    public function testAnotherChangeKeepsNothing(): void
    {
        $done = $this->done('2026-11-11T07:00:00+01:00');
        $this->planned('2026-11-11T18:30:00+01:00');

        $this->reload($done)->setNotes('Bonnes jambes');
        $this->em()->flush();

        self::assertNull($this->reload($done)->getKeeps());
    }

    public function testAFailingEffectDoesNotUndoTheSave(): void
    {
        $this->em()->getEventManager()->addEventListener(
            [Events::preFlush, Events::onFlush, Events::postFlush],
            new PlannedWorkoutKeptListener(new class implements MessageBusInterface {
                public function dispatch(object $message, array $stamps = []): Envelope
                {
                    throw new \RuntimeException('keep failed');
                }
            }, $this->repository(), new NullLogger()),
        );

        $done = $this->done('2026-11-12T07:00:00+01:00');

        self::assertSame('Course', $this->reload($done)->getTitle());
    }

    private function done(string $startedAt): Workout
    {
        return $this->save($startedAt, WorkoutSource::Manual);
    }

    private function planned(string $startedAt): Workout
    {
        return $this->save($startedAt, WorkoutSource::Planned);
    }

    private function save(string $startedAt, WorkoutSource $source): Workout
    {
        $user = $this->em()->find(User::class, $this->getFixture('test_user')->getId());
        \assert($user instanceof User);
        $workout = (new Workout())
            ->setUser($user)
            ->setType(WorkoutType::Run)
            ->setTitle('Course')
            ->setStartedAt(new \DateTimeImmutable($startedAt))
            ->setDurationMinutes(30)
            ->setSource($source);
        $this->em()->persist($workout);
        $this->em()->flush();

        return $workout;
    }

    private function reload(Workout $workout): Workout
    {
        $this->em()->refresh($workout);

        return $workout;
    }

    private function repository(): WorkoutRepository
    {
        $repository = self::getContainer()->get(WorkoutRepository::class);
        \assert($repository instanceof WorkoutRepository);

        return $repository;
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }
}
