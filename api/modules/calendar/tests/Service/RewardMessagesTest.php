<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use Maggie\Calendar\Service\RewardMessages;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Random\Engine\Mt19937;
use Random\Randomizer;

/** The written words of a reward (MAG-508): never twice in a row, never a reproach. */
class RewardMessagesTest extends TestCase
{
    private const FORBIDDEN = ['retard', 'toujours pas', 'aurais dû', 'oublié', 'dépêche', 'enfin'];

    private static function seeded(int $seed = 508): RewardMessages
    {
        return new RewardMessages(new Randomizer(new Mt19937($seed)));
    }

    /** @return iterable<string, array{string}> */
    public static function tones(): iterable
    {
        yield 'doux' => ['doux'];
        yield 'direct' => ['direct'];
        yield 'drole' => ['drole'];
    }

    #[DataProvider('tones')]
    public function testThirtyTicksInARowNeverRepeatTheMessage(string $tone): void
    {
        $messages = self::seeded();
        $previous = null;
        $seen = [];

        for ($i = 0; $i < 30; ++$i) {
            $drawn = $messages->draw($tone, null, $previous['key'] ?? null);
            self::assertStringStartsWith($tone.'.', $drawn['key']);
            self::assertContains($drawn['message'], RewardMessages::TONES[$tone]);
            if (null !== $previous) {
                self::assertNotSame($previous['key'], $drawn['key']);
                self::assertNotSame($previous['message'], $drawn['message']);
            }
            $previous = $drawn;
            $seen[$drawn['key']] = true;
        }

        self::assertGreaterThan(5, \count($seen), 'the bank is drawn from, not one message');
    }

    public function testTheKeyMatchesTheMessage(): void
    {
        $drawn = self::seeded()->draw('direct', null, null);

        $index = (int) explode('.', $drawn['key'])[1] - 1;
        self::assertSame(RewardMessages::TONES['direct'][$index], $drawn['message']);
    }

    public function testASurpriseDrawsFromItsBankWithItsValues(): void
    {
        $messages = self::seeded();

        $waited = $messages->draw('direct', 'waited', null, ['jours' => 4]);
        self::assertMatchesRegularExpression('/^waited\.[1-3]$/', $waited['key']);
        self::assertStringContainsString('4 jours', $waited['message']);

        $record = $messages->draw('doux', 'monthRecord', $waited['key'], ['total' => 42]);
        self::assertStringContainsString('42', $record['message']);
        self::assertStringNotContainsString('{', $record['message']);
    }

    public function testASurpriseNeverRepeatsThePreviousOne(): void
    {
        $messages = self::seeded(1);
        $previous = null;
        for ($i = 0; $i < 30; ++$i) {
            $drawn = $messages->draw('doux', 'fullDay', $previous);
            self::assertNotSame($previous, $drawn['key']);
            $previous = $drawn['key'];
        }
    }

    public function testAnUnknownToneIsRefused(): void
    {
        $this->expectException(\InvalidArgumentException::class);

        self::seeded()->draw('sec', null, null);
    }

    public function testEveryBankHasTenMessagesAndEverySurpriseThree(): void
    {
        self::assertSame(['doux', 'direct', 'drole'], array_keys(RewardMessages::TONES));
        foreach (RewardMessages::TONES as $bank) {
            self::assertCount(10, array_unique($bank));
        }
        self::assertSame(['fullDay', 'monthRecord', 'waited'], array_keys(RewardMessages::SURPRISES));
        foreach (RewardMessages::SURPRISES as $bank) {
            self::assertCount(3, $bank);
            foreach ($bank as $message) {
                self::assertStringStartsWith('Étoile filante : ', $message);
            }
        }
    }

    public function testNoMessageOfAnyBankIsAReproach(): void
    {
        $all = RewardMessages::all();
        self::assertCount(39, $all);

        foreach ($all as $message) {
            foreach (self::FORBIDDEN as $word) {
                self::assertStringNotContainsStringIgnoringCase($word, $message, \sprintf('« %s » in « %s »', $word, $message));
            }
        }
    }
}
