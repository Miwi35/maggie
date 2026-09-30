<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Resource\Factory\ResourceMetadataCollectionFactoryInterface;
use ApiPlatform\Metadata\Resource\Factory\ResourceNameCollectionFactoryInterface;
use ApiPlatform\OpenApi\Factory\OpenApiFactoryInterface;
use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Query\ElasticsearchFilterTranslator;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
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
 * com.maggie.app.data.api.QueryParameterContractTest, on the mobile side,
 * asserts the converse — that the app sends no parameter missing from here.
 * Both directions are needed: this file cannot tell that the list is
 * complete, and that is exactly how `accountId` went unnoticed.
 */
final class QueryParameterContractTest extends WebTestCase
{
    use ContractSnapshotTrait;
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

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

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

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
            $query = self::parseQueryString($name.'='.self::sampleValueFor($name));
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
     * Every field a client filters or sorts on exists in the Elasticsearch
     * index, in a form Elasticsearch can use.
     *
     * The clause-count check above proves the translator produced *a* clause;
     * it cannot tell whether that clause names a field the index holds. The
     * two failures differ, and the second is worse:
     *
     *   - a sort on an analysed `text` field throws, the provider catches it
     *     and serves the collection from Doctrine — right answer, wrong
     *     implementation, one warning in a log nobody reads;
     *   - a term query on a field the mapping does not declare throws
     *     nothing. It matches zero documents, and the client shows an empty
     *     list. Unfiltered was the bug this ticket started from; empty is
     *     worse, and just as quiet.
     *
     * Relations are the trap: API Platform names the filter `account`, while
     * IndexManager flattens the relation to `accountId`. The translator
     * bridges that, and this asserts the bridge reaches something real.
     *
     * @param array<string, string> $parameters
     */
    #[DataProvider('clientCollections')]
    public function testEveryParameterTheClientsSendNamesAnIndexedField(string $path, array $parameters): void
    {
        $entityClass = $this->entityClassFor($path);

        $reader = self::getContainer()->get(IndexMetadataReader::class);
        self::assertInstanceOf(IndexMetadataReader::class, $reader);
        $meta = $reader->read($entityClass);

        if (null === $meta) {
            // Not an indexed entity: Doctrine serves the collection and works
            // off the ORM mapping, which API Platform already validated when
            // it accepted the filter declaration.
            self::assertTrue(true);

            return;
        }

        // What the index actually holds: the declared fields, plus the
        // flattened relation keys IndexManager adds.
        $indexed = $meta['fields'];
        foreach ($meta['relations'] as $relation) {
            $indexed[$relation['sourceField']] ??= ['type' => 'keyword'];
        }

        foreach ($parameters as $name => $sender) {
            $sorting = (bool) preg_match('/^order\[(.+)]$/', $name, $matches);
            $property = $sorting ? $matches[1] : self::filteredProperty($name);

            if (null === $property) {
                // exists[…] compiles to an `exists` query, which is
                // meaningful on any field, present or not.
                continue;
            }

            // A relation is filtered under the property name and stored under
            // its source field; resolve before looking it up.
            $field = $meta['relations'][$property]['sourceField'] ?? $property;
            $declared = $indexed[$field] ?? null;

            self::assertIsArray($declared, sprintf(
                "GET %s accepts \"%s\" (sent by %s), but %s does not index \"%s\".\nElasticsearch serves this collection: a %s on a field the mapping does not declare %s.\nAdd an #[IndexedField] (or an #[IndexedRelation]) for it and reindex.",
                $path,
                $name,
                $sender,
                $entityClass,
                $field,
                $sorting ? 'sort' : 'term query',
                $sorting ? 'throws, and the provider silently falls back to Doctrine' : 'matches nothing, and the client shows an empty list',
            ));

            if (!$sorting) {
                continue;
            }

            self::assertTrue(
                'text' !== $declared['type'] || isset($declared['fields']['keyword']),
                sprintf(
                    "GET %s accepts \"%s\" (sent by %s), but %s::\$%s is indexed as an analysed text field with no keyword sub-field.\nElasticsearch throws on such a sort, the provider falls back to Doctrine and the degradation is invisible.\nAdd keyword: true to its #[IndexedField] and reindex.",
                    $path,
                    $name,
                    $sender,
                    $entityClass,
                    $field,
                ),
            );
        }
    }

    /**
     * The entity property a query parameter narrows on, or null when the
     * parameter does not name one.
     */
    private static function filteredProperty(string $name): ?string
    {
        if (str_starts_with($name, 'exists[')) {
            return null;
        }

        // dueDate[before] → dueDate
        if (1 === preg_match('/^([A-Za-z0-9_]+)\[[a-z_]+]$/', $name, $matches)) {
            return $matches[1];
        }

        return $name;
    }

