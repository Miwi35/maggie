<?php

namespace Maggie\Health\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutFeeling;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The workouts through the API Platform resource (MAG-705). */
class WorkoutApiTest extends WebTestCase
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

        $this->client->request(
            $method,
            $uri,
            [],
            [],
            $authenticated ? array_merge($headers, $this->authHeaders()) : $headers,
            null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR),
        );
    }

    /** @return array<string, mixed> */
    private function response(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function iri(string $fixture): string
    {
        return '/api/workouts/'.$this->id($fixture);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function reload(string $fixture): Workout
    {
        $workout = $this->em()->find(Workout::class, $this->id($fixture));
        \assert($workout instanceof Workout);

        return $workout;
    }

    private function rows(): int
    {
        return $this->em()->getRepository(Workout::class)->count([]);
    }

    /** @return list<string> the titles listed by a GET on the collection */
    private function titles(string $query = ''): array
    {
        $this->send('GET', '/api/workouts'.$query);
        self::assertResponseIsSuccessful();

        return array_column($this->response()['member'], 'title');
    }

    /** @return array<string, mixed> */
    private static function body(array $override = []): array
    {
        return $override + [
            'type' => 'bike',
            'title' => 'Vélo',
            'startedAt' => '2026-10-22T18:00:00+02:00',
            'durationMinutes' => 65,
            'distanceMeters' => 22000,
        ];
    }

    public function testCollectionRequiresAuthentication(): void
    {
        $this->send('GET', '/api/workouts', authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateRequiresAuthentication(): void
    {
        $this->send('POST', '/api/workouts', self::body(), authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame(4, $this->rows());
    }

    public function testCreatePersistsAndPublishes(): void
    {
        $this->login();

        $this->send('POST', '/api/workouts', self::body(['feeling' => 'very_good', 'avgHeartRate' => 140]));

        self::assertResponseStatusCodeSame(201);
        $data = $this->response();
        self::assertSame('bike', $data['type']);
        self::assertSame(22000, $data['distanceMeters']);
        self::assertSame('manual', $data['source']);
        self::assertArrayNotHasKey('kcal', $data);

        $workout = $this->em()->getRepository(Workout::class)->findOneBy(['title' => 'Vélo']);
        self::assertInstanceOf(Workout::class, $workout);
        self::assertSame(WorkoutType::Bike, $workout->getType());
        self::assertSame(65, $workout->getDurationMinutes());
        self::assertSame(22000, $workout->getDistanceMeters());
        self::assertSame(140, $workout->getAvgHeartRate());
        self::assertSame(WorkoutFeeling::VeryGood, $workout->getFeeling());
        self::assertSame(WorkoutSource::Manual, $workout->getSource());
        // The same instant: the database hands it back in its own zone.
        self::assertEquals(new \DateTimeImmutable('2026-10-22T18:00:00+02:00'), $workout->getStartedAt());
        self::assertSame($this->id('test_user'), (string) $workout->getUser()->getId());

        $this->assertMercureUpdatePublished('/api/workouts/'.$workout->getId());
    }

    public function testADurationBelowOneMinuteIsRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/workouts', self::body(['durationMinutes' => 0]));

        self::assertResponseStatusCodeSame(422);
        self::assertSame(4, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testOtherOutOfRangeFiguresAreRejected(): void
    {
        $this->login();

        foreach ([['durationMinutes' => 1441], ['distanceMeters' => -1], ['kcal' => 20001], ['avgHeartRate' => 19], ['avgHeartRate' => 251], ['title' => ' ']] as $override) {
            $this->send('POST', '/api/workouts', self::body($override));
            self::assertResponseStatusCodeSame(422, 'for '.json_encode($override));
        }

        self::assertSame(4, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnUnknownFeelingIsABadRequest(): void
    {
        $this->login();

        $this->send('POST', '/api/workouts', self::body(['feeling' => 'meh']));

        self::assertResponseStatusCodeSame(400);
        self::assertSame(4, $this->rows());
    }

    public function testAMissingTypeIsRefused(): void
    {
        $this->login();
        $body = self::body();
        unset($body['type']);

        $this->send('POST', '/api/workouts', $body);

        self::assertResponseStatusCodeSame(422);
        self::assertSame(4, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testAMissingDurationIsRefused(): void
    {
        $this->login();
        $body = self::body();
        unset($body['durationMinutes']);

        $this->send('POST', '/api/workouts', $body);

        self::assertResponseStatusCodeSame(422);
        self::assertSame(4, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testAPageOfFiveHundredServesAllTheWorkouts(): void
    {
        $this->login();
        $em = $this->em();
        $user = $em->find(User::class, $this->id('test_user'));
        \assert($user instanceof User);
        for ($i = 0; $i < 120; ++$i) {
            $em->persist((new Workout())
                ->setUser($user)
                ->setType(WorkoutType::Run)
                ->setTitle('Bulk '.$i)
                ->setStartedAt(new \DateTimeImmutable('2026-01-01T07:00:00+01:00'))
                ->setDurationMinutes(30));
        }
        $em->flush();

        $this->send('GET', '/api/workouts?itemsPerPage=500');

        self::assertResponseIsSuccessful();
        self::assertCount(123, $this->response()['member']);
    }

    public function testAMissingStartIsRefused(): void
    {
        $this->login();
        $body = self::body();
        unset($body['startedAt']);

        $this->send('POST', '/api/workouts', $body);

        self::assertContains($this->client->getResponse()->getStatusCode(), [400, 422]);
        self::assertSame(4, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testACreateWithoutAnOffsetIsRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/workouts', self::body(['startedAt' => '2026-10-22T18:00:00']));
        self::assertResponseStatusCodeSame(422);
        self::assertStringContainsString('offset', (string) $this->client->getResponse()->getContent());

        $this->send('POST', '/api/workouts', self::body(['startedAt' => '2026-10-22']));
        self::assertResponseStatusCodeSame(422);

        self::assertSame(4, $this->rows());
        $this->assertMercureUpdateCount(0);
        // A trailing Z, or milliseconds, are still an offset.
        $this->send('POST', '/api/workouts', self::body(['startedAt' => '2026-10-22T16:00:00.000Z']));
        self::assertResponseStatusCodeSame(201);
    }

    public function testTheCollectionIsTheUsersOwnMostRecentFirst(): void
    {
        $this->login();

        $this->send('GET', '/api/workouts');

        self::assertResponseIsSuccessful();
        $members = $this->response()['member'];
        self::assertSame(['Marche rapide', 'Course', 'Haut du corps'], array_column($members, 'title'));

        $run = $members[1];
        self::assertSame($this->iri('run'), $run['@id']);
        self::assertSame($this->id('run'), $run['id']);
        self::assertSame('run', $run['type']);
        self::assertEquals(new \DateTimeImmutable('2026-10-20T07:10:00+02:00'), new \DateTimeImmutable($run['startedAt']));
        self::assertSame(32, $run['durationMinutes']);
        self::assertSame(5400, $run['distanceMeters']);
        self::assertSame(386, $run['kcal']);
        self::assertSame(152, $run['avgHeartRate']);
        self::assertSame('good', $run['feeling']);
        self::assertArrayNotHasKey('notes', $run);
        self::assertSame('watch', $run['source']);
        self::assertSame('watch-4711', $run['externalId']);
        self::assertArrayNotHasKey('distanceMeters', $members[2]);
    }

    public function testTheFiltersNarrowTheList(): void
    {
        $this->login();

        self::assertSame(['Course'], $this->titles('?type=run'));
        self::assertSame(['Course'], $this->titles('?source=watch'));
        self::assertSame(['Marche rapide'], $this->titles('?source=planned'));
        self::assertSame(['Marche rapide', 'Course'], $this->titles('?startedAt[after]=2026-10-19T00:00:00%2B02:00'));
        self::assertSame(['Haut du corps'], $this->titles('?startedAt[before]=2026-10-19T00:00:00%2B02:00'));
    }

    public function testAnotherUsersWorkoutIsNotFound(): void
    {
        $this->login();

        $this->send('GET', $this->iri('secret'));
        self::assertResponseStatusCodeSame(404);

        $this->send('PATCH', $this->iri('secret'), ['title' => 'Volé']);
        self::assertResponseStatusCodeSame(404);

        $this->send('DELETE', $this->iri('secret'));
        self::assertResponseStatusCodeSame(404);

        self::assertSame('Séance secrète', $this->reload('secret')->getTitle());
    }

    public function testPatchRequiresAuthentication(): void
    {
        $this->send('PATCH', $this->iri('run'), ['title' => 'Footing'], authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame('Course', $this->reload('run')->getTitle());
    }

    public function testPatchChangesWhatIsSentAndClearsWhatIsNull(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('run'), ['title' => 'Footing', 'kcal' => null]);

        self::assertResponseIsSuccessful();
        $run = $this->reload('run');
        self::assertSame('Footing', $run->getTitle());
        self::assertNull($run->getKcal());
        self::assertSame(5400, $run->getDistanceMeters());
        self::assertSame(152, $run->getAvgHeartRate());
        self::assertSame(WorkoutFeeling::Good, $run->getFeeling());
        self::assertArrayNotHasKey('kcal', $this->response());

        $payloads = $this->mercurePayloadsOn('/api/workouts/'.$this->id('run'));
        self::assertCount(1, $payloads);
        self::assertSame('Footing', $payloads[0]['title']);
        self::assertNull($payloads[0]['kcal']);
    }

    /** The admin sends the whole record read back, `id` an IRI (MAG-480). */
    public function testPatchSendingBackTheWholeRecordReadBeforeSavesIt(): void
    {
        $this->login();

        $this->send('GET', $this->iri('run'));
        self::assertResponseIsSuccessful();
        $body = $this->response();
        $body['id'] = $body['@id'];
        $body['durationMinutes'] = 40;

        $this->send('PATCH', $this->iri('run'), $body);

        self::assertResponseIsSuccessful();
        $run = $this->reload('run');
        self::assertSame(40, $run->getDurationMinutes());
        self::assertSame('watch-4711', $run->getExternalId());
    }

    public function testPatchWithAnInvalidDurationIsRejected(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('run'), ['durationMinutes' => 0]);

        self::assertResponseStatusCodeSame(422);
        self::assertSame(32, $this->reload('run')->getDurationMinutes());
        $this->assertMercureUpdateCount(0);
    }

    public function testDeleteRequiresAuthentication(): void
    {
        $this->send('DELETE', $this->iri('run'), authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame(4, $this->rows());
    }

    public function testDeleteRemovesAndPublishes(): void
    {
        $this->login();

        $this->send('DELETE', $this->iri('run'));

        self::assertResponseStatusCodeSame(204);
        self::assertNull($this->em()->find(Workout::class, $this->id('run')));
        self::assertSame(3, $this->rows());
        $this->assertMercureDeletePublished('/api/workouts/'.$this->id('run'));
    }

    public function testTheSameExternalIdTwiceForAUserIsRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/workouts', self::body(['externalId' => 'watch-4711']));

        self::assertResponseStatusCodeSame(422);
        self::assertStringContainsString('externalId', (string) $this->client->getResponse()->getContent());
        self::assertSame(4, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testTheSameExternalIdForAnotherUserIsAccepted(): void
    {
        $this->login();

        // `watch-4711` also belongs to the other user's workout.
        $this->send('POST', '/api/workouts', self::body(['externalId' => 'watch-9']));

        self::assertResponseStatusCodeSame(201);
        self::assertSame(5, $this->rows());
    }

    public function testPatchingAnExternalIdAlreadyUsedByAnotherWorkoutIsRejected(): void
    {
        $this->login();
        $this->send('PATCH', $this->iri('strength'), ['externalId' => 'watch-4711']);

        self::assertResponseStatusCodeSame(422);
        self::assertNull($this->reload('strength')->getExternalId());
    }
}
