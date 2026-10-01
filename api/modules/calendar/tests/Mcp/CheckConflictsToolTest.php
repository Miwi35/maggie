<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Mcp\Tool\CheckConflictsTool;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * `check_conflicts` reads the day in Europe/Paris, not in UTC (MAG-114 § 1).
 *
 * The tool receives a bare date and time — "2026-11-10", "10:30" — and the
 * events it compares them against are stored as instants. Building the slot
 * without the user's offset puts 10:30 Paris an hour or two away from where the
 * owner meant it, so a check that lands squarely inside a meeting answers that
 * the slot is free. That was the bug; it is fixed, and nothing kept it fixed —
 * the tool had no test of its own, and neither of the two cases below is
 * expressible without an offset. Both dates sit on opposite sides of the DST
 * change on purpose: a fixture written with `tomorrow` exercises one offset, and
 * which one depends on the day the suite happens to run.
 *
 * The caller's identity is covered next door, in
 * {@see UserIsolationToolsTest}: another user's events never count as a
 * conflict, the caller's own always do, and a call with nobody bound is
 * refused. This file does not repeat them.
 */
class CheckConflictsToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    /** @return iterable<string, array{string, string, string}> */
    public static function parisOffsetProvider(): iterable
    {
        // Winter, UTC+1: the meeting is 09:00–10:00 UTC, so a slot built in UTC
        // would place 10:30 an hour past its end and report the day as free.
        yield 'winter, UTC+1' => ['2026-11-10', '10:30', "Réunion d'hiver"];
        // Summer, UTC+2: two hours out, and the same answer for a second reason.
        yield 'summer, UTC+2' => ['2026-07-14', '10:30', "Réunion d'été"];
    }

    #[DataProvider('parisOffsetProvider')]
    public function testASlotInsideAMeetingConflictsAtTheParisOffset(string $date, string $time, string $expected): void
    {
        $this->loadFixtures('CheckConflictsToolTest.yaml');
        $this->loginFixtureUser();

        $data = $this->check($date, $time, 30);

        self::assertTrue($data['hasConflicts'], 'a slot inside a meeting must conflict');
        self::assertSame([$expected], array_column($data['conflicts'], 'summary'));
    }

    /**
     * The slot the tool says it checked, spelled out in the answer.
     *
     * Asserted because it is the only part of the result the model sees that can
     * show *why* a conflict was missed: a `checkedSlot` an hour away from what
     * was asked is exactly the shape of MAG-114 § 1, and it would be silent
     * otherwise — the conflict list alone cannot tell "no overlap" from "the
     * wrong hour was checked".
     */
    #[DataProvider('parisOffsetProvider')]
    public function testTheCheckedSlotIsReportedAtTheParisOffset(string $date, string $time, string $expected): void
    {
        $this->loadFixtures('CheckConflictsToolTest.yaml');
        $this->loginFixtureUser();

        $data = $this->check($date, $time, 30);

        $start = new \DateTimeImmutable($data['checkedSlot']['start']);
        $end = new \DateTimeImmutable($data['checkedSlot']['end']);
        $paris = new \DateTimeZone('Europe/Paris');

        $because = sprintf('the slot checked against "%s" must be the one the owner asked for', $expected);
        self::assertSame($date.' '.$time, $start->setTimezone($paris)->format('Y-m-d H:i'), $because);
        self::assertSame($date.' 11:00', $end->setTimezone($paris)->format('Y-m-d H:i'), $because);
    }

    public function testASlotOutsideEveryMeetingIsFree(): void
    {
        $this->loadFixtures('CheckConflictsToolTest.yaml');
        $this->loginFixtureUser();

        // 16:00 on the winter date: past the meeting, and inside the all-day
        // birthday — which covers every hour and must still leave the slot free.
        $data = $this->check('2026-11-10', '16:00', 60);

        self::assertFalse($data['hasConflicts']);
        self::assertSame([], $data['conflicts']);
    }

    /**
     * Back-to-back is free; one minute of overlap is not.
     *
     * The duration is applied in Paris too, so this is the same bug seen from the
     * other end — and the pair is what makes the boundary exact. Either
     * assertion alone would pass on a tool off by an hour: a slot nowhere near
     * the meeting is free, and a slot swallowing the whole morning conflicts.
     */
    public function testBackToBackIsFreeAndOneMinuteOfOverlapIsNot(): void
    {
        $this->loadFixtures('CheckConflictsToolTest.yaml');
        $this->loginFixtureUser();

        // 09:00 Paris for 60 minutes ends exactly when the meeting starts.
        self::assertFalse(
            $this->check('2026-11-10', '09:00', 60)['hasConflicts'],
            'a slot ending when the meeting starts must not conflict',
        );

        // One minute more reaches into it.
        self::assertTrue(
            $this->check('2026-11-10', '09:00', 61)['hasConflicts'],
            'a slot overlapping the meeting by a minute must conflict',
        );
    }

    /** @return iterable<string, array{string, string}> */
    public static function badInputProvider(): iterable
    {
        yield 'a date nothing can parse' => ['not-a-date', '10:30'];
        yield 'a time nothing can parse' => ['2026-11-10', 'half past ten'];
        // The one that would be silent rather than loud: `new DateTimeImmutable('')`
        // means *now*, so a missing date used to answer about today.
        yield 'an empty date' => ['', '10:30'];
        yield 'an empty time' => ['2026-11-10', ''];
        yield 'a date in the right shape and out of range' => ['2026-99-99', '10:30'];
    }

    /**
     * Bad input answers with an error the model can read.
     *
     * An unparsable date used to throw out of the tool, and the agent then saw a
     * transport failure rather than a sentence — which it cannot explain to
     * anyone and cannot retry from. Every other calendar tool returns
     * `{"error": …}`; this one does too.
     */
    #[DataProvider('badInputProvider')]
    public function testUnparsableInputIsRefusedWithAnError(string $date, string $time): void
    {
        $this->loadFixtures('CheckConflictsToolTest.yaml');
        $this->loginFixtureUser();

        $data = $this->check($date, $time);

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('conflicts', $data);
    }

    /**
     * A slot of no length is refused rather than answered.
     *
     * Zero or negative minutes make `end <= start`, which no event can overlap —
     * so the tool would report an empty day with complete confidence, whatever
     * the agenda holds. That is the one wrong answer worth refusing outright.
     */
    public function testANonPositiveDurationIsRefused(): void
    {
        $this->loadFixtures('CheckConflictsToolTest.yaml');
        $this->loginFixtureUser();

        foreach ([0, -30] as $duration) {
            $data = $this->check('2026-11-10', '10:30', $duration);

            self::assertArrayHasKey('error', $data, sprintf('duration %d', $duration));
            self::assertArrayNotHasKey('conflicts', $data);
        }
    }

    /** @return array<string, mixed> */
    private function check(string $date, string $time, int $duration = 60): array
    {
        $tool = self::getContainer()->get(CheckConflictsTool::class);

        return json_decode($tool($date, $time, $duration), true, 512, JSON_THROW_ON_ERROR);
    }
}
