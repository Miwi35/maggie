<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use Maggie\Calendar\Service\TaskHierarchy;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** The position after the last sibling: Google's twenty digits, compared as strings (MAG-404). */
class TaskHierarchyTest extends TestCase
{
    /** @return iterable<string, array{?string, string}> */
    public static function positions(): iterable
    {
        yield 'no sibling' => [null, '00000000000000000000'];
        yield 'after the first' => ['00000000000000000000', '00000000000000000001'];
        yield 'a carry' => ['00000000000000000099', '00000000000000000100'];
        yield 'beyond PHP integers' => ['99999999999999999998', '99999999999999999999'];
        yield 'every digit a nine' => ['99999999999999999999', '999999999999999999990'];
        yield 'not digits' => ['abc', 'abc0'];
        yield 'short digits keep their length' => ['7', '8'];
        yield 'a short nine' => ['9', '90'];
    }

    #[DataProvider('positions')]
    public function testAfterSortsAfterItsPrevious(?string $last, string $expected): void
    {
        $next = TaskHierarchy::after($last);

        self::assertSame($expected, $next);
        if (null !== $last) {
            self::assertGreaterThan(0, strcmp($next, $last));
        }
    }
}
