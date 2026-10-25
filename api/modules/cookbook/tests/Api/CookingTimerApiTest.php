<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\CookingTimer;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Enum\CookingTimerStatus;
use Maggie\Cookbook\Message\FinishCookingTimerCommand;
use Maggie\Core\Entity\User;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\SendPushNotificationCommand;
use Maggie\Notification\Message\WithdrawPushNotificationCommand;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\DelayStamp;
use Symfony\Component\Messenger\Stamp\ReceivedStamp;

/** The timers of the Cuisine activity over REST, and their end (MAG-582). */
class CookingTimerApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string ENDPOINT = '/api/cooking/timers';

    private KernelBrowser $client;
    private MockClock $clock;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        // One kernel for the whole test, so the clock and the entities stay the same ones; the async
        // transport is still emptied between requests, so what a request queued is read right after it.
        $this->client->disableReboot();
        $this->clock = new MockClock('2026-10-25 19:00:00 UTC');
        Clock::set($this->clock);
        $this->loadFixtures('CookingTimerApiTest.yaml');
        $this->authenticateAsUser($this->user('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT);
        self::assertResponseStatusCodeSame(401);

        $this->client->request('POST', self::ENDPOINT, [], [], ['CONTENT_TYPE' => 'application/json'], '{}');
        self::assertResponseStatusCodeSame(401);

        $this->client->request('PATCH', self::ENDPOINT.'/01JCOOKINGTIMER0000000000A', [], [], ['CONTENT_TYPE' => 'application/json'], '{"action": "pause"}');
        self::assertResponseStatusCodeSame(401);
    }

    public function testStartingATimerStoresItPublishesItAndQueuesItsEnd(): void
    {
        $data = $this->start('Pâtes', 3, 720);

        self::assertResponseStatusCodeSame(201);
        self::assertSame('Pâtes', $data['label']);
        self::assertSame('/api/recipes/'.$this->recipe('pasta')->getId(), $data['recipe']);
        self::assertSame(3, $data['stepPosition']);
        self::assertSame(720, $data['durationSeconds']);
        self::assertSame('2026-10-25T19:12:00+00:00', $data['endsAt']);
        self::assertSame(720, $data['remainingSeconds']);
        self::assertSame('running', $data['status']);

        $stored = $this->timer($data['id']);
        self::assertSame(CookingTimerStatus::Running, $stored->getStatus());
        self::assertSame('Pâtes', $stored->getLabel());
        self::assertEquals($this->user('test_user')->getId(), $stored->getUser()->getId());
        self::assertEquals(new \DateTimeImmutable('2026-10-25 19:12:00 UTC'), $stored->getEndsAt());

        $this->assertMercurePublishedOnce('/api/cooking/timers/'.$data['id']);
        $payloads = $this->mercurePayloadsOn('/api/cooking/timers/'.$data['id']);
        self::assertSame('running', $payloads[0]['status']);
        self::assertSame('2026-10-25T19:12:00+00:00', $payloads[0]['endsAt']);
        // Not searched, so never indexed.
        $this->assertNoElasticsearchIndexDispatched(CookingTimer::class);

        $ends = $this->queuedEnds();
        self::assertCount(1, $ends);
        self::assertSame($data['id'], $ends[0]['message']->timerId);
        self::assertSame(720_000, $ends[0]['delay']);
    }

    public function testTheRecipeCanBeGivenByItsIri(): void
    {
        $data = $this->post([
            'label' => 'Eau',
            'recipe' => '/api/recipes/'.$this->recipe('pasta')->getId(),
            'stepPosition' => 1,
            'durationSeconds' => 600,
        ]);

        self::assertResponseStatusCodeSame(201);
        self::assertSame('/api/recipes/'.$this->recipe('pasta')->getId(), $data['recipe']);
    }

    /** @return iterable<string, array{array<string, mixed>|string}> */
    public static function startsThatAreRefused(): iterable
    {
        yield 'no duration' => [['label' => 'Pâtes', 'stepPosition' => 3, 'durationSeconds' => 0]];
        yield 'a negative duration' => [['label' => 'Pâtes', 'stepPosition' => 3, 'durationSeconds' => -60]];
        yield 'a duration of more than a day' => [['label' => 'Pâtes', 'stepPosition' => 3, 'durationSeconds' => 86401]];
        yield 'a duration as a string' => [['label' => 'Pâtes', 'stepPosition' => 3, 'durationSeconds' => '720']];
        yield 'no label' => [['stepPosition' => 3, 'durationSeconds' => 720]];
        yield 'a blank label' => [['label' => '  ', 'stepPosition' => 3, 'durationSeconds' => 720]];
        yield 'no step' => [['label' => 'Pâtes', 'durationSeconds' => 720]];
        yield 'step zero' => [['label' => 'Pâtes', 'stepPosition' => 0, 'durationSeconds' => 720]];
        yield 'an unknown field' => [['label' => 'Pâtes', 'stepPosition' => 3, 'durationSeconds' => 720, 'endsAt' => 'now']];
        yield 'a recipe that is not an id' => [['recipe' => 'xyz', 'label' => 'Pâtes', 'stepPosition' => 3, 'durationSeconds' => 720]];
        yield 'not JSON' => ['twelve minutes'];
    }

    /** @param array<string, mixed>|string $body */
    #[DataProvider('startsThatAreRefused')]
    public function testARefusedStartIsA400AndStoresNothing(array|string $body): void
    {
        if (\is_array($body)) {
            $body += ['recipe' => (string) $this->recipe('pasta')->getId()];
        }

        $this->post($body);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(0, $this->em()->getRepository(CookingTimer::class)->count([]));
        $this->assertMercureUpdateCount(0);
        self::assertSame([], $this->queuedEnds());
    }

    public function testAnotherUsersRecipeIsRefused(): void
    {
        $this->post(['label' => 'Soupe', 'recipe' => (string) $this->recipe('other_recipe')->getId(), 'stepPosition' => 1, 'durationSeconds' => 60]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(0, $this->em()->getRepository(CookingTimer::class)->count([]));
    }

    public function testAnotherUsersTimerIsA404(): void
    {
        $this->authenticateAsUser($this->user('other_user'));
        $theirs = $this->post(['label' => 'Soupe', 'recipe' => (string) $this->recipe('other_recipe')->getId(), 'stepPosition' => 1, 'durationSeconds' => 60]);
        $this->authenticateAsUser($this->user('test_user'));
        $this->resetMercure();

        $this->patch($theirs['id'], ['action' => 'cancel']);

        self::assertResponseStatusCodeSame(404);
        self::assertSame(CookingTimerStatus::Running, $this->timer($theirs['id'])->getStatus());
        $this->assertMercureUpdateCount(0);

        $this->patch('01JCOOKINGTIMER0000000000A', ['action' => 'cancel']);
        self::assertResponseStatusCodeSame(404);
        $this->patch('not-an-id', ['action' => 'cancel']);
        self::assertResponseStatusCodeSame(404);
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function changesThatAreRefused(): iterable
    {
        yield 'an unknown action' => [['action' => 'stop']];
        yield 'no action' => [[]];
        yield 'seconds as a string' => [['action' => 'add', 'seconds' => '60']];
        yield 'seconds on pause' => [['action' => 'pause', 'seconds' => 60]];
        yield 'nothing added' => [['action' => 'add', 'seconds' => 0]];
        yield 'more than an hour added' => [['action' => 'add', 'seconds' => 3601]];
        yield 'resume a running timer' => [['action' => 'resume']];
        yield 'dismiss a running timer' => [['action' => 'dismiss']];
        yield 'an unknown field' => [['action' => 'pause', 'endsAt' => 'now']];
    }

    /** @param array<string, mixed> $body */
    #[DataProvider('changesThatAreRefused')]
    public function testARefusedChangeIsA400ThatAnswersTheTimerAsItStands(array $body): void
    {
        $timer = $this->start('Pâtes', 3, 720);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->patch($timer['id'], $body);

        self::assertResponseStatusCodeSame(400);
        self::assertArrayHasKey('error', $data);
        self::assertSame('running', $data['timer']['status']);
        self::assertSame(720, $data['timer']['remainingSeconds']);
        $stored = $this->timer($timer['id']);
        self::assertSame(CookingTimerStatus::Running, $stored->getStatus());
        self::assertSame(0, $stored->getRevision());
        $this->assertMercureUpdateCount(0);
        self::assertSame([], $this->queuedEnds());
    }

    public function testPauseKeepsWhatIsLeftAndResumeRecomputesTheEnd(): void
    {
        $timer = $this->start('Pâtes', 3, 720);
        $this->clock->sleep(120);

        $paused = $this->patch($timer['id'], ['action' => 'pause']);

        self::assertResponseIsSuccessful();
        self::assertSame('paused', $paused['status']);
        self::assertSame(600, $paused['remainingSeconds']);
        self::assertNull($paused['endsAt']);
        self::assertSame(CookingTimerStatus::Paused, $this->timer($timer['id'])->getStatus());
        $payloads = $this->mercurePayloadsOn('/api/cooking/timers/'.$timer['id']);
        self::assertSame('paused', end($payloads)['status']);

        // A pause lasts: what is left does not move.
        $this->clock->sleep(300);
        $this->resetAsyncTransport();

        $resumed = $this->patch($timer['id'], ['action' => 'resume']);

        self::assertSame('running', $resumed['status']);
        self::assertSame(600, $resumed['remainingSeconds']);
        self::assertSame('2026-10-25T19:17:00+00:00', $resumed['endsAt']);
        self::assertEquals(new \DateTimeImmutable('2026-10-25 19:17:00 UTC'), $this->timer($timer['id'])->getEndsAt());
        $ends = $this->queuedEnds();
        self::assertCount(1, $ends);
        self::assertSame(600_000, $ends[0]['delay']);
        self::assertSame(2, $ends[0]['message']->revision);
    }

    public function testAddMovesTheEndAMinuteEitherWay(): void
    {
        $timer = $this->start('Légumes', 4, 480);

        $more = $this->patch($timer['id'], ['action' => 'add']);
        self::assertSame(540, $more['remainingSeconds']);
        self::assertSame(540, $more['durationSeconds']);
        self::assertSame('2026-10-25T19:09:00+00:00', $more['endsAt']);

        $less = $this->patch($timer['id'], ['action' => 'add', 'seconds' => -60]);
        self::assertSame(480, $less['remainingSeconds']);
        self::assertEquals(new \DateTimeImmutable('2026-10-25 19:08:00 UTC'), $this->timer($timer['id'])->getEndsAt());
    }

    public function testRemovingMoreThanWhatIsLeftEndsItNow(): void
    {
        $timer = $this->start('Eau', 1, 30);
        $this->resetAsyncTransport();

        $data = $this->patch($timer['id'], ['action' => 'add', 'seconds' => -60]);

        self::assertSame(0, $data['remainingSeconds']);
        self::assertSame('running', $data['status']);
        self::assertSame(0, $this->queuedEnds()[0]['delay']);
    }

    public function testTheEndRingsOnceAndPushesOneNotification(): void
    {
        $timer = $this->start('Pâtes', 3, 720);
        $end = $this->queuedEnds()[0]['message'];
        $this->clock->sleep(720);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->handle($end);

        $stored = $this->timer($timer['id']);
        self::assertSame(CookingTimerStatus::Done, $stored->getStatus());
        self::assertEquals(new \DateTimeImmutable('2026-10-25 19:12:00 UTC'), $stored->getFinishedAt());
        $payloads = $this->mercurePayloadsOn('/api/cooking/timers/'.$timer['id']);
        self::assertSame('done', end($payloads)['status']);

        $notifications = $this->notificationsOf('test_user');
        self::assertCount(1, $notifications);
        self::assertSame(NotificationType::CookingTimer, $notifications[0]->getType());
        self::assertSame('Pâtes : c\'est prêt', $notifications[0]->getTitle());
        self::assertSame('minuteur de l\'étape 3 · Pâtes aux légumes', $notifications[0]->getBody());
        self::assertSame('/api/cooking/timers/'.$timer['id'], $notifications[0]->getRelatedEntityIri());
        $this->assertMercureUpdatePublished('/api/notifications/');
        self::assertCount(1, $this->pushes());

        // The broker delivers it again (a retry): nothing more.
        $this->handle($end);
        self::assertCount(1, $this->notificationsOf('test_user'));
        self::assertCount(1, $this->pushes());
    }

    /**
     * The delay decides, not the worker's clock: in the e2e stack every process ticks its own simulated
     * clock, and a worker 1 s behind php-fpm queued the end again forever instead of ringing it.
     */
    public function testTheEndRingsWhenItsDelayIsOverEvenIfTheWorkerClockLags(): void
    {
        $timer = $this->start('Pâtes', 3, 720);
        $end = $this->queuedEnds()[0]['message'];
        $this->clock->sleep(700);
        $this->resetAsyncTransport();

        $this->handle($end);

        self::assertSame(CookingTimerStatus::Done, $this->timer($timer['id'])->getStatus());
        self::assertCount(1, $this->notificationsOf('test_user'));
        self::assertSame([], $this->queuedEnds());
    }

    public function testNoEndRingsAfterAPause(): void
    {
        $timer = $this->start('Pâtes', 3, 720);
        $end = $this->queuedEnds()[0]['message'];
        $this->patch($timer['id'], ['action' => 'pause']);
        $this->clock->sleep(900);

        $this->handle($end);

        self::assertSame(CookingTimerStatus::Paused, $this->timer($timer['id'])->getStatus());
        self::assertSame([], $this->notificationsOf('test_user'));
        self::assertSame([], $this->pushes());
    }

    public function testNoEndRingsAfterACancel(): void
    {
        $timer = $this->start('Pâtes', 3, 720);
        $end = $this->queuedEnds()[0]['message'];
        $cancelled = $this->patch($timer['id'], ['action' => 'cancel']);
        self::assertSame('cancelled', $cancelled['status']);
        $this->clock->sleep(900);

        $this->handle($end);

        self::assertSame(CookingTimerStatus::Cancelled, $this->timer($timer['id'])->getStatus());
        self::assertSame([], $this->notificationsOf('test_user'));
        self::assertSame([], $this->pushes());
    }

    public function testOnlyTheEndOfTheLatestScheduleRings(): void
    {
        $timer = $this->start('Pâtes', 3, 720);
        $first = $this->queuedEnds()[0]['message'];
        $this->resetAsyncTransport();
        $this->patch($timer['id'], ['action' => 'add']);
        $moved = $this->queuedEnds()[0]['message'];
        $this->clock->sleep(780);

        $this->handle($first);
        self::assertSame(CookingTimerStatus::Running, $this->timer($timer['id'])->getStatus());

        $this->handle($moved);
        self::assertSame(CookingTimerStatus::Done, $this->timer($timer['id'])->getStatus());
        self::assertCount(1, $this->notificationsOf('test_user'));
    }

    public function testOkDismissesTheEndAndReadsItsNotificationEverywhere(): void
    {
        $timer = $this->start('Pâtes', 3, 720);
        $this->clock->sleep(720);
        $this->handle($this->queuedEnds()[0]['message']);
        $this->resetMercure();

        $data = $this->patch($timer['id'], ['action' => 'dismiss']);

        self::assertResponseIsSuccessful();
        self::assertSame('dismissed', $data['status']);
        self::assertSame(CookingTimerStatus::Dismissed, $this->timer($timer['id'])->getStatus());
        $notifications = $this->notificationsOf('test_user');
        self::assertNotNull($notifications[0]->getReadAt());
        $this->assertMercureUpdatePublished('/api/notifications/'.$notifications[0]->getId());
        $this->assertMercureUpdatePublished('/api/cooking/timers/'.$timer['id']);
        // A phone whose app is closed only hears it through a push.
        $withdrawals = array_values(array_filter(
            array_map(static fn ($envelope) => $envelope->getMessage(), $this->getAsyncTransport()->getSent()),
            static fn (object $message) => $message instanceof WithdrawPushNotificationCommand,
        ));
        self::assertCount(1, $withdrawals);
        self::assertSame((string) $notifications[0]->getId(), $withdrawals[0]->notificationId);
    }

    public function testCancellingARungTimerReadsItsNotificationToo(): void
    {
        $timer = $this->start('Pâtes', 3, 720);
        $this->clock->sleep(720);
        $this->handle($this->queuedEnds()[0]['message']);

        $data = $this->patch($timer['id'], ['action' => 'cancel']);

        self::assertSame('cancelled', $data['status']);
        self::assertNotNull($this->notificationsOf('test_user')[0]->getReadAt());
    }

    /**
     * The reference recipe: the water (10 min, in the background) and the
     * pasta (12 min, in the background) run with the vegetables (8 min); each
     * rings at its own end, once.
     */
    public function testThreeTimersRunTogetherAndRingEachAtItsEnd(): void
    {
        // The transport is emptied between requests: each end is read right after its start.
        $ends = [];
        $water = $this->start('Eau', 1, 600);
        $ends[$water['id']] = $this->queuedEnds()[0]['message'];
        $this->clock->sleep(60);
        $pasta = $this->start('Pâtes', 3, 720);
        $ends[$pasta['id']] = $this->queuedEnds()[0]['message'];
        $vegetables = $this->start('Légumes', 4, 480);
        $ends[$vegetables['id']] = $this->queuedEnds()[0]['message'];

        $list = $this->get(self::ENDPOINT);
        self::assertSame(['Eau', 'Pâtes', 'Légumes'], array_column($list['member'], 'label'));
        self::assertSame([540, 720, 480], array_column($list['member'], 'remainingSeconds'));

        $this->clock->sleep(480);
        $this->handle($ends[$vegetables['id']]);
        $this->clock->sleep(60);
        $this->handle($ends[$water['id']]);

        $list = $this->get(self::ENDPOINT.'?status=running');
        self::assertSame(['Pâtes'], array_column($list['member'], 'label'));
        self::assertSame(180, $list['member'][0]['remainingSeconds']);

        $this->clock->sleep(180);
        $this->handle($ends[$pasta['id']]);

        $done = $this->get(self::ENDPOINT.'?status=done');
        self::assertSame(['Eau', 'Pâtes', 'Légumes'], array_column($done['member'], 'label'));
        self::assertCount(3, $this->notificationsOf('test_user'));
    }

    public function testTheListHoldsTheUsersActiveTimersOnly(): void
    {
        $kept = $this->start('Pâtes', 3, 720);
        $cancelled = $this->start('Eau', 1, 600);
        $this->patch($cancelled['id'], ['action' => 'cancel']);
        $this->authenticateAsUser($this->user('other_user'));
        $this->post(['label' => 'Soupe', 'recipe' => (string) $this->recipe('other_recipe')->getId(), 'stepPosition' => 1, 'durationSeconds' => 60]);
        $this->authenticateAsUser($this->user('test_user'));

        $list = $this->get(self::ENDPOINT);
        self::assertSame([$kept['id']], array_column($list['member'], 'id'));
        self::assertSame(1, $list['totalItems']);

        $all = $this->get(self::ENDPOINT.'?status=running,cancelled');
        self::assertSame([$kept['id'], $cancelled['id']], array_column($all['member'], 'id'));

        $this->client->request('GET', self::ENDPOINT.'?status=running,lost', [], [], $this->authHeaders());
        self::assertResponseStatusCodeSame(400);
    }

    /** @return array<string, mixed> */
    private function start(string $label, int $step, int $seconds): array
    {
        return $this->post(['label' => $label, 'recipe' => (string) $this->recipe('pasta')->getId(), 'stepPosition' => $step, 'durationSeconds' => $seconds]);
    }

    /**
     * @param array<string, mixed>|string $body
     *
     * @return array<string, mixed>
     */
    private function post(array|string $body): array
    {
        $this->client->request('POST', self::ENDPOINT, [], [], array_merge(['CONTENT_TYPE' => 'application/json'], $this->authHeaders()), \is_string($body) ? $body : json_encode($body, \JSON_THROW_ON_ERROR));

        return $this->decoded();
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array<string, mixed>
     */
    private function patch(string $id, array $body): array
    {
        $this->client->request('PATCH', self::ENDPOINT.'/'.$id, [], [], array_merge(['CONTENT_TYPE' => 'application/json'], $this->authHeaders()), json_encode((object) $body, \JSON_THROW_ON_ERROR));

        return $this->decoded();
    }

    /** @return array<string, mixed> */
    private function get(string $path): array
    {
        $this->client->request('GET', $path, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return $this->decoded();
    }

    /** @return array<string, mixed> */
    private function decoded(): array
    {
        $data = json_decode((string) $this->client->getResponse()->getContent(), true);

        return \is_array($data) ? $data : [];
    }

    /** What the worker does once the delay is over. */
    private function handle(FinishCookingTimerCommand $end): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($end, [new ReceivedStamp('async')]);
    }

    /** @return list<array{message: FinishCookingTimerCommand, delay: int}> */
    private function queuedEnds(): array
    {
        $ends = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof FinishCookingTimerCommand) {
                $ends[] = ['message' => $message, 'delay' => $envelope->last(DelayStamp::class)?->getDelay() ?? -1];
            }
        }

        return $ends;
    }

    /** @return list<SendPushNotificationCommand> */
    private function pushes(): array
    {
        return array_values(array_filter(
            array_map(static fn ($envelope) => $envelope->getMessage(), $this->getAsyncTransport()->getSent()),
            static fn (object $message) => $message instanceof SendPushNotificationCommand,
        ));
    }

    /** @return list<Notification> */
    private function notificationsOf(string $ref): array
    {
        /** @var list<Notification> $notifications */
        $notifications = $this->em()->getRepository(Notification::class)->findBy(['user' => $this->user($ref)->getId()], ['createdAt' => 'ASC']);

        return $notifications;
    }

    private function timer(string $id): CookingTimer
    {
        $timer = $this->em()->find(CookingTimer::class, $id);
        self::assertInstanceOf(CookingTimer::class, $timer);

        return $timer;
    }

    private function user(string $ref): User
    {
        $user = $this->getFixture($ref);
        self::assertInstanceOf(User::class, $user);

        return $user;
    }

    private function recipe(string $ref): Recipe
    {
        $recipe = $this->getFixture($ref);
        self::assertInstanceOf(Recipe::class, $recipe);

        return $recipe;
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }
}
