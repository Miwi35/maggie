<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Enum\TaskAlertReason;
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskPriority;
use Maggie\Calendar\Repository\DayPlanRepository;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Calendar\Service\TaskAlert;
use Maggie\Calendar\Service\TaskAlertSchedule;
use Maggie\Calendar\Service\TaskOverdue;
use Maggie\Calendar\Service\TaskPostponement;
use Maggie\Calendar\Service\TaskUrgency;
use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserPreferenceRepository;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Clock\MockClock;

/** The spec's « Barème d'alertes par défaut » (MAG-407), morning by morning from J-3 to four days late. */
class TaskAlertScheduleTest extends TestCase
{
    private const MORNING = '2026-10-13';

    /** @param list<Task> $tasks */
    private function schedule(array $tasks, ?TaskSettings $settings = null): TaskAlertSchedule
    {
        $tasksRepository = $this->createStub(TaskRepository::class);
        $tasksRepository->method('findOpenDueUntil')->willReturnCallback(
            static fn (User $user, \DateTimeImmutable $until): array => array_values(array_filter(
                $tasks,
                // What the query keeps: not done, dated, due by $until.
                static fn (Task $t): bool => !$t->isDone() && null !== $t->getDueDate() && $t->getDueDate()->format('Y-m-d') <= $until->format('Y-m-d'),
            )),
        );
        $settingsRepository = $this->createStub(TaskSettingsRepository::class);
        $settingsRepository->method('findOneByUser')->willReturn($settings);

        $urgency = new TaskUrgency(
            new TaskOverdue($this->createStub(UserPreferenceRepository::class), new MockClock(self::MORNING.' 12:00:00 UTC')),
            $settingsRepository,
            new TaskPostponement($this->createStub(DayPlanRepository::class)),
        );

        return new TaskAlertSchedule($tasksRepository, $settingsRepository, $urgency);
    }

    private static function task(TaskCriticality $criticality, ?int $dueInDays, string $title = 'task'): Task
    {
        return (new Task())
            ->setTitle($title)
            ->setCriticality($criticality)
            ->setPriority(TaskPriority::Medium)
            ->setDueDate(null === $dueInDays ? null : (new \DateTimeImmutable(self::MORNING))->modify(\sprintf('%+d days', $dueInDays)));
    }

    /**
     * @param list<Task> $tasks
     *
     * @return list<array{string, string, int}> title, reason, days
     */
    private function alertsOn(array $tasks, ?TaskSettings $settings = null): array
    {
        return array_map(
            static fn (TaskAlert $a): array => [$a->task->getTitle(), $a->reason->value, $a->days],
            $this->schedule($tasks, $settings)->dueAlerts(new User(), new \DateTimeImmutable(self::MORNING)),
        );
    }

    /**
     * Every criticality, every morning from J-3 to four days late: the
     * urgency of the morning picks the rule (critical J-3 and J-1, high J-1,
     * then due day and every late morning; low and normal only from the day).
     *
     * @return iterable<string, array{TaskCriticality, int, string|null, int}>
     */
    public static function mornings(): iterable
    {
        $expected = [
            // criticality => [J-4, J-3, J-2, J-1, J, J+1, J+2, J+3, J+4]
            'critical' => [null, ['days_before', 3], null, ['days_before', 1], ['due_day', 0], ['overdue', 1], ['overdue', 2], ['overdue', 3], ['overdue', 4]],
            'high' => [null, null, null, ['days_before', 1], ['due_day', 0], ['overdue', 1], ['overdue', 2], ['overdue', 3], ['overdue', 4]],
            'medium' => [null, null, null, null, ['due_day', 0], ['overdue', 1], ['overdue', 2], ['overdue', 3], ['overdue', 4]],
            'low' => [null, null, null, null, ['due_day', 0], ['overdue', 1], ['overdue', 2], ['overdue', 3], ['overdue', 4]],
        ];
        foreach ($expected as $criticality => $row) {
            foreach ($row as $i => $alert) {
                $dueInDays = 4 - $i;
                $when = $dueInDays > 0 ? "J-{$dueInDays}" : (0 === $dueInDays ? 'J' : -$dueInDays.' days late');
                yield "{$criticality} criticality, {$when}" => [TaskCriticality::from($criticality), $dueInDays, $alert[0] ?? null, $alert[1] ?? 0];
            }
        }
    }

    #[DataProvider('mornings')]
    public function testEachMorningFollowsTheScheduleOfItsUrgency(TaskCriticality $criticality, int $dueInDays, ?string $reason, int $days): void
    {
        self::assertSame(
            null === $reason ? [] : [['task', $reason, $days]],
            $this->alertsOn([self::task($criticality, $dueInDays)]),
        );
    }

    /** @return iterable<string, array{int, TaskAlertReason|null}> */
    public static function normalMornings(): iterable
    {
        yield 'J-3' => [3, null];
        yield 'J-1' => [1, null];
        yield 'J' => [0, TaskAlertReason::DueDay];
        yield '1 day late' => [-1, null];
        yield '2 days late' => [-2, TaskAlertReason::Overdue];
        yield '3 days late' => [-3, null];
        yield '4 days late' => [-4, TaskAlertReason::Overdue];
    }

    /** A normal task late alerts one morning in two — the table makes every late task high, so the rule is read alone. */
    #[DataProvider('normalMornings')]
    public function testANormalTaskLateAlertsEveryOtherMorning(int $dueInDays, ?TaskAlertReason $expected): void
    {
        self::assertSame($expected, TaskAlertSchedule::reasonFor(TaskAlertSchedule::DEFAULTS['normal'], $dueInDays));
    }

