<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Resource\Factory\ResourceMetadataCollectionFactoryInterface;
use ApiPlatform\Metadata\Resource\Factory\ResourceNameCollectionFactoryInterface;
use ApiPlatform\OpenApi\Factory\OpenApiFactoryInterface;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Query\ElasticsearchFilterTranslator;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;

/**
 * Every query parameter the admin and the mobile app send, and the proof that
 * the API does something with it.
 *
 * A query parameter API Platform does not know about is not an error: it is
 * dropped, and the collection comes back unfiltered. The client shows the
 * wrong list and nothing anywhere says why. Two of the regressions behind this
 * ticket are exactly that —
 *
 *   33f0506  Task declared ExistsFilter on doneDate only, so the dashboard's
 *            `exists[dueDate]=false` filtered nothing and dated tasks leaked
 *            into "today".
 *   04f1de6  the Elasticsearch filter translator ignored PHP's nested-array
 *            shape, so `exists[completedAt]=false` never reached the query and
 *            completed tasks came back in the dashboard.
 *
 * — one on the declaration side, one on the Elasticsearch side. Hence the two
 * assertions below: the parameter is declared, *and* it survives translation
 * to Elasticsearch. Collections of indexed entities are served from
 * Elasticsearch in production and only fall back to Doctrine on an exception,
 * so a parameter that passes the first check and fails the second is ignored
 * in production and honoured in every test that is not this one.
 *
 * The list is hand-written on purpose. It is the client contract, and it is
 * meant to be read: adding a line is how you say "a client now sends this".
 * MobileQueryParameterContractTest, on the mobile side, asserts the converse —
 * that no client sends a parameter missing from here.
 */
final class QueryParameterContractTest extends KernelTestCase
{
    use ContractSnapshotTrait;

    /**
     * Collection path => parameter => who sends it.
     *
     * Pagination (`page`, `itemsPerPage`) is left out: API Platform provides
     * it for every collection and no entity declares it.
     *
     * @var array<string, array<string, string>>
     */
    private const CLIENT_QUERY_PARAMETERS = [
        '/api/events' => [
            'startAt[after]' => 'admin CalendarView and Dashboard, mobile getEvents',
            'startAt[before]' => 'admin CalendarView and Dashboard',
            'startAt[strictly_before]' => 'admin recurring-event lookup, mobile getRecurringEvents',
            'endAt[before]' => 'mobile getEvents',
            'exists[rrule]' => 'admin recurring-event lookup, mobile getRecurringEvents',
            'order[startAt]' => 'admin CalendarView, Dashboard and MealsWeekView',
        ],
        '/api/tasks' => [
            'exists[completedAt]' => 'admin Dashboard, mobile getOpenTasks',
            'exists[dueDate]' => 'admin Dashboard, mobile getUndatedTasks',
            'dueDate[after]' => 'admin CalendarView',
            'dueDate[before]' => 'admin CalendarView and Dashboard, mobile getOpenTasks',
            'order[dueDate]' => 'admin CalendarView and Dashboard',
            'order[criticality]' => 'admin Dashboard',
        ],
        '/api/meals' => [
            'startAt[after]' => 'admin MealsWeekView and CalendarView, mobile getMeals',
            'startAt[before]' => 'admin MealsWeekView and CalendarView, mobile getMeals',
            'order[startAt]' => 'admin MealsWeekView and CalendarView',
        ],
        '/api/notifications' => [
            'exists[readAt]' => 'admin NotificationBell, mobile getNotifications',
            'order[createdAt]' => 'admin NotificationBell',
        ],
        '/api/transactions' => [
            'account' => 'mobile getTransactions, narrowing to one account',
            'order[bookedAt]' => 'mobile getTransactions',
        ],
        '/api/grocery_lists' => [
            'order[createdAt]' => 'admin GroceryListView',
        ],
        '/api/agendas' => [
            'order[name]' => 'admin CalendarView, EventCreateDialog and UserPreferenceSettings',
        ],
        '/api/recipes' => [
            'order[name]' => 'admin MealsWeekView',
        ],
        '/api/products' => [
            'order[name]' => 'admin GroceryListView',
        ],
        '/api/stores' => [
            'order[name]' => 'admin GroceryListView',
        ],
    ];

