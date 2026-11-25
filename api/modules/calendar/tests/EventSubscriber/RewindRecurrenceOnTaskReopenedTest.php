<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\EventSubscriber;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Calendar\Event\TaskReopened;
use Maggie\Calendar\Tests\Support\RecurringTaskTrait;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Messenger\MessageBusInterface;

/** A reopening from any door gives the series back (MAG-1033). */
final class RewindRecurrenceOnTaskReopenedTest extends KernelTestCase
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

    public function testAReopenedTaskGivesTheSeriesBack(): void
    {
        $task = $this->createRecurring($this->user);
        $this->complete($task);
        // As a Google sync would leave it: stored open, its event raised apart.
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->getConnection()->executeStatement('UPDATE task SET completed_at = NULL WHERE id = ?', [$task->getId()->toRfc4122()]);
        $em->clear();

        $bus = self::getContainer()->get('event.bus');
        \assert($bus instanceof MessageBusInterface);
        $bus->dispatch(new TaskReopened((string) $task->getId(), (string) $this->user->getId()));

        $series = $this->series();
        self::assertCount(1, $series);
        self::assertSame(self::EVERY_OTHER_TUESDAY, $series[0]->getRrule());
    }
}
