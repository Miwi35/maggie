<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Command;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Calendar\Command\AdvanceRecurringTasksCommand;
use Maggie\Calendar\Tests\Support\RecurringTaskTrait;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Tester\CommandTester;

/** `maggie:calendar:advance-recurring-tasks` — the next reminder goes out even when the last one was not ticked (MAG-870). */
final class AdvanceRecurringTasksCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use RecurringTaskTrait;

    private CommandTester $tester;
    private User $user;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(\dirname(__DIR__).'/MessageHandler/fixtures/TaskRecurrenceTest.yaml');
        $this->user = $this->getFixture('test_user');
        $this->today('2026-11-24');

        $application = new Application();
        $application->add(self::getContainer()->get(AdvanceRecurringTasksCommand::class));
        $this->tester = new CommandTester($application->find('maggie:calendar:advance-recurring-tasks'));
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testAPassedOccurrenceIsAdvanced(): void
    {
        $this->createRecurring($this->user);
        $this->today('2026-11-25');

        self::assertSame(Command::SUCCESS, $this->tester->execute([]));

        [$missed, $next] = $this->series();
        self::assertFalse($missed->isDone());
        self::assertNull($missed->getRrule());
        self::assertSame('2026-12-08', $next->getScheduledDate()?->format('Y-m-d'));
        self::assertSame(self::EVERY_OTHER_TUESDAY, $next->getRrule());

        $this->tester->execute([]);
        self::assertCount(2, $this->series(), 'a second run creates nothing more');
    }

    public function testTodaySOccurrenceIsLeftAlone(): void
    {
        $this->createRecurring($this->user);

        self::assertSame(Command::SUCCESS, $this->tester->execute([]));

        $series = $this->series();
        self::assertCount(1, $series);
        self::assertSame(self::EVERY_OTHER_TUESDAY, $series[0]->getRrule());
    }

    public function testEachUserSTasksAreAdvancedOnTheirOwnDay(): void
    {
        // 23:00 in Paris on the Tuesday, already Wednesday in Auckland.
        Clock::set(new MockClock('2026-11-24 22:00:00 UTC'));
        $this->createRecurring($this->user, title: 'Paris');
        $this->createRecurring($this->getFixture('other_user'), title: 'Auckland');

        $this->tester->execute([]);

        self::assertCount(1, $this->series('Paris'), 'still Tuesday in Paris');
        self::assertCount(2, $this->series('Auckland'), 'Wednesday in Auckland');
    }
}
