<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Put;
use ApiPlatform\Metadata\Resource\Factory\ResourceMetadataCollectionFactoryInterface;
use ApiPlatform\Metadata\Resource\Factory\ResourceNameCollectionFactoryInterface;
use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserPreference;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * The identifier-less operations — /users/me, /user_preferences/me,
 * /safety_cushions/me — and the rule that keeps them working.
 *
 * a1deb6d: on a uriTemplate with no identifier, API Platform does not hydrate
 * the object the provider returned. It builds a fresh one, so every field the
 * request left out came back as the entity's default and was written as such.
 * The admin changed the theme and lost the timezone; the mobile app changed
 * the timezone and lost the theme. Neither client saw an error — the response
 * was a 200 carrying the wrong object.
 *
 * CLAUDE.md has carried the rule ever since. What it did not have is a test,
 * which is why this file exists. Two halves:
 *
 *   - the convention: no Patch or Put may be declared on an identifier-less
 *     uriTemplate, whatever anybody adds later;
 *   - the behaviour: a partial PATCH, sent the way both clients send it,
 *     preserves the fields it did not mention.
 */
final class MeOperationContractTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    /**
     * What admin useUserPreferences.UserPreference and mobile
     * data.model.UserPreference both declare. Every one of them has a default
     * on the client side, so a field missing from a response is not an error
     * there — it is a setting silently reverting.
     */
    private const CLIENT_DTO_FIELDS = [
        'id',
        'theme',
        'locale',
        'timezone',
        'defaultCalendarView',
        'enabledAgendaIds',
        'notificationsEnabled',
    ];

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    /**
     * The guard for a1deb6d. It reads the resource metadata rather than the
     * entity files, so it also catches an operation declared in a config file
     * or added by a bundle.
     */
    public function testNoWriteOperationIsDeclaredOnAnIdentifierLessUriTemplate(): void
    {
        $container = self::getContainer();

        $names = $container->get(ResourceNameCollectionFactoryInterface::class);
        self::assertInstanceOf(ResourceNameCollectionFactoryInterface::class, $names);

        $metadata = $container->get(ResourceMetadataCollectionFactoryInterface::class);
        self::assertInstanceOf(ResourceMetadataCollectionFactoryInterface::class, $metadata);

        foreach ($names->create() as $resourceClass) {
            foreach ($metadata->create($resourceClass) as $resource) {
                foreach ($resource->getOperations() ?? [] as $operation) {
                    if (!$operation instanceof Patch && !$operation instanceof Put) {
                        continue;
                    }

                    $uriTemplate = $operation->getUriTemplate();

                    // Any variable segment counts as an identifier, not only
                    // one spelled {id}: API Platform lets an operation name
                    // its own, and flagging {ulid} as identifier-less would
                    // be a false positive somebody has to argue with.
                    // {._format} is the format suffix every template carries.
                    if (null === $uriTemplate) {
                        continue;
                    }

                    if (1 === preg_match('/\{(?!\._format)[^}]+}/', $uriTemplate)) {
                        continue;
                    }

                    self::fail(sprintf(
                        "%s declares a %s on \"%s\", a uriTemplate with no identifier.\nAPI Platform does not populate the object the provider returns for such an operation — it instantiates a new one, so every field the request omits is silently reset to its default (a1deb6d).\nUse a dedicated controller that applies only the fields present, as UpdateUserPreferenceController does.",
                        $resourceClass,
                        $operation instanceof Put ? 'Put' : 'Patch',
                        $uriTemplate,
                    ));
                }
            }
        }

        self::assertTrue(true, 'No identifier-less write operation is declared.');
    }

    /**
     * The round trip both clients perform: read the preferences, send back
     * one changed field, keep the response as the new state.
     *
     * The admin (useUserPreferences.ts) and the mobile app
     * (MaggieApiService.updateUserPreferences) both send
     * application/merge-patch+json with a partial body, and both replace
     * their local state with the response — so a field missing from the
     * response is a field lost on screen, not only in the database.
     */
    public function testAPartialPatchKeepsTheFieldsItDoesNotMention(): void
    {
        $preference = $this->existingPreference();

        $this->patchPreferences(['theme' => 'dark']);

        self::assertResponseIsSuccessful();
        $body = $this->decodeResponse();

        self::assertSame('dark', $body['theme'], 'The field the client sent was not applied.');
        self::assertSame('en', $body['locale'], 'locale was reset although the request never mentioned it — the shape of a1deb6d.');
        self::assertSame('Europe/Lisbon', $body['timezone'], 'timezone was reset although the request never mentioned it.');
        self::assertSame('week', $body['defaultCalendarView'], 'defaultCalendarView was reset although the request never mentioned it.');
        self::assertFalse($body['notificationsEnabled'], 'notificationsEnabled was reset although the request never mentioned it.');
        self::assertSame(['agenda-one'], $body['enabledAgendaIds'], 'enabledAgendaIds was reset although the request never mentioned it.');

        // Database state, not just the response: the response is built from
        // the refreshed entity, but a handler that wrote defaults and a
        // response that echoed the request would both look right above.
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->getRepository(UserPreference::class)->find($preference->getId());
        self::assertInstanceOf(UserPreference::class, $stored);
        self::assertSame('dark', $stored->getTheme());
        self::assertSame('en', $stored->getLocale());
        self::assertSame('Europe/Lisbon', $stored->getTimezone());
        self::assertSame('week', $stored->getDefaultCalendarView());
        self::assertFalse($stored->isNotificationsEnabled());
        self::assertSame(['agenda-one'], $stored->getEnabledAgendaIds());
    }

    /**
     * Two successive partial patches, because that is what a settings screen
     * does: one control at a time. The second must not undo the first.
     */
    public function testSuccessivePartialPatchesAccumulate(): void
    {
        $this->existingPreference();

        $this->patchPreferences(['theme' => 'dark']);
        self::assertResponseIsSuccessful();

        $this->patchPreferences(['timezone' => 'Europe/Paris']);
        self::assertResponseIsSuccessful();

        $body = $this->decodeResponse();
        self::assertSame('dark', $body['theme'], 'The second patch undid the first.');
        self::assertSame('Europe/Paris', $body['timezone']);
    }

    /**
     * The GET both clients read on start-up. Their DTOs
     * (admin useUserPreferences.UserPreference, mobile
     * data.model.UserPreference) list these keys and nothing else optional —
     * a missing one lands as the DTO's default, which is indistinguishable
     * from the user having chosen it.
     */
    public function testTheGetCarriesEveryFieldTheClientDtosDeclare(): void
    {
        $this->existingPreference();

        $this->client->request('GET', '/api/user_preferences/me', [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $this->authHeaders(),
        ));

        self::assertResponseIsSuccessful();
        $body = $this->decodeResponse();

        foreach (self::CLIENT_DTO_FIELDS as $field) {
            self::assertArrayHasKey($field, $body, sprintf('The client DTOs declare "%s"; the response does not carry it.', $field));
        }
    }

    /**
     * The PATCH response and the GET response describe the same resource, and
     * both clients feed both into the same DTO — the admin replaces its state
     * with whichever came last, the mobile app deserialises both into
     * data.model.UserPreference.
     *
     * They are allowed to differ: the GET is API Platform's and carries the
     * JSON-LD envelope plus the audit fields, the PATCH is a hand-written
     * controller's. What they may not do is disagree about the fields the
     * clients read, in either direction — a field the PATCH omits lands in
     * the DTO as its default, which looks exactly like the user having chosen
     * it.
     */
    public function testThePatchAndTheGetAgreeOnTheFieldsTheClientsRead(): void
    {
        $this->existingPreference();

        $this->client->request('GET', '/api/user_preferences/me', [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $this->authHeaders(),
        ));
        self::assertResponseIsSuccessful();
        $read = $this->decodeResponse();

        $this->patchPreferences(['theme' => 'dark']);
        self::assertResponseIsSuccessful();
        $written = $this->decodeResponse();

        foreach (self::CLIENT_DTO_FIELDS as $field) {
            self::assertArrayHasKey($field, $read, sprintf('The GET does not carry "%s", which the client DTOs declare.', $field));
            self::assertArrayHasKey($field, $written, sprintf('The PATCH response does not carry "%s", which the client DTOs declare. The clients replace their state with this response, so the field lands as the DTO default.', $field));
        }

        // Nothing the PATCH invents on its own either: a key only the write
        // path returns is a key the read path will drop on the next refresh,
        // and the screen changes without anybody touching it.
        $envelope = ['@context', '@id', '@type'];
        $extra = array_diff(array_keys($written), array_keys($read), $envelope);

        self::assertSame([], array_values($extra), 'The PATCH response carries fields the GET does not; the next refresh would drop them.');
    }

    public function testTheMeOperationsAreAllReachable(): void
    {
        $this->existingPreference();

        foreach (['/api/users/me', '/api/user_preferences/me', '/api/safety_cushions/me'] as $path) {
            $this->client->request('GET', $path, [], [], array_merge(
                ['HTTP_ACCEPT' => 'application/ld+json'],
                $this->authHeaders(),
            ));

            self::assertResponseIsSuccessful(sprintf('GET %s is no longer reachable.', $path));
        }
    }

    public function testThePatchRequiresAuthentication(): void
    {
        $this->existingPreference();

        $this->client->request('PATCH', '/api/user_preferences/me', [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], json_encode(['theme' => 'dark'], \JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    /**
     * A preference row whose every field differs from the entity's default,
     * so "the field was preserved" cannot be confused with "the field was
     * reset to a value that happens to match".
     */
    private function existingPreference(): UserPreference
    {
        $this->purgeDatabase();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $user = new User();
        $user->setEmail('me-operation@example.com');
        $user->setGoogleId('google-me-operation');
        $user->setName('Me Operation');
        $em->persist($user);

        $preference = (new UserPreference())
            ->setUser($user)
            ->setTheme('light')
            ->setLocale('en')
            ->setTimezone('Europe/Lisbon')
            ->setDefaultCalendarView('week')
            ->setEnabledAgendaIds(['agenda-one'])
            ->setNotificationsEnabled(false);
        $em->persist($preference);
        $em->flush();

        $this->authenticateAsUser($user);

        return $preference;
    }

    /** @param array<string, mixed> $payload */
    private function patchPreferences(array $payload): void
    {
        $this->client->request('PATCH', '/api/user_preferences/me', [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, \JSON_THROW_ON_ERROR));
    }

    /** @return array<string, mixed> */
    private function decodeResponse(): array
    {
        $decoded = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($decoded);

        /* @var array<string, mixed> $decoded */
        return $decoded;
    }
}
