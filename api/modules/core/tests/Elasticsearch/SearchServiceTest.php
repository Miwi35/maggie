<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\IndexableEntityRegistry;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\SearchService;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * MAG-338: hundreds of bank lines drown the useful results, so the global search
 * leaves `transactions` out unless the caller asks for it by name.
 */
final class SearchServiceTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use RecordingElasticsearchTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('SearchServiceTest.yaml');
    }

    public function testTheGlobalSearchLeavesTheTransactionsOut(): void
    {
        $service = $this->service(static fn (): array => [200, ['hits' => ['total' => ['value' => 0], 'hits' => []]]]);

        $service->search('carrefour', $this->userId('owner'));

        $indices = $this->searchedIndices();
        self::assertContains('stores', $indices);
        self::assertContains('events', $indices);
        self::assertNotContains('transactions', $indices);
    }

    public function testTheTransactionsAreSearchedWhenAskedForByName(): void
    {
        $service = $this->service(static fn (): array => [200, ['hits' => ['total' => ['value' => 0], 'hits' => []]]]);

        $service->search('carrefour', $this->userId('owner'), ['transactions']);

        self::assertSame(['transactions'], $this->searchedIndices());
    }

    public function testTheDoctrineFallbackFollowsTheSameRule(): void
    {
        $service = $this->service(static fn (): array => [503, ['error' => 'unavailable']]);

        $result = $service->search('carrefour', $this->userId('owner'));

        self::assertSame(['stores'], $this->indicesOf($result));
        self::assertSame($this->storeId('owner'), $result['results'][0]['id']);
    }

    public function testTheDoctrineFallbackFindsOnlyTheUsersOwnTransactionsWhenAskedFor(): void
    {
        $service = $this->service(static fn (): array => [503, ['error' => 'unavailable']]);

        $result = $service->search('carrefour', $this->userId('owner'), ['transactions']);

        self::assertSame(['transactions'], $this->indicesOf($result));
        self::assertSame(1, $result['total']);
        self::assertSame((string) $this->getFixture('transaction_owner')->getId(), $result['results'][0]['id']);
    }

    /**
     * @param \Closure(string, string): array{int, array<string, mixed>} $respond
     */
    private function service(\Closure $respond): SearchService
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $reader = new IndexMetadataReader();

        return new SearchService(
            $this->recordingClient($respond),
            new IndexableEntityRegistry($em, $reader),
            $reader,
            $em,
            new NullLogger(),
        );
    }

    /**
     * @return list<string>
     */
    private function searchedIndices(): array
    {
        $request = $this->requests[array_key_last($this->requests)] ?? self::fail('No request reached Elasticsearch.');
        self::assertStringEndsWith('/_search', $request['path']);

        return explode(',', trim(urldecode(substr($request['path'], 0, -\strlen('/_search'))), '/'));
    }

    /**
     * @param array{results: list<array{index: string}>} $result
     *
     * @return list<string>
     */
    private function indicesOf(array $result): array
    {
        return array_values(array_unique(array_column($result['results'], 'index')));
    }

    private function userId(string $name): string
    {
        return (string) $this->getFixture("user_{$name}")->getId();
    }

    private function storeId(string $name): string
    {
        return (string) $this->getFixture("store_{$name}")->getId();
    }
}