    /**
     * The half neither of the checks above can give: the parameter is not
     * only declared and translated, it changes the answer.
     *
     * A filter can be declared, reach Elasticsearch, and still narrow
     * nothing — 33f0506 was an ExistsFilter listing the wrong property. So
     * each case below sends a real request against a fixture holding both a
     * row the filter must keep and a row it must drop, and asserts on which
     * rows came back rather than on how many.
     *
     * Eleven of the twenty-four parameters in the contract get a case of
     * their own: one per filter *kind* per resource — an exists, a date
     * operator, a relation, a sort — rather than one per parameter. The
     * remainder are the same kind on another field (`startAt[before]` beside
     * `startAt[after]`, `order[name]` on four resources), and they rest on
     * the three checks every parameter gets: declared, translated to
     * Elasticsearch, and naming a field the index holds. Repeating the
     * fixtures for each would grow a world nobody reads without testing a
     * new way for a filter to be wrong. Written down rather than left
     * implicit, because "which ones are covered" is the question a reader of
     * this file will have.
     *
     * @param list<string> $expected labels that must come back
     * @param list<string> $excluded labels that must not
     */
    #[DataProvider('narrowingCases')]
    public function testTheFilterChangesTheResult(string $path, string $query, string $labelField, array $expected, array $excluded): void
    {
        $this->loadFixtures('QueryParameters.yaml');

        $user = $this->getFixture('filter_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $labels = $this->labelsOf($path.'?'.$query, $labelField);

        foreach ($expected as $label) {
            self::assertContains($label, $labels, sprintf('GET %s?%s dropped "%s", which it should keep.', $path, $query, $label));
        }

        foreach ($excluded as $label) {
            self::assertNotContains($label, $labels, sprintf(
                "GET %s?%s returned \"%s\", which the filter should have excluded.\nA declared parameter that narrows nothing is the shape of 33f0506: the client asks, the API answers with everything, and nothing reports it.",
                $path,
                $query,
                $label,
            ));
        }
    }

    /**
     * Ordering, asserted on the sequence rather than on the membership —
     * `order[…]` is the one parameter family that cannot change *which* rows
     * come back, only their order, so a count-based check would pass on a
     * filter that was ignored entirely.
     *
     * @param list<string> $expected the labels, in the order they must arrive
     */
    #[DataProvider('orderingCases')]
    public function testTheSortChangesTheOrder(string $path, string $query, string $labelField, array $expected): void
    {
        $this->loadFixtures('QueryParameters.yaml');

        $user = $this->getFixture('filter_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $labels = $this->labelsOf($path.'?'.$query, $labelField);

        self::assertSame($expected, array_values(array_intersect($labels, $expected)), sprintf(
            'GET %s?%s did not order the collection as asked. An ignored sort returns the rows in insertion order, which looks right often enough to go unnoticed.',
            $path,
            $query,
        ));
    }

    /** @return iterable<string, array{string, string, string, list<string>, list<string>}> */
    public static function narrowingCases(): iterable
    {
        yield 'tasks not completed' => [
            '/api/tasks', 'exists[completedAt]=false', 'title',
            ['Open, due in March', 'Open, no due date'],
            ['Completed'],
        ];

        // 33f0506 itself: the dashboard's "no due date" column.
        yield 'tasks without a due date' => [
            '/api/tasks', 'exists[dueDate]=false', 'title',
            ['Open, no due date'],
            ['Open, due in March', 'Completed'],
        ];

        yield 'tasks due before a date' => [
            '/api/tasks', 'dueDate[before]=2026-04-01T00:00:00%2B00:00', 'title',
            ['Open, due in March'],
            ['Completed'],
        ];

        yield 'recurring events only' => [
            '/api/events', 'exists[rrule]=true', 'summary',
            ['A recurring event, late'],
            ['A one-off event, early'],
        ];

        yield 'events starting after a date' => [
            '/api/events', 'startAt[after]=2026-04-01T00:00:00%2B00:00', 'summary',
            ['A recurring event, late'],
            ['A one-off event, early'],
        ];

        yield 'unread notifications' => [
            '/api/notifications', 'exists[readAt]=false', 'title',
            ['Unread'],
            ['Read'],
        ];

        // The parameter the mobile app sends to show one account's history.
        // It is passed as the IRI the provider handed out, never as a bare
        // id (c359b43).
        yield 'transactions of one account' => [
            '/api/transactions', 'account=IRI:filter_account', 'label',
            ['On account A'],
            ['On account B'],
        ];
    }

    /** @return iterable<string, array{string, string, string, list<string>}> */
    public static function orderingCases(): iterable
    {
        yield 'tasks by due date, ascending' => [
            '/api/tasks', 'order[dueDate]=asc', 'title',
            ['Open, due in March', 'Completed'],
        ];

        yield 'events by start, descending' => [
            '/api/events', 'order[startAt]=desc', 'summary',
            ['A recurring event, late', 'A one-off event, early'],
        ];

        yield 'transactions by booking date, descending' => [
            '/api/transactions', 'order[bookedAt]=desc', 'label',
            ['On account B', 'On account A'],
        ];

        yield 'agendas by name, ascending' => [
            '/api/agendas', 'order[name]=asc', 'name',
            ['Agenda A', 'Agenda B'],
        ];
    }

    /**
     * @return list<string>
     */
    private function labelsOf(string $uri, string $labelField): array
    {
        // Late-bound because a fixture's ULID is not known when the data
        // provider runs.
        if (str_contains($uri, 'IRI:')) {
            $uri = (string) preg_replace_callback(
                '/IRI:([a-z_]+)/',
                fn (array $m): string => rawurlencode('/api/accounts/'.$this->getFixture($m[1])->getId()),
                $uri,
            );
        }

        $this->client->request('GET', $uri, [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $this->authHeaders(),
        ));

        self::assertResponseIsSuccessful(sprintf('GET %s failed: %s', $uri, (string) $this->client->getResponse()->getContent()));

        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertArrayHasKey('member', $body);

        return array_map(
            static fn (array $member): string => (string) $member[$labelField],
            $body['member'],
        );
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
                    if ($operation instanceof GetCollection && '/api'.$operation->getUriTemplate() === $path.'{._format}') {
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
