<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Specification;

use Maggie\Finance\Specification\RealCurrency;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** What counts as a currency an account can be held in (MAG-376). */
final class RealCurrencyTest extends TestCase
{
    /** @return iterable<string, array{string}> */
    public static function realCodes(): iterable
    {
        foreach (['EUR', 'CHF', 'USD', 'GBP', 'JPY', 'XPF', 'XAF', 'XOF', 'XCD'] as $code) {
            yield $code => [$code];
        }
    }

    /** @return iterable<string, array{string}> */
    public static function reservedCodes(): iterable
    {
        foreach (['XXX', 'XTS', 'XAU', 'XAG', 'XPT', 'XPD', 'XDR', 'XBA', 'XSU', 'XUA'] as $code) {
            yield $code => [$code];
        }
    }

    /** @return iterable<string, array{mixed}> */
    public static function malformed(): iterable
    {
        yield 'lower case' => ['eur'];
        yield 'two letters' => ['EU'];
        yield 'four letters' => ['EURO'];
        yield 'padded' => [' EUR'];
        yield 'digits' => ['978'];
        yield 'empty' => [''];
        yield 'null' => [null];
        yield 'number' => [978];
        yield 'list' => [['EUR']];
    }

    #[DataProvider('realCodes')]
    public function testACodeOfARealCurrencyIsReal(string $code): void
    {
        self::assertTrue(RealCurrency::isReal($code));
    }

    #[DataProvider('reservedCodes')]
    public function testACodeReservedForNoCurrencyIsNotReal(string $code): void
    {
        self::assertFalse(RealCurrency::isReal($code));
    }

    #[DataProvider('malformed')]
    public function testAMalformedCodeIsNotReal(mixed $code): void
    {
        self::assertFalse(RealCurrency::isReal($code));
    }

    public function testTheFirstRealCandidateWins(): void
    {
        self::assertSame('CHF', RealCurrency::resolve('XXX', null, 'CHF', 'USD'));
        self::assertSame('USD', RealCurrency::resolve('USD', 'CHF'));
    }

    public function testWithoutARealCandidateItIsEuro(): void
    {
        self::assertSame('EUR', RealCurrency::resolve('XXX', 'XTS', null, 'eur'));
        self::assertSame('EUR', RealCurrency::resolve());
    }
}
