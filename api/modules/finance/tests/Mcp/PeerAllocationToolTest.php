<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\PeerDebtAllocation;
use Maggie\Finance\Entity\PeerDebtShare;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Mcp\Tool\ManagePeerDebtsTool;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** `manage_peer_debts` `allocate` (MAG-453): one allocation per call, the same rules as the allocation endpoint. */
class PeerAllocationToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    private const FIXTURES = __DIR__.'/../Api/fixtures/peer_allocations.yaml';

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function login(): void
    {
        $this->loadFixtures(self::FIXTURES);
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$args): array
    {
        $tool = self::getContainer()->get(ManagePeerDebtsTool::class);

        return json_decode($tool($action, ...$args), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em;
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    private function share(string $ref): PeerDebtShare
    {
        $share = $this->em()->find(PeerDebtShare::class, $this->getFixture($ref)->getId());
        self::assertNotNull($share);

        return $share;
    }

    private function transaction(string $ref): Transaction
    {
        $transaction = $this->em()->find(Transaction::class, $this->getFixture($ref)->getId());
        self::assertNotNull($transaction);

        return $transaction;
    }

    public function testWithoutAUserTheToolRefuses(): void
    {
        $this->loadFixtures(self::FIXTURES);

        $data = $this->call('allocate', transactionId: $this->id('julie_in'), kind: 'repayment', shareId: $this->id('gite_julie'), amountCents: 20000);

        self::assertArrayHasKey('error', $data);
        self::assertSame(0, $this->share('gite_julie')->getRepaidCents());
    }

    public function testAllocateStoresPublishesAndIndexes(): void
    {
        $this->login();

        $data = $this->call('allocate', transactionId: $this->id('julie_in'), kind: 'repayment', shareId: $this->id('gite_julie'), amountCents: 20000);

        self::assertTrue($data['success']);
        self::assertSame('repayment', $data['peerKind']);
        self::assertSame(0, $data['unallocatedCents']);
        self::assertSame(20000, $this->share('gite_julie')->getRepaidCents());
        self::assertSame('repayment', $this->transaction('julie_in')->getPeerKind()?->value);
        self::assertCount(1, $this->em()->getRepository(PeerDebtAllocation::class)->findAll());
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('julie_in'));
        $this->assertMercureUpdatePublished('/peer_debts/'.$this->id('gite_debt'));
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('julie_in'));
        $this->assertElasticsearchIndexDispatchedFor(PeerDebt::class, $this->id('gite_debt'));

        self::assertSame(20000 + 4500 + 9000, $this->call('summary')['owedToMeCents']);
    }

    public function testOneAllocationPerCallKeepsTheOthersAndZeroTakesOneOff(): void
    {
        $this->login();

        $this->call('allocate', transactionId: $this->id('tom_in'), kind: 'repayment', shareId: $this->id('gite_tom'), amountCents: 20000);
        $data = $this->call('allocate', transactionId: $this->id('tom_in'), kind: 'repayment', shareId: $this->id('birthday_tom'), amountCents: 4500);

        self::assertSame(24500, $data['allocatedCents']);
        self::assertSame(15500, $data['unallocatedCents']);
        self::assertSame(20000, $this->share('gite_tom')->getRepaidCents());
        self::assertSame(4500, $this->share('birthday_tom')->getRepaidCents());

        $data = $this->call('allocate', transactionId: $this->id('tom_in'), kind: 'repayment', shareId: $this->id('gite_tom'), amountCents: 0);

        self::assertSame(4500, $data['allocatedCents']);
        self::assertSame(0, $this->share('gite_tom')->getRepaidCents());

        // A null kind undoes the answer.
        $data = $this->call('allocate', transactionId: $this->id('tom_in'));

        self::assertTrue($data['success']);
        self::assertNull($data['peerKind']);
        self::assertSame(0, $this->share('birthday_tom')->getRepaidCents());
    }

    public function testASentTransferRepaysADetteOrLendsMoney(): void
    {
        $this->login();

        $data = $this->call('allocate', transactionId: $this->id('tom_out'), kind: 'repayment', shareId: $this->id('petrol_tom'), amountCents: 28000);
        self::assertSame('settled', $this->share('petrol_tom')->getPeerDebt()->getStatus()->value);
        self::assertSame(0, $data['unallocatedCents']);

        $data = $this->call('allocate', transactionId: $this->id('tom_out'), kind: 'new_claim', object: 'Prêt à Tom');
        self::assertSame('new_claim', $data['peerKind']);
        self::assertSame(28000, $data['peerDebt']['amountCents']);
        self::assertSame(0, $this->share('petrol_tom')->getRepaidCents());
    }

    public function testANewDetteFromAReceivedTransfer(): void
    {
        $this->login();

        $data = $this->call('allocate', transactionId: $this->id('tom_in'), kind: 'new_debt', amountCents: 28000);

        self::assertSame('new_debt', $data['peerKind']);
        self::assertSame('i_owe', $data['peerDebt']['direction']);
        self::assertSame(28000, $data['peerDebt']['amountCents']);
    }

    /** @return iterable<string, array{0: string, 1: array<string, mixed>}> */
    public static function badAllocations(): iterable
    {
        yield 'more than what is left' => ['tom_in', ['kind' => 'repayment', 'shareId' => 'birthday_tom', 'amountCents' => 4501]];
        yield 'an internal transfer' => ['from_savings', ['kind' => 'income']];
        yield 'a repayment without share' => ['tom_in', ['kind' => 'repayment', 'amountCents' => 100]];
        yield 'a repayment without amount' => ['tom_in', ['kind' => 'repayment', 'shareId' => 'gite_tom']];
        yield 'a sent kind on a received transfer' => ['tom_in', ['kind' => 'other']];
        yield 'an unknown kind' => ['tom_in', ['kind' => 'gift']];
        yield 'another user\'s share' => ['tom_in', ['kind' => 'repayment', 'shareId' => 'others_share', 'amountCents' => 100]];
    }

    /** @param array<string, mixed> $args */
    #[\PHPUnit\Framework\Attributes\DataProvider('badAllocations')]
    public function testABadAllocationIsRefused(string $transactionRef, array $args): void
    {
        $this->login();
        if (isset($args['shareId'])) {
            $args['shareId'] = $this->id($args['shareId']);
        }

        $data = $this->call('allocate', ...['transactionId' => $this->id($transactionRef), ...$args]);

        self::assertArrayHasKey('error', $data);
        self::assertNull($this->transaction($transactionRef)->getPeerKind());
        self::assertCount(0, $this->em()->getRepository(PeerDebtAllocation::class)->findAll());
    }

    public function testAllocateNeedsAKnownTransaction(): void
    {
        $this->login();

        self::assertArrayHasKey('error', $this->call('allocate', kind: 'income'));
        self::assertArrayHasKey('error', $this->call('allocate', transactionId: '01JZZZZZZZZZZZZZZZZZZZZZZZ', kind: 'income'));
    }

    public function testManageTransactionsGivesThePeerKindAndTheToSortReason(): void
    {
        $this->login();
        $this->call('allocate', transactionId: $this->id('julie_in'), kind: 'repayment', shareId: $this->id('gite_julie'), amountCents: 20000);
        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        $data = json_decode($tool('list', accountId: $this->id('checking')), true, 512, JSON_THROW_ON_ERROR);
        $byLabel = array_column($data['transactions'], null, 'label');
        self::assertSame('repayment', $byLabel['VIR DE JULIE ROUX']['peerKind']);
        self::assertSame(20000, $byLabel['VIR DE JULIE ROUX']['peerAllocatedCents']);
        self::assertNull($byLabel['VIR DE TOM MARTIN']['peerKind']);

        $pile = json_decode($tool('to_sort'), true, 512, JSON_THROW_ON_ERROR);
        $byId = array_column($pile['transactions'], null, 'id');
        self::assertSame('peer_transfer', $byId[$this->id('tom_in')]['reason']);
        self::assertTrue($byId[$this->id('tom_in')]['asksForPeerKind']);
    }
}
