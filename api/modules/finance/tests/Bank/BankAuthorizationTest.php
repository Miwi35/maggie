<?php

namespace Maggie\Finance\Tests\Bank;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Enum\BankConnectionStatus;
use Maggie\Finance\Repository\BankConnectionRepository;
use Maggie\Finance\UseCase\CompleteBankAuthorization;
use Maggie\Finance\UseCase\StartBankAuthorization;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;

/**
 * The consent journey: what is recorded before the user leaves, and what comes
 * back when the bank answers.
 */
class BankAuthorizationTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private string $keyPath;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->keyPath = tempnam(sys_get_temp_dir(), 'eb-key-');
        $key = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
        openssl_pkey_export($key, $pem);
        file_put_contents($this->keyPath, $pem);
    }

    protected function tearDown(): void
    {
        @unlink($this->keyPath);
        parent::tearDown();
    }

    private function client(MockHttpClient $http): EnableBankingClient
    {
        return new EnableBankingClient($http, 'app-id', $this->keyPath, 'https://api.example.test');
    }

    private function starter(MockHttpClient $http): StartBankAuthorization
    {
        return new StartBankAuthorization(
            $this->client($http),
            self::getContainer()->get('doctrine.orm.entity_manager'),
            self::getContainer()->get(BankConnectionRepository::class),
            'https://maggieai.fr/api/finance/bank-callback',
        );
    }

    private function completer(MockHttpClient $http): CompleteBankAuthorization
    {
        $container = self::getContainer();

        return new CompleteBankAuthorization(
            $this->client($http),
            $container->get(BankConnectionRepository::class),
            $container->get(\Maggie\Finance\Repository\AccountRepository::class),
            $container->get('doctrine.orm.entity_manager'),
            $container->get('messenger.default_bus'),
        );
    }

    public function testStartingAJourneyRecordsItBeforeTheUserLeaves(): void
    {
        $this->loadFixtures('account.yaml');
        $sent = null;

        $http = new MockHttpClient(function (string $method, string $url, array $options) use (&$sent) {
            $sent = json_decode($options['body'], true, 512, JSON_THROW_ON_ERROR);

            return new MockResponse(json_encode(['url' => 'https://bank.example/consent?x=1']));
        });

        $started = $this->starter($http)->execute($this->getFixture('test_user'), 'Revolut', 'FR');

        self::assertSame('https://bank.example/consent?x=1', $started['url']);
        self::assertSame(BankConnectionStatus::Pending, $started['connection']->getStatus());

        // What the bank is told: where to come back, and the state that ties
        // its answer to this journey.
        self::assertSame('https://maggieai.fr/api/finance/bank-callback', $sent['redirect_url']);
        self::assertSame($started['connection']->getState(), $sent['state']);
        self::assertSame('Revolut', $sent['aspsp']['name']);
        self::assertSame('FR', $sent['aspsp']['country']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(1, $em->getRepository(BankConnection::class)->findAll());
    }

    public function testTheStateIsUnguessableAndUniquePerJourney(): void
    {
        $this->loadFixtures('account.yaml');
        $http = new MockHttpClient(fn () => new MockResponse(json_encode(['url' => 'https://bank.example/c'])));

        $first = $this->starter($http)->execute($this->getFixture('test_user'), 'Revolut')['connection'];
        $second = $this->starter($http)->execute($this->getFixture('test_user'), 'N26')['connection'];

        self::assertNotSame($first->getState(), $second->getState());
        self::assertGreaterThanOrEqual(32, \strlen($first->getState()));
    }

    public function testAProviderThatReturnsNoUrlLeavesNoHalfOpenJourney(): void
    {
        $this->loadFixtures('account.yaml');
        $http = new MockHttpClient(fn () => new MockResponse(json_encode(['message' => 'nope'])));

        try {
            $this->starter($http)->execute($this->getFixture('test_user'), 'Revolut');
            self::fail('the journey should not start without a URL');
        } catch (\RuntimeException) {
            // expected
        }

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertCount(0, $em->getRepository(BankConnection::class)->findAll());
    }

    public function testTheBankAnswerActivatesTheConnectionAndBringsItsAccounts(): void
    {
        $this->loadFixtures('account.yaml');

        $startHttp = new MockHttpClient(fn () => new MockResponse(json_encode(['url' => 'https://bank.example/c'])));
        $connection = $this->starter($startHttp)->execute($this->getFixture('test_user'), 'N26')['connection'];

        $completeHttp = new MockHttpClient(fn () => new MockResponse(json_encode([
            'session_id' => 'session-42',
            'access' => ['valid_until' => '2026-12-15T10:00:00+00:00'],
            'accounts' => [
                ['uid' => 'acc-1', 'name' => 'N26 Main', 'currency' => 'EUR'],
                ['uid' => 'acc-2', 'name' => 'N26 Spaces', 'currency' => 'EUR'],
            ],
        ])));

        $result = $this->completer($completeHttp)->execute($connection->getState(), 'the-code');

        self::assertSame(BankConnectionStatus::Active, $result['connection']->getStatus());
        self::assertSame('session-42', $result['connection']->getSessionId());
        self::assertSame('2026-12-15', $result['connection']->getConsentExpiresAt()->format('Y-m-d'));
        self::assertTrue($result['connection']->isUsable(new \DateTimeImmutable('2026-10-01')));
        self::assertFalse($result['connection']->isUsable(new \DateTimeImmutable('2027-01-01')));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $accounts = $em->getRepository(Account::class)->findBy(['externalAccountId' => 'acc-1']);
        self::assertCount(1, $accounts);
        self::assertSame($connection->getId(), $accounts[0]->getBankConnection()->getId());
    }

    public function testAnAccountAlreadyTrackedByHandIsAdoptedRatherThanDuplicated(): void
    {
        // The fixture holds a hand-typed "Compte courant" at Crédit Agricole.
        $this->loadFixtures('account.yaml');
        $existing = $this->getFixture('checking');

        $startHttp = new MockHttpClient(fn () => new MockResponse(json_encode(['url' => 'https://bank.example/c'])));
        $connection = $this->starter($startHttp)->execute($this->getFixture('test_user'), 'Compte courant')['connection'];

        $completeHttp = new MockHttpClient(fn () => new MockResponse(json_encode([
            'session_id' => 'session-7',
            'accounts' => [['uid' => 'remote-1', 'name' => 'Compte courant', 'currency' => 'EUR']],
        ])));

        $result = $this->completer($completeHttp)->execute($connection->getState(), 'code');

        self::assertSame(1, $result['linked']);
        self::assertSame(0, $result['created']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Account::class, $existing->getId());
        self::assertSame('remote-1', $refreshed->getExternalAccountId());
    }

    public function testAnAnswerWhoseStateMatchesNothingIsRefused(): void
    {
        $this->loadFixtures('account.yaml');
        $http = new MockHttpClient(fn () => new MockResponse(json_encode(['session_id' => 'x'])));

        $this->expectException(\DomainException::class);

        $this->completer($http)->execute('a-state-we-never-issued', 'code');
    }

    public function testAStateCannotBeUsedTwice(): void
    {
        $this->loadFixtures('account.yaml');

        $startHttp = new MockHttpClient(fn () => new MockResponse(json_encode(['url' => 'https://bank.example/c'])));
        $connection = $this->starter($startHttp)->execute($this->getFixture('test_user'), 'N26')['connection'];

        $completeHttp = new MockHttpClient(fn () => new MockResponse(json_encode([
            'session_id' => 'session-1',
            'accounts' => [],
        ])));

        $completer = $this->completer($completeHttp);
        $completer->execute($connection->getState(), 'code');

        // The endpoint is public: replaying a state that already worked must
        // not re-open the connection.
        $this->expectException(\DomainException::class);
        $this->expectExceptionMessageMatches('/already been used/');

        $completer->execute($connection->getState(), 'code');
    }

    public function testReconnectingTheSameBankReopensTheLinkInsteadOfPilingUpAnother(): void
    {
        $this->loadFixtures('account.yaml');
        $http = new MockHttpClient(fn () => new MockResponse(json_encode(['url' => 'https://bank.example/c'])));

        $first = $this->starter($http)->execute($this->getFixture('test_user'), 'Revolut')['connection'];
        $completeHttp = new MockHttpClient(fn () => new MockResponse(json_encode([
            'session_id' => 'session-1',
            'accounts' => [],
        ])));
        $this->completer($completeHttp)->execute($first->getState(), 'code');

        $second = $this->starter($http)->execute($this->getFixture('test_user'), 'Revolut')['connection'];

        // Same link, sent back through consent: a new state, no stale session.
        self::assertTrue($first->getId()->equals($second->getId()));
        self::assertSame(BankConnectionStatus::Pending, $second->getStatus());
        self::assertNull($second->getSessionId());

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(1, $em->getRepository(BankConnection::class)->findAll());
    }

    public function testAFailedReconnectionLeavesTheWorkingLinkAlone(): void
    {
        $this->loadFixtures('account.yaml');

        $startHttp = new MockHttpClient(fn () => new MockResponse(json_encode(['url' => 'https://bank.example/c'])));
        $connection = $this->starter($startHttp)->execute($this->getFixture('test_user'), 'N26')['connection'];

        $completeHttp = new MockHttpClient(fn () => new MockResponse(json_encode([
            'session_id' => 'session-live',
            'access' => ['valid_until' => '2027-01-01T00:00:00+00:00'],
            'accounts' => [],
        ])));
        $this->completer($completeHttp)->execute($connection->getState(), 'code');

        $failing = new MockHttpClient(fn () => new MockResponse(json_encode(['message' => 'nope'])));

        try {
            $this->starter($failing)->execute($this->getFixture('test_user'), 'N26');
            self::fail('the journey should not start without a URL');
        } catch (\RuntimeException) {
            // expected
        }

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(BankConnection::class, $connection->getId());

        // A failed attempt must not cost the user a consent that still works.
        self::assertSame(BankConnectionStatus::Active, $refreshed->getStatus());
        self::assertSame('session-live', $refreshed->getSessionId());
    }

    public function testForgettingALinkKeepsTheAccountsItBrought(): void
    {
        $this->loadFixtures('account.yaml');

        $startHttp = new MockHttpClient(fn () => new MockResponse(json_encode(['url' => 'https://bank.example/c'])));
        $connection = $this->starter($startHttp)->execute($this->getFixture('test_user'), 'Compte courant')['connection'];

        $completeHttp = new MockHttpClient(fn () => new MockResponse(json_encode([
            'session_id' => 'session-3',
            'accounts' => [['uid' => 'remote-9', 'name' => 'Compte courant', 'currency' => 'EUR']],
        ])));
        $this->completer($completeHttp)->execute($connection->getState(), 'code');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::getContainer()->get(\Maggie\Finance\UseCase\ForgetBankConnection::class)->execute($connection);

        $em->clear();
        self::assertCount(0, $em->getRepository(BankConnection::class)->findAll());

        // Months of movements do not disappear because a consent was dropped.
        $account = $em->find(Account::class, $this->getFixture('checking')->getId());
        self::assertNotNull($account);
        self::assertNull($account->getBankConnection());
        self::assertNull($account->getExternalAccountId());
    }

    public function testAConnectionWithoutAnExpiryStaysUsable(): void
    {
        $this->loadFixtures('account.yaml');

        $startHttp = new MockHttpClient(fn () => new MockResponse(json_encode(['url' => 'https://bank.example/c'])));
        $connection = $this->starter($startHttp)->execute($this->getFixture('test_user'), 'Revolut')['connection'];

        $completeHttp = new MockHttpClient(fn () => new MockResponse(json_encode([
            'session_id' => 'session-9',
            'accounts' => [],
        ])));

        $result = $this->completer($completeHttp)->execute($connection->getState(), 'code');

        self::assertNull($result['connection']->getConsentExpiresAt());
        self::assertTrue($result['connection']->isUsable());
        self::assertNull($result['connection']->daysBeforeExpiry());
    }
}
