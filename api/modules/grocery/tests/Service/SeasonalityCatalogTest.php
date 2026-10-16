<?php

namespace Maggie\Grocery\Tests\Service;

use Maggie\Grocery\Service\SeasonalityCatalog;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** The seasonality calendar (MAG-494): which name, synonym or Ciqual code it knows, and for which months. */
class SeasonalityCatalogTest extends TestCase
{
    /** @return iterable<string, array{string, ?string, ?list<int>}> */
    public static function products(): iterable
    {
        yield 'a name' => ['Potiron', null, [1, 9, 10, 11, 12]];
        yield 'a plural, other case' => ['TOMATES', null, [6, 7, 8, 9]];
        yield 'a synonym' => ['Tomates cerises', null, [6, 7, 8, 9]];
        yield 'without its accents' => ['epinards', null, [1, 2, 3, 4, 5, 9, 10, 11, 12]];
        yield 'with an accent the calendar lacks' => ['Céleri-rave', null, [1, 2, 3, 10, 11, 12]];
        yield 'a Ciqual code, whatever the name' => ['Ma courge du marché', '20138', [1, 9, 10, 11, 12]];
        yield 'not a fruit nor a vegetable' => ['Lessive', null, null];
        yield 'a longer name is another product' => ['Pomme de terre', null, null];
        yield 'imported all year: never in the calendar' => ['Banane', null, null];
    }

    /** @param list<int>|null $expected */
    #[DataProvider('products')]
    public function testMonthsFor(string $name, ?string $ciqual, ?array $expected): void
    {
        self::assertSame($expected, (new SeasonalityCatalog())->monthsFor($name, $ciqual));
    }

    public function testInSeasonInListsTheProduceOfTheMonth(): void
    {
        $names = array_column((new SeasonalityCatalog())->inSeasonIn(10), 'name');

        self::assertContains('Potiron', $names);
        self::assertNotContains('Tomate', $names);
    }

    public function testEveryMonthOfTheFileIsFromOneToTwelve(): void
    {
        $document = json_decode((string) file_get_contents(\dirname(__DIR__, 2).'/resources/seasonality.json'), true, 512, JSON_THROW_ON_ERROR);

        self::assertNotEmpty($document['source']);
        foreach ($document['produce'] as $entry) {
            self::assertNotEmpty($entry['months'], $entry['name']);
            foreach ($entry['months'] as $month) {
                self::assertThat($month, self::logicalAnd(self::greaterThanOrEqual(1), self::lessThanOrEqual(12)), $entry['name']);
            }
        }
    }
}
