<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Mcp\Tool\GetTreasuryOutlookTool;
use Maggie\Finance\Tests\UseCase\SavingsAdvancesWorld;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * « Je vais être à découvert ? » (MAG-279). The one argument, the horizon in
 * months (MAG-506), is refused out of 1 to 12; the user bound is the other
 * thing that can be wrong. No salary ever came in, so the previous month is late
 * whatever the day the suite runs, and the Courant at -120 € is overdrawn
 * today. A pure read — it publishes and indexes nothing.
 */
class GetTreasuryOutlookToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;
    use SavingsAdvancesWorld;

    private const string FIXTURES = __DIR__.'/../UseCase/fixtures/treasury_outlook.yaml';

    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->loadFixtures(self::FIXTURES);
    }

    /** @return array<string, mixed> */
    private function read(?int $months = null): array
    {
        $tool = self::getContainer()->get(GetTreasuryOutlookTool::class);

        return json_decode($tool($months), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserBoundItReportsTheError(): void
    {
        self::assertArrayHasKey('error', $this->read());
    }

    public function testItReadsTheDelayTheOverdraftAndTheAdvice(): void
    {
        $this->add('checking', -3000, 'today', label: 'Courses prévues')->setStatus(TransactionStatus::Planned);
        $this->flushWithoutTransactionEffects($this->em);
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->read();

        $today = (new \DateTimeImmutable('today'))->format('Y-m-d');
        self::assertTrue($data['salary']['isLate']);
        self::assertGreaterThan(0, $data['salary']['delayDays']);
        self::assertSame(-12000, $data['balanceCents']);
        self::assertSame(['Courses prévues'], array_column($data['projection'][0]['movements'], 'label'));
        self::assertSame(-15000, $data['projection'][0]['balanceCents']);
        self::assertSame(['onDate' => $today, 'balanceCents' => -15000], $data['balanceBeforeSalary']);
        self::assertTrue($data['overdraft']['isForecast']);
        self::assertSame($today, $data['overdraft']['onDate']);
        self::assertSame('Livret A', $data['advice']['sourceAccountName']);
        self::assertGreaterThanOrEqual(15000, $data['advice']['amountCents']);
        self::assertSame(0, $data['outstandingAdvancesCents']);
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Transaction::class);
    }

    public function testAUserNeverReadsTheTreasuryOfAnother(): void
    {
        $this->add('other_checking', -100000, 'today', label: 'Chez l\'autre')->setStatus(TransactionStatus::Planned);
        $this->flushWithoutTransactionEffects($this->em);
        $this->loginFixtureUser();

        $data = $this->read();

        self::assertSame(-12000, $data['balanceCents']);
        self::assertSame([], $data['projection'][0]['movements']);
        self::assertNotSame('Livret de l\'autre', $data['advice']['sourceAccountName']);
    }

    public function testWithoutAnExpectedDayNothingIsForecast(): void
    {
        $this->loginFixtureUser('bare_user');

        $data = $this->read();

        self::assertFalse($data['salary']['isConfigured']);
        self::assertSame([], $data['projection']);
        self::assertNull($data['balanceBeforeSalary']);
        self::assertFalse($data['overdraft']['isForecast']);
        self::assertNull($data['advice']);
    }

    public function testTheHorizonAskedForSetsHowFarItProjects(): void
    {
        $this->loginFixtureUser();

        $year = $this->read(12);

        self::assertGreaterThanOrEqual(365, \count($year['projection']));
        self::assertLessThan(\count($year['projection']), \count($this->read()['projection']));
    }

    public function testAHorizonOutOfRangeIsReportedAsAnError(): void
    {
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->read(0));
        self::assertArrayHasKey('error', $this->read(13));
    }
}