    /**
     * Published so the mobile and admin suites can check they send nothing
     * that is not in it. Nothing reads it on the PHP side — the constant above
     * is the source, this is its copy on disk.
     */
    public function testTheClientQueryContractIsPublished(): void
    {
        $this->assertMatchesContract(
            'query-parameters.json',
            self::CLIENT_QUERY_PARAMETERS,
            'A client query parameter was added or removed. Every entry here must be declared by an ApiFilter and survive translation to Elasticsearch — the two tests below check that.',
        );
    }

    /**
     * @param array<string, string> $parameters
     */
    #[DataProvider('clientCollections')]
    public function testEveryParameterTheClientsSendIsDeclared(string $path, array $parameters): void
    {
        $declared = $this->declaredParameters($path);

        foreach ($parameters as $name => $sender) {
            self::assertContains($name, $declared, sprintf(
                "GET %s does not declare the query parameter \"%s\", sent by %s.\nAPI Platform drops what it does not declare, so the collection comes back unfiltered and the client shows the wrong list, silently.\nDeclare it with an #[ApiFilter] on the entity.\nDeclared today: %s",
                $path,
                $name,
                $sender,
                implode(', ', $declared),
            ));
        }
    }

    /**
     * The Elasticsearch half. Collections of indexed entities are served from
     * Elasticsearch in production, and its translator is a separate
     * implementation of the same contract: a parameter it does not recognise
     * produces no clause, and the query runs unfiltered.
     *
     * @param array<string, string> $parameters
     */
    #[DataProvider('clientCollections')]
    public function testEveryParameterTheClientsSendSurvivesElasticsearchTranslation(string $path, array $parameters): void
    {
        $translator = new ElasticsearchFilterTranslator();

        foreach ($parameters as $name => $sender) {
            $query = self::parseQueryString($name . '=' . self::sampleValueFor($name));
            $translated = $translator->translate($query);

            $clauses = \count($translated['must']) + \count($translated['filter']) + \count($translated['sort']);

            self::assertGreaterThan(0, $clauses, sprintf(
                "The Elasticsearch filter translator produces no clause for \"%s\" (GET %s, sent by %s).\nCollections are served from Elasticsearch in production and fall back to Doctrine only on an exception, so this parameter is honoured in tests and ignored in production — the shape of 04f1de6.",
                $name,
                $path,
                $sender,
            ));
        }
    }

    /**
     * Sorting is the half Elasticsearch cannot fake. It refuses to sort on an
     * analysed `text` field, the provider catches the exception and quietly
     * serves the collection from Doctrine instead — right answer, wrong
     * implementation, one warning in a log nobody reads.
     *
     * So for every `order[…]` a client sends, the indexed entity must offer a
     * sortable form of that field: a date, a keyword, or a text field with a
     * keyword sub-field. Adding `order[summary]` to the list above without
     * `#[IndexedField(type: 'text', keyword: true)]` on the entity fails here.
     *
     * @param array<string, string> $parameters
     */
    #[DataProvider('clientCollections')]
    public function testEverySortTheClientsAskForIsServableByElasticsearch(string $path, array $parameters): void
    {
        $entityClass = $this->entityClassFor($path);
        $mapping = self::getContainer()->get(IndexMetadataReader::class)?->read($entityClass);

        if ($mapping === null) {
            // Not an indexed entity: Doctrine serves the collection and sorts
            // on any mapped column.
            self::assertTrue(true);

            return;
        }

        foreach ($parameters as $name => $sender) {
            if (!preg_match('/^order\[(.+)]$/', $name, $matches)) {
                continue;
            }

            $field = $matches[1];
            $declared = $mapping['fields'][$field] ?? null;

            self::assertIsArray($declared, sprintf(
                'GET %s accepts "%s" (sent by %s) but %s does not index "%s" at all, so Elasticsearch cannot sort on it.',
                $path,
                $name,
                $sender,
                $entityClass,
                $field,
            ));

            $sortable = $declared['type'] !== 'text' || isset($declared['fields']['keyword']);

            self::assertTrue($sortable, sprintf(
                "GET %s accepts \"%s\" (sent by %s), but %s::\$%s is indexed as an analysed text field with no keyword sub-field.\nElasticsearch throws on such a sort, the provider falls back to Doctrine and the degradation is invisible.\nAdd keyword: true to its #[IndexedField] and reindex.",
                $path,
                $name,
                $sender,
                $entityClass,
                $field,
            ));
        }
    }

