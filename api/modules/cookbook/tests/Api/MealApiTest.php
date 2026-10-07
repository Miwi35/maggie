<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * A meal is a day and a slot (MAG-251).
 *
 * The regression this file was opened on: the week view sent
 * `startAt: "<day>T00:00:00+01:00"`, and the offset is wrong for eight months
 * of the year. In summer time that instant is 23:00 the day before in UTC, so
 * every reader that takes the date from the stored timestamp ranged the meal on
 * the previous day. `testTheDayTheOwnerAsksForIsTheDayStored` is that bug: the
 * three dates it runs on are an October day under summer time, a December day
 * under winter time, and the changeover itself.
 *
 * The day is now a `date` column, sent and read as `Y-m-d`, and `startAt` is
 * derived from it server-side — which is what the last test here pins down: a
 * client that still sends an instant does not get to decide the day.
 */
class MealApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testAnonymousCannotCreateAMeal(): void
    {
        $this->loadFixtures('MealApiTest.yaml');

        $this->client->request('POST', '/api/meals', [], [], [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode(['date' => '2026-10-07', 'slot' => 'lunch'], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnonymousCannotListMeals(): void
    {
        $this->loadFixtures('MealApiTest.yaml');

        $this->client->request('GET', '/api/meals', [], [], ['HTTP_ACCEPT' => 'application/ld+json']);

        self::assertResponseStatusCodeSame(401);
    }

    /**
     * @return iterable<string, array{string}>
     */
    public static function daysOfTheYear(): iterable
    {
        yield 'summer time, the day the owner reported' => ['2026-10-07'];
        yield 'winter time' => ['2026-12-07'];
        yield 'the day the clocks go back' => ['2026-10-25'];
    }

    #[DataProvider('daysOfTheYear')]
    public function testTheDayTheOwnerAsksForIsTheDayStored(string $day): void
    {
        $this->authenticateFixtureUser();

        $data = $this->postMeal(['date' => $day, 'slot' => 'lunch']);

        self::assertResponseStatusCodeSame(201);
        self::assertSame($day, $data['date']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        /** @var Meal $stored */
        $stored = $em->getRepository(Meal::class)->find($data['id']);
        self::assertSame($day, $stored->getDate()->format('Y-m-d'));
    }

    public function testCreatingAMealPublishesAndIndexesIt(): void
    {
        $this->authenticateFixtureUser();
        $recipe = $this->getFixture('pasta');

        $data = $this->postMeal([
            'date' => '2026-10-07',
            'slot' => 'dinner',
            'recipes' => ['/api/recipes/'.$recipe->getId()],
        ]);

        self::assertResponseStatusCodeSame(201);
        self::assertSame('2026-10-07', $data['date']);
        self::assertSame('dinner', $data['slot']);

        $this->assertMercureUpdatePublished('/meals/');
        $this->assertElasticsearchIndexDispatched(Meal::class);
    }

    public function testADayThatIsNotADayIsRefused(): void
    {
        $this->authenticateFixtureUser();

        $this->postMeal(['date' => '2026-13-45', 'slot' => 'lunch']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testADayWithATimeIsRefused(): void
    {
        $this->authenticateFixtureUser();

        $this->postMeal(['date' => '2026-10-07T00:00:00+01:00', 'slot' => 'lunch']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testAMealWithoutADayIsRefused(): void
    {
        $this->authenticateFixtureUser();

        $this->postMeal(['slot' => 'lunch']);

        self::assertResponseStatusCodeSame(422);
    }

    public function testMovingAMealToAnotherDay(): void
    {
        $this->authenticateFixtureUser();

        $created = $this->postMeal(['date' => '2026-10-07', 'slot' => 'lunch']);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->client->request('PATCH', '/api/meals/'.$created['id'], [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['date' => '2026-10-09'], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('2026-10-09', $data['date']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        /** @var Meal $stored */
        $stored = $em->getRepository(Meal::class)->find($created['id']);
        self::assertSame('2026-10-09', $stored->getDate()->format('Y-m-d'));

        $this->assertMercureUpdatePublished('/meals/');
        $this->assertElasticsearchIndexDispatched(Meal::class);
    }

    public function testAnonymousCannotMoveAMeal(): void
    {
        $this->loadFixtures('MealApiTest.yaml');

        $this->client->request('PATCH', '/api/meals/01JZZZZZZZZZZZZZZZZZZZZZZZ', [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode(['date' => '2026-10-09', 'slot' => 'dinner'], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    /**
     * Drag and drop in the week view (MAG-250): the body carries the day and the
     * slot, and nothing else. Both change in one request, and the recipes — and
     * the summary's list of them — come out the other side as they went in.
     *
     * An April day and a December one: the first is under summer time, where the
     * hard-coded `+01:00` the view used to send put a meal on the day before.
     *
     * @return iterable<string, array{string, string}>
     */
    public static function movesAcrossTheYear(): iterable
    {
        yield 'summer time' => ['2026-04-14', '2026-04-16'];
        yield 'winter time' => ['2026-12-08', '2026-12-10'];
    }

    #[DataProvider('movesAcrossTheYear')]
    public function testMovingAMealToAnotherDayAndSlotKeepsItsRecipes(string $from, string $to): void
    {
        $this->authenticateFixtureUser();
        $recipe = $this->getFixture('pasta');

        $created = $this->postMeal([
            'date' => $from,
            'slot' => 'lunch',
            'recipes' => ['/api/recipes/'.$recipe->getId()],
        ]);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->patchMeal($created['id'], ['date' => $to, 'slot' => 'dinner']);

        self::assertResponseIsSuccessful();
        self::assertSame($to, $data['date']);
        self::assertSame('dinner', $data['slot']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        /** @var Meal $stored */
        $stored = $em->getRepository(Meal::class)->find($created['id']);
        self::assertSame($to, $stored->getDate()->format('Y-m-d'));
        self::assertSame('dinner', $stored->getSlot()->value);
        // The agenda's own reading of the meal follows the day.
        self::assertSame($to, $stored->getStartAt()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('Y-m-d'));
        self::assertSame(
            [(string) $recipe->getId()],
            $stored->getRecipes()->map(static fn ($r) => (string) $r->getId())->getValues(),
        );
        self::assertSame('Dîner : Pâtes à la tomate', $stored->getSummary());

        $this->assertMercureUpdatePublished('/meals/');
        $this->assertElasticsearchIndexDispatched(Meal::class);
    }

    public function testASlotThatDoesNotExistIsRefusedAndTheMealStays(): void
    {
        $this->authenticateFixtureUser();
        $created = $this->postMeal(['date' => '2026-10-07', 'slot' => 'lunch']);
        $this->resetMercure();

        $this->patchMeal($created['id'], ['date' => '2026-10-09', 'slot' => 'brunch']);

        self::assertResponseStatusCodeSame(400);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        /** @var Meal $stored */
        $stored = $em->getRepository(Meal::class)->find($created['id']);
        self::assertSame('2026-10-07', $stored->getDate()->format('Y-m-d'));
        self::assertSame('lunch', $stored->getSlot()->value);
    }

    public function testADayWithATimeIsRefusedWhenMovingAMeal(): void
    {
        $this->authenticateFixtureUser();
        $created = $this->postMeal(['date' => '2026-10-07', 'slot' => 'lunch']);

        $this->patchMeal($created['id'], ['date' => '2026-10-09T00:00:00+01:00']);

        self::assertResponseStatusCodeSame(400);
    }

    /**
     * The week view asks for a day's worth of meals by day, not by instant:
     * `date[after]`/`date[before]` are what the clients send now.
     */
    public function testTheWeekIsFilteredOnTheDay(): void
    {
        $this->authenticateFixtureUser();

        $this->postMeal(['date' => '2026-10-07', 'slot' => 'lunch']);
        $this->postMeal(['date' => '2026-10-20', 'slot' => 'lunch']);

        $this->client->request('GET', '/api/meals?date%5Bafter%5D=2026-10-05&date%5Bbefore%5D=2026-10-11', [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $this->authHeaders(),
        ));

        self::assertResponseIsSuccessful();
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(1, $data['member']);
        self::assertSame('2026-10-07', $data['member'][0]['date']);
    }

    /**
     * An instant a client sends does not get to decide the day: `startAt` is
     * derived from `date`, as the whole day in the meal's time zone. The body
     * below is exactly what the week view used to send — the offset is an
     * hour short for an October day, and honouring it is the bug.
     */
    public function testAnInstantSentByAClientDoesNotMoveTheMeal(): void
    {
        $this->authenticateFixtureUser();

        $data = $this->postMeal([
            'date' => '2026-10-07',
            'slot' => 'lunch',
            'startAt' => '2026-10-06T00:00:00+01:00',
            'endAt' => '2026-10-06T23:59:59+01:00',
        ]);

        self::assertResponseStatusCodeSame(201);
        self::assertSame('2026-10-07', $data['date']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        /** @var Meal $stored */
        $stored = $em->getRepository(Meal::class)->find($data['id']);
        $paris = new \DateTimeZone('Europe/Paris');
        self::assertSame('2026-10-07 00:00:00', $stored->getStartAt()->setTimezone($paris)->format('Y-m-d H:i:s'));
        self::assertSame('2026-10-07 23:59:59', $stored->getEndAt()->setTimezone($paris)->format('Y-m-d H:i:s'));
    }

    /**
     * A meal moved through the *event* door moves its day with it.
     *
     * `Meal` shares its primary key with `event`, so `EventRepository::find()`
     * returns a meal for a meal's id and `findByDateRange()` hands Maggie one
     * among the events. `update_event` and `PATCH /api/events/{id}` then set the
     * instants directly. With `date` the field the API, Elasticsearch, Mercure
     * and every week view read, an inherited `setStartAt()` would move the
     * instants and leave the day behind: Maggie would answer "c'est décalé" and
     * the meal would not have moved anywhere the owner can see.
     */
    public function testMovingAMealThroughTheEventEndpointMovesItsDay(): void
    {
        $this->authenticateFixtureUser();

        $created = $this->postMeal(['date' => '2026-10-06', 'slot' => 'dinner']);
        self::assertResponseStatusCodeSame(201);

        $this->client->request('PATCH', '/api/events/'.$created['id'], [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'startAt' => '2026-10-08T19:30:00+02:00',
            'endAt' => '2026-10-08T20:30:00+02:00',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        /** @var Meal $stored */
        $stored = $em->getRepository(Meal::class)->find($created['id']);

        self::assertSame('2026-10-08', $stored->getDate()->format('Y-m-d'), 'the day did not follow the instant');
        // And the instants are the whole of that day again, not the hour sent.
        $paris = new \DateTimeZone('Europe/Paris');
        self::assertSame('2026-10-08 00:00:00', $stored->getStartAt()->setTimezone($paris)->format('Y-m-d H:i:s'));
        self::assertSame('2026-10-08 23:59:59', $stored->getEndAt()->setTimezone($paris)->format('Y-m-d H:i:s'));
    }

    /**
     * An instant just past midnight in Paris is the day before in UTC, and the
     * day the writer meant is the Paris one — the same reading the migration
     * applies to the rows the old clients wrote.
     */
    public function testAnInstantIsReadAsADayInTheMealsOwnTimeZone(): void
    {
        $this->authenticateFixtureUser();

        $created = $this->postMeal(['date' => '2026-10-06', 'slot' => 'dinner']);

        $this->client->request('PATCH', '/api/events/'.$created['id'], [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            // 2026-10-09 00:30 in Paris, which is 2026-10-08 22:30 in UTC.
            'startAt' => '2026-10-08T22:30:00+00:00',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        /** @var Meal $stored */
        $stored = $em->getRepository(Meal::class)->find($created['id']);

        self::assertSame('2026-10-09', $stored->getDate()->format('Y-m-d'));
    }

    /** @param array<string, mixed> $body */
    private function postMeal(array $body): array
    {
        if (!isset($body['agenda'])) {
            $body['agenda'] = '/api/agendas/'.$this->getFixture('test_agenda')->getId();
        }
        // An Event needs one, and the week view sends it; the handler then
        // rebuilds it from the slot and the recipes.
        $body['summary'] ??= 'Déjeuner';

        $this->client->request('POST', '/api/meals', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));

        $decoded = json_decode((string) $this->client->getResponse()->getContent(), true);

        return \is_array($decoded) ? $decoded : [];
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array<string, mixed>
     */
    private function patchMeal(string $id, array $body): array
    {
        $this->client->request('PATCH', '/api/meals/'.$id, [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));

        $decoded = json_decode((string) $this->client->getResponse()->getContent(), true);

        return \is_array($decoded) ? $decoded : [];
    }

    private function authenticateFixtureUser(): void
    {
        $this->loadFixtures('MealApiTest.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
    }
}
