<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\UseCase;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Enum\MakeupOption;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Exception\WorkoutMakeupConflictException;
use Maggie\Health\UseCase\ChooseWorkoutMakeup;
use Maggie\Health\UseCase\KeepPlannedWorkout;
use Maggie\Health\UseCase\ProposeWorkoutMakeup;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * The use cases check their rule again (MAG-771): a replay keeps nothing
 * twice, proposes nothing twice, and a choice too late tonight is refused.
 * On the fixture: Tuesday's 18:30 run (45 min), streak 3 before it.
 */
final class WorkoutMakeupUseCasesTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-11-10 18:46:00 UTC'));
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/workout_makeup.yaml');
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testProposingTwiceProposesOnce(): void
    {
        $tuesday = $this->workout('tuesday');

        self::assertInstanceOf(WorkoutMakeup::class, $this->propose()->execute($tuesday));
        self::assertNull($this->propose()->execute($tuesday));

        self::assertCount(1, $this->em()->getRepository(WorkoutMakeup::class)->findBy(['missed' => $tuesday]));
        self::assertCount(1, $this->em()->getRepository(Notification::class)->findBy(['type' => NotificationType::WorkoutMakeup]));
    }

    public function testNothingIsProposedBeforeTheMissNorForAKeptSessionNorAMakeup(): void
    {
        Clock::set(new MockClock('2026-11-10 18:44:00 UTC'));
        self::assertNull($this->propose()->execute($this->workout('tuesday')));

        Clock::set(new MockClock('2026-11-10 18:46:00 UTC'));
        self::assertNull($this->propose()->execute($this->workout('planned_3')));
        $makeup = $this->save('2026-11-10T08:00:00+01:00', WorkoutSource::Planned)->setMakeupOf($this->workout('planned_3'));
        $this->em()->flush();
        self::assertNull($this->propose()->execute($makeup));
    }

    public function testKeepingTwiceKeepsOnce(): void
    {
        $done = $this->save('2026-11-11T07:00:00+01:00', WorkoutSource::Manual);
        $tuesday = $this->workout('tuesday');

        // Another day: the rule refuses.
        self::assertFalse($this->keep()->execute($done, $tuesday));

        $done->setStartedAt(new \DateTimeImmutable('2026-11-10T21:00:00+01:00'));
        $this->em()->flush();
        $done = $this->workout($done);
        self::assertSame((string) $tuesday->getId(), (string) $done->getKeeps()?->getId());

        $again = $this->save('2026-11-10T22:00:00+01:00', WorkoutSource::Manual);
        self::assertFalse($this->keep()->execute($again, $tuesday));
        self::assertNull($this->workout($again)->getKeeps());
    }

    public function testSofterOrShorterIsRefusedOnceTooLateTonightTomorrowStaysPossible(): void
    {
        $makeup = $this->propose()->execute($this->workout('tuesday'));
        self::assertNotNull($makeup);

        Clock::set(new MockClock('2026-11-11 07:00:00 UTC'));
        try {
            $this->choose()->execute($makeup, MakeupOption::Shorter);
            self::fail('shorter the next morning should be refused.');
        } catch (WorkoutMakeupConflictException) {
        }

        $this->choose()->execute($makeup, MakeupOption::Tomorrow);
        self::assertEquals(new \DateTimeImmutable('2026-11-11T18:30:00+01:00'), $makeup->getChosenWorkout()?->getStartedAt());
    }

    public function testTomorrowChosenDaysLaterIsStillAhead(): void
    {
        $makeup = $this->propose()->execute($this->workout('tuesday'));
        self::assertNotNull($makeup);

        Clock::set(new MockClock('2026-11-12 19:00:00 UTC'));
        $this->choose()->execute($makeup, MakeupOption::Tomorrow);

        self::assertEquals(new \DateTimeImmutable('2026-11-13T18:30:00+01:00'), $makeup->getChosenWorkout()?->getStartedAt());
    }

    private function propose(): ProposeWorkoutMakeup
    {
        $useCase = self::getContainer()->get(ProposeWorkoutMakeup::class);
        \assert($useCase instanceof ProposeWorkoutMakeup);

        return $useCase;
    }

    private function keep(): KeepPlannedWorkout
    {
        $useCase = self::getContainer()->get(KeepPlannedWorkout::class);
        \assert($useCase instanceof KeepPlannedWorkout);

        return $useCase;
    }

    private function choose(): ChooseWorkoutMakeup
    {
        $useCase = self::getContainer()->get(ChooseWorkoutMakeup::class);
        \assert($useCase instanceof ChooseWorkoutMakeup);

        return $useCase;
    }

    private function save(string $startedAt, WorkoutSource $source): Workout
    {
        $user = $this->em()->find(User::class, $this->getFixture('test_user')->getId());
        \assert($user instanceof User);
        $workout = (new Workout())->setUser($user)->setType(WorkoutType::Run)->setTitle('Course')
            ->setStartedAt(new \DateTimeImmutable($startedAt))->setDurationMinutes(30)->setSource($source);
        $this->em()->persist($workout);
        $this->em()->flush();

        return $workout;
    }

    private function workout(string|Workout $workout): Workout
    {
        $id = $workout instanceof Workout ? $workout->getId() : $this->getFixture($workout)->getId();
        $found = $this->em()->find(Workout::class, $id);
        \assert($found instanceof Workout);
        $this->em()->refresh($found);

        return $found;
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }
}
