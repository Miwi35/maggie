<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Entity;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/**
 * A meal's day, and the instants derived from it (MAG-251).
 *
 * The invariant this pins down: whatever is set on a meal, `date` is the day
 * the meal is eaten and `startAt`/`endAt` are the whole of that day in the
 * meal's time zone. It has to hold through the `Event` setters too — `Meal`
 * shares its primary key with `event`, so `update_event` and
 * `PATCH /api/events/{id}` reach a meal and used to set the instants directly.
 */
final class MealDayTest extends TestCase
{
    private const PARIS = 'Europe/Paris';

    private function aMeal(string $day = '2026-10-07'): Meal
    {
        $user = new User();
        $user->setEmail('repas@example.com');
        $user->setGoogleId('google-repas');
        $user->setName('Repas');

        $agenda = new Agenda();
        $agenda->setName('Repas');
        $agenda->setUser($user);

        $meal = new Meal();
        $meal->setAgenda($agenda);
        $meal->setSlot(MealSlot::Lunch);
        $meal->setSummary('Déjeuner');
        $meal->setDate(new \DateTimeImmutable($day));

        return $meal;
    }

    /** @return iterable<string, array{string}> */
    public static function days(): iterable
    {
        yield 'summer time' => ['2026-10-07'];
        yield 'winter time' => ['2026-12-07'];
        yield 'the day the clocks go back' => ['2026-10-25'];
    }

    /**
     * A meal is an all-day event on its one day, as a date: no instant, so no
     * zone to read it in and no clock change to move it (MAG-382).
     */
    #[DataProvider('days')]
    public function testAMealIsAnAllDayEventOnItsDayWithNoInstant(string $day): void
    {
        $meal = $this->aMeal($day);

        self::assertSame($day, $meal->getDate()?->format('Y-m-d'));
        self::assertSame($day, $meal->getStartDate()?->format('Y-m-d'));
        self::assertSame($day, $meal->getEndDate()?->format('Y-m-d'));
        self::assertNull($meal->getStartAt());
        self::assertNull($meal->getEndAt());
        self::assertTrue($meal->isAllDay());
    }

    public function testOnlyTheDayOfAnInstantHandedToSetDateIsKept(): void
    {
        $meal = $this->aMeal();

        $meal->setDate(new \DateTimeImmutable('2026-10-09 19:30:00', new \DateTimeZone(self::PARIS)));

        self::assertSame('2026-10-09', $meal->getDate()?->format('Y-m-d'));
        self::assertSame('2026-10-09', $meal->getStartDate()?->format('Y-m-d'));
        self::assertNull($meal->getStartAt());
    }

    /**
     * The `Event` door: `scheduleTimed()` sets `startAt` and then `endAt` on
     * whatever event it found, and a meal is one of them.
     */
    public function testAnInstantSetThroughTheEventSetterMovesTheDay(): void
    {
        $meal = $this->aMeal();

        $meal->scheduleTimed(new \DateTimeImmutable('2026-10-09T19:30:00+02:00'), new \DateTimeImmutable('2026-10-09T20:30:00+02:00'));

        self::assertSame('2026-10-09', $meal->getDate()?->format('Y-m-d'));
        self::assertSame('2026-10-09', $meal->getStartDate()?->format('Y-m-d'));
        self::assertSame('2026-10-09', $meal->getEndDate()?->format('Y-m-d'));
        self::assertNull($meal->getStartAt());
        self::assertNull($meal->getEndAt());
        self::assertTrue($meal->isAllDay());
    }

    /** The `Event` door's all-day schedule names the day by its first one. */
    public function testAnAllDayScheduleThroughTheEventDoorMovesTheDayAndKeepsItOneDay(): void
    {
        $meal = $this->aMeal();

        $meal->scheduleAllDay(new \DateTimeImmutable('2026-10-12'), new \DateTimeImmutable('2026-10-14'));

        self::assertSame('2026-10-12', $meal->getDate()?->format('Y-m-d'));
        self::assertSame('2026-10-12', $meal->getEndDate()?->format('Y-m-d'));
    }

    /** The day is the one the writer meant, which is the day in the meal's zone. */
    public function testAnInstantIsReadAsADayInTheMealsTimeZone(): void
    {
        $meal = $this->aMeal();

        // 2026-10-10 00:30 in Paris, which is still 2026-10-09 in UTC.
        $meal->setStartAt(new \DateTimeImmutable('2026-10-09T22:30:00+00:00'));

        self::assertSame('2026-10-10', $meal->getDate()?->format('Y-m-d'));
    }

    /** Setting the end alone is not a move: the end of a meal is the end of its day. */
    public function testSettingTheEndAloneDoesNotMoveTheDay(): void
    {
        $meal = $this->aMeal('2026-10-07');

        $meal->setEndAt(new \DateTimeImmutable('2026-11-30T20:30:00+01:00'));
        $meal->setEndDate(new \DateTimeImmutable('2026-11-30'));

        self::assertSame('2026-10-07', $meal->getDate()?->format('Y-m-d'));
        self::assertSame('2026-10-07', $meal->getEndDate()?->format('Y-m-d'));
        self::assertNull($meal->getEndAt());
    }

    public function testMovingTheTimeZoneDoesNotMoveTheDay(): void
    {
        $meal = $this->aMeal('2026-10-07');

        $meal->setTimeZone('Pacific/Auckland');

        self::assertSame('2026-10-07', $meal->getDate()?->format('Y-m-d'));
        self::assertSame('2026-10-07', $meal->getStartDate()?->format('Y-m-d'));
    }

