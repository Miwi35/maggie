<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Mcp\Tool\CheckConflictsTool;
use Maggie\Calendar\Mcp\Tool\CreateEventTool;
use Maggie\Calendar\Mcp\Tool\GetEventsByDateTool;
use Maggie\Calendar\Mcp\Tool\GetTasksTool;
use Maggie\Calendar\Mcp\Tool\GetUpcomingEventsTool;
use Maggie\Core\Mcp\MissingMcpUserException;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * Two users share the database: every calendar tool must only see its caller's data.
 */
class UserIsolationToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    private function tomorrow(): string
    {
        return (new \DateTimeImmutable('tomorrow', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
    }

    /** @return array<string, mixed> */
    private function decode(string $json): array
    {
        return json_decode($json, true, 512, JSON_THROW_ON_ERROR);
    }

    /**
     * @param array<string, mixed> $data
     *
     * @return string[]
     */
    private function titles(array $data): array
    {
        return array_map(fn (array $task) => $task['title'], $data['tasks']);
    }

    /**
     * @param array<string, mixed> $data
     *
     * @return string[]
     */
    private function summaries(array $data, string $key = 'events'): array
    {
        return array_map(fn (array $event) => $event['summary'], $data[$key]);
    }

    /** @return iterable<string, array{string, string, string}> */
    public static function taskStatusProvider(): iterable
    {
        yield 'pending' => ['pending', 'Own pending task', 'Other pending task'];
        yield 'overdue' => ['overdue', 'Own overdue task', 'Other overdue task'];
        yield 'done' => ['done', 'Own done task', 'Other done task'];
    }

    #[DataProvider('taskStatusProvider')]
    public function testGetTasksOnlyReturnsTheCallersTasks(string $status, string $own, string $other): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');
        $this->loginFixtureUser();

        $data = $this->decode((self::getContainer()->get(GetTasksTool::class))($status));

        self::assertContains($own, $this->titles($data));
        self::assertNotContains($other, $this->titles($data));
    }

    public function testGetTasksAllOnlyReturnsTheCallersTasks(): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');
        $this->loginFixtureUser();

        $data = $this->decode((self::getContainer()->get(GetTasksTool::class))('all'));

        self::assertEqualsCanonicalizing(
            ['Own pending task', 'Own overdue task', 'Own done task'],
            $this->titles($data),
        );
    }

    public function testGetTasksUpcomingOnlyReturnsTheCallersTasks(): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');
        $this->loginFixtureUser();

        $data = $this->decode((self::getContainer()->get(GetTasksTool::class))('pending', 7));

        self::assertSame(['Own pending task'], $this->titles($data));
    }

    public function testGetTasksWithoutUserIsRefused(): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');

        $data = $this->decode((self::getContainer()->get(GetTasksTool::class))('all'));

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertArrayNotHasKey('tasks', $data);
    }

    public function testGetEventsByDateOnlyReturnsTheCallersEvents(): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');
        $this->loginFixtureUser();

        $data = $this->decode((self::getContainer()->get(GetEventsByDateTool::class))($this->tomorrow()));

        self::assertSame(['Own event'], $this->summaries($data));
    }

    public function testGetEventsByDateWithoutUserIsRefused(): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');

        $data = $this->decode((self::getContainer()->get(GetEventsByDateTool::class))($this->tomorrow()));

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertArrayNotHasKey('events', $data);
    }

    public function testGetUpcomingEventsOnlyReturnsTheCallersEvents(): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');
        $this->loginFixtureUser();

        $data = $this->decode((self::getContainer()->get(GetUpcomingEventsTool::class))(7));

        self::assertSame(['Own event'], $this->summaries($data));
    }

    public function testGetUpcomingEventsWithoutUserIsRefused(): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');

        $data = $this->decode((self::getContainer()->get(GetUpcomingEventsTool::class))(7));

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertArrayNotHasKey('events', $data);
    }

    public function testCheckConflictsIgnoresOtherUsersEvents(): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');
        $this->loginFixtureUser();

        // 10:00-11:00 is taken by the other user's event only.
        $data = $this->decode((self::getContainer()->get(CheckConflictsTool::class))($this->tomorrow(), '10:00', 60));

        self::assertFalse($data['hasConflicts']);
        self::assertSame([], $data['conflicts']);
    }

    public function testCheckConflictsStillDetectsTheCallersOwnEvents(): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');
        $this->loginFixtureUser();

        $data = $this->decode((self::getContainer()->get(CheckConflictsTool::class))($this->tomorrow(), '15:30', 60));

        self::assertTrue($data['hasConflicts']);
        self::assertSame(['Own event'], $this->summaries($data, 'conflicts'));
    }

    public function testCheckConflictsWithoutUserIsRefused(): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');

        $data = $this->decode((self::getContainer()->get(CheckConflictsTool::class))($this->tomorrow(), '10:00', 60));

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertArrayNotHasKey('conflicts', $data);
    }

    public function testCreateEventWithoutAgendaUsesTheCallersDefaultAgenda(): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');
        $this->loginFixtureUser();

        $data = $this->decode((self::getContainer()->get(CreateEventTool::class))('Dentist', '2030-01-15', '09:00'));

        self::assertTrue($data['success']);
        self::assertSame('Own agenda', $data['event']['agenda']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $event = $em->getRepository(Event::class)->findOneBy(['summary' => 'Dentist']);
        self::assertSame('fixture@example.com', $event->getAgenda()->getUser()->getEmail());
    }

    public function testCreateEventNeverFallsBackToAnotherUsersAgenda(): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->remove($this->getFixture('own_event'));
        $em->flush();
        $em->remove($this->getFixture('own_agenda'));
        $em->flush();
        $this->loginFixtureUser();

        $data = $this->decode((self::getContainer()->get(CreateEventTool::class))('Dentist', '2030-01-15', '09:00'));

        self::assertStringContainsString('No default agenda', $data['error']);
        self::assertNull($em->getRepository(Event::class)->findOneBy(['summary' => 'Dentist']));
    }

    public function testCreateEventRefusesAnotherUsersAgenda(): void
    {
        $this->loadFixtures('UserIsolationToolsTest.yaml');
        $this->loginFixtureUser();
        $otherAgendaId = (string) $this->getFixture('other_agenda')->getId();

        $data = $this->decode((self::getContainer()->get(CreateEventTool::class))('Dentist', '2030-01-15', '09:00', 60, null, null, $otherAgendaId));

        self::assertSame('No agenda found.', $data['error']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertNull($em->getRepository(Event::class)->findOneBy(['summary' => 'Dentist']));
    }
}
