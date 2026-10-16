<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Mcp\Tool\GetMonthBalanceTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class GetMonthBalanceToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    /** @return array<string, mixed> */
    private function balance(?string $month): array
    {
        $tool = self::getContainer()->get(GetMonthBalanceTool::class);

        return json_decode($tool($month), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testItReadsTheWholeBalanceOfAMonth(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/month_balance.yaml');
        $this->loginFixtureUser();

        $balance = $this->balance('2026-09');

        self::assertSame('2026-09', $balance['month']);
        self::assertCount(3, $balance['envelopes']);
        self::assertSame(71, $balance['satisfaction']['keptPercent']);
        self::assertSame(30000, $balance['savings']['savedCents']);
        self::assertSame(1, $balance['toQualify']['count']);
    }

    public function testABadMonthIsAnError(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/month_balance.yaml');
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->balance('2026-13'));
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        self::assertArrayHasKey('error', $this->balance('2026-09'));
    }
}
