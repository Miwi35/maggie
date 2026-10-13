<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Mcp\Tool\ProposeAdvanceRepaymentsTool;
use Maggie\Finance\Tests\UseCase\SavingsAdvancesWorld;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * « Quoi virer sur mon livret, et combien ? » (MAG-277). No argument: the user
 * bound is all that can be wrong. Maggie proposes and never writes, which the
 * happy path checks.
 */
class ProposeAdvanceRepaymentsToolTest extends KernelTestCase
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
        $tool = self::getContainer()->get(ProposeAdvanceRepaymentsTool::class);

        return json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function fundChecking(string $ref, int $balanceCents): void
    {
        $account = $this->getFixture($ref);
        \assert($account instanceof Account);
        $account->setBalanceCents($balanceCents);
        $this->flushWithoutTransactionEffects($this->em);
    }

    public function testWithoutAUserBoundItReportsTheError(): void
    {
        self::assertArrayHasKey('error', $this->read());
    }

    public function testItProposesTheExactCounterTransferAndWritesNothing(): void
    {
        $this->fundChecking('checking', 400000);
        [$advance] = $this->transfer('livret', 'checking', 150000, 'today -5 days', TransferKind::Advance);
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $before = $this->em->getRepository(Transaction::class)->count([]);

        $data = $this->read();

        self::assertSame(150000, $data['totalCents']);
        self::assertSame([[
            'fromAccountId' => (string) $this->getFixture('checking')->getId(),
            'toAccountId' => (string) $this->getFixture('livret')->getId(),
            'amountCents' => 150000,
            'advanceId' => (string) $advance->getId(),
            'label' => 'Remise avance Livret A',
            'currency' => 'EUR',
            'isPartial' => false,
        ]], $data['proposals']);
        self::assertSame([], $data['unproposed']);
        // Proposed, not done: no line was added, nothing published or indexed.
        self::assertSame($before, $this->em->getRepository(Transaction::class)->count([]));
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Transaction::class);
    }

    public function testWhatTheBalanceCannotCoverIsAnnounced(): void
    {
        $this->fundChecking('checking', 100000);
        $this->transfer('livret', 'checking', 150000, 'today -5 days', TransferKind::Advance);
        $this->loginFixtureUser();

        $data = $this->read();

        self::assertSame(100000, $data['totalCents']);
        self::assertTrue($data['proposals'][0]['isPartial']);
        self::assertSame(50000, $data['unproposedCents']);
        self::assertSame('insufficient_balance', $data['unproposed'][0]['reason']);
    }

    public function testNoOpenAdvanceIsAnEmptyAnswerNotAnError(): void
    {
        $this->loginFixtureUser();

        $data = $this->read();

        self::assertArrayNotHasKey('error', $data);
        self::assertSame([], $data['proposals']);
        self::assertSame(0, $data['totalCents']);
    }

    public function testAUserNeverGetsTheProposalsOfAnother(): void
    {
        $this->fundChecking('other_checking', 400000);
        $this->transfer('other_livret', 'other_checking', 150000, 'today -5 days', TransferKind::Advance);
        $this->loginFixtureUser();

        self::assertSame([], $this->read()['proposals']);
    }
}
