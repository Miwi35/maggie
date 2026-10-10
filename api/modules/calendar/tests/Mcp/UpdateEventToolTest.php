<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Mcp\Tool\UpdateEventTool;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class UpdateEventToolTest extends KernelTestCase
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
        $this->loadFixtures('UpdateEventToolTest.yaml');
        $this->loginFixtureUser();
    }

    private function tool(): UpdateEventTool
    {
        return self::getContainer()->get(UpdateEventTool::class);
    }

    private function id(string $fixture = 'event_full'): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function reload(string $fixture = 'event_full'): Event
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Event::class)->find($this->getFixture($fixture)->getId());
    }

    /** @return array<string, mixed> */
    private function call(string $fixture, mixed ...$args): array
    {
        // The fixtures were published too: only what the tool does is counted.
        $this->resetMercure();

        return json_decode(($this->tool())($this->id($fixture), ...$args), true, 512, JSON_THROW_ON_ERROR);
    }

    /**
     * The event as the owner reads it: its own zone, not the database's — and
     * an all-day event as its days, the end excluded, with no instant (MAG-382).
     */
    private function span(string $fixture): string
    {
        $event = $this->reload($fixture);
        if ($event->isAllDay()) {
            self::assertNull($event->getStartAt(), 'An all-day event has no instant');
            self::assertNull($event->getEndAt(), 'An all-day event has no instant');

            return $event->getStartDate()?->format('Y-m-d').' … '.$event->getEndDate()?->format('Y-m-d');
        }
        self::assertNull($event->getStartDate(), 'A timed event has no day');
        $zone = new \DateTimeZone($event->getTimeZone());

        return $event->getStartAt()->setTimezone($zone)->format('Y-m-d H:i').' → '.$event->getEndAt()->setTimezone($zone)->format('Y-m-d H:i');
    }

    /** MAG-321: « la soirée avec Julie ce sera demain soir » used to land at midnight, with no length. */
    public function testShiftingTheEveningToTheNextDayWithItsFullScheduleIsStored(): void
    {
        $data = $this->call('event_evening', start_date: '2026-10-08', start_time: '19:00', end_date: '2026-10-09', end_time: '00:00');

        self::assertTrue($data['success']);
        self::assertSame('2026-10-08 19:00 → 2026-10-09 00:00', $this->span('event_evening'));
        self::assertSame('2026-10-08T19:00:00+02:00', $data['event']['startAt']);
        self::assertSame('2026-10-09T00:00:00+02:00', $data['event']['endAt']);
        self::assertFalse($data['event']['allDay']);
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testADateAloneIsRefusedWithTheCurrentScheduleAndWritesNothing(): void
    {
        $data = $this->call('event_evening', start_date: '2026-10-08');

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('start_date, start_time, end_date and end_time', $data['error']);
        self::assertSame(
            ['allDay' => false, 'startAt' => '2026-10-07T19:00:00+02:00', 'endAt' => '2026-10-08T00:00:00+02:00', 'timeZone' => 'Europe/Paris'],
            $data['currentSchedule'],
        );
        self::assertSame('2026-10-07 19:00 → 2026-10-08 00:00', $this->span('event_evening'));
        $this->assertMercureUpdateCount(0);
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function incompleteSchedules(): iterable
    {
        yield 'a start without an end' => [['start_date' => '2026-10-08', 'start_time' => '19:00']];
        yield 'a time alone' => [['start_time' => '20:30']];
        yield 'dates without times' => [['start_date' => '2026-10-08', 'end_date' => '2026-10-09']];
        yield 'an end without a start' => [['end_date' => '2026-10-09', 'end_time' => '00:00']];
        yield 'all day without an end date' => [['all_day' => true, 'start_date' => '2026-10-08']];
        yield 'all day with times' => [['all_day' => true, 'start_date' => '2026-10-08', 'end_date' => '2026-10-08', 'start_time' => '09:00']];
        yield 'all day alone' => [['all_day' => true]];
    }

    /** @param array<string, mixed> $args */
    #[DataProvider('incompleteSchedules')]
    public function testAnIncompleteScheduleIsRefusedAndNothingIsWritten(array $args): void
    {
        $data = $this->call('event_evening', ...$args);

        self::assertArrayNotHasKey('success', $data);
        self::assertArrayHasKey('error', $data);
        self::assertArrayHasKey('currentSchedule', $data);
        self::assertSame('2026-10-07 19:00 → 2026-10-08 00:00', $this->span('event_evening'));
        $this->assertMercureUpdateCount(0);
    }

    public function testAMalformedDateOrTimeIsRefused(): void
    {
        $data = $this->call('event_evening', start_date: 'demain', start_time: '19:00', end_date: '2026-10-09', end_time: '00:00');
        self::assertStringContainsString('YYYY-MM-DD', $data['error']);

        $data = $this->call('event_evening', start_date: '2026-10-08', start_time: '7pm', end_date: '2026-10-09', end_time: '00:00');
        self::assertStringContainsString('HH:MM', $data['error']);

        self::assertSame('2026-10-07 19:00 → 2026-10-08 00:00', $this->span('event_evening'));
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function endsNotAfterTheStart(): iterable
    {
        yield 'the end is the start' => [['start_date' => '2026-10-08', 'start_time' => '19:00', 'end_date' => '2026-10-08', 'end_time' => '19:00']];
        yield 'the end is before the start' => [['start_date' => '2026-10-08', 'start_time' => '19:00', 'end_date' => '2026-10-08', 'end_time' => '00:00']];
        yield 'all day ending before it starts' => [['all_day' => true, 'start_date' => '2026-10-09', 'end_date' => '2026-10-08']];
    }

    /** @param array<string, mixed> $args */
    #[DataProvider('endsNotAfterTheStart')]
    public function testAnEndNotAfterTheStartIsRefusedWithoutWriteOrPublication(array $args): void
    {
        $data = $this->call('event_evening', ...$args);

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('after', $data['error']);
        self::assertSame('2026-10-07 19:00 → 2026-10-08 00:00', $this->span('event_evening'));
        $this->assertMercureUpdateCount(0);
    }

    public function testATimedEventBecomesAllDayAndTheOtherWayRound(): void
    {
        $data = $this->call('event_evening', all_day: true, start_date: '2026-10-08', end_date: '2026-10-09');

        self::assertTrue($data['success']);
        $event = $this->reload('event_evening');
        self::assertTrue($event->isAllDay());
        self::assertSame('2026-10-08 … 2026-10-09', $this->span('event_evening'));
        self::assertTrue($data['event']['allDay']);
        self::assertSame('2026-10-08', $data['event']['startDate']);
        self::assertSame('2026-10-09', $data['event']['endDate'], 'Excluded, as Google stores it');
        self::assertSame('2026-10-08', $data['event']['lastDay'], 'So that Maggie does not announce the day after');
        $this->assertMercureUpdatePublished('/events/');

        $data = $this->call('event_evening', start_date: '2026-10-08', start_time: '19:00', end_date: '2026-10-09', end_time: '00:00');

        self::assertTrue($data['success']);
        self::assertFalse($this->reload('event_evening')->isAllDay());
        self::assertSame('2026-10-08 19:00 → 2026-10-09 00:00', $this->span('event_evening'));
        self::assertArrayNotHasKey('startDate', $data['event']);
    }

    /** MAG-317: the owner's holiday was fixed by hand, from a timed event to a whole-day range. */
    public function testATimedEventBecomesAnAllDayRangeOverSeveralDays(): void
    {
        $this->resetAsyncTransport();

        $data = $this->call('event_evening', all_day: true, start_date: '2026-12-22', end_date: '2027-01-04');

        self::assertTrue($data['success']);
        self::assertTrue($data['event']['allDay']);
        self::assertSame('2026-12-22', $data['event']['startDate']);
        self::assertSame('2027-01-04', $data['event']['endDate']);
        self::assertTrue($this->reload('event_evening')->isAllDay());
        self::assertSame('2026-12-22 … 2027-01-04', $this->span('event_evening'));
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testAllDayEndingBeforeItStartsLeavesTheTimedEventAsItWas(): void
    {
        $before = $this->span('event_evening');

        $data = $this->call('event_evening', all_day: true, start_date: '2027-01-03', end_date: '2026-12-22');

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('must be after start_date', $data['error']);
        self::assertFalse($this->reload('event_evening')->isAllDay());
        self::assertSame($before, $this->span('event_evening'));
        $this->assertMercureUpdateCount(0);
    }

    public function testAnAllDayEventShiftedByADayStaysAllDayOnTheNewDay(): void
    {
        $data = $this->call('event_all_day', all_day: true, start_date: '2026-10-11', end_date: '2026-10-12');

        self::assertTrue($data['success']);
        self::assertTrue($this->reload('event_all_day')->isAllDay());
        self::assertSame('2026-10-11 … 2026-10-12', $this->span('event_all_day'));
    }

    public function testAMultiDayAllDayEventEndsTheDayAfterItsLastDay(): void
    {
        $data = $this->call('event_all_day', all_day: true, start_date: '2026-10-10', end_date: '2026-10-13');

        self::assertSame('2026-10-10 … 2026-10-13', $this->span('event_all_day'));
        self::assertSame('2026-10-13', $data['event']['endDate']);
        self::assertSame('2026-10-12', $data['event']['lastDay']);
    }

    public function testTheCurrentScheduleOfAnAllDayEventNamesItsLastDay(): void
    {
        $data = $this->call('event_all_day', start_date: '2026-10-11');

        self::assertTrue($data['currentSchedule']['allDay']);
        self::assertSame('2026-10-10', $data['currentSchedule']['startDate']);
        self::assertSame('2026-10-11', $data['currentSchedule']['endDate']);
        self::assertSame('2026-10-10', $data['currentSchedule']['lastDay']);
    }

    public function testTheScheduleIsReadInTheZoneOfTheEvent(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $evening = $em->getRepository(Event::class)->find($this->id('event_evening'));
        $evening->setTimeZone('America/New_York');
        $em->flush();

        $data = $this->call('event_evening', start_date: '2026-10-08', start_time: '19:00', end_date: '2026-10-09', end_time: '00:00');

        self::assertSame('2026-10-08T19:00:00-04:00', $data['event']['startAt']);
        self::assertSame('2026-10-08 19:00 → 2026-10-09 00:00', $this->span('event_evening'));
    }

    public function testOtherFieldsChangeAloneWithoutTouchingTheSchedule(): void
    {
        $data = $this->call('event_evening', title: 'Soirée chez Julie', location: 'Chez Julie');

        self::assertTrue($data['success']);
        self::assertSame('2026-10-07 19:00 → 2026-10-08 00:00', $this->span('event_evening'));
        self::assertSame('2026-10-07T19:00:00+02:00', $data['event']['startAt']);
        self::assertSame('Soirée chez Julie', $this->reload('event_evening')->getSummary());
    }

    public function testAnotherUsersEventIsNotFoundAndItsScheduleIsNotLeaked(): void
    {
        $data = $this->call('event_foreign', start_date: '2026-10-08');

        self::assertStringContainsString('Event not found', $data['error']);
        self::assertArrayNotHasKey('currentSchedule', $data);

        $data = $this->call('event_foreign', title: 'Hijacked');
        self::assertStringContainsString('Event not found', $data['error']);
        self::assertSame('Rendez-vous de quelqu\'un d\'autre', $this->reload('event_foreign')->getSummary());
    }

    public function testTheDurationParameterIsGone(): void
    {
        $parameters = array_map(
            static fn (\ReflectionParameter $p) => $p->getName(),
            (new \ReflectionMethod(UpdateEventTool::class, '__invoke'))->getParameters(),
        );

        self::assertNotContains('duration', $parameters);
        self::assertNotContains('date', $parameters);
        self::assertNotContains('time', $parameters);
    }

    public function testClearEmptiesOptionalFields(): void
    {
        $data = json_decode(
            ($this->tool())($this->id(), clear: ['description', 'location', 'rrule', 'summary']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        $event = $this->reload();
        self::assertNull($event->getDescription());
        self::assertNull($event->getLocation());
        self::assertNull($event->getRrule());
        self::assertSame('Weekly sync', $event->getSummary(), 'Required fields cannot be cleared');
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testWithoutClearNullFieldsAreLeftUntouched(): void
    {
        $data = json_decode(($this->tool())($this->id(), title: 'Renamed'), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        $event = $this->reload();
        self::assertSame('Renamed', $event->getSummary());
        self::assertSame('Bring slides', $event->getDescription());
        self::assertSame('Room 4', $event->getLocation());
        self::assertSame('FREQ=WEEKLY', $event->getRrule());
    }

    public function testUnknownEventReturnsAnError(): void
    {
        $data = json_decode(($this->tool())('01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['description']), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    /**
     * "Finalement, préviens-moi la veille" — the reminders are replaced, not added to.
     *
     * Replacing rather than merging is what the owner means when he names the
     * reminders he wants, and it is the only reading that can ever remove one.
     */
    public function testRemindersReplaceWhatTheEventHad(): void
    {
        $data = json_decode(($this->tool())($this->id(), reminders: [1440]), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame([1440], $data['event']['reminders']);
        self::assertSame(
            ['useDefault' => false, 'overrides' => [['method' => 'popup', 'minutes' => 1440]]],
            $this->reload()->getReminders(),
        );
    }

    /** "Enlève le rappel" — said as `clear`, or as a list that came back empty. */
    public function testRemindersAreClearedBothWays(): void
    {
        $data = json_decode(($this->tool())($this->id(), clear: ['reminders']), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertNull($this->reload()->getReminders());

        $this->loadFixtures('UpdateEventToolTest.yaml');
        $this->loginFixtureUser();
        $data = json_decode(($this->tool())($this->id(), reminders: []), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame([], $data['event']['reminders']);
        self::assertNull($this->reload()->getReminders());
    }

    public function testAReminderOfZeroMinutesIsRefusedAndChangesNothing(): void
    {
        $data = json_decode(($this->tool())($this->id(), reminders: [0]), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('between 1 and', $data['error']);
        self::assertSame(
            ['useDefault' => false, 'overrides' => [['method' => 'popup', 'minutes' => 15]]],
            $this->reload()->getReminders(),
        );
    }

    /** MAG-246: « Confirme le déjeuner de jeudi » and its opposite. */
    public function testTheStatusChangesAloneBothWays(): void
    {
        $data = $this->call('event_full', status: 'tentative');

        self::assertTrue($data['success']);
        self::assertSame('tentative', $data['event']['status']);
        self::assertSame(EventStatus::Tentative, $this->reload()->getStatus());
        self::assertSame('Weekly sync', $this->reload()->getSummary());
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);

        $data = $this->call('event_full', status: 'confirmed');

        self::assertSame('confirmed', $data['event']['status']);
        self::assertSame(EventStatus::Confirmed, $this->reload()->getStatus());
    }

    public function testWithoutAStatusTheStatusIsLeftAlone(): void
    {
        $this->call('event_full', status: 'tentative');
        $data = $this->call('event_full', title: 'Renamed');

        self::assertSame('tentative', $data['event']['status']);
        self::assertSame(EventStatus::Tentative, $this->reload()->getStatus());
    }

    /** @return iterable<string, array{string}> */
    public static function unknownStatuses(): iterable
    {
        yield 'unknown' => ['maybe'];
        yield 'cancelled goes through deletion' => ['cancelled'];
    }

    #[DataProvider('unknownStatuses')]
    public function testAnUnknownStatusIsRefusedAndChangesNothing(string $status): void
    {
        $data = $this->call('event_full', title: 'Renamed', status: $status);

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('"confirmed" or "tentative"', $data['error']);
        self::assertSame('Weekly sync', $this->reload()->getSummary());
        self::assertSame(EventStatus::Confirmed, $this->reload()->getStatus());
    }
}
