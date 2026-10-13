<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Mcp\Tool\CreateEventTool;
use Maggie\Calendar\Mcp\Tool\GetEventsByDateTool;
use Maggie\Calendar\Mcp\Tool\GetEventTool;
use Maggie\Calendar\Mcp\Tool\GetUpcomingEventsTool;
use Maggie\Calendar\Mcp\Tool\UpdateEventTool;
use Maggie\Core\Mcp\MissingMcpUserException;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * MAG-431: Maggie called the 24th of October 2026 a Monday, and the event landed on a Saturday.
 * Every local date the agenda tools hand back carries its day of the week, worked out by the
 * code in the event's own time zone.
 */
class EventWeekdaysToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('EventWeekdaysToolsTest.yaml');
    }

    /** @return array<string, mixed> */
    private function call(string $tool, mixed ...$arguments): array
    {
        return json_decode((self::getContainer()->get($tool))(...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    /**
     * @param array<string, mixed> $data
     *
     * @return array<string, mixed>
     */
    private function eventNamed(array $data, string $summary): array
    {
        foreach ($data['events'] as $event) {
            if ($summary === $event['summary']) {
                return $event;
            }
        }

        self::fail("No event named {$summary} in the answer.");
    }

    private function storedEvents(): int
    {
        return self::getContainer()->get(EntityManagerInterface::class)->getRepository(Event::class)->count([]);
    }

    public function testTheAllDayHolidaysSayWhichDaysTheyStartAndEndOn(): void
    {
        $this->loginFixtureUser();
        $before = $this->storedEvents();

        $data = $this->call(GetEventsByDateTool::class, '2026-10-20');
        $holidays = $this->eventNamed($data, 'Vacances de la Toussaint');

        self::assertSame('2026-10-17', $holidays['startDate']);
        self::assertSame('samedi', $holidays['startWeekday'], 'The 17th of October 2026 is a Saturday');
        self::assertSame('2026-11-03', $holidays['endDate'], 'Excluded, as Google stores it');
        self::assertSame('2026-11-02', $holidays['lastDay']);
        self::assertSame('lundi', $holidays['lastDayWeekday']);
        self::assertArrayNotHasKey('endWeekday', $holidays, 'The excluded end is not a day of the event');
        self::assertSame($before, $this->storedEvents(), 'Reading changes nothing');
    }

    public function testATimedEventSaysItsWeekdayInItsOwnZoneNotTheDatabasesOne(): void
    {
        $this->loginFixtureUser();

        // The tool reads the day it is asked for from midnight to midnight in the server's zone (UTC).
        $data = $this->call(GetEventsByDateTool::class, '2026-10-24');
        $late = $this->eventNamed($data, 'Soirée qui finit tard');

        // 22:30 UTC on Saturday the 24th is 00:30 on Sunday the 25th in Paris.
        self::assertSame('2026-10-25T00:30:00+02:00', $late['startAt']);
        self::assertSame('dimanche', $late['startWeekday']);
        self::assertSame('dimanche', $late['endWeekday']);
    }

    public function testGetEventSaysTheWeekdays(): void
    {
        $this->loginFixtureUser();
        $holidays = $this->getFixture('toussaint');
        $late = $this->getFixture('after_midnight');

        $allDay = $this->call(GetEventTool::class, (string) $holidays->getId())['event'];
        self::assertSame('samedi', $allDay['startWeekday']);
        self::assertSame('lundi', $allDay['lastDayWeekday']);

        $timed = $this->call(GetEventTool::class, (string) $late->getId())['event'];
        self::assertSame('dimanche', $timed['startWeekday']);
        self::assertSame('dimanche', $timed['endWeekday']);
    }

    public function testGetUpcomingEventsSaysTheWeekday(): void
    {
        $this->loginFixtureUser();
        $tomorrow = new \DateTimeImmutable('tomorrow', new \DateTimeZone('Europe/Paris'));
        $expected = ['lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi', 'dimanche'][(int) $tomorrow->format('N') - 1];

        $event = $this->eventNamed($this->call(GetUpcomingEventsTool::class, 3), 'Demain matin');

        self::assertSame($expected, $event['startWeekday']);
        self::assertSame($expected, $event['endWeekday']);
    }

    public function testGetEventsByDateRefusesADateThatIsNotOne(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(GetEventsByDateTool::class, 'lundi 24 octobre');

        self::assertStringContainsString('YYYY-MM-DD', $data['error']);
        self::assertArrayNotHasKey('events', $data);
    }

    public function testTheReadsAreRefusedWithoutUser(): void
    {
        foreach ([
            [GetEventsByDateTool::class, ['2026-10-20']],
            [GetUpcomingEventsTool::class, [7]],
            [GetEventTool::class, [(string) $this->getFixture('toussaint')->getId()]],
        ] as [$tool, $arguments]) {
            $data = $this->call($tool, ...$arguments);

            self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        }
    }

    /** @return iterable<string, array{string, string, string}> */
    public static function creations(): iterable
    {
        // Summer time ends in the night of the 24th to the 25th.
        yield 'the Monday of the second week' => ['2026-10-26', 'lundi', '+01:00'];
        yield 'the Friday of the first week' => ['2026-10-23', 'vendredi', '+02:00'];
        yield 'the Saturday Maggie took for a Monday' => ['2026-10-24', 'samedi', '+02:00'];
    }

    #[DataProvider('creations')]
    public function testCreateEventAnswersWithTheWeekdayOfTheDayItCreated(string $day, string $weekday, string $offset): void
    {
        $this->loginFixtureUser();

        $data = $this->call(CreateEventTool::class, 'Cheval', $day, '18:00', $day, '19:00');

        self::assertTrue($data['success']);
        self::assertSame($day.'T18:00:00'.$offset, $data['event']['startAt']);
        self::assertSame($weekday, $data['event']['weekday']);
        self::assertSame($weekday, $data['event']['startWeekday']);
        self::assertSame($weekday, $data['event']['endWeekday']);
    }

    public function testCreateAnAllDayEventAnswersWithTheWeekdayOfItsFirstAndLastDay(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(CreateEventTool::class, 'Stage', '2026-10-26', end_date: '2026-10-29', all_day: true);

        self::assertSame('lundi', $data['event']['weekday']);
        self::assertSame('lundi', $data['event']['startWeekday']);
        self::assertSame('2026-10-28', $data['event']['lastDay']);
        self::assertSame('mercredi', $data['event']['lastDayWeekday']);
    }

    public function testCreateEventThatIsRefusedCreatesNothing(): void
    {
        $this->loginFixtureUser();
        $before = $this->storedEvents();

        $data = $this->call(CreateEventTool::class, 'Cheval', '2026-10-26', '18:00');

        self::assertArrayHasKey('error', $data);
        self::assertSame($before, $this->storedEvents());
    }

    public function testUpdateEventAnswersWithTheWeekdayOfTheNewDay(): void
    {
        $this->loginFixtureUser();
        $event = $this->getFixture('tomorrow_morning');

        $data = $this->call(
            UpdateEventTool::class,
            (string) $event->getId(),
            start_date: '2026-10-26',
            start_time: '18:00',
            end_date: '2026-10-26',
            end_time: '19:00',
        );

        self::assertTrue($data['success']);
        self::assertSame('lundi', $data['event']['weekday']);
        self::assertSame('lundi', $data['event']['startWeekday']);
        self::assertSame('lundi', $data['event']['endWeekday']);
    }

    public function testUpdateEventTellsTheWeekdaysOfTheScheduleItKeptWhenTheNewOneIsRefused(): void
    {
        $this->loginFixtureUser();
        $holidays = $this->getFixture('toussaint');

        $data = $this->call(UpdateEventTool::class, (string) $holidays->getId(), start_date: '2026-10-26');

        self::assertArrayHasKey('error', $data);
        self::assertSame('samedi', $data['currentSchedule']['startWeekday']);
        self::assertSame('lundi', $data['currentSchedule']['lastDayWeekday']);
    }
}
