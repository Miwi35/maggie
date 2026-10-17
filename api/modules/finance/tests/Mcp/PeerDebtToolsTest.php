<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Mcp\Tool\ManagePeerDebtsTool;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** `manage_peer_debts` (MAG-451): the same share and créances as the my-share endpoint. */
class PeerDebtToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    private const FIXTURES = __DIR__.'/../Api/fixtures/peer_debts.yaml';

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

    private function loginWithReceivedTransfers(): void
    {
        $this->loadFixtures(self::FIXTURES, __DIR__.'/../Api/fixtures/peer_debts_received.yaml');
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

    private function debtOf(string $transactionRef): ?PeerDebt
    {
        return $this->em()->getRepository(PeerDebt::class)->findOneBy(['originTransaction' => $this->getFixture($transactionRef)->getId()]);
    }

    /** @return list<array{contactId: string, amountCents?: int}> */
    private function people(string ...$refs): array
    {
        return array_map(fn (string $ref): array => ['contactId' => $this->id($ref)], array_values($refs));
    }

    public function testWithoutAUserTheToolRefuses(): void
    {
        $this->loadFixtures(self::FIXTURES);

        self::assertArrayHasKey('error', $this->call('summary'));
        self::assertArrayHasKey('error', $this->call('set_my_share', transactionId: $this->id('gite'), myShareCents: 20000, shares: $this->people('tom')));
    }

    public function testSetMyShareStoresPublishesAndIndexes(): void
    {
        $this->login();

        $data = $this->call('set_my_share', transactionId: $this->id('gite'), myShareCents: 20000, shares: $this->people('julie', 'tom', 'ines'), object: 'Gîte Bretagne');

        self::assertTrue($data['success']);
        self::assertSame(60000, $data['peerDebt']['amountCents']);
        self::assertSame([20000, 20000, 20000], array_column($data['peerDebt']['shares'], 'amountCents'));

        self::assertSame(20000, $this->em()->find(Transaction::class, $this->getFixture('gite')->getId())?->getMyShareCents());
        $debt = $this->debtOf('gite');
        self::assertNotNull($debt);
        self::assertSame('Gîte Bretagne', $debt->getObject());
        self::assertSame(60000, $debt->getAmountCents());

        $this->assertMercureUpdatePublished('/transactions/'.$this->id('gite'));
        $this->assertMercureUpdatePublished('/peer_debts/'.$debt->getId());
        $this->assertElasticsearchIndexDispatchedFor(PeerDebt::class, (string) $debt->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('gite'));

        self::assertSame(['owedToMeCents' => 60000 + 5000, 'iOweCents' => 0], $this->call('summary'));
    }

    /** @return iterable<string, array{0: string, 1: int, 2: list<array{0: string, 1?: int}>}> */
    public static function badShares(): iterable
    {
        yield 'an income' => ['salary', 1000, [['julie']]];
        yield 'my part as much as the spend' => ['gite', 80000, [['julie']]];
        yield 'another user\'s contact' => ['gite', 20000, [['others_contact']]];
        yield 'a wrong sum' => ['gite', 20000, [['julie', 30000], ['tom', 20000], ['ines', 20000]]];
        yield 'nobody' => ['gite', 20000, []];
    }

    /** @param list<array{0: string, 1?: int}> $people */
    #[\PHPUnit\Framework\Attributes\DataProvider('badShares')]
    public function testABadShareIsRefused(string $transactionRef, int $myShareCents, array $people): void
    {
        $this->login();

        $data = $this->call('set_my_share', transactionId: $this->id($transactionRef), myShareCents: $myShareCents, shares: array_map(
            fn (array $person): array => ['contactId' => $this->id($person[0])] + (isset($person[1]) ? ['amountCents' => $person[1]] : []),
            $people,
        ));

        self::assertArrayHasKey('error', $data);
        self::assertNull($this->debtOf($transactionRef));
        $this->assertMercureUpdateCount(0);
    }

    public function testANullPartRemovesTheShare(): void
    {
        $this->login();
        $this->call('set_my_share', transactionId: $this->id('gite'), myShareCents: 20000, shares: $this->people('julie', 'tom'));

        $data = $this->call('set_my_share', transactionId: $this->id('gite'), myShareCents: null);

        self::assertTrue($data['success']);
        self::assertNull($data['myShareCents']);
        self::assertNull($this->debtOf('gite'));
        self::assertNull($this->em()->find(Transaction::class, $this->getFixture('gite')->getId())?->getMyShareCents());
    }

    public function testListKeepsThoseOfOnePersonOrOfAStatus(): void
    {
        $this->login();
        $this->call('set_my_share', transactionId: $this->id('gite'), myShareCents: 20000, shares: $this->people('julie', 'ines'));

        self::assertSame(['GITE DES FALAISES', 'Dîner au Phare'], array_column($this->call('list')['peerDebts'], 'object'));
        self::assertSame(['Dîner au Phare'], array_column($this->call('list', contactId: $this->id('tom'))['peerDebts'], 'object'));
        self::assertSame([], $this->call('list', status: 'settled')['peerDebts']);
        self::assertArrayHasKey('error', $this->call('list', contactId: $this->id('others_contact')));
    }

    public function testManageTransactionsGivesMyShare(): void
    {
        $this->login();
        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        $data = json_decode($tool('list', accountId: $this->id('checking')), true, 512, JSON_THROW_ON_ERROR);

        $byLabel = array_column($data['transactions'], null, 'label');
        self::assertSame(3000, $byLabel['RESTAURANT LE PHARE']['myShareCents']);
        self::assertNull($byLabel['GITE DES FALAISES']['myShareCents']);
    }

    // --- record_debt (MAG-452) -----------------------------------------------

    public function testRecordDebtStoresPublishesAndIndexes(): void
    {
        $this->loginWithReceivedTransfers();

        $data = $this->call('record_debt', transactionId: $this->id('tom_transfer'), contactId: $this->id('tom'), amountCents: 28000, object: 'Avance gîte');

        self::assertTrue($data['success']);
        self::assertSame('i_owe', $data['peerDebt']['direction']);
        self::assertSame(28000, $data['peerDebt']['amountCents']);
        self::assertSame(['Tom'], array_column($data['peerDebt']['shares'], 'contactName'));

        $debt = $this->debtOf('tom_transfer');
        self::assertNotNull($debt);
        self::assertSame('Avance gîte', $debt->getObject());
        self::assertSame(28000, $debt->getAmountCents());
        $this->assertMercureUpdatePublished('/peer_debts/'.$debt->getId());
        $this->assertElasticsearchIndexDispatchedFor(PeerDebt::class, (string) $debt->getId());

        // Julie's dette (100 € left) is in the fixtures too.
        self::assertSame(38000, $this->call('summary')['iOweCents']);
    }

    public function testRecordDebtAgainUpdatesAndANullAmountRemoves(): void
    {
        $this->loginWithReceivedTransfers();
        $this->call('record_debt', transactionId: $this->id('tom_transfer'), contactId: $this->id('tom'), amountCents: 28000);
        $first = $this->debtOf('tom_transfer');

        $this->call('record_debt', transactionId: $this->id('tom_transfer'), contactId: $this->id('tom'), amountCents: 30000);
        self::assertSame((string) $first?->getId(), (string) $this->debtOf('tom_transfer')?->getId());
        self::assertSame(30000, $this->debtOf('tom_transfer')?->getAmountCents());

        $data = $this->call('record_debt', transactionId: $this->id('tom_transfer'), amountCents: null);
        self::assertTrue($data['success']);
        self::assertNull($data['peerDebt']);
        self::assertNull($this->debtOf('tom_transfer'));
    }

    /** @return iterable<string, array{0: string, 1: ?string, 2: int}> */
    public static function badDebts(): iterable
    {
        yield 'more than the transfer' => ['tom_transfer', 'tom', 45000];
        yield 'nothing' => ['tom_transfer', 'tom', 0];
        yield 'a spend' => ['gite', 'tom', 10000];
        yield 'an internal transfer' => ['from_savings', 'tom', 10000];
        yield 'another user\'s contact' => ['tom_transfer', 'others_contact', 10000];
        yield 'no contact' => ['tom_transfer', null, 10000];
        yield 'a dette partly given back, made smaller' => ['julie_transfer', 'julie', 4000];
    }

    #[\PHPUnit\Framework\Attributes\DataProvider('badDebts')]
    public function testABadDebtIsRefused(string $transactionRef, ?string $contactRef, int $amountCents): void
    {
        $this->loginWithReceivedTransfers();
        $before = $this->debtOf($transactionRef)?->getAmountCents();

        $data = $this->call('record_debt', transactionId: $this->id($transactionRef), contactId: null === $contactRef ? null : $this->id($contactRef), amountCents: $amountCents);

        self::assertArrayHasKey('error', $data);
        self::assertSame($before, $this->debtOf($transactionRef)?->getAmountCents());
        $this->assertMercureUpdateCount(0);
    }

    public function testRecordDebtNeedsAUserAndATransaction(): void
    {
        $this->loadFixtures(self::FIXTURES, __DIR__.'/../Api/fixtures/peer_debts_received.yaml');
        self::assertArrayHasKey('error', $this->call('record_debt', transactionId: $this->id('tom_transfer'), contactId: $this->id('tom'), amountCents: 1000));

        $this->loginFixtureUser();
        self::assertArrayHasKey('error', $this->call('record_debt', contactId: $this->id('tom'), amountCents: 1000));
        self::assertArrayHasKey('error', $this->call('record_debt', transactionId: $this->id('julie_transfer'), amountCents: null));
    }
}
