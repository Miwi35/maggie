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
 * My part of a shared spend (MAG-451): only it counts in the envelopes, the
 * rest is a créance split between people, read back as `/api/peer_debts` and
 * summed up by `/api/finance/peer-debts/summary`.
 */
class PeerDebtApiTest extends WebTestCase
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
        $this->loadFixtures('peer_debts.yaml');
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

    private function debtOf(string $transactionRef): ?PeerDebt
    {
        return $this->em()->getRepository(PeerDebt::class)->findOneBy(['originTransaction' => $this->getFixture($transactionRef)->getId()]);
    }

    /** @return array<string, int> share amount by contact name */
    private static function sharesByName(PeerDebt $debt): array
    {
        $byName = [];
        foreach ($debt->getShares() as $share) {
            $byName[$share->getContactName()] = $share->getAmountCents();
        }

        return $byName;
    }

    /** @param list<array{0: string, 1?: int}> $people contact ref, optional amount */
    private function shareGite(int $myShareCents, array $people, ?string $object = null): void
    {
        $this->call('PUT', '/api/finance/transactions/'.$this->id('gite').'/my-share', array_filter([
            'myShareCents' => $myShareCents,
            'object' => $object,
            'notes' => 'Week-end de juillet',
            'shares' => array_map(fn (array $person): array => array_filter([
                'contactId' => $this->id($person[0]),
                'amountCents' => $person[1] ?? null,
            ], static fn ($value) => null !== $value), $people),
        ], static fn ($value) => null !== $value));
    }

    private function travelSpentInJuly(): int
    {
        $this->call('GET', '/api/finance/budget-status?year=2026&month=7');
        self::assertResponseIsSuccessful();
        $byCategory = array_column($this->response()['budgets'], null, 'categoryName');

        return $byCategory['Voyages']['spentCents'];
    }

    // --- Authentication -------------------------------------------------------

    public function testSettingMyShareRequiresAuthentication(): void
    {
        $this->loadFixtures('peer_debts.yaml');

        $this->call('PUT', '/api/finance/transactions/'.$this->id('gite').'/my-share', ['myShareCents' => 20000, 'shares' => []], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testRemovingMyShareRequiresAuthentication(): void
    {
        $this->loadFixtures('peer_debts.yaml');

        $this->call('DELETE', '/api/finance/transactions/'.$this->id('dinner').'/my-share', authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testTheDebtsAndTheirSummaryRequireAuthentication(): void
    {
        $this->call('GET', '/api/peer_debts', authenticated: false);
        self::assertResponseStatusCodeSame(401);

        $this->call('GET', '/api/finance/peer-debts/summary', authenticated: false);
        self::assertResponseStatusCodeSame(401);
    }

    // --- The gîte: 800 €, my part 200 €, Julie, Tom and Inès ---------------------

    public function testMyShareSplitsTheRestEquallyAndOnlyMyPartCountsInTheEnvelope(): void
    {
        $this->login();
        self::assertSame(80000 + 3000 + 10000, $this->travelSpentInJuly());

        $this->shareGite(20000, [['julie'], ['tom'], ['ines']]);

        self::assertResponseIsSuccessful();
        $body = $this->response();
        self::assertSame(20000, $body['myShareCents']);
        self::assertSame(60000, $body['peerDebt']['amountCents']);
        self::assertSame(60000, $body['peerDebt']['remainingCents']);
        self::assertSame('open', $body['peerDebt']['status']);
        self::assertSame('owed_to_me', $body['peerDebt']['direction']);
        self::assertSame(['Julie', 'Tom', 'Inès'], array_column($body['peerDebt']['shares'], 'contactName'));

        self::assertSame(20000, $this->transaction('gite')->getMyShareCents());
        $debt = $this->debtOf('gite');
        self::assertNotNull($debt);
        self::assertSame(['Julie' => 20000, 'Tom' => 20000, 'Inès' => 20000], self::sharesByName($debt));
        // The object is the spend's label unless given; the notes are kept.
        self::assertSame('GITE DES FALAISES', $debt->getObject());
        self::assertSame('Week-end de juillet', $debt->getNotes());
        self::assertTrue($debt->getUser()->getId()->equals($this->getFixture('test_user')->getId()));

        $this->assertMercureUpdatePublished('/transactions/'.$this->id('gite'));
        $this->assertMercureUpdatePublished('/peer_debts/'.$debt->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('gite'));
        $this->assertElasticsearchIndexDispatchedFor(PeerDebt::class, (string) $debt->getId());

        // The Voyages envelope counts 200 € of the gîte, not 800 €.
        self::assertSame(20000 + 3000 + 10000, $this->travelSpentInJuly());

        $this->call('GET', '/api/finance/peer-debts/summary');
        self::assertResponseIsSuccessful();
        // 600 € for the gîte, 50 € still owed on the dinner.
        self::assertSame(['owedToMeCents' => 60000 + 5000, 'iOweCents' => 0], $this->response());
    }

    public function testSharesMayBeGivenOneByOne(): void
    {
        $this->login();

        $this->shareGite(20000, [['julie', 30000], ['tom', 20000], ['ines', 10000]], 'Gîte Bretagne');

        self::assertResponseIsSuccessful();
        $debt = $this->debtOf('gite');
        self::assertNotNull($debt);
        self::assertSame(['Julie' => 30000, 'Tom' => 20000, 'Inès' => 10000], self::sharesByName($debt));
        self::assertSame('Gîte Bretagne', $debt->getObject());
    }

    public function testSharesThatDoNotAddUpToWhatIsOwedAreRefused(): void
    {
        $this->login();

        $this->shareGite(20000, [['julie', 30000], ['tom', 20000], ['ines', 20000]]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('60000', $this->response()['error']);
        self::assertNull($this->transaction('gite')->getMyShareCents());
        self::assertNull($this->debtOf('gite'));
        $this->assertMercureUpdateCount(0);
    }

    public function testTheLastCentGoesOnTheLastShare(): void
    {
        $this->login();

        $this->call('PUT', '/api/finance/transactions/'.$this->id('small_spend').'/my-share', [
            'myShareCents' => 0,
            'shares' => [['contactId' => $this->id('julie')], ['contactId' => $this->id('tom')], ['contactId' => $this->id('ines')]],
        ]);

        self::assertResponseIsSuccessful();
        $debt = $this->debtOf('small_spend');
        self::assertNotNull($debt);
        self::assertSame(['Julie' => 3333, 'Tom' => 3333, 'Inès' => 3334], self::sharesByName($debt));
        self::assertSame(0, $this->transaction('small_spend')->getMyShareCents());
    }

    public function testSettingMyShareAgainUpdatesTheSameDebt(): void
    {
        $this->login();
        $this->shareGite(20000, [['julie'], ['tom'], ['ines']]);
        $first = $this->debtOf('gite');
        self::assertNotNull($first);

        $this->shareGite(40000, [['julie'], ['tom']]);

        self::assertResponseIsSuccessful();
        $debt = $this->debtOf('gite');
        self::assertNotNull($debt);
        self::assertTrue($first->getId()->equals($debt->getId()));
        self::assertSame(['Julie' => 20000, 'Tom' => 20000], self::sharesByName($debt));
        self::assertCount(2, $this->em()->getRepository(PeerDebtShare::class)->findBy(['peerDebt' => $debt->getId()]));
        self::assertSame(40000, $this->transaction('gite')->getMyShareCents());
    }

    // --- Refusals -------------------------------------------------------------

    /** @return iterable<string, array{0: string, 1: array<string, mixed>|string}> */
    public static function badShares(): iterable
    {
        yield 'an income' => ['salary', ['myShareCents' => 1000, 'shares' => [['contactId' => 'julie']]]];
        yield 'an internal transfer' => ['to_savings', ['myShareCents' => 1000, 'shares' => [['contactId' => 'julie']]]];
        yield 'my part as much as the spend' => ['gite', ['myShareCents' => 80000, 'shares' => [['contactId' => 'julie']]]];
        yield 'a negative part' => ['gite', ['myShareCents' => -1, 'shares' => [['contactId' => 'julie']]]];
        yield 'another user\'s contact' => ['gite', ['myShareCents' => 20000, 'shares' => [['contactId' => 'others_contact']]]];
        yield 'nobody' => ['gite', ['myShareCents' => 20000, 'shares' => []]];
        yield 'the same person twice' => ['gite', ['myShareCents' => 20000, 'shares' => [['contactId' => 'tom'], ['contactId' => 'tom']]]];
        yield 'some amounts only' => ['gite', ['myShareCents' => 20000, 'shares' => [['contactId' => 'tom', 'amountCents' => 30000], ['contactId' => 'julie']]]];
        yield 'no part' => ['gite', ['shares' => [['contactId' => 'julie']]]];
        yield 'shares not a list' => ['gite', ['myShareCents' => 20000, 'shares' => 'julie']];
        yield 'not JSON' => ['gite', '{oops'];
    }

    /** @param array<string, mixed>|string $body */
    #[\PHPUnit\Framework\Attributes\DataProvider('badShares')]
    public function testABadShareIsRefused(string $transactionRef, array|string $body): void
    {
        $this->login();
        if (\is_array($body) && \is_array($body['shares'] ?? null)) {
            $body['shares'] = array_map(fn (array $share): array => ['contactId' => $this->id($share['contactId'])] + $share, $body['shares']);
        }

        $this->call('PUT', '/api/finance/transactions/'.$this->id($transactionRef).'/my-share', $body);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->transaction($transactionRef)->getMyShareCents());
        self::assertNull($this->debtOf($transactionRef));
    }

    public function testAnotherUsersTransactionIsNotFound(): void
    {
        $this->loadFixtures('peer_debts.yaml');
        $this->authenticateAsUser($this->getFixture('other_user'));

        $this->shareGite(20000, [['julie']]);

        self::assertResponseStatusCodeSame(404);
    }

    // --- Removing the share --------------------------------------------------

    public function testRemovingTheSharePutsTheWholeSpendBackAndForgetsTheDebt(): void
    {
        $this->login();
        $this->shareGite(20000, [['julie'], ['tom'], ['ines']]);
        $debtId = (string) $this->debtOf('gite')?->getId();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->call('DELETE', '/api/finance/transactions/'.$this->id('gite').'/my-share');

        self::assertResponseIsSuccessful();
        self::assertNull($this->response()['myShareCents']);
        self::assertNull($this->transaction('gite')->getMyShareCents());
        self::assertNull($this->debtOf('gite'));
        self::assertCount(0, $this->em()->getRepository(PeerDebtShare::class)->findBy(['peerDebt' => $debtId]));
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('gite'));
        $this->assertMercureDeletePublished('/api/peer_debts/'.$debtId);
        $this->assertElasticsearchDeleteDispatched('peer_debts');
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('gite'));

        self::assertSame(80000 + 3000 + 10000, $this->travelSpentInJuly());
    }

    public function testRemovingTheShareIsRefusedOnceSomeoneGaveMoneyBack(): void
    {
        $this->login();

        $this->call('DELETE', '/api/finance/transactions/'.$this->id('dinner').'/my-share');

        self::assertResponseStatusCodeSame(400);
        self::assertSame(3000, $this->transaction('dinner')->getMyShareCents());
        self::assertNotNull($this->debtOf('dinner'));
    }

    public function testAShareCannotGoBelowWhatWasGivenBack(): void
    {
        $this->login();

        $this->call('PUT', '/api/finance/transactions/'.$this->id('dinner').'/my-share', [
            'myShareCents' => 3000,
            'shares' => [['contactId' => $this->id('julie')]],
        ]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(['Tom' => 6000], self::sharesByName($this->debtOf('dinner') ?? self::fail('the dinner debt is gone')));
    }

    // --- What the share protects ----------------------------------------------

    public function testASharedSpendCannotShrinkBelowMyPart(): void
    {
        $this->login();

        $this->call('PATCH', '/api/transactions/'.$this->id('dinner'), ['amountCents' => -2000]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(-9000, $this->transaction('dinner')->getAmountCents());
    }

    public function testAContactWithAShareCannotBeDeleted(): void
    {
        $this->login();

        $this->call('DELETE', '/api/contacts/'.$this->id('tom'));

        self::assertResponseStatusCodeSame(400);
        self::assertNotNull($this->debtOf('dinner'));
    }

    // --- Reading --------------------------------------------------------------

    public function testTheDebtsListOnlyTheOwnersWithTheirShares(): void
    {
        $this->login();
        $this->shareGite(20000, [['julie'], ['tom'], ['ines']]);

        $this->call('GET', '/api/peer_debts');

        self::assertResponseIsSuccessful();
        $debts = $this->response()['member'];
        self::assertSame(['GITE DES FALAISES', 'Dîner au Phare'], array_column($debts, 'object'));
        $dinner = $debts[1];
        self::assertSame(6000, $dinner['amountCents']);
        self::assertSame(5000, $dinner['remainingCents']);
        self::assertSame('open', $dinner['status']);
        self::assertSame($this->id('dinner'), $dinner['originTransactionId']);
        unset($dinner['shares'][0]['@type']);
        self::assertEquals([[
            'id' => (string) $this->em()->getRepository(PeerDebtShare::class)->findOneBy(['contact' => $this->getFixture('tom')->getId(), 'amountCents' => 6000])?->getId(),
            'contactId' => $this->id('tom'),
            'contactName' => 'Tom',
            'amountCents' => 6000,
            'repaidCents' => 1000,
            'remainingCents' => 5000,
        ]], $dinner['shares']);
    }

    public function testMyShareIsReadWithTheTransaction(): void
    {
        $this->login();

        $this->call('GET', '/api/finance/transactions/'.$this->id('dinner').'/my-share');
        self::assertResponseIsSuccessful();
        $body = $this->response();
        self::assertSame(3000, $body['myShareCents']);
        self::assertTrue($body['canBeShared']);
        self::assertSame('Dîner au Phare', $body['peerDebt']['object']);

        $this->call('GET', '/api/finance/transactions/'.$this->id('salary').'/my-share');
        self::assertResponseIsSuccessful();
        self::assertSame(['transactionId' => $this->id('salary'), 'amountCents' => 250000, 'myShareCents' => null, 'canBeShared' => false, 'peerDebt' => null], $this->response());

        $this->call('GET', '/api/transactions/'.$this->id('dinner'));
        self::assertResponseIsSuccessful();
        self::assertSame(3000, $this->response()['myShareCents']);
    }

    public function testTheMonthlyReviewWeighsMyPartOnly(): void
    {
        $this->login();
        $this->shareGite(20000, [['julie'], ['tom'], ['ines']]);

        $this->call('GET', '/api/finance/monthly-review?year=2026&month=7');

        self::assertResponseIsSuccessful();
        // The gîte 200 €, the toll 100 €, the dinner 30 €.
        self::assertSame(20000 + 10000 + 3000, $this->response()['reviewableCents']);
        self::assertSame(20000 + 10000 + 3000, $this->response()['comparison']['thisMonthCents']);
    }
}
