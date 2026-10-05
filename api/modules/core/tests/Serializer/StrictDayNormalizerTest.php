<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Serializer;

use Maggie\Core\Serializer\StrictDayNormalizer;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Serializer\Exception\NotNormalizableValueException;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;

/**
 * A field declared as a day takes days, and nothing else (MAG-251).
 */
final class StrictDayNormalizerTest extends TestCase
{
    private StrictDayNormalizer $normalizer;

    protected function setUp(): void
    {
        $this->normalizer = new StrictDayNormalizer(new DateTimeNormalizer());
    }

    /** @return array<string, mixed> */
    private function dayContext(): array
    {
        return [DateTimeNormalizer::FORMAT_KEY => StrictDayNormalizer::DAY_FORMAT];
    }

    public function testADayIsReadAsThatDayAtMidnight(): void
    {
        $day = $this->normalizer->denormalize('2026-10-07', \DateTimeImmutable::class, 'json', $this->dayContext());

        self::assertInstanceOf(\DateTimeImmutable::class, $day);
        self::assertSame('2026-10-07 00:00:00', $day->format('Y-m-d H:i:s'));
    }

    /**
     * @return iterable<string, array{string}>
     */
    public static function notDays(): iterable
    {
        // `createFromFormat` rolls these over instead of failing, which is how
        // a meal nobody planned ended up in February 2027.
        yield 'a month and a day that do not exist' => ['2026-13-45'];
        yield 'the 31st of a 30-day month' => ['2026-04-31'];
        yield 'the 29th of a common February' => ['2026-02-29'];
        yield 'a day without its padding' => ['2026-10-7'];
        yield 'a day with a time' => ['2026-10-07T00:00:00+01:00'];
        yield 'not a date at all' => ['demain'];
    }

    #[DataProvider('notDays')]
    public function testAnythingThatIsNotADayIsRefused(string $value): void
    {
        $this->expectException(NotNormalizableValueException::class);

        $this->normalizer->denormalize($value, \DateTimeImmutable::class, 'json', $this->dayContext());
    }

    /**
     * Only the properties that ask for it are held to this: every other
     * date-time on the API keeps the serializer's own behaviour.
     */
    public function testAnInstantFieldIsLeftAlone(): void
    {
        $instant = $this->normalizer->denormalize('2026-10-07T19:30:00+02:00', \DateTimeImmutable::class, 'json');

        self::assertInstanceOf(\DateTimeImmutable::class, $instant);
        self::assertSame('2026-10-07T19:30:00+02:00', $instant->format('c'));
    }

    public function testItStillNormalizes(): void
    {
        $normalized = $this->normalizer->normalize(
            new \DateTimeImmutable('2026-10-07'),
            'json',
            [DateTimeNormalizer::FORMAT_KEY => 'Y-m-d'],
        );

        self::assertSame('2026-10-07', $normalized);
    }
}
