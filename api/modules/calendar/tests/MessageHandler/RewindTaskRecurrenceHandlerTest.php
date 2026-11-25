<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\AdvanceTaskRecurrenceCommand;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Calendar\Message\RewindTaskRecurrenceCommand;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Maggie\Calendar\Tests\Support\RecurringTaskTrait;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\NativeClock;

/** A reopened occurrence gives the series back while its next one is untouched (MAG-1033). */
final class RewindTaskRecurrenceHandlerTest extends KernelTestCase
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

    public function testReopeningAHandedOverOccurrenceGivesItsRuleBack(): void
    {
        $current = $this->createRecurring($this->user);
        $this->complete($current);
        $next = $this->series()[1];
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->reopen($current);

        $series = $this->series();
        self::assertCount(1, $series, 'the next occurrence is gone');
        self::assertSame((string) $current->getId(), (string) $series[0]->getId());
        self::assertFalse($series[0]->isDone());
        self::assertSame(self::EVERY_OTHER_TUESDAY, $series[0]->getRrule());
        self::assertSame('2026-11-24', $series[0]->getRecurrenceDate()?->format('Y-m-d'));
        self::assertNull($series[0]->getNextOccurrence());
        self::assertNull($this->em()->find(Task::class, $next->getId()));
        $this->assertMercureUpdatePublished('/tasks/'.$current->getId());
        $this->assertMercureUpdatePublished('/tasks/'.$next->getId());
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testAMovedOccurrenceKeepsItsDayOfTheRule(): void
    {
        $current = $this->createRecurring($this->user);
        $this->handled(new UpdateTaskCommand(taskId: (string) $current->getId(), scheduledDate: new \DateTimeImmutable('2026-11-25')));
        $this->today('2026-11-25');
        $this->complete($this->series()[0]);

        $this->reopen($current);

        $series = $this->series();
        self::assertCount(1, $series);
        self::assertSame(['2026-11-25', '2026-11-24'], [$series[0]->getScheduledDate()?->format('Y-m-d'), $series[0]->getRecurrenceDate()?->format('Y-m-d')]);
        self::assertSame(self::EVERY_OTHER_TUESDAY, $series[0]->getRrule());

        $this->complete($series[0]);
        self::assertSame('2026-12-08', $this->series()[1]->getScheduledDate()?->format('Y-m-d'), 'the series keeps its Tuesdays');
    }

    public function testANextOccurrenceAlreadyDoneIsKept(): void
    {
        $current = $this->createRecurring($this->user);
        $this->complete($current);
        // Done without handing its rule over, as a Google sync may leave it.
        $this->em()->getConnection()->executeStatement('UPDATE task SET completed_at = NOW() WHERE id = ?', [$this->series()[1]->getId()->toRfc4122()]);

        $this->reopen($current);

        $this->assertReopenedAlone($current);
    }

    public function testANextOccurrenceWithSubtasksIsKept(): void
    {
        $current = $this->createRecurring($this->user);
        $this->complete($current);
        $this->handled(new CreateTaskCommand(userId: (string) $this->user->getId(), title: 'Rincer le bac', parentId: (string) $this->series()[1]->getId()));

        $this->reopen($current);

        $this->assertReopenedAlone($current);
        self::assertCount(1, $this->series('Rincer le bac'), 'the subtask is still there');
    }

    public function testANextOccurrenceWithoutRuleIsKept(): void
    {
        $current = $this->createRecurring($this->user);
        $this->complete($current);
        $this->handled(new UpdateTaskCommand(taskId: (string) $this->series()[1]->getId(), clearFields: ['rrule']));

        $this->reopen($current);

        $this->assertReopenedAlone($current);
    }

    public function testAMissedOccurrenceTickedThenUntickedKeepsTheNextOne(): void
    {
        $missed = $this->createRecurring($this->user);
        $this->today('2026-11-26');
        $this->handled(new AdvanceTaskRecurrenceCommand((string) $this->user->getId(), (string) $missed->getId()));
        $this->complete($this->series()[0]);

        $this->reopen($missed);

        $this->assertReopenedAlone($missed);
    }

    public function testASecondRunChangesNothing(): void
    {
        $current = $this->createRecurring($this->user);
        $this->complete($current);
        $this->reopen($current);

        $this->handled(new RewindTaskRecurrenceCommand((string) $this->user->getId(), (string) $current->getId()));

        $series = $this->series();
        self::assertCount(1, $series);
        self::assertSame(self::EVERY_OTHER_TUESDAY, $series[0]->getRrule());
    }

    public function testAnotherUsersTaskIsLeftAlone(): void
    {
        $current = $this->createRecurring($this->user);
        $this->complete($current);
        // Reopened without its event, so only the commands below act.
        $this->em()->getConnection()->executeStatement('UPDATE task SET completed_at = NULL WHERE id = ?', [$current->getId()->toRfc4122()]);
        $other = $this->getFixture('other_user');
        \assert($other instanceof User);

        $this->handled(new RewindTaskRecurrenceCommand((string) $other->getId(), (string) $current->getId()));
        self::assertCount(2, $this->series());

        $this->handled(new RewindTaskRecurrenceCommand((string) $this->user->getId(), (string) $current->getId()));
        self::assertCount(1, $this->series());
    }

    private function reopen(Task $task): void
    {
        $this->handled(new UpdateTaskCommand(taskId: (string) $task->getId(), clearFields: ['completedAt']));
    }

    private function assertReopenedAlone(Task $task): void
    {
        $series = $this->series();
        self::assertCount(2, $series, 'nothing is deleted');
        self::assertSame((string) $task->getId(), (string) $series[0]->getId());
        self::assertFalse($series[0]->isDone());
        self::assertNull($series[0]->getRrule());
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }
}