    /** @return iterable<string, array{string, array<string, string>}> */
    public static function clientCollections(): iterable
    {
        foreach (self::CLIENT_QUERY_PARAMETERS as $path => $parameters) {
            yield $path => [$path, $parameters];
        }
    }

    /**
     * A value of the right shape for the parameter: `order[x]` takes a
     * direction, `exists[x]` a boolean, a date operator a date.
     */
    private static function sampleValueFor(string $name): string
    {
        return match (true) {
            str_starts_with($name, 'order[') => 'asc',
            str_starts_with($name, 'exists[') => 'false',
            str_contains($name, '[before]'),
            str_contains($name, '[after]'),
            str_contains($name, '[strictly_before]'),
            str_contains($name, '[strictly_after]') => '2026-01-01T00:00:00+00:00',
            default => 'a-value',
        };
    }

    /**
     * Through parse_str, so the test sees the parameter in the nested-array
     * shape PHP hands the application — which is the shape 04f1de6 was about.
     *
     * @return array<string, mixed>
     */
    private static function parseQueryString(string $queryString): array
    {
        parse_str($queryString, $parsed);

        return $parsed;
    }

    /**
     * @return class-string
     */
    private function entityClassFor(string $path): string
    {
        $container = self::getContainer();

        $names = $container->get(ResourceNameCollectionFactoryInterface::class);
        self::assertInstanceOf(ResourceNameCollectionFactoryInterface::class, $names);

        $metadata = $container->get(ResourceMetadataCollectionFactoryInterface::class);
        self::assertInstanceOf(ResourceMetadataCollectionFactoryInterface::class, $metadata);

        foreach ($names->create() as $resourceClass) {
            foreach ($metadata->create($resourceClass) as $resource) {
                foreach ($resource->getOperations() ?? [] as $operation) {
                    if ($operation instanceof GetCollection && '/api' . $operation->getUriTemplate() === $path . '{._format}') {
                        return $resourceClass;
                    }
                }
            }
        }

        self::fail(sprintf('No GetCollection operation is mapped to "%s".', $path));
    }

    /**
     * @return list<string>
     */
    private function declaredParameters(string $path): array
    {
        $container = self::getContainer();

        $factory = $container->get(OpenApiFactoryInterface::class);
        self::assertInstanceOf(OpenApiFactoryInterface::class, $factory);

        $normalizer = $container->get('serializer');
        self::assertInstanceOf(NormalizerInterface::class, $normalizer);

        $document = $normalizer->normalize($factory(), 'json');
        self::assertIsArray($document);

        $operation = $document['paths'][$path]['get'] ?? null;
        self::assertIsArray($operation, sprintf('GET %s is not exposed at all.', $path));

        $names = [];
        foreach ($operation['parameters'] ?? [] as $parameter) {
            if (\is_array($parameter) && \is_string($parameter['name'] ?? null)) {
                $names[] = $parameter['name'];
            }
        }

        return $names;
    }
}
