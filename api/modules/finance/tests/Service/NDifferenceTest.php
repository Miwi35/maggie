<?php

namespace Maggie\Finance\Tests\Service;

use Maggie\Finance\Service\NDifference;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/**
 * Exemption: N/A — pure wording of a gap, no persistence.
 */
class NDifferenceTest extends TestCase
{
    /** @return iterable<string, array{string|null, string|null, int|null, string}> */
    public static function gaps(): iterable
    {
        yield 'earlier by months' => ['2030-01', '2030-10', -9, 'N 9 mois plus tôt que « Rapide »'];
        yield 'later by a year' => ['2031-10', '2030-10', 12, 'N 1 an plus tard que « Rapide »'];
        yield 'later by years and months' => ['2033-01', '2030-10', 27, 'N 2 ans 3 mois plus tard que « Rapide »'];
        yield 'same date' => ['2030-10', '2030-10', 0, 'N à la même date que « Rapide »'];
        yield 'not reached, the reference reaches it' => [null, '2030-10', null, "N non atteinte avant 20 ans, alors qu'elle l'est en 2030-10 pour « Rapide »"];
        yield 'reached, the reference does not' => ['2030-10', null, null, 'N atteinte en 2030-10, non atteinte avant 20 ans pour « Rapide »'];
        yield 'neither reaches it' => [null, null, null, 'N non atteinte avant 20 ans, comme pour « Rapide »'];
    }

    #[DataProvider('gaps')]
    public function testItSaysTheGapInPlainFacts(?string $own, ?string $reference, ?int $months, string $sentence): void
    {
        self::assertSame(
            ['months' => $months, 'sentence' => $sentence],
            NDifference::between(['name' => 'Autre', 'nDate' => $own], ['name' => 'Rapide', 'nDate' => $reference]),
        );
    }

    public function testItNeverPassesAVerdict(): void
    {
        foreach (self::gaps() as [$own, $reference]) {
            $sentence = NDifference::between(['name' => 'A', 'nDate' => $own], ['name' => 'B', 'nDate' => $reference])['sentence'];
            self::assertDoesNotMatchRegularExpression('/meilleur|mieux|recommand|conseill/iu', $sentence);
        }
    }
}
