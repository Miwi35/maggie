<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Service;

use Maggie\Health\Entity\Workout;
use Maggie\Health\Service\RuleMakeupVariants;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** softer keeps the duration; shorter is half of it rounded down to 5 min, 10 min at least (MAG-771). */
final class RuleMakeupVariantsTest extends TestCase
{
    /** @return iterable<string, array{int, int}> */
    public static function durations(): iterable
    {
        yield '45 min' => [45, 20];
        yield '60 min' => [60, 30];
        yield '15 min: 10 at least' => [15, 10];
        yield '8 min: never longer than the session' => [8, 8];
    }

    #[DataProvider('durations')]
    public function testTheVariants(int $minutes, int $shorter): void
    {
        $variants = (new RuleMakeupVariants())->variantsFor((new Workout())->setDurationMinutes($minutes));

        self::assertSame($minutes, $variants->softerMinutes);
        self::assertSame($shorter, $variants->shorterMinutes);
    }
}
