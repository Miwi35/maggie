<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Mcp\Tool\GetRecurringOperationsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** The tool takes no action and no argument: a pure read, so nothing else can be wrong with a call. */
class GetRecurringOperationsToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    private const string FIXTURES = __DIR__.'/../UseCase/fixtures/recurring_overview_live.yaml';

    protected function setUp(): void
    {
        self::bootKernel();
    }

    public function testWithoutAUserBoundItReportsTheError(): void
    {
        $this->loadFixtures(self::FIXTURES);

        self::assertArrayHasKey('error', $this->overview());
    }

    public function testItReadsTheCostAndTheNextDueDate(): void
    {
        $this->loadFixtures(self::FIXTURES);
        $this->loginFixtureUser();

        $data = $this->overview();

        self::assertSame(['Abonnement Flixo'], array_column($data['operations'], 'label'));
        self::assertSame(-1349, $data['operations'][0]['monthlyCostCents']);
        self::assertSame(-16188, $data['operations'][0]['yearlyCostCents']);
        self::assertSame((new \DateTimeImmutable('first day of next month'))->format('Y-m-d'), $data['operations'][0]['nextOccurrenceOn']);
        self::assertSame(-1349, $data['totals']['monthlyCostCents']);
        self::assertSame(1, $data['totals']['activeCount']);
        self::assertSame([], $data['proposals']);
    }

    public function testAUserNeverReadsTheSeriesOfAnother(): void
    {
        $this->loadFixtures(self::FIXTURES);
        $this->loginFixtureUser('other_user');

        $data = $this->overview();

        self::assertSame(['Abonnement secret'], array_column($data['operations'], 'label'));
        self::assertSame(-999, $data['totals']['monthlyCostCents']);
    }

    /** @return array<string, mixed> */
    private function overview(): array
    {
        $tool = self::getContainer()->get(GetRecurringOperationsTool::class);

        return json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);
    }
}
