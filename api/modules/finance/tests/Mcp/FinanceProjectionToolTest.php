<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Mcp\Tool\GetFinanceProjectionTool;
use Maggie\Finance\Tests\UseCase\SavingsAdvancesWorld;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * « Qu'est-ce qui vient ? » (MAG-847): the treasury outlook and the week and
 * month after this one, in one read. No salary ever came in, so the previous
 * month is late whatever the day the suite runs. A pure read.
 */
class FinanceProjectionToolTest extends KernelTestCase
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
        $tool = self::getContainer()->get(GetFinanceProjectionTool::class);

        return json_decode($tool($months), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserBoundItReportsTheError(): void
    {
        self::assertArrayHasKey('error', $this->read());
    }

    public function testProjectionHasSalaryOverdraftNextWeekAndMonth(): void
    {
        $this->add('checking', -3000, 'today', label: 'Courses prévues')->setStatus(TransactionStatus::Planned);
        $this->flushWithoutTransactionEffects($this->em);
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->read();

        self::assertTrue($data['salary']['isLate']);
        self::assertSame(-12000, $data['balanceCents']);
        self::assertTrue($data['overdraft']['isForecast']);
        self::assertSame(['Courses prévues'], array_column($data['projection'][0]['movements'], 'label'));
        self::assertArrayHasKey('incomeCents', $data['nextWeek']);
        self::assertArrayHasKey('expenseCents', $data['nextWeek']);
        self::assertArrayHasKey('remainingCents', $data['nextMonth']);
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Transaction::class);
    }

    public function testMonthsOutOfRangeIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->read(0));
        self::assertArrayHasKey('error', $this->read(13));
    }

    public function testTheHorizonAskedForSetsHowFarItProjects(): void
    {
        $this->loginFixtureUser();

        self::assertGreaterThanOrEqual(365, \count($this->read(12)['projection']));
    }

    public function testUnconfiguredSalaryProjectsNothing(): void
    {
        $this->loginFixtureUser('bare_user');

        $data = $this->read();

        self::assertFalse($data['salary']['isConfigured']);
        self::assertSame([], $data['projection']);
        self::assertFalse($data['overdraft']['isForecast']);
        self::assertNull($data['advice']);
    }
}
