<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskPriority;
use Maggie\Calendar\Enum\TaskUrgencyLevel;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Calendar\Service\TaskOrderWeights;
use Maggie\Calendar\Service\TaskOverdue;
use Maggie\Calendar\Service\TaskUrgency;
use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserPreferenceRepository;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Clock\MockClock;

/** The two tables of the spec « Gestion des tâches » (MAG-406): urgency, and the order score with its examples. */
class TaskUrgencyTest extends TestCase
{
    private const TODAY = '2026-10-13';

    private TaskUrgency $urgency;
    private \DateTimeImmutable $today;

    protected function setUp(): void
    {
        $this->urgency = new TaskUrgency(
            new TaskOverdue($this->createStub(UserPreferenceRepository::class), new MockClock(self::TODAY.' 12:00:00 UTC')),
            $this->createStub(TaskSettingsRepository::class),
        );
        $this->today = new \DateTimeImmutable(self::TODAY.' 00:00:00', new \DateTimeZone('UTC'));
    }

    private static function task(TaskCriticality $criticality, TaskPriority $priority, ?int $dueInDays, string $title = 'task', ?string $position = null): Task
    {
        return (new Task())
            ->setTitle($title)
            ->setCriticality($criticality)
            ->setPriority($priority)
            ->setPosition($position)
            ->setDueDate(null === $dueInDays ? null : (new \DateTimeImmutable(self::TODAY))->modify(\sprintf('%+d days', $dueInDays)));
    }

    private function score(Task $task): int
    {
        return $this->urgency->orderScore($task, $this->today, TaskOrderWeights::DEFAULTS);
    }

    /** @return iterable<string, array{TaskCriticality, int, TaskUrgencyLevel}> the 16 cells of the urgency table */
    public static function urgencyTable(): iterable
    {
        // Columns: far (> 3 days, here 4 and 30), soon (≤ 3 days, here 1 and 3), today (0), late (here -1).
        $rows = [
            'low' => [TaskCriticality::Low, ['low', 'low', 'normal', 'high']],
            'medium' => [TaskCriticality::Medium, ['low', 'normal', 'high', 'high']],
            'high' => [TaskCriticality::High, ['normal', 'high', 'high', 'critical']],
            'critical' => [TaskCriticality::Critical, ['high', 'critical', 'critical', 'critical']],
        ];
        $columns = ['far' => 4, 'soon' => 3, 'today' => 0, 'late' => -1];

        foreach ($rows as $name => [$criticality, $levels]) {
            foreach (array_keys($columns) as $i => $column) {
                yield "{$name} criticality, {$column}" => [$criticality, $columns[$column], TaskUrgencyLevel::from($levels[$i])];
            }
        }
    }

    #[DataProvider('urgencyTable')]
    public function testTheUrgencyFollowsTheTable(TaskCriticality $criticality, int $dueInDays, TaskUrgencyLevel $expected): void
    {
        self::assertSame($expected, $this->urgency->urgency(self::task($criticality, TaskPriority::Medium, $dueInDays), $this->today));
    }

    public function testTheEdgesOfTheColumns(): void
    {
        $medium = static fn (int $days): Task => self::task(TaskCriticality::Medium, TaskPriority::Medium, $days);

        self::assertSame(TaskUrgencyLevel::Normal, $this->urgency->urgency($medium(1), $this->today), 'tomorrow is soon');
        self::assertSame(TaskUrgencyLevel::Low, $this->urgency->urgency($medium(30), $this->today), 'a month away is far');
        self::assertSame(TaskUrgencyLevel::High, $this->urgency->urgency($medium(-12), $this->today), 'twelve days late is late');
    }

    public function testAnUndatedTaskIsLowWhateverItsCriticality(): void
    {
        foreach (TaskCriticality::cases() as $criticality) {
            self::assertSame(TaskUrgencyLevel::Low, $this->urgency->urgency(self::task($criticality, TaskPriority::High, null), $this->today));
        }
    }

    public function testADoneTaskIsLowAndHasNoTimePressure(): void
    {
        $done = self::task(TaskCriticality::Critical, TaskPriority::High, -3)->setCompletedAt(new \DateTimeImmutable());

        self::assertSame(TaskUrgencyLevel::Low, $this->urgency->urgency($done, $this->today));
        self::assertSame(36, $this->score($done));
    }

    public function testTomorrowsBasicTaskComesBeforeACriticalOneDueInAMonth(): void
    {
        $inAMonth = self::task(TaskCriticality::Critical, TaskPriority::High, 30);
        $tomorrow = self::task(TaskCriticality::Low, TaskPriority::Low, 1);

        self::assertSame(41, $this->score($inAMonth));
        self::assertSame(45, $this->score($tomorrow));
    }

