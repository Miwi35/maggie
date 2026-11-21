<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Mcp\Tool\GetFinanceSummaryTool;
use Maggie\Finance\Tests\UseCase\SavingsAdvancesWorld;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * « Où j'en suis ? » (MAG-847): one read for the month or the year. The salary
 * is declared on the cushion (4 950 €), so it does not move with the day the
 * suite runs. A pure read — it publishes and indexes nothing.
 */
class FinanceSummaryToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;
    use SavingsAdvancesWorld;

    private const string FIXTURES = __DIR__.'/../UseCase/fixtures/savings_advances.yaml';

    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->loadFixtures(self::FIXTURES);
    }

    /** @return array<string, mixed> */
    private function read(?string $period = null): array
    {
        $tool = self::getContainer()->get(GetFinanceSummaryTool::class);

        return json_decode($tool($period), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserBoundItReportsTheError(): void
    {
        self::assertArrayHasKey('error', $this->read());
    }

    public function testMonthSummaryHasEverySection(): void
    {
        $month = (new \DateTimeImmutable('today'))->format('Y-m');
        $this->transfer('livret', 'checking', 150000, 'today -5 days', TransferKind::Advance);
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->read();

        self::assertSame($month, $data['period']);
        foreach (['spending', 'envelopes', 'satisfaction', 'savings', 'cushion', 'toQualify', 'scores', 'salary', 'advances', 'independence'] as $section) {
            self::assertArrayHasKey($section, $data, $section);
        }
        self::assertArrayHasKey('week', $data['scores']);
        self::assertArrayHasKey('month', $data['scores']);
        self::assertSame(495000, $data['salary']['monthlyCents']);
        self::assertSame('declared', $data['salary']['source']);
        self::assertArrayHasKey('isLate', $data['salary']['delay']);
        self::assertSame(150000, $data['advances']['totalOutstandingCents']);
        self::assertArrayHasKey('isMeasurable', $data['independence']);
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Transaction::class);
    }

    public function testYearSummaryHasNoScores(): void
    {
        $this->loginFixtureUser();

        $data = $this->read('2026');

        self::assertSame('2026', $data['period']);
        self::assertNull($data['scores']);
        self::assertArrayHasKey('months', $data);
        self::assertArrayHasKey('salary', $data);
        self::assertArrayHasKey('advances', $data);
        self::assertArrayHasKey('independence', $data);
    }

    public function testMalformedPeriodIsRefused(): void
    {
        $this->loginFixtureUser();

        foreach (['2026-13', '2026-Q1', 'octobre', '26', '1999'] as $bad) {
            $data = $this->read($bad);
            self::assertArrayHasKey('error', $data, $bad);
            self::assertStringContainsString('YYYY-MM', $data['error']);
        }
    }

    public function testUnknownSalaryIsNullWithSourceUnknown(): void
    {
        $this->loginFixtureUser('unknown_reference_user');

        $data = $this->read();

        self::assertNull($data['salary']['monthlyCents']);
        self::assertSame('unknown', $data['salary']['source']);
    }

    public function testAdvancesBySourceAfterPartialRepayment(): void
    {
        [$advance] = $this->transfer('livret', 'checking', 150000, 'today -5 days', TransferKind::Advance);
        $this->repay($advance, 'livret', 50000, 'today -2 days');
        $this->loginFixtureUser();

        $data = $this->read();

        self::assertSame(100000, $data['advances']['totalOutstandingCents']);
        self::assertSame(
            [['sourceAccountId' => (string) $this->getFixture('livret')->getId(), 'sourceAccountName' => 'Livret A', 'advancedCents' => 150000, 'repaidCents' => 50000, 'outstandingCents' => 100000, 'openCount' => 1]],
            $data['advances']['bySource'],
        );
    }

    public function testAnotherUsersLinesAreNotCounted(): void
    {
        $this->transfer('other_livret', 'other_checking', 150000, 'today -5 days', TransferKind::Advance);
        $this->loginFixtureUser();

        $data = $this->read();

        self::assertSame([], $data['advances']['advances']);
        self::assertSame(0, $data['advances']['totalOutstandingCents']);
    }
}
