<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use Maggie\Calendar\Enum\TaskAlertReason;
use Maggie\Calendar\Service\ReminderMessages;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Random\Engine\Mt19937;
use Random\Randomizer;

/** The written words of a reminder (MAG-510): the banks as written, never the same message twice in a row. */
class ReminderMessagesTest extends TestCase
{
    private static function seeded(int $seed = 510): ReminderMessages
    {
        return new ReminderMessages(new Randomizer(new Mt19937($seed)));
    }

    /** @return iterable<string, array{string}> */
    public static function tones(): iterable
    {
        yield 'doux' => ['doux'];
        yield 'direct' => ['direct'];
        yield 'drole' => ['drole'];
    }

    public function testTheBanksHoldTenNudgesAndThreeAlertsPerReasonForEachTone(): void
    {
        foreach (ReminderMessages::NUDGE as $bank) {
            self::assertCount(10, $bank);
        }
        foreach (ReminderMessages::DUE as $reason) {
            self::assertSame(['doux', 'direct', 'drole'], array_keys($reason));
            foreach ($reason as $bank) {
                self::assertCount(3, $bank);
            }
        }
    }

    #[DataProvider('tones')]
    public function testThirtyNudgesInARowNeverRepeatTheMessage(string $tone): void
    {
        $messages = self::seeded();
        $previous = null;
        $seen = [];

        for ($i = 0; $i < 30; ++$i) {
            $drawn = $messages->nudge($tone, 'Appeler le garagiste', $previous['key'] ?? null);
            self::assertStringStartsWith('nudge.'.$tone.'.', $drawn['key']);
            $index = (int) explode('.', $drawn['key'])[2] - 1;
            self::assertSame(str_replace('{titre}', 'Appeler le garagiste', ReminderMessages::NUDGE[$tone][$index]), $drawn['message']);
            if (null !== $previous) {
                self::assertNotSame($previous['key'], $drawn['key']);
            }
            $previous = $drawn;
            $seen[$drawn['key']] = true;
        }

        self::assertGreaterThan(5, \count($seen), 'the bank is drawn from, not one message');
    }

    public function testThirtyAlertsInARowNeverRepeatTheMessage(): void
    {
        $messages = self::seeded();
        $previous = null;

        for ($i = 0; $i < 30; ++$i) {
            $drawn = $messages->due('direct', TaskAlertReason::Overdue, 4, 'Rendre le livre', $previous['key'] ?? null);
            self::assertStringStartsWith('due.overdue.direct.', $drawn['key']);
            self::assertStringContainsString('« Rendre le livre »', $drawn['message']);
            self::assertStringNotContainsString('{', $drawn['message']);
            if (null !== $previous) {
                self::assertNotSame($previous['key'], $drawn['key']);
            }
            $previous = $drawn;
        }
    }

    public function testTheRoutineBankHoldsSixMessagesPerTone(): void
    {
        self::assertSame(['doux', 'direct', 'drole'], array_keys(ReminderMessages::ROUTINE));
        foreach (ReminderMessages::ROUTINE as $bank) {
            self::assertCount(6, $bank);
            foreach ($bank as $message) {
                self::assertStringContainsString('{liste}', $message);
            }
        }
    }

    #[DataProvider('tones')]
    public function testThirtyRoutineRemindersInARowNeverRepeatTheMessage(string $tone): void
    {
        $messages = self::seeded();
        $previous = null;
        $seen = [];

        for ($i = 0; $i < 30; ++$i) {
            $drawn = $messages->routine($tone, ['Brosser les dents', 'Passer le fil'], $previous['key'] ?? null);
            self::assertStringStartsWith('routine.'.$tone.'.', $drawn['key']);
            $index = (int) explode('.', $drawn['key'])[2] - 1;
            self::assertSame(str_replace('{liste}', 'Brosser les dents, Passer le fil', ReminderMessages::ROUTINE[$tone][$index]), $drawn['message']);
            self::assertStringNotContainsString('{', $drawn['message']);
            if (null !== $previous) {
                self::assertNotSame($previous['key'], $drawn['key']);
            }
            $previous = $drawn;
            $seen[$drawn['key']] = true;
        }

        self::assertCount(6, $seen, 'the whole bank is drawn from');
    }

    public function testAnUnknownRoutineToneIsRefused(): void
    {
        $this->expectException(\InvalidArgumentException::class);

        self::seeded()->routine('sec', ['x'], null);
    }

    public function testTheDaysAreWrittenIn(): void
    {
        $drawn = self::seeded()->due('doux', TaskAlertReason::DaysBefore, 3, 'Déclarer les impôts', null);

        self::assertStringContainsString('3 j', $drawn['message']);
        self::assertStringStartsWith('due.daysBefore.doux.', $drawn['key']);
    }

    public function testALongTitleIsCutSoTheWholeFitsANotificationTitle(): void
    {
        $drawn = self::seeded()->nudge('drole', str_repeat('é', 400), null);

        self::assertLessThanOrEqual(ReminderMessages::TITLE_MAX, mb_strlen($drawn['message']));
        self::assertStringContainsString('…', $drawn['message']);
    }

    public function testAnUnknownToneIsRefused(): void
    {
        $this->expectException(\InvalidArgumentException::class);

        self::seeded()->nudge('sec', 'x', null);
    }
}
