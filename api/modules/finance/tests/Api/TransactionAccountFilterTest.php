<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * `GET /api/transactions?account=…`, the filter the mobile app uses to show
 * one account's history.
 *
 * Its own file because UlidRelationFilter exists for a case the framework
 * gets wrong — API Platform's SearchFilter binds a ULID untyped and Postgres
 * answers with a 500 — and because the branch that matters most is the one
 * nothing else reaches: a value that names no account. Skipping the clause
 * there would answer a narrowed request with every account's transactions,
 * on a screen that says it is showing one.
 */
final class TransactionAccountFilterTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    public function testTheIriTheProviderHandedOutNarrowsToThatAccount(): void
    {
        $this->loadWorld();

        $labels = $this->get('/api/transactions?account=' . rawurlencode('/api/accounts/' . $this->account('filter_account')->getId()));

        self::assertSame(['On account A'], $labels);
    }

    /**
     * The same identifier without the prefix. Accepted so that a caller
     * holding the raw id does not have to build an IRI to be understood —
     * and, more to the point, so that neither spelling silently returns
     * everything.
     */
    public function testABareUlidNarrowsToThatAccountToo(): void
    {
        $this->loadWorld();

        $labels = $this->get('/api/transactions?account=' . $this->account('filter_other_account')->getId());

        self::assertSame(['On account B'], $labels);
    }

    /**
     * A stale deep link, a hand-typed id, an account deleted since. The
     * request is answerable — there simply is no such account — so it is a
     * 200 with nothing in it, not a 500 and not the whole collection.
     */
    public function testAValueThatNamesNoAccountReturnsNothing(): void
    {
        $this->loadWorld();

        $labels = $this->get('/api/transactions?account=' . rawurlencode('/api/accounts/not-a-ulid'));

        self::assertSame([], $labels, 'An unparseable account returned transactions. Returning the unfiltered collection here puts one account\'s history on another account\'s screen.');
    }

    public function testAWellFormedIdentifierForNoExistingAccountReturnsNothing(): void
    {
        $this->loadWorld();

        $labels = $this->get('/api/transactions?account=' . rawurlencode('/api/accounts/01ARZ3NDEKTSV4RRFFQ69G5FAV'));

        self::assertSame([], $labels);
    }

    /**
     * `?account[]=…` — the array shape a client produces by accident, and the
     * one an attacker produces on purpose. It reaches the filter as an array,
     * which names no account.
     */
    public function testAnArrayValueReturnsNothingRatherThanEverything(): void
    {
        $this->loadWorld();

        $labels = $this->get('/api/transactions?account[]=' . rawurlencode('/api/accounts/' . $this->account('filter_account')->getId()));

        self::assertSame([], $labels, 'An array-shaped account parameter was ignored, and the response carried every account\'s transactions.');
    }

    public function testAnEmptyValueReturnsNothingRatherThanEverything(): void
    {
        $this->loadWorld();

        self::assertSame([], $this->get('/api/transactions?account='));
    }

    /**
     * The control: without the parameter, both accounts' transactions come
     * back. Without it, every assertion above would also hold on an API that
     * returned nothing at all.
     */
    public function testWithoutTheParameterEveryTransactionComesBack(): void
    {
        $this->loadWorld();

        self::assertEqualsCanonicalizing(
            ['On account A', 'On account B'],
            $this->get('/api/transactions'),
        );
    }

    private function loadWorld(): void
    {
        // The Contract suite's fixture: two accounts, one transaction each.
        // Shared rather than copied so the filter and its contract test
        // cannot drift into describing different worlds.
        $this->loadFixtures(\dirname(__DIR__, 4) . '/tests/Contract/fixtures/QueryParameters.yaml');

        $user = $this->getFixture('filter_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);
    }

    private function account(string $reference): Account
    {
        $account = $this->getFixture($reference);
        self::assertInstanceOf(Account::class, $account);

        return $account;
    }

    /** @return list<string> */
    private function get(string $uri): array
    {
        $this->client->request('GET', $uri, [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $this->authHeaders(),
        ));

        self::assertResponseIsSuccessful(sprintf(
            'GET %s failed: %s',
            $uri,
            substr((string) $this->client->getResponse()->getContent(), 0, 400),
        ));

        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);

        return array_map(static fn (array $m): string => (string) $m['label'], $body['member']);
    }
}
