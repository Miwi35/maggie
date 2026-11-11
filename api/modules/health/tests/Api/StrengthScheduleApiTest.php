<?php

namespace Maggie\Health\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The week's guard rails on a planned strength session (MAG-776): the week of Monday 2 November 2026. */
class StrengthScheduleApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('workout.yaml');
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);
        $this->authenticateAsUser($user);
    }

    /** @param array<string, mixed> $body */
    private function send(string $method, string $uri, array $body): void
    {
        $headers = [
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ] + $this->authHeaders();

        $this->client->request($method, $uri, [], [], $headers, json_encode($body, JSON_THROW_ON_ERROR));
    }

    /** @param list<string> $slugs */
    private function plan(string $startedAt, array $slugs = ['plank'], string $type = 'strength'): void
    {
        $exercises = array_map(static fn (string $slug): array => [
            'exercise' => $slug, 'step' => 1, 'sets' => 2, 'target' => 5, 'targetMin' => 3, 'targetMax' => 6, 'restSeconds' => 90,
        ], $slugs);
        $body = ['type' => $type, 'title' => 'Séance '.$startedAt, 'startedAt' => $startedAt, 'durationMinutes' => 40, 'source' => 'planned'];
        if ('strength' === $type) {
            $body['exercises'] = $exercises;
        }
        $this->send('POST', '/api/workouts', $body);
    }

    /** @return array<string, mixed> */
    private function refusal(): array
    {
        self::assertResponseStatusCodeSame(400);

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function rows(): int
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em->getRepository(Workout::class)->count([]);
    }

    public function testAFourthSessionOfTheWeekIsRefusedWithTheWeek(): void
    {
        foreach (['2026-11-02T18:00:00+01:00', '2026-11-04T18:00:00+01:00', '2026-11-06T18:00:00+01:00'] as $start) {
            $this->plan($start);
            self::assertResponseStatusCodeSame(201);
        }
        $before = $this->rows();

        $this->plan('2026-11-08T10:00:00+01:00');

        $data = $this->refusal();
        self::assertSame('startedAt', $data['field']);
        self::assertCount(3, $data['current']);
        self::assertSame(
            [1793638800, 1793811600, 1793984400],
            array_map(static fn (string $at): int => (new \DateTimeImmutable($at))->getTimestamp(), array_column($data['current'], 'startedAt')),
        );
        self::assertSame($before, $this->rows());
    }

    public function testTheNextWeekIsOpen(): void
    {
        foreach (['2026-11-02T18:00:00+01:00', '2026-11-04T18:00:00+01:00', '2026-11-06T18:00:00+01:00', '2026-11-09T07:00:00+01:00'] as $start) {
            $this->plan($start);
            self::assertResponseStatusCodeSame(201);
        }
    }

    public function testASessionTwentyHoursAfterAnotherIsRefused(): void
    {
        $this->plan('2026-11-02T18:00:00+01:00');
        self::assertResponseStatusCodeSame(201);

        $this->plan('2026-11-03T14:00:00+01:00');

        self::assertSame('startedAt', $this->refusal()['field']);
    }

    public function testTheDayBetweenTwoWeeksCountsToo(): void
    {
        $this->plan('2026-11-08T20:00:00+01:00');
        self::assertResponseStatusCodeSame(201);

        $this->plan('2026-11-09T07:00:00+01:00');

        self::assertSame('startedAt', $this->refusal()['field']);
    }

    public function testAThirdSessionWithANegativeIsRefused(): void
    {
        $this->plan('2026-11-02T18:00:00+01:00', ['pull-up-negative']);
        $this->plan('2026-11-05T18:00:00+01:00', ['dip-negative']);
        self::assertResponseStatusCodeSame(201);

        $this->plan('2026-11-07T18:00:00+01:00', ['pull-up-negative']);

        $data = $this->refusal();
        self::assertSame('exercises', $data['field']);
        self::assertTrue($data['current'][0]['eccentric']);
    }

    public function testTwoNegativesLessThanFortyEightHoursApartAreRefused(): void
    {
        $this->plan('2026-11-02T18:00:00+01:00', ['pull-up-negative']);
        self::assertResponseStatusCodeSame(201);

        $this->plan('2026-11-04T08:00:00+01:00', ['dip-negative']);

        self::assertSame('exercises', $this->refusal()['field']);
    }

    public function testARunIsNeverRefused(): void
    {
        foreach (['2026-11-02T18:00:00+01:00', '2026-11-04T18:00:00+01:00', '2026-11-06T18:00:00+01:00'] as $start) {
            $this->plan($start);
        }

        $this->plan('2026-11-06T19:00:00+01:00', type: 'run');

        self::assertResponseStatusCodeSame(201);
    }

    public function testAMoveIntoAConflictIsRefusedAndNothingIsMoved(): void
    {
        $this->plan('2026-11-02T18:00:00+01:00');
        $this->plan('2026-11-05T18:00:00+01:00');
        $id = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['id'];

        $this->send('PATCH', '/api/workouts/'.$id, ['startedAt' => '2026-11-03T10:00:00+01:00']);

        self::assertSame('startedAt', $this->refusal()['field']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();
        self::assertSame(1793898000, $em->find(Workout::class, $id)?->getStartedAt()->getTimestamp());
    }

    public function testASessionCanBeMovedWithinItsOwnWeek(): void
    {
        $this->plan('2026-11-02T18:00:00+01:00');
        $id = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['id'];

        $this->send('PATCH', '/api/workouts/'.$id, ['startedAt' => '2026-11-02T19:00:00+01:00']);

        self::assertResponseIsSuccessful();
    }
}
