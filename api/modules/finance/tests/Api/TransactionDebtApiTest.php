<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\PeerDebtShare;
use Maggie\Finance\Entity\Transaction;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * « À rembourser » on a received transfer (MAG-452): Tom advances 400 €, 280 €
 * of it are to give back. The amount becomes a dette (`i_owe`, one share) and
 * leaves the incomes; the rest of the transfer stays an income.
 */
class TransactionDebtApiTest extends WebTestCase
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
        $this->loadFixtures('peer_debts.yaml', 'peer_debts_received.yaml');
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

    private function debtOf(string $transactionRef): ?PeerDebt
    {
        return $this->em()->getRepository(PeerDebt::class)->findOneBy(['originTransaction' => $this->getFixture($transactionRef)->getId()]);
    }

    /** @param array<string, mixed> $extra */
    private function owe(string $transactionRef, int $amountCents, string $contactRef = 'tom', array $extra = []): void
    {
        $this->call('POST', '/api/finance/transactions/'.$this->id($transactionRef).'/debt', [
            'contactId' => $this->id($contactRef),
            'amountCents' => $amountCents,
            ...$extra,
        ]);
    }

    /** @return array{incomeCents: int, expenseCents: int} July 2026, as the dashboard draws it */
    private function julyFlows(): array
    {
        $this->call('GET', '/api/finance/dashboard?year=2026&month=7');
        self::assertResponseIsSuccessful();
        $byMonth = array_column($this->response()['monthlyFlows'], null, 'month');

        return $byMonth['2026-07'];
    }

    // --- Authentication -------------------------------------------------------

    public function testDeclaringADebtRequiresAuthentication(): void
    {
        $this->loadFixtures('peer_debts.yaml', 'peer_debts_received.yaml');

        $this->call('POST', '/api/finance/transactions/'.$this->id('tom_transfer').'/debt', ['contactId' => $this->id('tom'), 'amountCents' => 28000], authenticated: false);
        self::assertResponseStatusCodeSame(401);

        $this->call('GET', '/api/finance/transactions/'.$this->id('tom_transfer').'/debt', authenticated: false);
        self::assertResponseStatusCodeSame(401);

        $this->call('DELETE', '/api/finance/transactions/'.$this->id('tom_transfer').'/debt', authenticated: false);
        self::assertResponseStatusCodeSame(401);
    }

    // --- Tom advances 400 €, 280 € to give back --------------------------------

    public function testATransferToGiveBackBecomesADebtAndOnlyTheRestIsIncome(): void
    {
        $this->login();
        $before = $this->julyFlows();

        $this->owe('tom_transfer', 28000, extra: ['object' => 'Avance pour le gîte', 'notes' => 'À rendre en août']);

        self::assertResponseStatusCodeSame(201);
        $body = $this->response();
        self::assertSame(40000, $body['amountCents']);
        self::assertTrue($body['canBeDebt']);
        self::assertSame('i_owe', $body['peerDebt']['direction']);
        self::assertSame(28000, $body['peerDebt']['amountCents']);
        self::assertSame(28000, $body['peerDebt']['remainingCents']);
        self::assertSame('open', $body['peerDebt']['status']);
        self::assertSame(['Tom'], array_column($body['peerDebt']['shares'], 'contactName'));

        $debt = $this->debtOf('tom_transfer');
        self::assertNotNull($debt);
        self::assertSame('i_owe', $debt->getDirection()->value);
        self::assertSame('Avance pour le gîte', $debt->getObject());
        self::assertSame('À rendre en août', $debt->getNotes());
        self::assertCount(1, $debt->getShares());
        self::assertSame(28000, $debt->getShares()[0]->getAmountCents());
        self::assertSame(0, $debt->getShares()[0]->getRepaidCents());
        self::assertSame($this->id('tom'), $debt->getShares()[0]->getContactId());
        self::assertTrue($debt->getUser()->getId()->equals($this->getFixture('test_user')->getId()));
        // The transfer itself is untouched: still +400 € on the statement.
        self::assertSame(40000, $this->em()->find(Transaction::class, $this->getFixture('tom_transfer')->getId())?->getAmountCents());

        $this->assertMercureUpdatePublished((string) $debt->getId());
        $this->assertElasticsearchIndexDispatchedFor(PeerDebt::class, (string) $debt->getId());

        // 120 € of the 400 € are income: the 280 € to give back are not.
        self::assertSame($before['incomeCents'] - 28000, $this->julyFlows()['incomeCents']);
        self::assertSame($before['expenseCents'], $this->julyFlows()['expenseCents']);

        // Julie's dette (100 € left) is in the fixtures: Tom's 280 € come on top.
        $this->call('GET', '/api/finance/peer-debts/summary');
        self::assertSame(28000 + 10000, $this->response()['iOweCents']);
    }

    public function testTheSavingCapacityIsNotMovedByADebt(): void
    {
        $this->login();
        $this->call('GET', '/api/finance/debt-timeline');
        $before = $this->response()['savingCapacity'];

        $this->owe('tom_transfer', 28000);
        self::assertResponseStatusCodeSame(201);

        $this->call('GET', '/api/finance/debt-timeline');
        self::assertSame($before, $this->response()['savingCapacity']);
    }

    public function testDeclaringItAgainUpdatesTheSameDebtAndTheContactMayChange(): void
    {
        $this->login();
        $this->owe('tom_transfer', 28000);
        $first = $this->debtOf('tom_transfer');
        self::assertNotNull($first);
        $this->resetMercure();

        $this->owe('tom_transfer', 30000, 'julie', ['object' => 'Avance']);

        self::assertResponseStatusCodeSame(200);
        $debt = $this->debtOf('tom_transfer');
        self::assertNotNull($debt);
        self::assertTrue($first->getId()->equals($debt->getId()));
        self::assertCount(1, $this->em()->getRepository(PeerDebtShare::class)->findBy(['peerDebt' => $debt->getId()]));
        self::assertSame(['Julie'], array_column($this->response()['peerDebt']['shares'], 'contactName'));
        self::assertSame(30000, $debt->getAmountCents());
        self::assertSame('Avance', $debt->getObject());
        $this->assertMercureUpdatePublished((string) $debt->getId());
        self::assertSame(10000, $this->julyIncomeFrom('tom_transfer'));
    }

    public function testTheObjectDefaultsToTheLabelOfTheTransfer(): void
    {
        $this->login();

        $this->owe('tom_transfer', 28000);

        self::assertSame('VIR. DE TOM MARTIN', $this->debtOf('tom_transfer')?->getObject());
    }

    public function testTheWholeTransferMayBeToGiveBack(): void
    {
        $this->login();
        $before = $this->julyFlows();

        $this->owe('tom_transfer', 40000);

        self::assertResponseStatusCodeSame(201);
        self::assertSame($before['incomeCents'] - 40000, $this->julyFlows()['incomeCents']);
    }

    // --- Refusals -------------------------------------------------------------

    /** @return iterable<string, array{0: string, 1: array<string, mixed>|string}> */
    public static function badDebts(): iterable
    {
        yield 'more than the transfer' => ['tom_transfer', ['contactId' => 'tom', 'amountCents' => 45000]];
        yield 'one cent more than the transfer' => ['tom_transfer', ['contactId' => 'tom', 'amountCents' => 40001]];
        yield 'nothing to give back' => ['tom_transfer', ['contactId' => 'tom', 'amountCents' => 0]];
        yield 'a negative amount' => ['tom_transfer', ['contactId' => 'tom', 'amountCents' => -100]];
        yield 'a spend' => ['gite', ['contactId' => 'tom', 'amountCents' => 10000]];
        yield 'an internal transfer' => ['from_savings', ['contactId' => 'tom', 'amountCents' => 10000]];
        yield 'another user\'s contact' => ['tom_transfer', ['contactId' => 'others_contact', 'amountCents' => 10000]];
        yield 'an unknown contact' => ['tom_transfer', ['contactId' => '01JZZZZZZZZZZZZZZZZZZZZZZZ', 'amountCents' => 10000]];
        yield 'no contact' => ['tom_transfer', ['amountCents' => 10000]];
        yield 'no amount' => ['tom_transfer', ['contactId' => 'tom']];
        yield 'an amount that is not an integer' => ['tom_transfer', ['contactId' => 'tom', 'amountCents' => '100']];
        yield 'an object that is not a string' => ['tom_transfer', ['contactId' => 'tom', 'amountCents' => 10000, 'object' => 3]];
        yield 'too long notes' => ['tom_transfer', ['contactId' => 'tom', 'amountCents' => 10000, 'notes' => 'x']];
        yield 'not JSON' => ['tom_transfer', '{oops'];
    }

    /** @param array<string, mixed>|string $body */
    #[\PHPUnit\Framework\Attributes\DataProvider('badDebts')]
    public function testABadDebtIsRefused(string $transactionRef, array|string $body): void
    {
        $this->login();
        if (\is_array($body)) {
            if (\in_array($body['contactId'] ?? null, ['tom', 'others_contact'], true)) {
                $body['contactId'] = $this->id($body['contactId']);
            }
            if ('x' === ($body['notes'] ?? null)) {
                $body['notes'] = str_repeat('x', 2001);
            }
        }

        $this->call('POST', '/api/finance/transactions/'.$this->id($transactionRef).'/debt', $body);

        self::assertResponseStatusCodeSame(400);
        self::assertArrayHasKey('error', $this->response());
        self::assertNull($this->debtOf($transactionRef));
        $this->assertMercureUpdateCount(0);
    }

    public function testAnotherUsersOrAnUnknownTransactionIsNotFound(): void
    {
        $this->login();

        $this->call('POST', '/api/finance/transactions/01JZZZZZZZZZZZZZZZZZZZZZZZ/debt', ['contactId' => $this->id('tom'), 'amountCents' => 1000]);
        self::assertResponseStatusCodeSame(404);

        $this->call('GET', '/api/finance/transactions/not-an-id/debt');
        self::assertResponseStatusCodeSame(404);

        $this->call('DELETE', '/api/finance/transactions/01JZZZZZZZZZZZZZZZZZZZZZZZ/debt');
        self::assertResponseStatusCodeSame(404);
    }

    // --- Taking it back --------------------------------------------------------

    public function testRemovingTheDebtMakesTheTransferAnIncomeAgain(): void
    {
        $this->login();
        $before = $this->julyFlows();
        $this->owe('tom_transfer', 28000);
        $debtId = (string) $this->debtOf('tom_transfer')?->getId();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->call('DELETE', '/api/finance/transactions/'.$this->id('tom_transfer').'/debt');

        self::assertResponseIsSuccessful();
        self::assertNull($this->response()['peerDebt']);
        self::assertNull($this->debtOf('tom_transfer'));
        $this->assertMercureDeletePublished('/api/peer_debts/'.$debtId);
        $this->assertElasticsearchDeleteDispatched('peer_debts');
        self::assertSame($before, $this->julyFlows());
    }

    public function testRemovingTheDebtOfAPlainIncomeChangesNothing(): void
    {
        $this->login();

        $this->call('DELETE', '/api/finance/transactions/'.$this->id('tom_transfer').'/debt');

        self::assertResponseIsSuccessful();
        self::assertNull($this->response()['peerDebt']);
    }

    public function testADebtPartlyGivenBackCannotBeRemovedNorShrunkNorMovedToSomeoneElse(): void
    {
        $this->login();

        // Julie got 50 € of her 150 € back.
        $this->call('DELETE', '/api/finance/transactions/'.$this->id('julie_transfer').'/debt');
        self::assertResponseStatusCodeSame(400);

        $this->owe('julie_transfer', 4000, 'julie');
        self::assertResponseStatusCodeSame(400);

        $this->owe('julie_transfer', 15000, 'tom');
        self::assertResponseStatusCodeSame(400);

        $debt = $this->debtOf('julie_transfer');
        self::assertNotNull($debt);
        self::assertSame(15000, $debt->getAmountCents());
        self::assertSame(5000, $debt->getRepaidCents());

        // It may still grow.
        $this->owe('julie_transfer', 18000, 'julie');
        self::assertResponseStatusCodeSame(200);
        self::assertSame(18000, $this->debtOf('julie_transfer')?->getAmountCents());
        self::assertSame(5000, $this->debtOf('julie_transfer')?->getRepaidCents());
    }

    // --- What the debt protects ------------------------------------------------

    public function testATransferWithADebtCannotShrinkBelowItNorBecomeASpendOrATransfer(): void
    {
        $this->login();
        $this->owe('tom_transfer', 28000);

        $this->call('PATCH', '/api/transactions/'.$this->id('tom_transfer'), ['amountCents' => 20000]);
        self::assertResponseStatusCodeSame(400);

        $this->call('PATCH', '/api/transactions/'.$this->id('tom_transfer'), ['amountCents' => -40000]);
        self::assertResponseStatusCodeSame(400);

        $this->call('PUT', '/api/finance/transactions/'.$this->id('tom_transfer').'/transfer', ['transferKind' => 'internal']);
        self::assertResponseStatusCodeSame(400);

        $transaction = $this->em()->find(Transaction::class, $this->getFixture('tom_transfer')->getId());
        self::assertSame(40000, $transaction?->getAmountCents());
        self::assertSame('none', $transaction?->getTransferKind()->value);
    }

    public function testDeletingTheTransferForgetsItsDebtUnlessPartlyGivenBack(): void
    {
        $this->login();
        $this->owe('tom_transfer', 28000);
        $debtId = (string) $this->debtOf('tom_transfer')?->getId();
        $julieDebtId = (string) $this->debtOf('julie_transfer')?->getId();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->call('DELETE', '/api/transactions/'.$this->id('tom_transfer'));
        self::assertResponseStatusCodeSame(204);
        self::assertNull($this->em()->find(PeerDebt::class, $debtId));
        $this->assertMercureDeletePublished('/api/peer_debts/'.$debtId);

        $this->call('DELETE', '/api/transactions/'.$this->id('julie_transfer'));
        self::assertResponseStatusCodeSame(204);
        $kept = $this->em()->find(PeerDebt::class, $julieDebtId);
        self::assertNotNull($kept);
        self::assertNull($kept->getOriginTransactionId());
        self::assertSame(10000, $kept->getRemainingCents());
    }

    // --- Reading --------------------------------------------------------------

    public function testTheDebtIsReadWithTheTransactionAndProposesItsContact(): void
    {
        $this->login();

        $this->call('GET', '/api/finance/transactions/'.$this->id('tom_transfer').'/debt');
        self::assertResponseIsSuccessful();
        self::assertSame([
            'transactionId' => $this->id('tom_transfer'),
            'amountCents' => 40000,
            'canBeDebt' => true,
            'suggestedContactId' => $this->id('tom'),
            'peerDebt' => null,
        ], $this->response());

        $this->call('GET', '/api/finance/transactions/'.$this->id('julie_transfer').'/debt');
        $body = $this->response();
        self::assertNull($body['suggestedContactId']);
        self::assertSame('Avance de Julie', $body['peerDebt']['object']);
        self::assertSame('i_owe', $body['peerDebt']['direction']);
        self::assertSame(10000, $body['peerDebt']['remainingCents']);

        foreach (['gite', 'from_savings'] as $notAnIncome) {
            $this->call('GET', '/api/finance/transactions/'.$this->id($notAnIncome).'/debt');
            self::assertFalse($this->response()['canBeDebt']);
        }
    }

    public function testTheDebtsListShowsItWithItsDirection(): void
    {
        $this->login();
        $this->owe('tom_transfer', 28000);

        $this->call('GET', '/api/peer_debts');

        self::assertResponseIsSuccessful();
        $byObject = array_column($this->response()['member'], null, 'object');
        self::assertSame('i_owe', $byObject['VIR. DE TOM MARTIN']['direction']);
        self::assertSame(28000, $byObject['VIR. DE TOM MARTIN']['amountCents']);
        self::assertSame($this->id('tom_transfer'), $byObject['VIR. DE TOM MARTIN']['originTransactionId']);
    }

    private function julyIncomeFrom(string $transactionRef): int
    {
        $transaction = $this->em()->find(Transaction::class, $this->getFixture($transactionRef)->getId());
        $debt = $this->debtOf($transactionRef);

        return ($transaction?->getAmountCents() ?? 0) - ($debt?->getAmountCents() ?? 0);
    }
}
