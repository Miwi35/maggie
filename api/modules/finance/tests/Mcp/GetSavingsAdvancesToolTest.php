<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Mcp\Tool\GetSavingsAdvancesTool;
use Maggie\Finance\Tests\UseCase\SavingsAdvancesWorld;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * « Combien je dois remettre sur mon livret ? » (MAG-276). No argument: the
 * user bound is all that can be wrong. A pure read — it publishes and indexes
 * nothing, which the happy path checks.
 */
class GetSavingsAdvancesToolTest extends KernelTestCase
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
    private function read(): array
    {
        $tool = self::getContainer()->get(GetSavingsAdvancesTool::class);

        return json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserBoundItReportsTheError(): void
    {
        self::assertArrayHasKey('error', $this->read());
    }

    public function testItReadsWhatIsLeftToGiveBackPerSourceAndTheDelay(): void
    {
        $this->transfer('livret', 'checking', 150000, 'today -5 days', TransferKind::Advance);
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->read();

        self::assertSame(150000, $data['totalOutstandingCents']);
        self::assertSame([['sourceAccountId' => (string) $this->getFixture('livret')->getId(), 'sourceAccountName' => 'Livret A', 'outstandingCents' => 150000, 'openCount' => 1]], $data['bySource']);
        self::assertSame(150000, $data['advances'][0]['outstandingCents']);
        // No salary ever came in: the previous month is late, whatever the day.
        self::assertTrue($data['salaryDelay']['isLate']);
        // Read, not written: both legs are still the one advance.
        self::assertSame(2, $this->em->getRepository(Transaction::class)->count(['transferKind' => TransferKind::Advance, 'user' => $this->user()]));
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Transaction::class);
    }

    public function testAUserNeverReadsTheAdvancesOfAnother(): void
    {
        $this->transfer('other_livret', 'other_checking', 150000, 'today -5 days', TransferKind::Advance);
        $this->loginFixtureUser();

        $data = $this->read();

        self::assertSame([], $data['advances']);
        self::assertSame(0, $data['totalOutstandingCents']);
    }
}
