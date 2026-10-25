<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Entity\ActivityStatus;
use Maggie\Core\Entity\User;
use Maggie\Core\Enum\Activity;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * The activity in progress, shared between devices (MAG-613): created then
 * replaced by PUT, one row per user and activity, read back by its owner only,
 * removed by DELETE, published on /api/activity-status/{activity}. Nothing to
 * search, so never indexed.
 */
class ActivityStatusControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string ENDPOINT = '/api/activity-status';
    private const string TOPIC = '/api/activity-status/cuisine';

    private KernelBrowser $client;
    private MockClock $clock;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        $this->clock = new MockClock('2026-10-25 12:00:00 UTC');
        Clock::set($this->clock);
        $this->loadFixtures('activity_status.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** @return array<string, mixed>|null */
    private function request(string $method, string $endpoint, mixed $body = null, bool $authenticated = true): ?array
    {
        $this->client->request(
            $method,
            $endpoint,
            [],
            [],
            ($authenticated ? $this->authHeaders() : []) + ['CONTENT_TYPE' => 'application/json'],
            null === $body ? null : (\is_string($body) ? $body : json_encode($body, \JSON_THROW_ON_ERROR)),
        );

        $content = (string) $this->client->getResponse()->getContent();

        return '' === $content ? null : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private static function cooking(string $recipeId = '01JB0000000000000000000001', int $step = 2): array
    {
        return [
            'origin' => ['kind' => 'recipe', 'id' => $recipeId],
            'state' => [
                'queue' => [['recipeId' => $recipeId, 'servings' => 4]],
                'recipeIndex' => 0,
                'step' => $step,
                'doneSteps' => [[0, 1]],
                'guests' => 4,
                'phase' => 'cooking',
            ],
        ];
    }

    /** @return list<ActivityStatus> */
    private function storedFor(string $userRef): array
    {
        $this->em()->clear();
        $user = $this->em()->find(User::class, $this->getFixture($userRef)->getId());

        return $this->em()->getRepository(ActivityStatus::class)->findBy(['user' => $user]);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->request('GET', self::ENDPOINT, authenticated: false);
        self::assertResponseStatusCodeSame(401);
        $this->request('GET', self::ENDPOINT.'/cuisine', authenticated: false);
        self::assertResponseStatusCodeSame(401);
        $this->request('PUT', self::ENDPOINT.'/cuisine', self::cooking(), authenticated: false);
        self::assertResponseStatusCodeSame(401);
        $this->request('DELETE', self::ENDPOINT.'/cuisine', authenticated: false);
        self::assertResponseStatusCodeSame(401);

        self::assertSame([], $this->storedFor('test_user'));
        self::assertCount(1, $this->storedFor('other_user'));
    }

    public function testAnUnknownActivityReturns400(): void
    {
        foreach ([['GET', self::ENDPOINT.'?activity=jardin'], ['GET', self::ENDPOINT.'/jardin'], ['PUT', self::ENDPOINT.'/jardin'], ['DELETE', self::ENDPOINT.'/jardin']] as [$method, $path]) {
            $response = $this->request($method, $path, 'PUT' === $method ? self::cooking() : null);
            self::assertResponseStatusCodeSame(400, $method.' '.$path);
            self::assertStringContainsString('cuisine, comptes', (string) $response['error']);
        }
        self::assertSame([], $this->storedFor('test_user'));
    }

    /** @return iterable<string, array{mixed}> */
    public static function badBodies(): iterable
    {
        $valid = self::cooking();

        yield 'not json' => ['not json'];
        yield 'a list' => [[1, 2]];
        yield 'no origin' => [['state' => $valid['state']]];
        yield 'origin not an object' => [['origin' => 'recipe', 'state' => $valid['state']]];
        yield 'origin without kind' => [['origin' => ['id' => 'x'], 'state' => $valid['state']]];
        yield 'origin without id' => [['origin' => ['kind' => 'recipe'], 'state' => $valid['state']]];
        yield 'origin with an empty id' => [['origin' => ['kind' => 'recipe', 'id' => ' '], 'state' => $valid['state']]];
        yield 'origin id not a string' => [['origin' => ['kind' => 'recipe', 'id' => 42], 'state' => $valid['state']]];
        yield 'origin id too long' => [['origin' => ['kind' => 'recipe', 'id' => str_repeat('a', 65)], 'state' => $valid['state']]];
        yield 'origin kind of another activity' => [['origin' => ['kind' => 'review', 'id' => 'x'], 'state' => $valid['state']]];
        yield 'no state' => [['origin' => $valid['origin']]];
        yield 'state null' => [['origin' => $valid['origin'], 'state' => null]];
        yield 'state a string' => [['origin' => $valid['origin'], 'state' => 'step 2']];
        yield 'state a list' => [['origin' => $valid['origin'], 'state' => [1, 2]]];
        yield 'state an empty list' => [['origin' => $valid['origin'], 'state' => []]];
        yield 'state too big' => [['origin' => $valid['origin'], 'state' => ['blob' => str_repeat('a', ActivityStatus::MAX_STATE_BYTES)]]];
    }

    #[DataProvider('badBodies')]
    public function testABadBodyReturns400WithItsErrorAndWritesNothing(mixed $body): void
    {
        $response = $this->request('PUT', self::ENDPOINT.'/cuisine', $body);

        self::assertResponseStatusCodeSame(400);
        self::assertIsString($response['error'] ?? null);
        self::assertSame([], $this->storedFor('test_user'));
        $this->assertMercureUpdateCount(0);
    }

    public function testAStateJustUnderTheLimitIsAccepted(): void
    {
        // {"blob":"…"} is 11 bytes around the string.
        $body = ['origin' => ['kind' => 'meal', 'id' => 'm1'], 'state' => ['blob' => str_repeat('a', ActivityStatus::MAX_STATE_BYTES - 11)]];

        $this->request('PUT', self::ENDPOINT.'/cuisine', $body);

        self::assertResponseStatusCodeSame(201);
    }

    public function testPutCreatesThenReplacesOneRowAndPublishes(): void
    {
        $created = $this->request('PUT', self::ENDPOINT.'/cuisine', self::cooking());

        self::assertResponseStatusCodeSame(201);
        self::assertSame([
            'activity' => 'cuisine',
            'origin' => ['kind' => 'recipe', 'id' => '01JB0000000000000000000001'],
            'state' => self::cooking()['state'],
            'startedAt' => '2026-10-25T12:00:00+00:00',
            'updatedAt' => '2026-10-25T12:00:00+00:00',
        ], $created);

        $stored = $this->storedFor('test_user');
        self::assertCount(1, $stored);
        self::assertSame(Activity::Cuisine, $stored[0]->getActivity());
        self::assertSame('recipe', $stored[0]->getOriginKind());
        self::assertSame(self::cooking()['state'], json_decode($stored[0]->getState(), true));

        $this->assertMercurePublishedOnce(self::TOPIC);
        $this->assertMercureUpdatePublished('/users/'.$this->getFixture('test_user')->getId().self::TOPIC);
        $payload = $this->mercurePayloadsOn(self::TOPIC)[0];
        self::assertSame(self::TOPIC, $payload['@id']);
        self::assertSame(self::cooking()['state'], $payload['state']);
        self::assertSame(['kind' => 'recipe', 'id' => '01JB0000000000000000000001'], $payload['origin']);
        $this->assertNoElasticsearchIndexDispatched(ActivityStatus::class);

        // Ten minutes later, one step further, on the other device.
        $this->clock->modify('+10 minutes');
        $this->resetMercure();
        $replaced = $this->request('PUT', self::ENDPOINT.'/cuisine', self::cooking(step: 3));

        self::assertResponseStatusCodeSame(200);
        self::assertSame(3, $replaced['state']['step']);
        self::assertSame('2026-10-25T12:00:00+00:00', $replaced['startedAt'], 'the same activity carries on');
        self::assertSame('2026-10-25T12:10:00+00:00', $replaced['updatedAt']);

        $stored = $this->storedFor('test_user');
        self::assertCount(1, $stored, 'one row per user and activity');
        self::assertSame(3, json_decode($stored[0]->getState(), true)['step']);

        $this->assertMercurePublishedOnce(self::TOPIC);
        self::assertSame(3, $this->mercurePayloadsOn(self::TOPIC)[0]['state']['step']);
        $this->assertNoElasticsearchIndexDispatched(ActivityStatus::class);

        $others = $this->storedFor('other_user');
        self::assertCount(1, $others);
        self::assertSame('{"recipeIndex":0,"step":3}', $others[0]->getState(), 'another user\'s activity is untouched');
    }

    public function testAnotherOriginStartsTheActivityAgain(): void
    {
        $this->request('PUT', self::ENDPOINT.'/cuisine', self::cooking());
        $this->clock->modify('+1 hour');

        $replaced = $this->request('PUT', self::ENDPOINT.'/cuisine', [
            'origin' => ['kind' => 'meal', 'id' => '01JB0000000000000000000009'],
            'state' => ['step' => 0],
        ]);

        self::assertResponseStatusCodeSame(200);
        self::assertSame(['kind' => 'meal', 'id' => '01JB0000000000000000000009'], $replaced['origin']);
        self::assertSame('2026-10-25T13:00:00+00:00', $replaced['startedAt']);
        self::assertCount(1, $this->storedFor('test_user'));
    }

    public function testTheStateIsKeptAsSentEmptyObjectsIncluded(): void
    {
        $this->request('PUT', self::ENDPOINT.'/cuisine', '{"origin":{"kind":"recipe","id":"r1"},"state":{"timers":{},"doneSteps":[],"note":"crème brûlée"}}');
        self::assertResponseStatusCodeSame(201);

        $this->client->request('GET', self::ENDPOINT.'/cuisine', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $state = json_decode((string) $this->client->getResponse()->getContent(), false, 512, \JSON_THROW_ON_ERROR)->state;
        self::assertEquals((object) ['timers' => new \stdClass(), 'doneSteps' => [], 'note' => 'crème brûlée'], $state, 'an empty object stays an object, an empty list a list');
    }

    public function testGetReadsOnlyTheCurrentUsersActivities(): void
    {
        self::assertSame(['member' => []], $this->request('GET', self::ENDPOINT));
        $this->request('GET', self::ENDPOINT.'/cuisine');
        self::assertResponseStatusCodeSame(404, 'another user\'s activity is not mine');

        $this->request('PUT', self::ENDPOINT.'/cuisine', self::cooking());
        $this->clock->modify('+1 minute');
        $this->request('PUT', self::ENDPOINT.'/comptes', ['origin' => ['kind' => 'review', 'id' => '2026-10'], 'state' => ['step' => 'balance']]);

        $all = $this->request('GET', self::ENDPOINT);
        self::assertResponseIsSuccessful();
        self::assertSame(['comptes', 'cuisine'], array_column($all['member'], 'activity'), 'the latest touched first');

        $filtered = $this->request('GET', self::ENDPOINT.'?activity=cuisine');
        self::assertSame(['cuisine'], array_column($filtered['member'], 'activity'));
        self::assertSame(2, $filtered['member'][0]['state']['step']);

        $one = $this->request('GET', self::ENDPOINT.'/cuisine');
        self::assertResponseIsSuccessful();
        self::assertSame($filtered['member'][0], $one);
        self::assertSame('01JB0000000000000000000001', $one['origin']['id']);
    }

    public function testDeleteRemovesTheActivityAndPublishes(): void
    {
        $this->request('PUT', self::ENDPOINT.'/cuisine', self::cooking());
        $this->resetMercure();

        $this->request('DELETE', self::ENDPOINT.'/cuisine');

        self::assertResponseStatusCodeSame(204);
        self::assertSame([], $this->storedFor('test_user'));
        self::assertCount(1, $this->storedFor('other_user'), 'only the current user\'s');
        $this->assertMercureDeletePublished(self::TOPIC);
        self::assertSame(self::TOPIC, $this->mercurePayloadsOn(self::TOPIC)[0]['@id']);
        $this->assertElasticsearchDeleteDispatchedNowhere();

        $this->request('GET', self::ENDPOINT.'/cuisine');
        self::assertResponseStatusCodeSame(404);

        // Ending it again, or « Recommencer » before anything was saved: not an error, nothing published.
        $this->resetMercure();
        $this->request('DELETE', self::ENDPOINT.'/cuisine');
        self::assertResponseStatusCodeSame(204);
        $this->assertMercureUpdateCount(0);
    }

    private function assertElasticsearchDeleteDispatchedNowhere(): void
    {
        $deletes = array_filter(
            $this->getAsyncTransport()->getSent(),
            static fn ($envelope): bool => $envelope->getMessage() instanceof DeleteDocumentCommand,
        );

        self::assertSame([], $deletes, 'Nothing is indexed, so nothing is deindexed.');
    }
}