    public function testALowTaskNeverAlerts(): void
    {
        // No rule for low, and none can be set (testABadScheduleIsRefused).
        self::assertArrayNotHasKey('low', TaskAlertSchedule::DEFAULTS);
        foreach ([30, 4, 0, -1, -4] as $days) {
            self::assertNull(TaskAlertSchedule::reasonFor(null, $days));
        }

        // Low that morning: far off and low or medium, or undated.
        self::assertSame([], $this->alertsOn([
            self::task(TaskCriticality::Low, 3, 'low, J-3'),
            self::task(TaskCriticality::Medium, 5, 'medium, J-5'),
            self::task(TaskCriticality::Critical, null, 'undated'),
        ]));
    }

    public function testADoneTaskNeverAlerts(): void
    {
        $done = self::task(TaskCriticality::Critical, 0, 'done')->setCompletedAt(new \DateTimeImmutable());
        $schedule = $this->schedule([$done]);

        self::assertNull($schedule->alertFor($done, new \DateTimeImmutable(self::MORNING), TaskAlertSchedule::DEFAULTS));
        self::assertSame([], $this->alertsOn([$done, self::task(TaskCriticality::Critical, -2, 'done late')->setCompletedAt(new \DateTimeImmutable())]));
    }

    public function testTheMorningsTasksComeTogetherWithTheirUrgency(): void
    {
        $alerts = $this->schedule([
            self::task(TaskCriticality::Critical, -1, 'late'),
            self::task(TaskCriticality::Medium, 0, 'today'),
            self::task(TaskCriticality::Critical, 3, 'in three days'),
            self::task(TaskCriticality::High, 2, 'in two days'),
        ])->dueAlerts(new User(), new \DateTimeImmutable(self::MORNING.' 08:00:00', new \DateTimeZone('Europe/Paris')));

        self::assertSame(
            [['late', 'overdue', 1, 'critical'], ['today', 'due_day', 0, 'high'], ['in three days', 'days_before', 3, 'critical']],
            array_map(static fn (TaskAlert $a): array => [$a->task->getTitle(), $a->reason->value, $a->days, $a->urgency->value], $alerts),
        );
    }

    public function testTheUsersScheduleReplacesTheDefaults(): void
    {
        $settings = (new TaskSettings())->setAlertSchedule(TaskAlertSchedule::merge(TaskAlertSchedule::DEFAULTS, [
            // No J-3 for the critical, a week ahead for the high, and late high every three days.
            'critical' => ['daysBefore' => [1]],
            'high' => ['daysBefore' => [7], 'overdueEveryDays' => 3],
        ]));

        self::assertSame([], $this->alertsOn([self::task(TaskCriticality::Critical, 3)], $settings));
        self::assertSame([['task', 'days_before', 7]], $this->alertsOn([self::task(TaskCriticality::Critical, 7)], $settings));
        self::assertSame([], $this->alertsOn([self::task(TaskCriticality::Medium, -2)], $settings));
        self::assertSame([['task', 'overdue', 3]], $this->alertsOn([self::task(TaskCriticality::Medium, -3)], $settings));
    }

    public function testMergeKeepsWhatIsNotSentAndSortsTheDays(): void
    {
        $schedule = TaskAlertSchedule::merge(TaskAlertSchedule::DEFAULTS, ['critical' => ['daysBefore' => [1, 7, 3, 7]]]);

        self::assertSame([7, 3, 1], $schedule['critical']['daysBefore']);
        self::assertTrue($schedule['critical']['dueDay']);
        self::assertSame(TaskAlertSchedule::DEFAULTS['high'], $schedule['high']);
    }

    public function testCompleteFillsWhatIsMissingOrNoLongerAccepted(): void
    {
        $schedule = TaskAlertSchedule::complete(['high' => ['dueDay' => false, 'overdueEveryDays' => -2], 'low' => ['dueDay' => true]]);

        self::assertFalse($schedule['high']['dueDay']);
        self::assertSame(1, $schedule['high']['overdueEveryDays']);
        self::assertSame(TaskAlertSchedule::DEFAULTS['critical'], $schedule['critical']);
        self::assertArrayNotHasKey('low', $schedule);
    }

    /** @return iterable<string, array{mixed, string}> */
    public static function refused(): iterable
    {
        yield 'an unknown urgency' => [['urgent' => ['dueDay' => true]], 'urgent'];
        yield 'the low urgency' => [['low' => ['dueDay' => true]], 'never alerted'];
        yield 'an unknown key' => [['high' => ['hoursBefore' => [2]]], 'hoursBefore'];
        yield 'a negative day' => [['critical' => ['daysBefore' => [-1]]], 'daysBefore'];
        yield 'day zero' => [['critical' => ['daysBefore' => [0]]], 'daysBefore'];
        yield 'too far ahead' => [['critical' => ['daysBefore' => [31]]], 'daysBefore'];
        yield 'days that are not a list' => [['critical' => ['daysBefore' => 3]], 'daysBefore'];
        yield 'a due day that is not a boolean' => [['normal' => ['dueDay' => 1]], 'dueDay'];
        yield 'a negative period' => [['normal' => ['overdueEveryDays' => -2]], 'overdueEveryDays'];
        yield 'a zero period' => [['normal' => ['overdueEveryDays' => 0]], 'overdueEveryDays'];
        yield 'a rule that is not an object' => [['normal' => true], 'alertSchedule.normal'];
        yield 'a list' => [[1, 2], 'alertSchedule'];
    }

    #[DataProvider('refused')]
    public function testABadScheduleIsRefused(mixed $given, string $named): void
    {
        $this->expectException(\InvalidArgumentException::class);
        $this->expectExceptionMessage($named);

        TaskAlertSchedule::merge(TaskAlertSchedule::DEFAULTS, $given);
    }
}
