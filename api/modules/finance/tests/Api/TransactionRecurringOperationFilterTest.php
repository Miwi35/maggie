<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Entity\RecurringOperation;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * `GET /api/transactions?recurringOperation=…`, the filter behind the
 * unrolled row of a series (MAG-438): the transactions it recognised, newest
 * first.
 */
final class TransactionRecurringOperationFilterTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures(__DIR__.'/fixtures/recurring_operation_filter.yaml');
    }

    public function testRequiresAuthentication(): void
    {
        $this->client->request('GET', '/api/transactions?recurringOperation='.$this->iri('series_gym'), [], [], [
            'HTTP_ACCEPT' => 'application/ld+json',
        ]);

        self::assertResponseStatusCodeSame(401);
    }

    public function testListsTheTransactionsOfTheSeriesNewestFirst(): void
    {
        $this->login();

        self::assertSame(
            ['CB CLUB FORME MARS', 'CB CLUB FORME FEVRIER', 'CB CLUB FORME JANVIER'],
            $this->labels('recurringOperation='.rawurlencode($this->iri('series_gym')).'&order[bookedAt]=desc'),
        );
    }

    public function testASeriesWithNoTransactionReturnsNothing(): void
    {
        $this->login();
        $this->client->getContainer()->get('doctrine')->getManager()->createQuery(
            'UPDATE '.\Maggie\Finance\Entity\Transaction::class.' t SET t.recurringOperation = NULL WHERE t.recurringOperation = :op',
        )->setParameter('op', $this->getFixture('series_rent')->getId(), 'ulid')->execute();

        self::assertSame([], $this->labels('recurringOperation='.rawurlencode($this->iri('series_rent'))));
    }

    public function testAnotherUsersSeriesReturnsNothing(): void
    {
        $this->login();

        self::assertSame([], $this->labels('recurringOperation='.rawurlencode($this->iri('series_secret'))));
    }

    public function testAValueThatNamesNoSeriesReturnsNothingRatherThanEverything(): void
    {
        $this->login();

        self::assertSame([], $this->labels('recurringOperation='.rawurlencode('/api/recurring_operations/not-a-ulid')));
        self::assertSame([], $this->labels('recurringOperation='));
    }

    private function login(): void
    {
        $this->authenticateAsUser($this->getFixture('series_user'));
    }

    private function iri(string $reference): string
    {
        $operation = $this->getFixture($reference);
        self::assertInstanceOf(RecurringOperation::class, $operation);

        return '/api/recurring_operations/'.$operation->getId();
    }

    /** @return list<string> */
    private function labels(string $query): array
    {
        $this->client->request('GET', '/api/transactions?'.$query, [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $this->authHeaders(),
        ));

        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);

        return array_map(static fn (array $m): string => (string) $m['label'], $body['member']);
    }
}