    /**
     * A time zone nobody can resolve must not be a 500: `Event::$timeZone` is
     * a free string on an old row. An instant handed in is then read in the
     * default zone, Europe/Paris.
     */
    public function testAnUnknownTimeZoneFallsBackOnTheDefaultToReadAnInstant(): void
    {
        $meal = $this->aMeal('2026-10-07');

        $meal->setTimeZone('Mars/Olympus');
        $meal->setStartAt(new \DateTimeImmutable('2026-10-09T22:30:00+00:00'));

        self::assertSame('2026-10-10', $meal->getDate()?->format('Y-m-d'));
    }

    /** @return iterable<string, array{string}> */
    public static function notDays(): iterable
    {
        yield 'a month and a day that do not exist' => ['2026-13-45'];
        yield 'the 31st of a 30-day month' => ['2026-04-31'];
        yield 'the 29th of a common February' => ['2026-02-29'];
        yield 'a day with a time' => ['2026-10-07 19:30'];
        yield 'a day without its padding' => ['2026-10-7'];
        yield 'words' => ['demain'];
        yield 'nothing' => [''];
    }

    #[DataProvider('notDays')]
    public function testDayFromStringRefusesAnythingThatIsNotADay(string $value): void
    {
        $this->expectException(\DomainException::class);

        Meal::dayFromString($value);
    }

    public function testDayFromStringReadsADay(): void
    {
        self::assertSame('2026-10-07', Meal::dayFromString('2026-10-07')->format('Y-m-d'));
        self::assertSame('00:00:00', Meal::dayFromString('2026-10-07')->format('H:i:s'));
    }

    /**
     * The range reader is the lenient half, and deliberately so: a window to
     * read is not a day to write on. `manage_meals action=list` and
     * `generate_grocery_list` both go through it.
     *
     * @return iterable<string, array{string, string}>
     */
    public static function rangeEnds(): iterable
    {
        yield 'a bare day' => ['2026-10-07', '2026-10-07'];
        yield 'a day with a time' => ['2026-10-07 19:30', '2026-10-07'];
        yield 'a full instant in Paris' => ['2026-10-07T00:30:00+02:00', '2026-10-07'];
        // 00:30 in Paris, which is still the day before in UTC — the day the
        // asker meant is the Paris one.
        yield 'an instant whose UTC day is the day before' => ['2026-10-06T22:30:00+00:00', '2026-10-07'];
    }

    #[DataProvider('rangeEnds')]
    public function testDayOfStringKeepsOnlyTheDay(string $value, string $expected): void
    {
        self::assertSame($expected, Meal::dayOfString($value)->format('Y-m-d'));
    }

    /** @return iterable<string, array{string}> */
    public static function notDates(): iterable
    {
        yield 'words' => ['demain'];
        // `new \DateTimeImmutable('')` is *now*, so an empty range end would
        // silently mean the day the server happens to be on.
        yield 'nothing' => [''];
        yield 'blanks' => ['   '];
    }

    #[DataProvider('notDates')]
    public function testDayOfStringRefusesWhatIsNotADateAtAll(string $value): void
    {
        $this->expectException(\DomainException::class);

        Meal::dayOfString($value);
    }

    /**
     * `allDay` is derived too, and `UpdateEventHandler` sets it *after* the
     * instants — so it is the last field the `Event` door could have knocked
     * out of step with a whole-day span.
     */
    public function testAMealStaysAllDayWhateverTheEventDoorSets(): void
    {
        $meal = $this->aMeal('2026-10-07');

        $meal->setAllDay(false);

        self::assertTrue($meal->isAllDay());
        self::assertSame('2026-10-07', $meal->getDate()?->format('Y-m-d'));
    }

    /** Before a day is known an end is nothing to keep: a meal never has an instant. */
    public function testAnEndSetBeforeAnyDayLeavesTheEntityReadable(): void
    {
        $meal = new Meal();
        $meal->setEndAt(new \DateTimeImmutable('2026-10-07T20:30:00+02:00'));

        self::assertNull($meal->getEndAt());
        self::assertNull($meal->getDate());
    }

    /**
     * Collections and items are rebuilt from the Elasticsearch document alone,
     * so a day missing from it is a day no client can read.
     */
    public function testTheSearchDocumentCarriesTheDayAsADay(): void
    {
        $document = $this->aMeal('2026-10-07')->toSearchDocument();

        self::assertSame('2026-10-07', $document['date']);
        self::assertSame('lunch', $document['slot']);
    }

    /** The field the week filters on is mapped as a day, not as an instant. */
    public function testTheDayIsIndexedAsADay(): void
    {
        $mapping = (new IndexMetadataReader())->getMapping(Meal::class);

        self::assertSame(['type' => 'date', 'format' => 'yyyy-MM-dd'], $mapping['date']);
    }

    /** A screen reading the payload has to put the meal where the API would. */
    public function testTheMercurePayloadCarriesTheDayAndNoInstant(): void
    {
        $payload = $this->aMeal('2026-10-07')->toMercurePayload();

        self::assertSame('2026-10-07', $payload['date']);
        self::assertSame('lunch', $payload['slot']);
        self::assertArrayNotHasKey('startAt', $payload);
        self::assertArrayNotHasKey('endAt', $payload);
    }
}
