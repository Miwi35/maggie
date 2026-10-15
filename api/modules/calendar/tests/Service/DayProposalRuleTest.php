<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskUrgencyLevel;
use Maggie\Calendar\Service\DayProposalRule;
use PHPUnit\Framework\TestCase;

/** The rule of the morning alone (MAG-498): no clock, no database, no agenda. */
class DayProposalRuleTest extends TestCase
{
    private \DateTimeImmutable $day;

    protected function setUp(): void
    {
        $this->day = new \DateTimeImmutable('2026-10-14 00:00:00', new \DateTimeZone('UTC'));
    }

    private function task(string $title, ?int $minutes = null, ?string $due = null, int $score = 0, ?TaskUrgencyLevel $urgency = null, bool $putOff = false, TaskCriticality $criticality = TaskCriticality::Low): Task
    {
        return (new Task())
            ->setTitle($title)
            ->setEstimatedMinutes($minutes)
            ->setDueDate(null === $due ? null : new \DateTimeImmutable($due))
            ->setOrderScore($score)
            ->setUrgency($urgency ?? TaskUrgencyLevel::Low)
            ->setCriticality($criticality)
            ->setPostponedSince($putOff ? new \DateTimeImmutable('2026-10-12') : null);
    }

    /** @param list<Task> $tasks @return list<string> */
    private static function titles(array $tasks): array
    {
        return array_map(static fn (Task $task): string => $task->getTitle(), $tasks);
    }

    public function testTheTiersAreRankedPutOffDueTodayUrgentDueTomorrowTheRest(): void
    {
        $ranked = DayProposalRule::rank([
            $this->task('rest', score: 900),
            $this->task('tomorrow', due: '2026-10-15', score: 800),
            $this->task('high urgency', due: '2026-10-20', score: 100, urgency: TaskUrgencyLevel::High),
            $this->task('critical task', score: 50, criticality: TaskCriticality::Critical),
            $this->task('today', due: '2026-10-14', score: 10),
            $this->task('put off', due: '2026-10-12', score: 1, putOff: true),
        ], $this->day);

        self::assertSame(['put off', 'today', 'high urgency', 'critical task', 'tomorrow', 'rest'], self::titles($ranked));
    }

    public function testWithinATierTheScoreOrdersAndAnEqualScoreFollowsTheDueDayThenTheManualOrder(): void
    {
        $ranked = DayProposalRule::rank([
            $this->task('low', score: 10),
            $this->task('high', score: 90),
            $this->task('tie dated', due: '2026-10-30', score: 50),
            $this->task('tie undated b', score: 50),
        ], $this->day);

        self::assertSame(['high', 'tie dated', 'tie undated b', 'low'], self::titles($ranked));
    }

    public function testTheReasonIsTheTierAndQuickOrWheneverForTheRest(): void
    {
        self::assertSame('postponed', DayProposalRule::reason($this->task('a', putOff: true), $this->day));
        self::assertSame('dueToday', DayProposalRule::reason($this->task('a', due: '2026-10-14'), $this->day));
        self::assertSame('urgent', DayProposalRule::reason($this->task('a', urgency: TaskUrgencyLevel::Critical), $this->day));
        self::assertSame('dueTomorrow', DayProposalRule::reason($this->task('a', due: '2026-10-15'), $this->day));
        self::assertSame('quick', DayProposalRule::reason($this->task('a', minutes: 10), $this->day));
        self::assertSame('quick', DayProposalRule::reason($this->task('a'), $this->day), 'unknown: 15 minutes');
        self::assertSame('whenever', DayProposalRule::reason($this->task('a', minutes: 45), $this->day));
    }

    public function testItFillsUpToFiveWhileTheMinutesFit(): void
    {
        $candidates = array_map(fn (int $i): Task => $this->task('t'.$i, score: 100 - $i), range(1, 8));

        $roomy = DayProposalRule::propose([], $candidates, $this->day, 180);
        self::assertSame(['t1', 't2', 't3', 't4', 't5'], self::titles($roomy['slots']));
        self::assertSame(['t6', 't7', 't8'], self::titles($roomy['reserve']));

        $tight = DayProposalRule::propose([], $candidates, $this->day, 30);
        self::assertSame(['t1', 't2'], self::titles($tight['slots']), '15 + 15 fits 30, a third does not');
        self::assertCount(6, $tight['reserve']);
    }

    public function testThereIsAlwaysAtLeastOneEvenWhenItDoesNotFit(): void
    {
        $proposal = DayProposalRule::propose([], [$this->task('long', minutes: 120), $this->task('short', minutes: 5)], $this->day, 10);

        self::assertSame(['long'], self::titles($proposal['slots']));
        self::assertSame(['short'], self::titles($proposal['reserve']));
    }

    public function testNothingToProposeIsAnEmptyDay(): void
    {
        self::assertSame(['slots' => [], 'reserve' => []], DayProposalRule::propose([], [], $this->day, 600));
    }

    public function testTheSlotsAlreadyWrittenAreKeptAndCountInTheFiveAndInTheTime(): void
    {
        $kept = [$this->task('kept long', minutes: 100), $this->task('kept short', minutes: 5)];
        $candidates = array_map(fn (int $i): Task => $this->task('t'.$i, minutes: 20, score: 100 - $i), range(1, 6));

        $roomy = DayProposalRule::propose($kept, $candidates, $this->day, 600);
        self::assertSame(['kept short', 't1', 't2', 't3', 'kept long'], self::titles($roomy['slots']), 'three more, shortest first');

        $tight = DayProposalRule::propose($kept, $candidates, $this->day, 125);
        self::assertSame(['kept short', 't1', 'kept long'], self::titles($tight['slots']), '100 + 5 + 20 fills 125');
    }

    public function testFiveWrittenSlotsLeaveNothingToAdd(): void
    {
        $kept = array_map(fn (int $i): Task => $this->task('kept'.$i), range(1, 5));

        $proposal = DayProposalRule::propose($kept, [$this->task('more')], $this->day, 600);

        self::assertSame(['kept1', 'kept2', 'kept3', 'kept4', 'kept5'], self::titles($proposal['slots']));
        self::assertSame(['more'], self::titles($proposal['reserve']));
    }

    public function testAWrittenTaskAlreadyDoneTakesASlotButNoTime(): void
    {
        $done = $this->task('done', minutes: 100)->setCompletedAt(new \DateTimeImmutable('2026-10-14 07:00'));

        $proposal = DayProposalRule::propose([$done], [$this->task('a', minutes: 20)], $this->day, 30);

        self::assertSame(['a', 'done'], self::titles($proposal['slots']), 'the done one takes a slot, not the 30 minutes');
    }

    public function testShortestFirstKeepsTheRankOrderOnATie(): void
    {
        $proposal = DayProposalRule::propose([], [$this->task('b', minutes: 10, score: 90), $this->task('a', minutes: 10, score: 80), $this->task('c', minutes: 5, score: 10)], $this->day, 600);

        self::assertSame(['c', 'b', 'a'], self::titles($proposal['slots']));
    }
}
