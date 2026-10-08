<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Category;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * `GET /api/transactions?category=…`, the filter the mobile app reads
 * `totalItems` from to tell the owner how many lines a category deletion
 * would leave uncategorised (MAG-353).
 *
 * A count that came back as the whole history would put « 2 transactions »
 * on a category that holds none, so the branch that matters is the one that
 * must narrow, not the one that must return something.
 */
final class TransactionCategoryFilterTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    public function testTheIriNarrowsToThatCategoryAndTotalItemsCountsIt(): void
    {
        $this->loadWorld();

        $body = $this->get('/api/transactions?itemsPerPage=1&category='.rawurlencode('/api/categories/'.$this->category()->getId()));

        self::assertSame(1, $body['totalItems']);
    }

    public function testAWellFormedIdentifierForNoExistingCategoryCountsNothing(): void
    {
        $this->loadWorld();

        $body = $this->get('/api/transactions?category='.rawurlencode('/api/categories/01ARZ3NDEKTSV4RRFFQ69G5FAV'));

        self::assertSame(0, $body['totalItems']);
        self::assertSame([], $body['member']);
    }

    public function testWithoutTheParameterEveryTransactionIsCounted(): void
    {
        $this->loadWorld();

        self::assertSame(2, $this->get('/api/transactions')['totalItems']);
    }

    private function loadWorld(): void
    {
        $this->loadFixtures(\dirname(__DIR__, 4).'/tests/Contract/fixtures/QueryParameters.yaml');

        $user = $this->getFixture('filter_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);
    }

    private function category(): Category
    {
        $category = $this->getFixture('filter_category');
        self::assertInstanceOf(Category::class, $category);

        return $category;
    }

    /** @return array<string, mixed> */
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

        return $body;
    }
}
