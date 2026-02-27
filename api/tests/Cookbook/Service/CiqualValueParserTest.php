<?php

namespace App\Tests\Cookbook\Service;

use Maggie\Cookbook\Service\CiqualValueParser;
use PHPUnit\Framework\TestCase;

class CiqualValueParserTest extends TestCase
{
    private CiqualValueParser $parser;

    protected function setUp(): void
    {
        $this->parser = new CiqualValueParser();
    }

    public function testParseNumericValue(): void
    {
        self::assertSame(12.3, $this->parser->parse('12,3'));
    }

    public function testParseIntegerValue(): void
    {
        self::assertSame(274.0, $this->parser->parse('274'));
    }

    public function testParseDash(): void
    {
        self::assertNull($this->parser->parse('-'));
    }

    public function testParseEmpty(): void
    {
        self::assertNull($this->parser->parse(''));
    }

    public function testParseTraces(): void
    {
        self::assertSame(0.0, $this->parser->parse('traces'));
    }

    public function testParseTracesUppercase(): void
    {
        self::assertSame(0.0, $this->parser->parse('Traces'));
    }

    public function testParseLessThan(): void
    {
        self::assertSame(0.25, $this->parser->parse('< 0,5'));
    }

    public function testParseLessThanNoSpace(): void
    {
        self::assertSame(0.25, $this->parser->parse('<0.5'));
    }

    public function testParseLessThanInteger(): void
    {
        self::assertSame(0.5, $this->parser->parse('< 1'));
    }

    public function testParseWithSpaces(): void
    {
        self::assertSame(59.7, $this->parser->parse('  59,7  '));
    }

    public function testParseDotDecimal(): void
    {
        self::assertSame(3.14, $this->parser->parse('3.14'));
    }

    public function testParseZero(): void
    {
        self::assertSame(0.0, $this->parser->parse('0'));
    }
}
