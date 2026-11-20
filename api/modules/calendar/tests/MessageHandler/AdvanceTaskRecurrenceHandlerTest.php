<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskMoment;
use Maggie\Calendar\Message\AdvanceTaskRecurrenceCommand;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Maggie\Calendar\Service\TaskUrgency;
use Maggie\Calendar\Tests\Support\RecurringTaskTrait;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\NativeClock;

/** The rule of a recurring task passes to the next occurrence, once, when the current one is done or its day has passed (MAG-870). */
final class AdvanceTaskRecurrenceHandlerTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use RecurringTaskTrait;

    private User $user;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('TaskRecurrenceTest.yaml');
        $this->user = $this->getFixture('test_user');
        $this->today('2026-11-24');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testADoneOccurrenceHandsItsRuleToTheNextOne(): void
    {
        $current = $this->createRecurring($this->user, moment: 'time', time: '19:30', reminders: [30]);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->complete($current);

        [$done, $next] = $this->series();
        self::assertSame((string) $current->getId(), (string) $done->getId());
        self::assertTrue($done->isDone());
        self::assertNull($done->getRrule(), 'a past occurrence has no rule');
        self::assertNull($done->getRecurrenceDate());
        self::assertFalse($next->isDone());
        self::assertSame('2026-12-08', $next->getScheduledDate()?->format('Y-m-d'));
        self::assertSame('2026-12-08', $next->getRecurrenceDate()?->format('Y-m-d'));
        self::assertSame(self::EVERY_OTHER_TUESDAY, $next->getRrule());
        self::assertSame([TaskMoment::Time, '19:30', [30]], [$next->getScheduledMoment(), $next->getScheduledTime(), $next->getReminders()]);
        self::assertSame(['high', 5, 'Descendre le bac'], [$next->getPriority()->value, $next->getEstimatedMinutes(), $next->getFirstStep()]);
        $this->assertMercureUpdatePublished('/tasks/'.$next->getId());
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testAMissedOccurrenceStaysOpenAndTheNextOneIsCreated(): void
    {
        $missed = $this->createRecurring($this->user);
        $this->today('2026-11-26');

        $this->handled(new AdvanceTaskRecurrenceCommand((string) $this->user->getId(), (string) $missed->getId()));

        [$stillOpen, $next] = $this->series();
        self::assertFalse($stillOpen->isDone(), 'a missed occurrence stays to be done');
        self::assertSame('2026-11-24', $stillOpen->getScheduledDate()?->format('Y-m-d'), 'never moved in silence');
        self::assertNull($stillOpen->getRrule());
        $marked = self::getContainer()->get(TaskUrgency::class)->mark($stillOpen);
        self::assertSame('2026-11-24', $marked->getPostponedSince()?->format('Y-m-d'), '« reportée »');
        self::assertSame('2026-12-08', $next->getScheduledDate()?->format('Y-m-d'));
        self::assertSame(self::EVERY_OTHER_TUESDAY, $next->getRrule());
    }

    public function testASecondRunCreatesNothingMore(): void
    {
        $current = $this->createRecurring($this->user);
        $this->complete($current);

        $this->today('2026-11-26');
        $this->handled(new AdvanceTaskRecurrenceCommand((string) $this->user->getId(), (string) $current->getId()));
        $this->handled(new AdvanceTaskRecurrenceCommand((string) $this->user->getId(), (string) $current->getId()));

        self::assertCount(2, $this->series());
    }

    public function testAMovedOccurrenceDoesNotShiftTheSeries(): void
    {
        $current = $this->createRecurring($this->user);
        $this->handled(new UpdateTaskCommand(taskId: (string) $current->getId(), scheduledDate: new \DateTimeImmutable('2026-11-25')));
        $moved = $this->series()[0];
        self::assertSame(['2026-11-25', '2026-11-24'], [$moved->getScheduledDate()?->format('Y-m-d'), $moved->getRecurrenceDate()?->format('Y-m-d')]);

        $this->today('2026-11-25');
        $this->complete($moved);

        $next = $this->series()[1];
        self::assertSame('2026-12-08', $next->getScheduledDate()?->format('Y-m-d'), 'the Tuesday of the series, not Wednesday + 14');
    }

    public function testTheDeadlineKeepsItsGapToThePlannedDay(): void
    {
        $current = $this->createRecurring($this->user, dueDate: '2026-11-25');

        $this->complete($current);

        $next = $this->series()[1];
        self::assertSame(['2026-12-08', '2026-12-09'], [$next->getScheduledDate()?->format('Y-m-d'), $next->getDueDate()?->format('Y-m-d')]);
    }

    public function testAnUntilReachedEndsTheSeriesWithoutANextOne(): void
    {
        $current = $this->createRecurring($this->user, rrule: self::EVERY_OTHER_TUESDAY.';UNTIL=20261201');

        $this->complete($current);

        $series = $this->series();
        self::assertCount(1, $series);
        self::assertNull($series[0]->getRrule());
    }

    public function testReopeningTheDoneOccurrenceCreatesNothing(): void
    {
        $current = $this->createRecurring($this->user);
        $this->complete($current);

        $this->handled(new UpdateTaskCommand(taskId: (string) $current->getId(), clearFields: ['completedAt']));
        $this->complete($this->series()[0]);

        self::assertCount(2, $this->series());
    }
}
