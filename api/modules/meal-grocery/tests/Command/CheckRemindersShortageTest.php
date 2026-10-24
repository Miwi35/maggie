<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\MealGrocery\Entity\ShortageAlert;
use Maggie\Notification\Command\CheckRemindersCommand;
use Maggie\Notification\Entity\Notification;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * « Il manque » (MAG-570) rides the every-minute cron: the first tick raises
 * what the meals of today and tomorrow miss, the next ones stay silent.
 * The world is the one of {@see \Maggie\MealGrocery\Tests\Service\ShortageAlertsTest}.
 */
final class CheckRemindersShortageTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private CommandTester $tester;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-10-14 08:00:00 UTC'));
        $this->loadFixtures(\dirname(__DIR__).'/Service/fixtures/ShortageAlertsTest.yaml');
        $this->em()->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $application = new Application();
        $application->add(self::getContainer()->get(CheckRemindersCommand::class));
        $this->tester = new CommandTester($application->find('maggie:notification:check-reminders'));
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testTheCronRaisesEachMissingProductOnceThenStaysSilent(): void
    {
        $this->tester->execute([]);

        self::assertSame(0, $this->tester->getStatusCode());
        $titles = $this->titles();
        self::assertSame(['Il manque : Crème fraîche', 'Il manque : Parmesan', 'Il manque : Parmesan'], $titles);
        self::assertCount(3, $this->em()->getRepository(ShortageAlert::class)->findAll());
        $this->assertMercureUpdatePublished('/api/notifications/');

        $this->tester->execute([]);

        self::assertSame($titles, $this->titles());
    }

    /** @return list<string> */
    private function titles(): array
    {
        $this->em()->clear();
        $titles = array_map(static fn (Notification $n): string => $n->getTitle(), $this->em()->getRepository(Notification::class)->findAll());
        sort($titles);

        return $titles;
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
