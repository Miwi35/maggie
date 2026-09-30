<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Resource\Factory\ResourceMetadataCollectionFactoryInterface;
use ApiPlatform\Metadata\Resource\Factory\ResourceNameCollectionFactoryInterface;
use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\MercureTopic;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * The topic the API publishes to is the topic the clients subscribe to.
 *
 * This is the most profitable family in the MAG-93 analysis: five of the
 * repository's real-time regressions, four of them nothing more than one
 * string differing from another.
 *
 *   b333376  an absolute IRI in the topic, so it carried the host that
 *            generated it and no client ever matched it
 *   8380178  a Google sync publishing into the acting user's scope instead of
 *            the owner's
 *   c2d3758  the same update published twice, once scoped and once not
 *   e9c17b9  the client subscribed to one topic, the API published another
 *   6ba9859  a payload without the items, so the client had to re-read an
 *            Elasticsearch index that was not up to date yet
 *
 * None of them needs a browser or a running stack to catch. What they need is
 * one place that spells the topic — Maggie\Core\Mercure\MercureTopic — and
 * something comparing it to what the clients expect. This test is that
 * comparison on the API side, and it writes contract/mercure-topics.json so
 * the admin and mobile suites can make the same comparison on theirs.
 */
final class MercureTopicContractTest extends WebTestCase
{
    use ContractSnapshotTrait;
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->purgeDatabase();
        $this->resetMercure();
    }

    /**
     * The collection topic and the collection path are the same string.
     *
     * The middleware derives the topic from the entity's class name and API
     * Platform derives the path from the same name, by two different pieces
     * of code. They agree today; e9c17b9 is what it looks like when they stop
     * agreeing, and "category" → "categorys" is how it happens.
     */
    public function testEveryPublishedTopicMatchesTheResourcePath(): void
    {
        $checked = 0;

        foreach ($this->publishableCollections() as $resourceClass => $collectionPath) {
            self::assertSame(
                $collectionPath,
                MercureTopic::collection($resourceClass),
                sprintf(
                    'The Mercure topic for %s does not match its API Platform collection path. The clients subscribe to the path; they will never see these updates.',
                    $resourceClass,
                ),
            );
            ++$checked;
        }

        self::assertGreaterThan(0, $checked, 'No publishable API resource was found, so this test proved nothing.');
    }

    /**
     * Published for the admin and mobile suites to check themselves against.
     * MercureTopicsContractTest on the mobile side, and
     * useMercure.contract.test.ts on the admin side, read this file.
     */
    public function testTheSubscriptionPatternsArePublished(): void
    {
        $patterns = [];
        foreach ($this->publishableResources() as $resourceClass => $topic) {
            $patterns[(new \ReflectionClass($resourceClass))->getShortName()] = MercureTopic::subscriptionPattern($topic);
        }

        ksort($patterns);

        $this->assertMatchesContract(
            'mercure-topics.json',
            $patterns,
            'The set of Mercure topics the API publishes changed. Any client subscribing to a topic that is no longer published stops receiving updates, in silence.',
        );
    }

    /**
     * One update, one topic, relative, scoped to the user — asserted on a real
     * publish rather than on the helper that builds the string.
     */
    public function testARealPublishIsScopedRelativeAndSingle(): void
    {
        $user = $this->persistUser('mercure-owner@example.com', 'google-mercure-owner');
        $this->authenticateAsUser($user);

        $bus = self::getContainer()->get(MessageBusInterface::class);
        $bus->dispatch(new CreateTaskCommand(
            userId: (string) $user->getId(),
            title: 'A task whose update the client is waiting for',
        ));

        $updates = $this->getMercureHub()->getUpdates();

        // c2d3758: the same change published twice, once scoped and once not,
        // made every client apply it twice.
        self::assertCount(1, $updates, 'A change must be published exactly once.');

        $topics = $updates[0]->getTopics();
        self::assertCount(1, $topics, 'An update must carry exactly one topic.');
        $topic = $topics[0];

        // b333376: an absolute IRI carries the host it was generated on, and
        // the client subscribed from another one.
        self::assertStringStartsWith('/users/', $topic, sprintf('The topic "%s" is not relative and user-scoped.', $topic));
        self::assertStringNotContainsString('://', $topic, sprintf('The topic "%s" contains an absolute URL; the host is the publisher\'s, not the subscriber\'s (b333376).', $topic));

        self::assertMatchesRegularExpression(
            '#^/users/[0-9A-Za-z]+/api/tasks/[0-9A-Za-z]+$#',
            $topic,
            'The topic does not follow the /users/{userId}/api/{resource}/{id} convention the clients subscribe to.',
        );
        self::assertStringStartsWith('/users/' . $user->getId() . '/api/tasks/', $topic);

        $payload = json_decode($updates[0]->getData(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($payload);
        self::assertArrayHasKey('@id', $payload);
        self::assertStringStartsWith('/api/tasks/', $payload['@id'], 'The @id in the payload must be a relative IRI.');

        // 6ba9859: a payload that carries only the identifier forces the
        // client to re-read the collection, and the Elasticsearch index it
        // reads is not up to date yet.
        self::assertGreaterThan(1, \count($payload), 'The payload carries nothing but @id, so the client has to re-read the resource to learn what changed (6ba9859).');
    }

    /**
     * 8380178: the Google sync runs as one user and writes another's data.
     * The update must reach the owner, not whoever triggered it — otherwise
     * the owner's screen never refreshes and a stranger's does.
     */
    public function testThePublishIsScopedToTheOwnerNotTheActingUser(): void
    {
        $owner = $this->persistUser('owner@example.com', 'google-owner');
        $actor = $this->persistUser('actor@example.com', 'google-actor');

        // Authenticated as the actor, acting on the owner's data — what a
        // sync, an import or an agent call does.
        $this->authenticateAsUser($actor);
        $this->client->request('GET', '/api/users/me', [], [], $this->authHeaders());
        $this->resetMercure();

        $bus = self::getContainer()->get(MessageBusInterface::class);
        $bus->dispatch(new CreateTaskCommand(
            userId: (string) $owner->getId(),
            title: 'A task created for somebody else',
        ));

        $updates = $this->getMercureHub()->getUpdates();
        self::assertCount(1, $updates);

        $topic = $updates[0]->getTopics()[0];

        self::assertStringStartsWith('/users/' . $owner->getId() . '/', $topic, sprintf(
            "The update was published to \"%s\", which is not the owner's scope (%s).\nThe owner's screen will not refresh, and whoever triggered the change receives an update about data that is not theirs (8380178).",
            $topic,
            '/users/' . $owner->getId(),
        ));
        self::assertStringNotContainsString((string) $actor->getId(), $topic);
    }

    /**
     * Every entity that publishes decided, somewhere, what to say when it
     * does — in its own class or in a MercurePublishable parent it extends
     * deliberately (Ingredient takes Product's payload, and adds no field of
     * its own).
     *
     * What this rules out is an entity that inherits its payload from a
     * class that is not itself publishable — nobody chose what the clients
     * receive for it.
     *
     * It does not catch a payload provided by a trait:
     * ReflectionMethod::getDeclaringClass() reports the *using* class for a
     * trait method, so such an entity looks like it declared its own. The
     * emptiness of a payload is covered where it can be, on real publishes —
     * testARealPublishIsScopedRelativeAndSingle and
     * testTheGroceryListPayloadCarriesItsItems.
     */
    public function testEveryPublishableEntityOwnsItsPayload(): void
    {
        foreach (array_keys(iterator_to_array($this->publishableResources())) as $resourceClass) {
            $declaring = (new \ReflectionClass($resourceClass))->getMethod('toMercurePayload')->getDeclaringClass()->getName();

            self::assertTrue(
                is_a($declaring, MercurePublishable::class, true),
                sprintf(
                    '%s publishes, but its toMercurePayload() comes from %s, which is not itself a publishable entity — nobody chose what the clients receive.',
                    $resourceClass,
                    $declaring,
                ),
            );
        }
    }

    /**
     * The same flag is spelled one way over REST and another over Mercure,
     * and a client can only be written against one of them.
     *
     * Symfony serialises `isCushion()` as `cushion`, so that is what the
     * collection carries; the Mercure payloads are hand-written arrays and
     * kept the property name. Three flags diverge that way today, and the
     * mobile DTOs had all three wrong — they read their own defaults, so the
     * "Matelas" badge never appeared and no agenda was ever marked default.
     *
     * Category diverges further still and is pinned with them: REST sends
     * `parent` as an IRI, Mercure sends `parentId` as a bare id, so the name
     * and the shape both differ. Nothing subscribes to categories today,
     * which is the only reason it has cost nothing.
     *
     * The divergence is pinned rather than resolved: renaming either side
     * breaks clients written against it, and the two names are the contract
     * until somebody decides otherwise. What this test buys is that the
     * decision cannot be made by accident — change either spelling and it
     * fails here, next to the reason.
     */
    public function testTheMercureAndRestSpellingsOfTheSameFlagsAreBothPinned(): void
    {
        $payloadKeys = [
            \Maggie\Finance\Entity\Account::class => 'isCushion',
            \Maggie\Calendar\Entity\Agenda::class => 'isDefault',
            \Maggie\Finance\Entity\Transaction::class => 'isExceptional',
            \Maggie\Finance\Entity\Category::class => 'parentId',
        ];

        foreach ($payloadKeys as $entityClass => $key) {
            // Bounded by the method's own lines. A substr from a strpos that
            // missed would search the whole file, where each of these keys
            // also appears in toSearchDocument() — the test would then report
            // the payload as pinned after the payload method was deleted.
            $method = (new \ReflectionClass($entityClass))->getMethod('toMercurePayload');
            $lines = file((string) $method->getFileName(), \FILE_IGNORE_NEW_LINES);
            self::assertIsArray($lines);

            $payload = implode("\n", \array_slice(
                $lines,
                $method->getStartLine() - 1,
                $method->getEndLine() - $method->getStartLine() + 1,
            ));

            self::assertStringContainsString(sprintf("'%s' =>", $key), $payload, sprintf(
                '%s no longer publishes "%s" over Mercure. The REST collection spells it "%s"; a client written against one and reading the other gets its own default, silently.',
                $entityClass,
                $key,
                lcfirst(substr($key, 2)),
            ));
        }

        // And the REST side, from a recording rather than from the entity:
        // the serializer is what decides, not the getter's name.
        foreach ([
            'responses/accounts.collection.json' => 'cushion',
            'responses/agendas.collection.json' => 'default',
            'responses/transactions.collection.json' => 'exceptional',
            'responses/categories.collection.json' => 'parent',
        ] as $recording => $key) {
            $path = self::contractPath($recording);

            self::assertFileExists($path, sprintf(
                'The recording contract/%s is missing, so this pin checks nothing. Regenerate it: UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract',
                $recording,
            ));

            $body = json_decode((string) file_get_contents($path), true, 512, \JSON_THROW_ON_ERROR);
            self::assertIsArray($body);

            $keys = array_merge(...array_map(array_keys(...), $body['member']));
            self::assertContains($key, $keys, sprintf(
                'The REST collection no longer carries "%s". The mobile DTO declares it under that name through @SerialName; renaming it makes the field read its default instead.',
                $key,
            ));
        }
    }

    /**
     * 6ba9859 itself. The grocery list published an update that did not carry
     * the items, so the mobile app re-read /api/grocery_lists — served from
     * an Elasticsearch index the write had not reached yet — and redrew the
     * list it already had.
     *
     * The payload has to be enough to apply the change without asking again.
     */
    public function testTheGroceryListPayloadCarriesItsItems(): void
    {
        $payload = (new GroceryList())->toMercurePayload();

        self::assertArrayHasKey('items', $payload, 'The grocery list update does not carry its items, so the client has to re-read a possibly stale index to redraw the list (6ba9859).');
    }

    /**
     * Every API resource that publishes over Mercure, with the topic the
     * middleware derives for it.
     *
     * Not filtered on having a GetCollection: UserPreference is exposed only
     * as /user_preferences/me and still publishes to
     * /api/user_preferences/{id}, which the mobile settings screen
     * subscribes to. Leaving it out of the contract made that subscription
     * look wrong when it was the contract that was incomplete.
     *
     * @return iterable<class-string, string>
     */
    private function publishableResources(): iterable
    {
        foreach ($this->apiResourceClasses() as $resourceClass) {
            if (!is_a($resourceClass, MercurePublishable::class, true)) {
                continue;
            }

            yield $resourceClass => MercureTopic::collection($resourceClass);
        }
    }

    /**
     * The subset that is also served as a collection, with the path it is
     * served at — what the topic is compared against.
     *
     * @return iterable<class-string, string>
     */
    private function publishableCollections(): iterable
    {
        $container = self::getContainer();

        $metadata = $container->get(ResourceMetadataCollectionFactoryInterface::class);
        self::assertInstanceOf(ResourceMetadataCollectionFactoryInterface::class, $metadata);

        foreach ($this->apiResourceClasses() as $resourceClass) {
            if (!is_a($resourceClass, MercurePublishable::class, true)) {
                continue;
            }

            foreach ($metadata->create($resourceClass) as $resource) {
                foreach ($resource->getOperations() ?? [] as $operation) {
                    if (!$operation instanceof GetCollection) {
                        continue;
                    }

                    $uriTemplate = $operation->getUriTemplate();
                    if ($uriTemplate === null) {
                        continue;
                    }

                    yield $resourceClass => '/api' . str_replace('{._format}', '', $uriTemplate);

                    continue 3;
                }
            }
        }
    }

    /** @return iterable<class-string> */
    private function apiResourceClasses(): iterable
    {
        $names = self::getContainer()->get(ResourceNameCollectionFactoryInterface::class);
        self::assertInstanceOf(ResourceNameCollectionFactoryInterface::class, $names);

        yield from $names->create();
    }

    private function persistUser(string $email, string $googleId): User
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $user = new User();
        $user->setEmail($email);
        $user->setGoogleId($googleId);
        $user->setName($email);
        $em->persist($user);
        $em->flush();

        return $user;
    }
}
