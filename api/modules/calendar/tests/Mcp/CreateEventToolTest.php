<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Mcp\Tool\CreateEventTool;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class CreateEventToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function getTool(): CreateEventTool
    {
        return self::getContainer()->get(CreateEventTool::class);
    }

    public function testCreateEventPersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();

        $tool = $this->getTool();

        $result = $tool('Team standup', '2026-03-20', '09:30', '2026-03-20', '10:00', description: 'Daily sync', location: 'Room A');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame('Team standup', $data['event']['summary']);
        self::assertSame('Main', $data['event']['agenda']);

        // DB persistence
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $events = $em->getRepository(Event::class)->findAll();
        self::assertCount(1, $events);
        self::assertSame('Team standup', $events[0]->getSummary());
        self::assertSame('Daily sync', $events[0]->getDescription());
        self::assertSame('Room A', $events[0]->getLocation());

        // Mercure publication
        $this->assertMercureUpdatePublished('/events/');

        // Elasticsearch indexation
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testCreateEventReturnsErrorWithoutDefaultAgenda(): void
    {
        $this->purgeDatabase();

        $tool = $this->getTool();

        $result = $tool('Event without calendar', '2026-03-20', '10:00', '2026-03-20', '11:00');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('No default agenda', $data['error']);
        self::assertStringContainsString('agenda_id', $data['error']);
    }

    public function testCreateEventDoesNotPickAnAgendaWhenNoneIsDefault(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->getConnection()->executeStatement('UPDATE agenda SET is_default = false');
        $em->clear();

        $data = json_decode(($this->getTool())('Dentist', '2026-03-20', '10:00', '2026-03-20', '11:00'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('No default agenda', $data['error']);
        self::assertSame(0, (int) $em->getConnection()->fetchOne('SELECT COUNT(*) FROM event'));
    }

    public function testCreateEventWithSpecificAgenda(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();

        // Create a second agenda
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $user = $em->getRepository(\Maggie\Core\Entity\User::class)->findOneBy(['email' => 'fixture@example.com']);
        $agenda = new \Maggie\Calendar\Entity\Agenda();
        $agenda->setName('Concerts');
        $agenda->setUser($user);
        $em->persist($agenda);
        $em->flush();

        $this->resetMercure();
        $this->resetAsyncTransport();

        $tool = $this->getTool();
        $result = $tool('Rock show', '2026-04-10', '20:00', '2026-04-10', '23:00', location: 'Venue', agenda_id: (string) $agenda->getId());

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame('Rock show', $data['event']['summary']);
        self::assertSame('Concerts', $data['event']['agenda']);
    }

    /**
     * "Déjeuner jeudi, préviens-moi une heure avant" — in one tool call.
     *
     * Reminders had no way in but Google's import: neither this tool nor the API's
     * POST carried them, so Maggie answered "c'est noté" to an event nobody would
     * ever be reminded of (MAG-121). The delays are what she hears; the entity
     * holds Google's `{useDefault, overrides}` and this is where the two meet.
     */
    public function testCreateEventStoresTheRemindersTheUserAskedFor(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            ($this->getTool())('Déjeuner avec Léa', '2026-03-20', '12:30', '2026-03-20', '13:30', reminders: [1440, 60]),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        // Said back in minutes, so Maggie can confirm what she set.
        self::assertSame([60, 1440], $data['event']['reminders']);
        self::assertSame(
            ['useDefault' => false, 'overrides' => [
                ['method' => 'popup', 'minutes' => 60],
                ['method' => 'popup', 'minutes' => 1440],
            ]],
            $this->stored('Déjeuner avec Léa')->getReminders(),
        );
    }

    public function testCreateEventWithoutRemindersStoresNone(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->getTool())('Déjeuner seul', '2026-03-20', '12:30', '2026-03-20', '13:30'), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame([], $data['event']['reminders']);
        self::assertNull($this->stored('Déjeuner seul')->getReminders());
    }

    /**
     * A delay nothing can honour is an error Maggie can read, not a silent event.
     *
     * Zero minutes is the one worth naming: the cron skips it, so storing it would
     * promise a reminder that never comes.
     */
    public function testCreateEventRefusesAReminderOfZeroMinutes(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            ($this->getTool())('Rendez-vous sans délai', '2026-03-20', '10:00', '2026-03-20', '11:00', reminders: [0]),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('between 1 and', $data['error']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertSame(0, (int) $em->getConnection()->fetchOne('SELECT COUNT(*) FROM event'));
    }

    /**
     * "Tous les lundis" — the recurrence Maggie could read but not write (MAG-121).
     *
     * `update_event` could already clear an rrule it had no way of setting, so a
     * repeating event could only be created from the web or the mobile app.
     */
    public function testCreateEventStoresARecurrenceRule(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            ($this->getTool())('Point hebdomadaire', '2026-03-23', '09:00', '2026-03-23', '10:00', rrule: 'FREQ=WEEKLY;BYDAY=MO'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame('FREQ=WEEKLY;BYDAY=MO', $data['event']['rrule']);
        self::assertSame('FREQ=WEEKLY;BYDAY=MO', $this->stored('Point hebdomadaire')->getRrule());
    }

    /** MAG-321: the duration is derived from the start and the end, and told in the event's own zone. */
    public function testCreateEventStoresTheScheduleItWasGivenAndAnswersInLocalTime(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            ($this->getTool())('Soirée', '2026-10-07', '19:00', '2026-10-08', '00:00'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertFalse($data['event']['allDay']);
        self::assertSame('2026-10-07T19:00:00+02:00', $data['event']['startAt']);
        self::assertSame('2026-10-08T00:00:00+02:00', $data['event']['endAt']);

        $stored = $this->stored('Soirée');
        $zone = new \DateTimeZone('Europe/Paris');
        self::assertSame('2026-10-07 19:00', $stored->getStartAt()->setTimezone($zone)->format('Y-m-d H:i'));
        self::assertSame('2026-10-08 00:00', $stored->getEndAt()->setTimezone($zone)->format('Y-m-d H:i'));
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testCreateAWholeDayEventOverSeveralDays(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            ($this->getTool())('Vacances', '2026-08-03', end_date: '2026-08-08', all_day: true),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertTrue($data['event']['allDay']);
        self::assertSame('2026-08-03', $data['event']['startDate']);
        self::assertSame('2026-08-08', $data['event']['endDate']);
        self::assertSame('2026-08-07', $data['event']['lastDay'], 'The day Maggie announces as the last');

        // MAG-382: dates in the database, the last day included, and no instant.
        $stored = $this->stored('Vacances');
        self::assertTrue($stored->isAllDay());
        self::assertSame('2026-08-03', $stored->getStartDate()?->format('Y-m-d'));
        self::assertSame('2026-08-08', $stored->getEndDate()?->format('Y-m-d'));
        self::assertNull($stored->getStartAt());
        self::assertNull($stored->getEndAt());
        self::assertNull($data['event']['startAt']);
    }

    /** MAG-317: « du 22 décembre au 3 janvier » ended as a single day, then as a daily series. */
    public function testAHolidayAcrossTheNewYearIsOneAllDayEventOfThirteenDays(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = json_decode(
            ($this->getTool())('Vacances de Noël', start_date: '2026-12-22', end_date: '2027-01-04', all_day: true),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertTrue($data['event']['allDay']);
        self::assertSame('2026-12-22', $data['event']['startDate']);
        self::assertSame('2027-01-04', $data['event']['endDate']);
        self::assertSame('2027-01-03', $data['event']['lastDay']);
        self::assertNull($data['event']['rrule']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertSame(1, (int) $em->getConnection()->fetchOne('SELECT COUNT(*) FROM event'));

        $stored = $this->stored('Vacances de Noël');
        self::assertTrue($stored->isAllDay());
        self::assertNull($stored->getRrule());
        self::assertSame('2026-12-22', $stored->getStartDate()?->format('Y-m-d'));
        self::assertSame('2027-01-04', $stored->getEndDate()?->format('Y-m-d'));
        self::assertSame(13, (int) $stored->getStartDate()?->diff($stored->getEndDate() ?? $stored->getStartDate())->days);
        self::assertNull($stored->getStartAt());
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    /** @return iterable<string, array{array<string, mixed>, string}> */
    public static function refusedSchedules(): iterable
    {
        yield 'no schedule at all' => [[], 'incomplete'];
        yield 'a start without an end' => [['start_date' => '2026-03-20', 'start_time' => '10:00'], 'end_date, end_time'];
        yield 'a date without a time' => [['start_date' => '2026-03-20', 'end_date' => '2026-03-20'], 'start_time, end_time'];
        yield 'the end is the start' => [['start_date' => '2026-03-20', 'start_time' => '10:00', 'end_date' => '2026-03-20', 'end_time' => '10:00'], 'after'];
        yield 'the end is before the start' => [['start_date' => '2026-03-20', 'start_time' => '10:00', 'end_date' => '2026-03-19', 'end_time' => '11:00'], 'after'];
        yield 'all day with a time' => [['all_day' => true, 'start_date' => '2026-03-20', 'end_date' => '2026-03-20', 'end_time' => '11:00'], 'no start_time or end_time'];
        yield 'all day ending before it starts' => [['all_day' => true, 'start_date' => '2026-03-20', 'end_date' => '2026-03-19'], 'after'];
        // The end is excluded: the start day itself is no day at all.
        yield 'all day ending on its start' => [['all_day' => true, 'start_date' => '2026-03-20', 'end_date' => '2026-03-20'], 'after'];
    }

    /** @param array<string, mixed> $schedule */
    #[DataProvider('refusedSchedules')]
    public function testCreateEventRefusesAnIncompleteOrBackwardsScheduleAndWritesNothing(array $schedule, string $expected): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();
        $this->resetMercure();

        $data = json_decode(($this->getTool())('Dentist', ...$schedule), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString($expected, $data['error']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertSame(0, (int) $em->getConnection()->fetchOne('SELECT COUNT(*) FROM event'));
        $this->assertMercureUpdateCount(0);
    }

    public function testTheDurationParameterIsGone(): void
    {
        $parameters = array_map(
            static fn (\ReflectionParameter $p) => $p->getName(),
            (new \ReflectionMethod(CreateEventTool::class, '__invoke'))->getParameters(),
        );

        self::assertNotContains('duration', $parameters);
        self::assertNotContains('date', $parameters);
        self::assertNotContains('time', $parameters);
    }

    /** MAG-246: « ajoute un déjeuner provisoire jeudi à midi ». */
    public function testCreateEventStoresATentativeStatusAndSaysSo(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            ($this->getTool())('Déjeuner provisoire', '2026-10-15', '12:00', '2026-10-15', '13:00', status: 'tentative'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame('tentative', $data['event']['status']);
        self::assertSame(EventStatus::Tentative, $this->stored('Déjeuner provisoire')->getStatus());
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testCreateEventIsConfirmedWithoutAStatus(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();

        $data = json_decode(
            ($this->getTool())('Déjeuner', '2026-10-15', '12:00', '2026-10-15', '13:00'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertSame('confirmed', $data['event']['status']);
        self::assertSame(EventStatus::Confirmed, $this->stored('Déjeuner')->getStatus());
    }

    /** @return iterable<string, array{string}> */
    public static function unknownStatuses(): iterable
    {
        yield 'unknown' => ['maybe'];
        yield 'cancelled goes through deletion' => ['cancelled'];
    }

    #[DataProvider('unknownStatuses')]
    public function testCreateEventRefusesAnUnknownStatusAndCreatesNothing(string $status): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');
        $this->loginFixtureUser();
        $this->resetMercure();

        $data = json_decode(
            ($this->getTool())('Déjeuner', '2026-10-15', '12:00', '2026-10-15', '13:00', status: $status),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('"confirmed" or "tentative"', $data['error']);
        self::assertSame([], self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(Event::class)->findAll());
    }

    /** An event read back from the database, not from the response that claimed to write it. */
    private function stored(string $summary): Event
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        $event = $em->getRepository(Event::class)->findOneBy(['summary' => $summary]);
        self::assertInstanceOf(Event::class, $event);

        return $event;
    }
}
