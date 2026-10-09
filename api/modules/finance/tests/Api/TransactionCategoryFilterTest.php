<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** `GET /api/transactions?category=…`, the REST twin of manage_transactions' categoryId. */
final class TransactionCategoryFilterTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('category_filter.yaml');
        $user = $this->getFixture('filter_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);
    }

    public function testTheIriNarrowsToThatCategory(): void
    {
        $iri = '/api/categories/'.$this->getFixture('filter_salary')->getId();

        self::assertSame(['In salary'], $this->get('/api/transactions?category='.rawurlencode($iri)));
    }

    public function testACategoryThatNamesNothingReturnsNothing(): void
    {
        self::assertSame([], $this->get('/api/transactions?category='.rawurlencode('/api/categories/01ARZ3NDEKTSV4RRFFQ69G5FAV')));
    }

    public function testWithoutTheParameterEveryTransactionComesBack(): void
    {
        self::assertEqualsCanonicalizing(['In salary', 'In food'], $this->get('/api/transactions'));
    }

    /** @return list<string> */
    private function get(string $uri): array
    {
        $this->client->request('GET', $uri, [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $this->authHeaders(),
        ));
        self::assertResponseIsSuccessful();

        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);

        return array_map(static fn (array $m): string => (string) $m['label'], $body['member']);
    }
}
