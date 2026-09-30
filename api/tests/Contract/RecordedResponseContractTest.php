<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * Records real API responses into contract/responses/, for the mobile DTO
 * tests to deserialise.
 *
 * The mobile DTOs are hand-written Kotlin data classes with a default on
 * nearly every field, so kotlinx.serialization does not complain when one is
 * missing — it fills the default and the screen shows a value the user never
 * chose. Testing them against JSON written by hand only proves the hand was
 * consistent with itself. Testing them against what the API actually emits is
 * the point.
 *
 * So these responses are not fixtures in the usual sense: they are recordings.
 * The API produces them, this test writes them down, DtoContractTest on the
 * mobile side reads them back. When the API changes shape, the recording
 * changes in the same commit and the Kotlin test fails there and then,
 * without a stack, an emulator or a network.
 *
 * Regenerate with UPDATE_CONTRACT=1 — see ContractSnapshotTrait.
 */
final class RecordedResponseContractTest extends WebTestCase
{
    use ContractSnapshotTrait;
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    /**
     * Recording name => path. One per DTO the mobile app deserialises from a
     * collection, plus the two identifier-less items.
     *
     * @var array<string, string>
     */
    private const RECORDINGS = [
        'events.collection' => '/api/events',
        'tasks.collection' => '/api/tasks',
        'agendas.collection' => '/api/agendas',
        'recipes.collection' => '/api/recipes',
        'meals.collection' => '/api/meals',
        'ingredients.collection' => '/api/ingredients',
        'products.collection' => '/api/products',
        'stores.collection' => '/api/stores',
        'grocery_lists.collection' => '/api/grocery_lists',
        'notifications.collection' => '/api/notifications',
        'accounts.collection' => '/api/accounts',
        'categories.collection' => '/api/categories',
        'transactions.collection' => '/api/transactions',
        'users.me' => '/api/users/me',
        'user_preferences.me' => '/api/user_preferences/me',
    ];

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    public function testTheRecordingsAreCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        foreach (self::RECORDINGS as $name => $path) {
            $this->client->request('GET', $path, [], [], array_merge(
                ['HTTP_ACCEPT' => 'application/ld+json'],
                $this->authHeaders(),
            ));

            self::assertResponseIsSuccessful(sprintf('GET %s failed, so it cannot be recorded.', $path));

            $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
            self::assertIsArray($body);

            $this->assertMatchesContract(
                'responses/' . $name . '.json',
                self::stabilise($body),
                sprintf('GET %s no longer returns what it used to. The mobile DTOs are written against this response; check mobile/app/src/main/java/com/maggie/app/data/model/ before regenerating.', $path),
            );
        }
    }

    /**
     * Every collection carries at least one member, or the recording proves
     * nothing about the fields.
     */
    public function testEveryRecordedCollectionHasAMember(): void
    {
        foreach (array_keys(self::RECORDINGS) as $name) {
            if (!str_ends_with($name, '.collection')) {
                continue;
            }

            $path = self::contractPath('responses/' . $name . '.json');

            if (!is_file($path)) {
                // testTheRecordingsAreCurrent already reports the absence,
                // with the command that fixes it.
                continue;
            }

            $recorded = json_decode((string) file_get_contents($path), true, 512, \JSON_THROW_ON_ERROR);
            self::assertIsArray($recorded);
            self::assertNotEmpty(
                $recorded['member'] ?? [],
                sprintf('The recording "%s" has no member, so the mobile DTO test would deserialise an empty list and pass whatever the DTOs look like.', $name),
            );
        }
    }

    /**
     * Two things in these responses change on every run: the ULIDs, and the
     * audit timestamps the entities stamp in their constructors. Left alone,
     * the recordings would differ every time and the contract would turn into
     * noise — the failure mode of a snapshot that changes too often is that
     * nobody reads its diff.
     *
     * Both are replaced by fixed values of the same shape, so the clients
     * still see a ULID where they expect one and an ISO-8601 instant where
     * they expect one. Every other date — startAt, dueDate, bookedAt,
     * readAt — comes from the fixture and is already deterministic, so it is
     * left exactly as the API emitted it. That matters: a `bookedAt` that
     * switched from a date to a date-time would break the mobile parser, and
     * flattening it here would hide precisely that.
     *
     * @param array<mixed> $value
     * @return array<mixed>
     */
    private static function stabilise(array $value): array
    {
        $stable = [];

        foreach ($value as $key => $item) {
            if (\is_array($item)) {
                $stable[$key] = self::stabilise($item);

                continue;
            }

            if (\is_string($item) && \in_array($key, ['createdAt', 'updatedAt'], true)) {
                $stable[$key] = '2026-01-01T00:00:00+00:00';

                continue;
            }

            if (\is_string($item)) {
                // A bare ULID, or one at the end of an IRI.
                $item = (string) preg_replace('/\b[0-7][0-9A-HJKMNP-TV-Z]{25}\b/', '01ARZ3NDEKTSV4RRFFQ69G5FAV', $item);
            }

            $stable[$key] = $item;
        }

        return $stable;
    }
}
