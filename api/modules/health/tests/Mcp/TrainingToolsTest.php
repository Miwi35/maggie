<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Dto\WorkoutExerciseInput;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Enum\WorkoutVariant;
use Maggie\Health\Mcp\Tool\GetNextPrescriptionTool;
use Maggie\Health\Mcp\Tool\GetTrainingStatusTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** get_next_prescription and get_training_status (MAG-776): the user's own sessions only. */
class TrainingToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    private \DateTimeImmutable $now;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->now = new \DateTimeImmutable('2026-11-10T15:00:00+01:00');
        Clock::set(new MockClock($this->now));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/workout.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function prescription(string $exercise, string $variant = 'normal'): array
    {
        $tool = self::getContainer()->get(GetNextPrescriptionTool::class);
        \assert($tool instanceof GetNextPrescriptionTool);

        return json_decode($tool($exercise, $variant), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function readStatus(string $date): array
    {
        $tool = self::getContainer()->get(GetTrainingStatusTool::class);
        \assert($tool instanceof GetTrainingStatusTool);

        return json_decode($tool($date), true, 512, JSON_THROW_ON_ERROR);
    }

    /**
     * @param array<string, list<int>> $done exercise slug => counts of the sets done
     */
    private function workout(string $owner, int $daysAgo, array $done = [], WorkoutSource $source = WorkoutSource::Manual, ?WorkoutVariant $variant = WorkoutVariant::Normal, ?int $difficulty = null, ?int $targetRir = null): Workout
    {
        $user = $this->getFixture($owner);
        \assert($user instanceof User);
        $inputs = [];
        foreach ($done as $slug => $counts) {
            $seconds = \in_array($slug, ['plank', 'active-hang'], true);
            $inputs[] = new WorkoutExerciseInput(
                $slug, $slug, $seconds ? 'seconds' : 'reps', 1, 3, $counts[0], $counts[0], $counts[0], null, 90, false, $targetRir, null, null,
                array_map(static fn (int $count): array => ['reps' => $seconds ? null : $count, 'seconds' => $seconds ? $count : null, 'meters' => null, 'loadKg' => null], $counts),
            );
        }
        $workout = (new Workout())
            ->setUser($user)
            ->setType(WorkoutType::Strength)
            ->setTitle('Force')
            ->setStartedAt($this->now->modify("-{$daysAgo} days"))
            ->setDurationMinutes(40)
            ->setSource($source)
            ->setVariant($variant)
            ->setDifficulty($difficulty)
            ->replaceExercises($inputs);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->persist($workout);
        $em->flush();

        return $workout;
    }

    public function testWithoutAUserBothToolsAnswerAnError(): void
    {
        self::assertArrayHasKey('error', $this->prescription('pull-up'));
        self::assertArrayHasKey('error', $this->readStatus('2026-11-10'));
    }

    public function testTheNextPrescriptionReadsTheUsersOwnSessions(): void
    {
        $this->loginUser($this->getFixture('test_user'));
        $mine = $this->workout('test_user', 3, ['assisted-pull-up' => [8, 7, 6]]);
        $this->workout('other_user', 1, ['assisted-pull-up' => [8, 8, 8]]);

        $result = $this->prescription('assisted-pull-up');

        self::assertSame('add_reps', $result['reason']);
        self::assertSame([(string) $mine->getId()], $result['basedOn']);
        self::assertSame(7, $result['prescription']['target']);
    }

    public function testAnotherUsersSessionsAreNeverRead(): void
    {
        $this->loginUser($this->getFixture('test_user'));
        $this->workout('other_user', 1, ['assisted-pull-up' => [8, 8, 8]]);

        $result = $this->prescription('assisted-pull-up');

        self::assertSame('start', $result['reason']);
        self::assertSame([], $result['basedOn']);
    }

    public function testUnknownInputsAnswerAnError(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        self::assertArrayHasKey('error', $this->prescription('levitation'));
        self::assertArrayHasKey('error', $this->prescription('pull-up', 'extreme'));
        self::assertArrayHasKey('error', $this->readStatus('10/11/2026'));
    }

    public function testEightWeeksWithoutDeloadMakesOneDue(): void
    {
        $this->loginUser($this->getFixture('test_user'));
        $this->workout('test_user', 63, variant: WorkoutVariant::Calibration);
        $this->workout('test_user', 2, ['assisted-pull-up' => [6, 6, 6]]);

        $status = $this->readStatus('2026-11-10');

        self::assertSame(['due' => true, 'reason' => 'scheduled', 'weeksSinceLast' => 9], $status['deload']);
        self::assertSame(10, $status['programWeek']);
        self::assertSame('normal', $status['phase']);
    }

    public function testTwoStagnatingExercisesMakeADeloadDue(): void
    {
        $this->loginUser($this->getFixture('test_user'));
        $this->workout('test_user', 30, variant: WorkoutVariant::Calibration);
        $this->workout('test_user', 22, ['assisted-pull-up' => [6, 6, 6], 'plank' => [25, 25, 25]]);
        $this->workout('test_user', 8, ['assisted-pull-up' => [6, 6, 6], 'plank' => [25, 25, 25]]);

        $status = $this->readStatus('2026-11-10');

        self::assertSame(['assisted-pull-up', 'plank'], $status['stagnating']);
        self::assertSame('stagnation', $status['deload']['reason']);
    }

    public function testTheWeekAndItsHardSets(): void
    {
        $this->loginUser($this->getFixture('test_user'));
        $this->workout('test_user', 30, variant: WorkoutVariant::Calibration);
        $this->workout('test_user', 1, ['assisted-pull-up' => [6, 6, 6]]);
        $this->workout('test_user', -1, source: WorkoutSource::Planned);
        $this->workout('other_user', 1, ['plank' => [30, 30, 30]]);

        $status = $this->readStatus('2026-11-10');

        self::assertSame(['planned' => 1, 'done' => 1], $status['week']);
        self::assertSame(['vertical_pull' => 3], $status['hardSetsByPattern']);
        self::assertFalse($status['deload']['due']);
    }
}
