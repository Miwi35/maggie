<?php

namespace Maggie\Calendar\Tests\Command;

use Maggie\Calendar\Command\GoogleCalendarSyncCommand;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Service\GoogleCalendarSyncService;
use Maggie\Calendar\Service\GoogleTasksSyncService;
use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserRepository;
use PHPUnit\Framework\Attributes\AllowMockObjectsWithoutExpectations;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Tester\CommandTester;

#[AllowMockObjectsWithoutExpectations]
class GoogleCalendarSyncCommandTest extends TestCase
{
    /**
     * @return array{CommandTester, GoogleCalendarSyncService, GoogleTasksSyncService}
     */
    private function tester(Agenda ...$agendas): array
    {
        $user = (new User())->setEmail('sync@example.com');
        $calendarSync = $this->createMock(GoogleCalendarSyncService::class);
        $tasksSync = $this->createMock(GoogleTasksSyncService::class);
        $agendaRepository = $this->createMock(AgendaRepository::class);
        $agendaRepository->method('findGoogleSyncedByUser')->willReturn($agendas);
        $userRepository = $this->createMock(UserRepository::class);
        $userRepository->method('find')->willReturn($user);

        $command = new GoogleCalendarSyncCommand($calendarSync, $tasksSync, $agendaRepository, $userRepository);

        return [new CommandTester($command), $calendarSync, $tasksSync];
    }

    private function agenda(string $name): Agenda
    {
        return (new Agenda())->setName($name)->setGoogleCalendarId($name.'@group.calendar.google.com');
    }

    public function testItSucceedsWhenEveryAgendaSyncs(): void
    {
        [$tester, $calendarSync] = $this->tester($this->agenda('A'), $this->agenda('B'));
        $calendarSync->expects(self::exactly(2))->method('pullFromGoogle');

        self::assertSame(Command::SUCCESS, $tester->execute(['--user' => 'u']));
    }

    public function testItFailsWhenAnAgendaFailsToSyncButStillSyncsTheOthers(): void
    {
        [$tester, $calendarSync] = $this->tester($this->agenda('Broken'), $this->agenda('Fine'));
        $calendarSync->expects(self::exactly(2))->method('pullFromGoogle')
            ->willReturnCallback(function (Agenda $agenda): void {
                if ('Broken' === $agenda->getName()) {
                    throw new \RuntimeException('token revoked');
                }
            });

        $status = $tester->execute(['--user' => 'u']);

        self::assertSame(Command::FAILURE, $status, 'A failed pull must show as a failed job in the cron logs.');
        self::assertStringContainsString('token revoked', $tester->getDisplay());
    }

    public function testTasksSyncFailsWhenTheUserFailsToSync(): void
    {
        [$tester, , $tasksSync] = $this->tester();
        $tasksSync->method('pullFromGoogle')->willThrowException(new \RuntimeException('quota exceeded'));

        $status = $tester->execute(['--tasks' => true, '--user' => 'u']);

        self::assertSame(Command::FAILURE, $status);
        self::assertStringContainsString('quota exceeded', $tester->getDisplay());
    }
}