    public function testACriticalTaskDueInSevenDaysComesBeforeTomorrowsBasicOne(): void
    {
        self::assertSame(51, $this->score(self::task(TaskCriticality::Critical, TaskPriority::High, 7)));
        self::assertSame(45, $this->score(self::task(TaskCriticality::Low, TaskPriority::Low, 1)));
    }

    public function testThePriorityBreaksTheTieAtEqualCriticalityAndDueDay(): void
    {
        $high = $this->urgency->mark(self::task(TaskCriticality::Medium, TaskPriority::High, 2, 'high')->setUser(new User()));
        $medium = $this->urgency->mark(self::task(TaskCriticality::Medium, TaskPriority::Medium, 2, 'medium')->setUser(new User()));

        self::assertGreaterThan($medium->getOrderScore(), $high->getOrderScore());
        self::assertSame(['high', 'medium'], array_map(static fn (Task $t): string => $t->getTitle(), $this->urgency->sort([$medium, $high])));
    }

    public function testABasicTaskThreeDaysLateScores71(): void
    {
        self::assertSame(71, $this->score(self::task(TaskCriticality::Low, TaskPriority::Low, -3)));
    }

    public function testTheLatenessPressureStopsAtItsCap(): void
    {
        self::assertSame(85, $this->score(self::task(TaskCriticality::Low, TaskPriority::Low, -10)));
        self::assertSame(85, $this->score(self::task(TaskCriticality::Low, TaskPriority::Low, -40)));
    }

    /** @return iterable<string, array{int|null, int}> */
    public static function timePressure(): iterable
    {
        yield 'today' => [0, 55];
        yield 'tomorrow' => [1, 45];
        yield 'in 2 days' => [2, 30];
        yield 'in 3 days' => [3, 30];
        yield 'in 4 days' => [4, 15];
        yield 'in 7 days' => [7, 15];
        yield 'in 8 days' => [8, 5];
        yield 'in 30 days' => [30, 5];
        yield 'in 31 days' => [31, 0];
        yield 'undated' => [null, 0];
        yield '1 day late' => [-1, 67];
    }

    #[DataProvider('timePressure')]
    public function testTheTimePressureFollowsTheWeights(?int $dueInDays, int $expected): void
    {
        self::assertSame($expected, $this->score(self::task(TaskCriticality::Low, TaskPriority::Low, $dueInDays)));
    }

    public function testChangedWeightsChangeTheScore(): void
    {
        $weights = TaskOrderWeights::merge(TaskOrderWeights::DEFAULTS, ['timePressure' => ['tomorrow' => 10], 'criticality' => ['critical' => 50]]);

        self::assertSame(61, $this->urgency->orderScore(self::task(TaskCriticality::Critical, TaskPriority::High, 30), $this->today, $weights));
        self::assertSame(10, $this->urgency->orderScore(self::task(TaskCriticality::Low, TaskPriority::Low, 1), $this->today, $weights));
    }

    public function testAtEqualScoreTheClosestDueDayThenThePositionLead(): void
    {
        $user = new User();
        // Four scores of 15: 5 + 10 for the first, 15 + 0 for the others.
        $later = $this->urgency->mark(self::task(TaskCriticality::Medium, TaskPriority::Low, 8, 'in 8 days')->setUser($user));
        $sooner = $this->urgency->mark(self::task(TaskCriticality::Low, TaskPriority::Low, 4, 'in 4 days')->setUser($user));
        $secondPlace = $this->urgency->mark(self::task(TaskCriticality::Low, TaskPriority::Low, 5, 'position 2', '00000000000000000002')->setUser($user));
        $firstPlace = $this->urgency->mark(self::task(TaskCriticality::Low, TaskPriority::Low, 5, 'position 1', '00000000000000000001')->setUser($user));

        self::assertSame(
            ['in 4 days', 'position 1', 'position 2', 'in 8 days'],
            array_map(static fn (Task $t): string => $t->getTitle(), $this->urgency->sort([$later, $secondPlace, $firstPlace, $sooner])),
        );
    }

    public function testUnknownOrNegativeWeightsAreRefused(): void
    {
        foreach ([
            ['timePressure' => ['today' => -1]],
            ['timePressure' => ['yesterday' => 3]],
            ['urgency' => ['high' => 3]],
            ['priority' => ['high' => 2.5]],
            ['priority' => 6],
            [6],
        ] as $given) {
            try {
                TaskOrderWeights::merge(TaskOrderWeights::DEFAULTS, $given);
                self::fail('refused: '.json_encode($given));
            } catch (\InvalidArgumentException) {
                $this->addToAssertionCount(1);
            }
        }
    }
}
