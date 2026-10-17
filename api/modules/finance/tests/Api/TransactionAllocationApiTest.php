<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\PeerDebtAllocation;
use Maggie\Finance\Entity\PeerDebtShare;
use Maggie\Finance\Entity\Transaction;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * « Ce virement est… » (MAG-453): a received transfer is an income, the
 * repayment of créances — what is not allocated stays an income — or a new
 * dette; a sent one the repayment of dettes, a new créance or anything else.
 * An allocated part is neither an income nor a spend.
 */
class TransactionAllocationApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @param array<string, mixed>|string|null $body */
    private function call(string $method, string $uri, array|string|null $body = null, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request(
            $method,
            $uri,
            [],
            [],
            $authenticated ? array_merge($headers, $this->authHeaders()) : $headers,
            \is_array($body) ? json_encode($body, JSON_THROW_ON_ERROR) : $body,
        );
    }

    /** @return array<string, mixed> */
    private function response(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function login(): void
    {
        $this->loadFixtures('peer_allocations.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
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

    private function transaction(string $ref): Transaction
    {
        $transaction = $this->em()->find(Transaction::class, $this->getFixture($ref)->getId());
        self::assertNotNull($transaction);

        return $transaction;
    }

    private function share(string $ref): PeerDebtShare
    {
        $share = $this->em()->find(PeerDebtShare::class, $this->getFixture($ref)->getId());
        self::assertNotNull($share);

        return $share;
    }

    /** @param array<string, mixed> $body */
    private function allocate(string $transactionRef, array $body): void
    {
        $this->call('PUT', '/api/finance/transactions/'.$this->id($transactionRef).'/allocation', $body);
    }

    /** @param array<string, int> $amountsByShareRef */
    private function repay(string $transactionRef, array $amountsByShareRef): void
    {
        $allocations = [];
        foreach ($amountsByShareRef as $shareRef => $amountCents) {
            $allocations[] = ['shareId' => $this->id($shareRef), 'amountCents' => $amountCents];
        }
        $this->allocate($transactionRef, ['kind' => 'repayment', 'allocations' => $allocations]);
    }

    /** @return array{incomeCents: int, expenseCents: int} July 2026, as the dashboard draws it */
    private function julyFlows(): array
    {
        $this->call('GET', '/api/finance/dashboard?year=2026&month=7');
        self::assertResponseIsSuccessful();
        $byMonth = array_column($this->response()['monthlyFlows'], null, 'month');

        return $byMonth['2026-07'];
    }

    private function owedToMe(): int
    {
        $this->call('GET', '/api/finance/peer-debts/summary');
        self::assertResponseIsSuccessful();

        return $this->response()['owedToMeCents'];
    }

    private function travelSpentInJuly(): int
    {
        $this->call('GET', '/api/finance/budget-status?year=2026&month=7');
        self::assertResponseIsSuccessful();
        $byCategory = array_column($this->response()['budgets'], null, 'categoryName');

        return $byCategory['Voyages']['spentCents'];
    }

    // --- Authentication -------------------------------------------------------

    public function testTheAllocationRequiresAuthentication(): void
    {
        $this->loadFixtures('peer_allocations.yaml');
        $uri = '/api/finance/transactions/'.$this->id('julie_in').'/allocation';

        $this->call('GET', $uri, authenticated: false);
        self::assertResponseStatusCodeSame(401);
        $this->call('PUT', $uri, ['kind' => 'income'], authenticated: false);
        self::assertResponseStatusCodeSame(401);
        $this->call('DELETE', $uri, authenticated: false);
        self::assertResponseStatusCodeSame(401);
    }

    // --- Acceptance criteria ---------------------------------------------------

    public function testJulieRepaysHerShareOfTheGite(): void
    {
        $this->login();
        $owedBefore = $this->owedToMe();
        $incomeBefore = $this->julyFlows()['incomeCents'];

        $this->repay('julie_in', ['gite_julie' => 20000]);

        self::assertResponseIsSuccessful();
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('julie_in'));
        $this->assertMercureUpdatePublished('/peer_debts/'.$this->id('gite_debt'));
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('julie_in'));
        $this->assertElasticsearchIndexDispatchedFor(PeerDebt::class, $this->id('gite_debt'));
        $body = $this->response();
        self::assertSame('repayment', $body['peerKind']);
        self::assertSame(20000, $body['allocatedCents']);
        self::assertSame(0, $body['unallocatedCents']);
        self::assertSame([['shareId' => $this->id('gite_julie'), 'peerDebtId' => $this->id('gite_debt'), 'amountCents' => 20000]], $body['allocations']);

        $share = $this->share('gite_julie');
        self::assertSame(20000, $share->getRepaidCents());
        self::assertSame(0, $share->getRemainingCents());
        // Tom still owes his 200 €: the gîte stays open.
        self::assertSame('open', $share->getPeerDebt()->getStatus()->value);
        $transaction = $this->transaction('julie_in');
        self::assertSame('repayment', $transaction->getPeerKind()?->value);
        self::assertSame(20000, $transaction->getPeerAllocatedCents());
        $allocations = $this->em()->getRepository(PeerDebtAllocation::class)->findBy(['transaction' => $transaction->getId()]);
        self::assertCount(1, $allocations);
        self::assertSame(20000, $allocations[0]->getAmountCents());

        self::assertSame($owedBefore - 20000, $this->owedToMe());
        // Money given back is no income.
        self::assertSame($incomeBefore - 20000, $this->julyFlows()['incomeCents']);
    }

    public function testTomCoversTwoCreancesAndTheRestStaysAnIncome(): void
    {
        $this->login();
        $incomeBefore = $this->julyFlows()['incomeCents'];

        $this->repay('tom_in', ['gite_tom' => 20000, 'birthday_tom' => 4500]);

        self::assertResponseIsSuccessful();
        self::assertSame(24500, $this->response()['allocatedCents']);
        self::assertSame(15500, $this->response()['unallocatedCents']);
        self::assertSame(0, $this->share('gite_tom')->getRemainingCents());
        self::assertSame('settled', $this->share('birthday_tom')->getPeerDebt()->getStatus()->value);
        self::assertSame($incomeBefore - 24500, $this->julyFlows()['incomeCents']);
    }

    public function testLucasRepaysPartOfHellfest(): void
    {
        $this->login();

        $this->repay('lucas_in', ['hellfest_lucas' => 5000]);

        self::assertResponseIsSuccessful();
        $share = $this->share('hellfest_lucas');
        self::assertSame(5000, $share->getRepaidCents());
        self::assertSame(4000, $share->getRemainingCents());
        self::assertSame('open', $share->getPeerDebt()->getStatus()->value);
    }

    public function testSendingMoneyToTomSettlesTheDetteAndLeavesTheEnvelopes(): void
    {
        $this->login();
        self::assertSame(28000, $this->travelSpentInJuly());
        $expenseBefore = $this->julyFlows()['expenseCents'];

        $this->repay('tom_out', ['petrol_tom' => 28000]);

        self::assertResponseIsSuccessful();
        self::assertSame('outgoing', $this->response()['direction']);
        self::assertSame('settled', $this->share('petrol_tom')->getPeerDebt()->getStatus()->value);
        self::assertSame(0, $this->travelSpentInJuly());
        self::assertSame($expenseBefore - 28000, $this->julyFlows()['expenseCents']);
        $this->call('GET', '/api/finance/peer-debts/summary');
        self::assertSame(0, $this->response()['iOweCents']);
    }

    // --- Refusals -------------------------------------------------------------

    public function testAllocatingMoreThanWhatIsLeftIsRefused(): void
    {
        $this->login();

        $this->repay('tom_in', ['birthday_tom' => 4501]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('4500', $this->response()['error']);
        self::assertSame(0, $this->share('birthday_tom')->getRepaidCents());
        self::assertNull($this->transaction('tom_in')->getPeerKind());
    }

    public function testAnInternalTransferCannotBeAllocated(): void
    {
        $this->login();

        $this->allocate('from_savings', ['kind' => 'income']);
        self::assertResponseStatusCodeSame(400);

        $this->call('GET', '/api/finance/transactions/'.$this->id('from_savings').'/allocation');
        self::assertFalse($this->response()['canBeAllocated']);
        self::assertSame([], $this->response()['candidates']);
    }

    /** @return iterable<string, array{0: string, 1: array<string, mixed>|string}> */
    public static function badAllocations(): iterable
    {
        yield 'no kind' => ['tom_in', []];
        yield 'an unknown kind' => ['tom_in', ['kind' => 'gift']];
        yield 'a sent kind on a received transfer' => ['tom_in', ['kind' => 'new_claim', 'claim' => []]];
        yield 'a received kind on a sent transfer' => ['tom_out', ['kind' => 'income']];
        yield 'a repayment without allocations' => ['tom_in', ['kind' => 'repayment', 'allocations' => []]];
        yield 'allocations on an income' => ['tom_in', ['kind' => 'income', 'allocations' => [['shareId' => 'gite_tom', 'amountCents' => 100]]]];
        yield 'a dette given back by a received transfer' => ['tom_in', ['kind' => 'repayment', 'allocations' => [['shareId' => 'petrol_tom', 'amountCents' => 100]]]];
        yield 'a créance given back by a sent transfer' => ['tom_out', ['kind' => 'repayment', 'allocations' => [['shareId' => 'gite_tom', 'amountCents' => 100]]]];
        yield 'another user\'s share' => ['tom_in', ['kind' => 'repayment', 'allocations' => [['shareId' => 'others_share', 'amountCents' => 100]]]];
        yield 'an unknown share' => ['tom_in', ['kind' => 'repayment', 'allocations' => [['shareId' => '01JZZZZZZZZZZZZZZZZZZZZZZZ', 'amountCents' => 100]]]];
        yield 'the same share twice' => ['tom_in', ['kind' => 'repayment', 'allocations' => [['shareId' => 'gite_tom', 'amountCents' => 100], ['shareId' => 'gite_tom', 'amountCents' => 100]]]];
        yield 'nothing on a share' => ['tom_in', ['kind' => 'repayment', 'allocations' => [['shareId' => 'gite_tom', 'amountCents' => 0]]]];
        yield 'more than the transfer' => ['lucas_in', ['kind' => 'repayment', 'allocations' => [['shareId' => 'hellfest_lucas', 'amountCents' => 5001]]]];
        yield 'an amount that is not an integer' => ['tom_in', ['kind' => 'repayment', 'allocations' => [['shareId' => 'gite_tom', 'amountCents' => '100']]]];
        yield 'allocations that are not a list' => ['tom_in', ['kind' => 'repayment', 'allocations' => 'gite']];
        yield 'a new dette without its debt' => ['tom_in', ['kind' => 'new_debt']];
        yield 'a new dette above the transfer' => ['tom_in', ['kind' => 'new_debt', 'debt' => ['amountCents' => 40001]]];
        yield 'a new dette without amount' => ['tom_in', ['kind' => 'new_debt', 'debt' => ['object' => 'Avance']]];
        yield 'a new créance without its claim' => ['tom_out', ['kind' => 'new_claim']];
        yield 'not JSON' => ['tom_in', '{oops'];
    }

    /** @param array<string, mixed>|string $body */
    #[\PHPUnit\Framework\Attributes\DataProvider('badAllocations')]
    public function testABadAllocationIsRefused(string $transactionRef, array|string $body): void
    {
        $this->login();
        if (\is_array($body) && \is_array($body['allocations'] ?? null)) {
            foreach ($body['allocations'] as $i => $allocation) {
                if (\is_array($allocation) && !str_starts_with($allocation['shareId'], '01')) {
                    $body['allocations'][$i]['shareId'] = $this->id($allocation['shareId']);
                }
            }
        }

        $this->call('PUT', '/api/finance/transactions/'.$this->id($transactionRef).'/allocation', $body);

        self::assertResponseStatusCodeSame(400);
        self::assertArrayHasKey('error', $this->response());
        self::assertNull($this->transaction($transactionRef)->getPeerKind());
        self::assertCount(0, $this->em()->getRepository(PeerDebtAllocation::class)->findAll());
    }

    public function testAnotherUsersTransactionIsNotFound(): void
    {
        $this->login();

        $this->call('PUT', '/api/finance/transactions/01JZZZZZZZZZZZZZZZZZZZZZZZ/allocation', ['kind' => 'income']);

        self::assertResponseStatusCodeSame(404);
    }

    // --- Changing or removing the answer ---------------------------------------

    public function testChangingTheAllocationGivesBackWhatTheDroppedSharesWereRepaid(): void
    {
        $this->login();
        $this->repay('tom_in', ['gite_tom' => 20000, 'birthday_tom' => 4500]);
        $this->resetMercure();

        $this->repay('tom_in', ['gite_tom' => 15000]);

        self::assertResponseIsSuccessful();
        self::assertSame(15000, $this->share('gite_tom')->getRepaidCents());
        self::assertSame(0, $this->share('birthday_tom')->getRepaidCents());
        self::assertSame(15000, $this->transaction('tom_in')->getPeerAllocatedCents());
        self::assertCount(1, $this->em()->getRepository(PeerDebtAllocation::class)->findAll());
        $this->assertMercureUpdatePublished('/peer_debts/'.$this->id('birthday_debt'));
    }

    public function testAnAllocationCanBeRaisedUpToWhatItAlreadyCovers(): void
    {
        $this->login();
        $this->repay('lucas_in', ['hellfest_lucas' => 3000]);

        // 60 € are left on the share, plus the 30 € this transfer already covers.
        $this->repay('lucas_in', ['hellfest_lucas' => 5000]);

        self::assertResponseIsSuccessful();
        self::assertSame(5000, $this->share('hellfest_lucas')->getRepaidCents());
    }

    public function testRemovingTheAllocationPutsTheDebtBack(): void
    {
        $this->login();
        $this->repay('julie_in', ['gite_julie' => 20000]);
        $this->resetMercure();

        $this->call('DELETE', '/api/finance/transactions/'.$this->id('julie_in').'/allocation');

        self::assertResponseIsSuccessful();
        self::assertNull($this->response()['peerKind']);
        self::assertSame(0, $this->share('gite_julie')->getRepaidCents());
        self::assertSame(0, $this->transaction('julie_in')->getPeerAllocatedCents());
        self::assertNull($this->transaction('julie_in')->getPeerKind());
        self::assertCount(0, $this->em()->getRepository(PeerDebtAllocation::class)->findAll());
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('julie_in'));
        $this->assertMercureUpdatePublished('/peer_debts/'.$this->id('gite_debt'));
    }

    public function testAnIncomeGivesBackNothing(): void
    {
        $this->login();
        $this->repay('tom_in', ['gite_tom' => 20000]);
        $incomeWithRepayment = $this->julyFlows()['incomeCents'];

        $this->allocate('tom_in', ['kind' => 'income']);

        self::assertResponseIsSuccessful();
        self::assertSame('income', $this->transaction('tom_in')->getPeerKind()?->value);
        self::assertSame(0, $this->share('gite_tom')->getRepaidCents());
        self::assertSame($incomeWithRepayment + 20000, $this->julyFlows()['incomeCents']);
    }

    public function testANewDetteFromAReceivedTransferAndBackToARepayment(): void
    {
        $this->login();

        $this->allocate('tom_in', ['kind' => 'new_debt', 'debt' => ['amountCents' => 28000, 'object' => 'Avance pour le gîte']]);

        self::assertResponseIsSuccessful();
        self::assertSame('new_debt', $this->response()['peerKind']);
        self::assertSame('i_owe', $this->response()['peerDebt']['direction']);
        // The transfer's contact is the natural one.
        self::assertSame(['Tom'], array_column($this->response()['peerDebt']['shares'], 'contactName'));
        $debtId = $this->response()['peerDebt']['id'];

        $this->repay('tom_in', ['gite_tom' => 20000]);

        self::assertResponseIsSuccessful();
        self::assertNull($this->response()['peerDebt']);
        self::assertNull($this->em()->find(PeerDebt::class, $debtId));
    }

    public function testANewCreanceFromASentTransferLeavesTheEnvelopes(): void
    {
        $this->login();

        $this->allocate('tom_out', ['kind' => 'new_claim', 'claim' => ['object' => 'Prêt à Tom']]);

        self::assertResponseIsSuccessful();
        $body = $this->response();
        self::assertSame('new_claim', $body['peerKind']);
        self::assertSame('owed_to_me', $body['peerDebt']['direction']);
        self::assertSame(28000, $body['peerDebt']['amountCents']);
        self::assertSame('Prêt à Tom', $body['peerDebt']['object']);
        self::assertSame(0, $this->transaction('tom_out')->getMyShareCents());
        self::assertSame(0, $this->travelSpentInJuly());

        // « Autre »: a plain spend again, its créance goes.
        $this->allocate('tom_out', ['kind' => 'other']);

        self::assertResponseIsSuccessful();
        self::assertNull($this->response()['peerDebt']);
        self::assertNull($this->transaction('tom_out')->getMyShareCents());
        self::assertSame(28000, $this->travelSpentInJuly());
    }

    public function testADetteSomeoneGaveBackOnCannotBecomeSomethingElse(): void
    {
        $this->login();
        $this->allocate('tom_in', ['kind' => 'new_debt', 'debt' => ['amountCents' => 28000]]);
        $shareId = $this->response()['peerDebt']['shares'][0]['id'];
        $this->allocate('tom_out', ['kind' => 'repayment', 'allocations' => [['shareId' => $shareId, 'amountCents' => 10000]]]);
        self::assertResponseIsSuccessful();

        $this->allocate('tom_in', ['kind' => 'income']);

        self::assertResponseStatusCodeSame(400);
        self::assertSame('new_debt', $this->transaction('tom_in')->getPeerKind()?->value);
    }

    public function testDeletingAnAllocatedTransferPutsTheDebtBack(): void
    {
        $this->login();
        $this->repay('lucas_in', ['hellfest_lucas' => 5000]);

        $this->call('DELETE', '/api/transactions/'.$this->id('lucas_in'));

        self::assertResponseStatusCodeSame(204);
        self::assertSame(0, $this->share('hellfest_lucas')->getRepaidCents());
        self::assertCount(0, $this->em()->getRepository(PeerDebtAllocation::class)->findAll());
    }

    public function testAnAllocatedTransferCannotBecomeAnInternalTransfer(): void
    {
        $this->login();
        $this->repay('lucas_in', ['hellfest_lucas' => 5000]);

        $this->call('PUT', '/api/finance/transactions/'.$this->id('lucas_in').'/transfer', ['transferKind' => 'internal']);

        self::assertResponseStatusCodeSame(400);
        self::assertSame('none', $this->transaction('lucas_in')->getTransferKind()->value);
    }

    // --- Reading ---------------------------------------------------------------

    public function testTheCandidatesAreTheContactsCreancesFirst(): void
    {
        $this->login();

        $this->call('GET', '/api/finance/transactions/'.$this->id('lucas_in').'/allocation');

        self::assertResponseIsSuccessful();
        $body = $this->response();
        self::assertSame('incoming', $body['direction']);
        self::assertTrue($body['canBeAllocated']);
        self::assertNull($body['peerKind']);
        self::assertSame(['id' => $this->id('lucas'), 'name' => 'Lucas'], $body['contact']);
        self::assertSame(5000, $body['unallocatedCents']);
        // Lucas's Hellfest first, then every other open créance; never a dette nor another user's.
        self::assertSame(
            [$this->id('hellfest_lucas'), $this->id('gite_julie'), $this->id('gite_tom'), $this->id('birthday_tom')],
            array_column($body['candidates'], 'shareId'),
        );
        self::assertSame([true, false, false, false], array_column($body['candidates'], 'fromContact'));
        self::assertSame(9000, $body['candidates'][0]['availableCents']);
        self::assertSame('Hellfest', $body['candidates'][0]['object']);
    }

    public function testASettledShareStaysAmongTheCandidatesOfTheTransferThatSettledIt(): void
    {
        $this->login();
        $this->repay('julie_in', ['gite_julie' => 20000]);

        $this->call('GET', '/api/finance/transactions/'.$this->id('julie_in').'/allocation');
        $byShare = array_column($this->response()['candidates'], null, 'shareId');
        self::assertSame(0, $byShare[$this->id('gite_julie')]['remainingCents']);
        self::assertSame(20000, $byShare[$this->id('gite_julie')]['allocatedCents']);
        self::assertSame(20000, $byShare[$this->id('gite_julie')]['availableCents']);

        // Another transfer no longer sees it.
        $this->call('GET', '/api/finance/transactions/'.$this->id('tom_in').'/allocation');
        self::assertNotContains($this->id('gite_julie'), array_column($this->response()['candidates'], 'shareId'));
    }

    public function testThePeerKindIsReadWithTheTransaction(): void
    {
        $this->login();
        $this->repay('julie_in', ['gite_julie' => 20000]);

        $this->call('GET', '/api/transactions/'.$this->id('julie_in'));

        self::assertResponseIsSuccessful();
        self::assertSame('repayment', $this->response()['peerKind']);
        self::assertSame(20000, $this->response()['peerAllocatedCents']);
    }

    // --- « À trier » ------------------------------------------------------------

    public function testATransferWithAContactWaitsInToSortUntilToldApart(): void
    {
        $this->login();

        $this->call('GET', '/api/finance/transactions/to-sort');
        self::assertResponseIsSuccessful();
        $byId = array_column($this->response()['transactions'], null, 'id');
        // Tom's 400 € have a category, but nobody said what they are.
        self::assertArrayHasKey($this->id('tom_in'), $byId);
        self::assertSame('peer_transfer', $byId[$this->id('tom_in')]['reason']);
        self::assertTrue($byId[$this->id('tom_in')]['asksForPeerKind']);
        // Lucas's have no category: the category comes first, the question too.
        self::assertSame('category', $byId[$this->id('lucas_in')]['reason']);
        self::assertTrue($byId[$this->id('lucas_in')]['asksForPeerKind']);
        self::assertArrayNotHasKey($this->id('from_savings'), $byId);
        $total = $this->response()['total'];

        $this->repay('tom_in', ['gite_tom' => 20000]);

        $this->call('GET', '/api/finance/transactions/to-sort');
        self::assertSame($total - 1, $this->response()['total']);
        self::assertNotContains($this->id('tom_in'), array_column($this->response()['transactions'], 'id'));
    }
}
