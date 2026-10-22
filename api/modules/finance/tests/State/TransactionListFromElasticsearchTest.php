<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\State;

use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Resource\Factory\ResourceMetadataCollectionFactoryInterface;
use ApiPlatform\State\ProviderInterface;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use App\Tests\Support\SqlQueryCounter;
use Doctrine\ORM\EntityManagerInterface;
use Elastic\Elasticsearch\Client;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Pagination\ElasticsearchPaginator;
use Maggie\Core\Elasticsearch\Query\ElasticsearchFilterTranslator;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Tests\Elasticsearch\RecordingElasticsearchTrait;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\State\TransactionCollectionProvider;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Serializer\SerializerInterface;

/**
 * The list as production serves it: the page comes from Elasticsearch, every
 * line is rebuilt from its document, then serialized. The test environment
 * answers from Doctrine instead, so this is the only place the cost of that
 * path is pinned (MAG-544).
 */
final class TransactionListFromElasticsearchTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use RecordingElasticsearchTrait;

    /** @var list<array{_id: string, _source: array<string, mixed>}> */
    private array $hits = [];

    private Client $client;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(dirname(__DIR__).'/Controller/fixtures/account_incidents.yaml');
        $this->world(60);
        $this->loginFixtureUser();
    }

    public function testTheQueriesOfAPageDoNotGrowWithItsLines(): void
    {
        $this->serveFirst(2);
        $small = $this->queriesToServeAPage();

        $this->serveFirst(60);
        $large = $this->queriesToServeAPage();

        self::assertSame($small, $large, 'Serving 60 lines runs more queries than serving 2: something is loaded line by line.');
    }

    public function testAPageNeverAsksElasticsearchForMoreThanTheCap(): void
    {
        $this->serveFirst(2);

        $this->provide(['itemsPerPage' => '1000']);
        self::assertSame(100, $this->lastRequestBody()['size']);

        $this->provide(['itemsPerPage' => '0', 'page' => '-3']);
        self::assertSame(30, $this->lastRequestBody()['size']);
        self::assertSame(0, $this->lastRequestBody()['from']);
    }

    public function testTheLinesAreServedWithTheirRelationsAsLinks(): void
    {
        $this->serveFirst(2);

        $lines = $this->serialize($this->provide([]))['member'];

        self::assertCount(2, $lines);
        self::assertMatchesRegularExpression('#^/api/accounts/#', $lines[0]['account']);
        self::assertMatchesRegularExpression('#^/api/categories/#', $lines[0]['category']);
        self::assertMatchesRegularExpression('#^/api/contacts/#', $lines[0]['contact']);
    }

    private function queriesToServeAPage(): int
    {
        $counter = self::getContainer()->get(SqlQueryCounter::class);
        \assert($counter instanceof SqlQueryCounter);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        $em->clear();
        $counter->reset();
        $this->serialize($this->provide([]));
        $em->clear();
        $counter->reset();
        $this->serialize($this->provide([]));

        return $counter->count();
    }

    private function world(int $count): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $user = $this->getFixture('test_user');
        $account = $this->getFixture('checking');
        $savings = $this->getFixture('savings');

        $transactions = [];
        $contacts = [];
        for ($i = 0; $i < $count; ++$i) {
            $category = (new Category())->setName('Catégorie '.$i)->setUser($user);
            $contact = (new Contact())->setName('Contact '.$i)->setUser($user);
            $em->persist($category);
            $em->persist($contact);

            $transaction = (new Transaction())
                ->setUser($user)
                ->setAccount($account)
                ->setCategory($category)
                ->setAmountCents(-1000 - $i)
                ->setCurrency('EUR')
                ->setBookedAt(new \DateTimeImmutable('2026-10-01'))
                ->setLabel('Ligne '.$i)
                ->setStatus(TransactionStatus::Spent);
            $em->persist($transaction);
            $transactions[] = $transaction;
            $contacts[] = $contact;

            if (0 === $i % 3) {
                $other = (new Transaction())
                    ->setUser($user)
                    ->setAccount($savings)
                    ->setAmountCents(1000 + $i)
                    ->setCurrency('EUR')
                    ->setBookedAt(new \DateTimeImmutable('2026-10-01'))
                    ->setLabel('Contrepartie '.$i)
                    ->setStatus(TransactionStatus::Spent);
                $em->persist($other);
                $transaction->markAsInternalTransfer($other, TransferSource::Auto);
            }
        }
        $this->flushWithoutTransactionEffects($em);

        foreach ($transactions as $i => $transaction) {
            // The contact is derived from the label on every write: the document names it directly.
            $this->hits[] = [
                '_id' => (string) $transaction->getId(),
                '_source' => ['contactId' => (string) $contacts[$i]->getId()] + $transaction->toSearchDocument(),
            ];
        }
        $em->clear();
    }

    private function serveFirst(int $count): void
    {
        $page = \array_slice($this->hits, 0, $count);
        $this->requests = [];
        $this->client = $this->recordingClient(static fn (): array => [200, [
            'hits' => ['total' => ['value' => \count($page)], 'hits' => $page],
        ]]);
    }

    /** @param array<string, mixed> $filters */
    private function provide(array $filters): ElasticsearchPaginator
    {
        $inner = new ElasticsearchCollectionProvider(
            $this->client,
            self::getContainer()->get(IndexMetadataReader::class),
            self::getContainer()->get(ElasticsearchEntityHydrator::class),
            new ElasticsearchFilterTranslator(),
            self::getContainer()->get(Security::class),
            new NullLogger(),
            $this->createStub(ProviderInterface::class),
            'prod',
        );

        $operation = self::getContainer()->get(ResourceMetadataCollectionFactoryInterface::class)
            ->create(Transaction::class)->getOperation(forceCollection: true);
        \assert($operation instanceof GetCollection);

        $result = (new TransactionCollectionProvider($inner))->provide($operation, [], ['filters' => $filters]);
        \assert($result instanceof ElasticsearchPaginator);

        return $result;
    }

    /** @return array<string, mixed> */
    private function serialize(ElasticsearchPaginator $page): array
    {
        $operation = self::getContainer()->get(ResourceMetadataCollectionFactoryInterface::class)
            ->create(Transaction::class)->getOperation(forceCollection: true);

        $serializer = self::getContainer()->get('serializer');
        \assert($serializer instanceof SerializerInterface);

        $json = $serializer->serialize($page, 'jsonld', [
            'resource_class' => Transaction::class,
            'operation' => $operation,
            'operation_name' => $operation->getName(),
            'request_uri' => '/api/transactions',
            'uri' => 'http://localhost/api/transactions',
        ]);

        return json_decode($json, true, 512, \JSON_THROW_ON_ERROR);
    }
}
