<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Service;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\MakeupOption;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Service\WorkoutStreak;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * The streak (MAG-771), on the fixture: three planned sessions kept, then
 * Tuesday's 18:30 (45 min) not kept, then Thursday's.
 */
final class WorkoutStreakTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/workout_makeup.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testThePlannedSessionsKeptInARowCount(): void
    {
        Clock::set(new MockClock('2026-11-10 12:00:00 UTC'));

        self::assertSame(3, $this->streak()->before($this->workout('tuesday')));
        self::assertSame(4, $this->streak()->keeping($this->workout('tuesday')));
    }

    public function testASessionOfTheDayNotOverYetBreaksNothing(): void
    {
        // 19:00 Paris: Tuesday's session is not missed before 19:45.
        Clock::set(new MockClock('2026-11-10 18:00:00 UTC'));

        self::assertSame(3, $this->streak()->before($this->workout('thursday')));
    }

    public function testAMissedSessionNeitherKeptNorMadeUpBreaksIt(): void
    {
        Clock::set(new MockClock('2026-11-12 08:00:00 UTC'));

        self::assertSame(0, $this->streak()->before($this->workout('thursday')));
    }

    public function testAMakeupStillToComeBreaksNothingAndKeptItCountsForTheMissedSession(): void
    {
        $tuesday = $this->workout('tuesday');
        $makeup = (new Workout())
            ->setUser($tuesday->getUser())
            ->setType($tuesday->getType())
            ->setTitle($tuesday->getTitle())
            ->setStartedAt(new \DateTimeImmutable('2026-11-11T18:30:00+01:00'))
            ->setDurationMinutes(45)
            ->setSource(WorkoutSource::Planned)
            ->setMakeupOf($tuesday)
            ->setMakeupOption(MakeupOption::Tomorrow);
        $this->em()->persist($makeup);
        $this->em()->flush();

        Clock::set(new MockClock('2026-11-11 08:00:00 UTC'));
        self::assertSame(3, $this->streak()->before($this->workout('thursday')));
        self::assertSame(4, $this->streak()->keeping($makeup));
    }

    private function streak(): WorkoutStreak
    {
        $streak = self::getContainer()->get(WorkoutStreak::class);
        \assert($streak instanceof WorkoutStreak);

        return $streak;
    }

    private function workout(string $fixture): Workout
    {
        $workout = $this->em()->find(Workout::class, $this->getFixture($fixture)->getId());
        \assert($workout instanceof Workout);

        return $workout;
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }
}
