<?php

namespace Maggie\Health\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Entity\WorkoutExercise;
use Maggie\Health\Entity\WorkoutSet;
use Maggie\Health\Enum\WorkoutVariant;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** A session's exercises, prescribed and done, its difficulty and variant (MAG-775). */
class WorkoutExercisesApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('workout.yaml');
        $this->resetMercure();
    }

    private function login(): void
    {
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);
        $this->authenticateAsUser($user);
    }

    /** @param array<string, mixed>|null $body */
    private function send(string $method, string $uri, ?array $body = null, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request($method, $uri, [], [], $authenticated ? array_merge($headers, $this->authHeaders()) : $headers, null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR));
    }

    /** @return array<string, mixed> */
    private function response(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function reload(string $id): Workout
    {
        $workout = $this->em()->find(Workout::class, $id);
        \assert($workout instanceof Workout);

        return $workout;
    }

    private function iri(string $fixture): string
    {
        return '/api/workouts/'.$this->getFixture($fixture)->getId();
    }

    /** @return list<array<string, mixed>> « Séance A »: six exercises, as the coach prescribes them */
    private static function sessionA(): array
    {
        $exercise = static fn (string $slug, int $step, int $sets, int $target, int $min, int $max, int $rest, array $more = []): array => $more + [
            'exercise' => $slug, 'step' => $step, 'sets' => $sets, 'target' => $target, 'targetMin' => $min, 'targetMax' => $max, 'restSeconds' => $rest,
        ];

        return [
            $exercise('pull-up-negative', 5, 3, 5, 3, 5, 120, ['tempo' => true]),
            $exercise('goblet-squat', 5, 3, 10, 8, 12, 90, ['loadKg' => 10, 'targetRir' => 2]),
            $exercise('push-up', 4, 3, 8, 6, 10, 90),
            $exercise('bent-over-bag-row', 4, 3, 10, 8, 12, 90, ['loadKg' => 12.5]),
            $exercise('glute-bridge', 1, 3, 12, 10, 15, 60, ['cue' => 'Serre les fessiers en haut']),
            $exercise('plank', 2, 3, 30, 20, 45, 60),
        ];
    }

    /** @param array<string, mixed> $override */
    private function create(array $override = []): string
    {
        $this->send('POST', '/api/workouts', $override + [
            'type' => 'strength',
            'title' => 'Séance A',
            'startedAt' => '2026-10-27T18:00:00+01:00',
            'durationMinutes' => 45,
            'source' => 'planned',
            'variant' => 'normal',
            'exercises' => self::sessionA(),
        ]);
        self::assertResponseStatusCodeSame(201, (string) $this->client->getResponse()->getContent());

        return (string) $this->response()['id'];
    }

    public function testAPlannedSessionOfSixExercisesReadsBackAsSent(): void
    {
        $this->login();

        $id = $this->create();

        $exercises = $this->response()['exercises'];
        self::assertSame(['pull-up-negative', 'goblet-squat', 'push-up', 'bent-over-bag-row', 'glute-bridge', 'plank'], array_column($exercises, 'exercise'));
        self::assertSame([5, 10, 8, 10, 12, 30], array_column($exercises, 'target'));
        self::assertEquals([null, 10, null, 12.5, null, null], array_map(static fn (array $e): mixed => $e['loadKg'], $exercises));
        self::assertSame([120, 90, 90, 90, 60, 60], array_column($exercises, 'restSeconds'));
        self::assertSame('Tractions négatives', $exercises[0]['name']);
        self::assertTrue($exercises[0]['tempo']);
        self::assertSame('seconds', $exercises[5]['unit']);
        self::assertSame([], $exercises[0]['done']);
        self::assertSame('normal', $this->response()['variant']);
        self::assertSame(array_column($exercises, 'exercise'), array_column($this->mercurePayloadsOn('/api/workouts/'.$id)[0]['exercises'], 'exercise'));

        $this->send('GET', '/api/workouts/'.$id);
        self::assertResponseIsSuccessful();
        self::assertSame($exercises, $this->response()['exercises']);

        $workout = $this->reload($id);
        self::assertSame(WorkoutVariant::Normal, $workout->getVariant());
        self::assertSame(6, $this->em()->getRepository(WorkoutExercise::class)->count([]));
    }

    public function testAPatchReplacesTheWholeList(): void
    {
        $this->login();
        $id = $this->create();
        $this->resetMercure();

        $this->send('PATCH', '/api/workouts/'.$id, ['exercises' => [self::sessionA()[5], self::sessionA()[1]]]);

        self::assertResponseIsSuccessful();
        self::assertSame(['plank', 'goblet-squat'], array_column($this->reload($id)->getExercises(), 'exercise'));
        self::assertSame(2, $this->em()->getRepository(WorkoutExercise::class)->count([]));
        self::assertSame(['plank', 'goblet-squat'], array_column($this->mercurePayloadsOn('/api/workouts/'.$id)[0]['exercises'], 'exercise'));

        $this->send('PATCH', '/api/workouts/'.$id, ['title' => 'Séance A bis']);
        self::assertResponseIsSuccessful();
        self::assertCount(2, $this->reload($id)->getExercises(), 'a PATCH without exercises keeps them');
    }

    public function testADoneSessionKeepsItsSetsItsFeelingAndItsDifficulty(): void
    {
        $this->login();
        $squat = self::sessionA()[1];
        $squat['done'] = [['reps' => 8, 'loadKg' => 10], ['reps' => 8, 'loadKg' => 10], ['reps' => 7, 'loadKg' => 10]];
        $squat['feeling'] = 'right';

        $id = $this->create(['source' => 'manual', 'exercises' => [$squat], 'difficulty' => 7]);

        $workout = $this->reload($id);
        self::assertSame(7, $workout->getDifficulty());
        $read = $workout->getExercises()[0];
        self::assertSame('right', $read['feeling']);
        self::assertSame([['reps' => 8, 'loadKg' => 10.0], ['reps' => 8, 'loadKg' => 10.0], ['reps' => 7, 'loadKg' => 10.0]], $read['done']);
        self::assertSame(3, $this->em()->getRepository(WorkoutSet::class)->count([]));
        self::assertSame(7, $this->response()['difficulty']);
    }

    /** @return iterable<string, array{array<string, mixed>, string}> */
    public static function refusals(): iterable
    {
        $squat = self::sessionA()[1];
        $plank = self::sessionA()[5];
        $pushUp = self::sessionA()[2];

        yield 'an unknown slug' => [['exercises' => [['exercise' => 'moonwalk'] + $squat]], 'exercises[0].exercise'];
        yield 'a load on an exercise that takes none' => [['exercises' => [['loadKg' => 5] + $pushUp]], 'exercises[0].loadKg'];
        yield 'a set in another unit' => [['source' => 'manual', 'exercises' => [['done' => [['reps' => 30]]] + $plank]], 'exercises[0].done[0].reps'];
        yield 'a feeling on a planned session' => [['exercises' => [['feeling' => 'right'] + $squat]], 'exercises[0].feeling'];
        yield 'sets done on a planned session' => [['exercises' => [['done' => [['reps' => 10]]] + $squat]], 'exercises[0].done'];
        yield 'a difficulty of 11' => [['source' => 'manual', 'difficulty' => 11], 'difficulty'];
        yield 'a difficulty on a planned session' => [['difficulty' => 5], 'difficulty'];
        yield 'an unknown variant' => [['variant' => 'heroic'], 'WorkoutVariant'];
    }

    /** @param array<string, mixed> $override */
    #[DataProvider('refusals')]
    public function testACreateIsRefusedNamingTheField(array $override, string $field): void
    {
        $this->login();

        $this->send('POST', '/api/workouts', $override + [
            'type' => 'strength',
            'title' => 'Séance refusée',
            'startedAt' => '2026-10-27T18:00:00+01:00',
            'durationMinutes' => 45,
            'source' => 'planned',
        ]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString($field, (string) $this->client->getResponse()->getContent());
        self::assertNull($this->em()->getRepository(Workout::class)->findOneBy(['title' => 'Séance refusée']));
        $this->assertMercureUpdateCount(0);
    }

    public function testAPatchRefusedLeavesTheSessionAsItWas(): void
    {
        $this->login();
        $id = $this->create();
        $this->resetMercure();

        $this->send('PATCH', '/api/workouts/'.$id, ['exercises' => [['loadKg' => 5] + self::sessionA()[2]]]);
        self::assertResponseStatusCodeSame(400);

        $this->send('PATCH', '/api/workouts/'.$id, ['difficulty' => 6]);
        self::assertResponseStatusCodeSame(400);

        self::assertCount(6, $this->reload($id)->getExercises());
        self::assertNull($this->reload($id)->getDifficulty());
        $this->assertMercureUpdateCount(0);
    }

    public function testADoneSessionMovedBackToPlannedCannotKeepItsSets(): void
    {
        $this->login();
        $squat = self::sessionA()[1];
        $squat['done'] = [['reps' => 10, 'loadKg' => 10]];
        $id = $this->create(['source' => 'manual', 'exercises' => [$squat]]);

        $this->send('PATCH', '/api/workouts/'.$id, ['source' => 'planned']);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('exercises[0].done', (string) $this->client->getResponse()->getContent());
    }

    public function testADifficultyIsClearedByANull(): void
    {
        $this->login();
        $this->send('PATCH', $this->iri('run'), ['difficulty' => 7]);
        self::assertResponseIsSuccessful();

        $this->send('PATCH', $this->iri('run'), ['difficulty' => null]);

        self::assertResponseIsSuccessful();
        self::assertNull($this->reload((string) $this->getFixture('run')->getId())->getDifficulty());
    }

    public function testTheExerciseFilterGivesTheSessionsHoldingItLatestFirst(): void
    {
        $this->login();
        $this->create(['title' => 'Séance A', 'startedAt' => '2026-10-27T18:00:00+01:00']);
        $this->create(['title' => 'Séance B', 'startedAt' => '2026-10-29T18:00:00+01:00', 'exercises' => [self::sessionA()[1]]]);
        $this->create(['title' => 'Séance C', 'startedAt' => '2026-10-31T18:00:00+01:00', 'exercises' => [self::sessionA()[0]]]);

        $this->send('GET', '/api/workouts?exercise=goblet-squat&order[startedAt]=desc');

        self::assertResponseIsSuccessful();
        self::assertSame(['Séance B', 'Séance A'], array_column($this->response()['member'], 'title'));
    }

    public function testAnotherUsersSessionIsNotFound(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('secret'), ['exercises' => [self::sessionA()[0]]]);

        self::assertResponseStatusCodeSame(404);
        self::assertSame([], $this->reload((string) $this->getFixture('secret')->getId())->getExercises());
    }

    public function testWritingExercisesRequiresAuthentication(): void
    {
        $this->send('PATCH', $this->iri('run'), ['exercises' => [self::sessionA()[0]]], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }
}
