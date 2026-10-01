<?php

namespace Maggie\Calendar\Tests\Command;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Tester\CommandTester;

class GoogleCalendarCheckSyncCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private CommandTester $tester;
    private EntityManagerInterface $em;
    private User $user;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->purgeDatabase();

        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->tester = new CommandTester((new Application(self::$kernel))->find('maggie:google-calendar:check-sync'));

        $this->user = (new User())
            ->setEmail('check-sync@example.com')
            ->setGoogleId('google-check-sync')
            ->setName('Check Sync');
        $this->em->persist($this->user);
        $this->em->flush();
    }

    private function agenda(string $name, ?string $googleCalendarId, ?\DateTimeImmutable $lastSync): Agenda
    {
        $agenda = (new Agenda())
            ->setUser($this->user)
            ->setName($name)
            ->setGoogleCalendarId($googleCalendarId)
            ->setLastGoogleSyncAt($lastSync);
        $this->em->persist($agenda);
        $this->em->flush();

        return $agenda;
    }

    public function testItSucceedsWhenEveryGoogleAgendaWasSyncedRecently(): void
    {
        $this->agenda('Fresh', 'fresh@group.calendar.google.com', new \DateTimeImmutable('-10 minutes'));

        $status = $this->tester->execute([]);

        self::assertSame(Command::SUCCESS, $status);
    }

    public function testItSucceedsWithoutAnyGoogleAgenda(): void
    {
        $this->agenda('Local only', null, null);

        self::assertSame(Command::SUCCESS, $this->tester->execute([]));
    }

    public function testItFailsAndNamesAnAgendaSyncedMoreThanAnHourAgo(): void
    {
        $this->agenda('Fresh', 'fresh@group.calendar.google.com', new \DateTimeImmutable('-10 minutes'));
        $this->agenda('Concerts', 'concerts@group.calendar.google.com', new \DateTimeImmutable('-2 hours'));

        $status = $this->tester->execute([]);

        self::assertSame(Command::FAILURE, $status);
        $display = $this->tester->getDisplay();
        self::assertStringContainsString('Concerts', $display);
        self::assertStringNotContainsString('Fresh', $display);
    }

    public function testItFailsForAnAgendaThatWasNeverSynchronized(): void
    {
        $this->agenda('Never', 'never@group.calendar.google.com', null);

        $status = $this->tester->execute([]);

        self::assertSame(Command::FAILURE, $status);
        self::assertStringContainsString('never synchronized', $this->tester->getDisplay());
    }

    public function testItIgnoresAStaleAgendaThatIsNotLinkedToGoogle(): void
    {
        $this->agenda('Local stale', null, new \DateTimeImmutable('-3 days'));

        self::assertSame(Command::SUCCESS, $this->tester->execute([]));
    }

    public function testMaxAgeMovesTheThreshold(): void
    {
        $this->agenda('Twenty minutes', 'a@group.calendar.google.com', new \DateTimeImmutable('-20 minutes'));

        self::assertSame(Command::SUCCESS, $this->tester->execute([]));
        self::assertSame(Command::FAILURE, $this->tester->execute(['--max-age' => '10']));
    }

    public function testItRejectsAnInvalidMaxAge(): void
    {
        self::assertSame(Command::INVALID, $this->tester->execute(['--max-age' => 'abc']));
    }
}
