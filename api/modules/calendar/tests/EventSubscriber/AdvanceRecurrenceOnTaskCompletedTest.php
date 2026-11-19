<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\EventSubscriber;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Calendar\Event\TaskCompleted;
use Maggie\Calendar\Event\TaskOccurrencePassed;
use Maggie\Calendar\Tests\Support\RecurringTaskTrait;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Messenger\MessageBusInterface;

/** Both facts that end an occurrence ask for the next one (MAG-870): a tick from any door, a day gone. */
final class AdvanceRecurrenceOnTaskCompletedTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use RecurringTaskTrait;

    private User $user;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(\dirname(__DIR__).'/MessageHandler/fixtures/TaskRecurrenceTest.yaml');
        $this->user = $this->getFixture('test_user');
        $this->today('2026-11-24');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testACompletedTaskAsksForItsNextOccurrence(): void
    {
        $task = $this->createRecurring($this->user);
        // As a Google sync would leave it: stored done, its event raised apart.
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->getConnection()->executeStatement('UPDATE task SET completed_at = NOW() WHERE id = ?', [$task->getId()->toRfc4122()]);
        $em->clear();

        $this->eventBus()->dispatch(new TaskCompleted((string) $task->getId(), (string) $this->user->getId(), new \DateTimeImmutable()));

        $series = $this->series();
        self::assertCount(2, $series);
        self::assertSame('2026-12-08', $series[1]->getScheduledDate()?->format('Y-m-d'));
    }

    public function testAPassedOccurrenceAsksForItsNextOccurrence(): void
    {
        $task = $this->createRecurring($this->user);
        $this->today('2026-11-25');

        $this->eventBus()->dispatch(new TaskOccurrencePassed((string) $task->getId(), (string) $this->user->getId()));

        $series = $this->series();
        self::assertCount(2, $series);
        self::assertFalse($series[0]->isDone());
        self::assertSame('2026-12-08', $series[1]->getScheduledDate()?->format('Y-m-d'));
    }

    private function eventBus(): MessageBusInterface
    {
        $bus = self::getContainer()->get('event.bus');
        \assert($bus instanceof MessageBusInterface);

        return $bus;
    }
}
