<?php

declare(strict_types=1);

namespace Maggie\Notification\Tests\Push;

use Maggie\Core\Entity\User;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Push\NavigationLinkProvider;
use Maggie\Notification\Push\PushMessageFactory;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/**
 * Every FCM message is data alone, at high priority (MAG-681): Android Auto only reads
 * notifications the app builds, and a `notification` block is shown by the system itself
 * while the app is closed. The `data` block carries all the app needs to build it.
 */
final class PushMessageFactoryTest extends TestCase
{
    /** @return iterable<string, array{NotificationType}> */
    public static function everyType(): iterable
    {
        foreach (NotificationType::cases() as $type) {
            yield $type->value => [$type];
        }
    }

    #[DataProvider('everyType')]
    public function testNoMessageCarriesANotificationBlock(NotificationType $type): void
    {
        $notification = $this->notification($type, 'Titre', 'Texte', '/api/events/01JEVENT');

        $message = (new PushMessageFactory())->build($notification, 't');

        self::assertArrayNotHasKey('notification', $message);
        self::assertArrayNotHasKey('notification', $message['android']);
        self::assertSame('high', $message['android']['priority']);
        self::assertSame('Titre', $message['data']['title']);
        self::assertSame('Texte', $message['data']['body']);
        self::assertSame($type->value, $message['data']['type']);
        self::assertContainsOnlyString($message['data']);
    }

    #[DataProvider('everyType')]
    public function testTheDataNamesTheChannelTheAppBuildsItsNotificationIn(NotificationType $type): void
    {
        $message = (new PushMessageFactory())->build($this->notification($type, 'Titre'), 't');

        self::assertContains($message['data']['channel'], [
            PushMessageFactory::CHANNEL_CHAT,
            PushMessageFactory::CHANNEL_REMINDERS,
            PushMessageFactory::CHANNEL_APPROVALS,
            PushMessageFactory::CHANNEL_FINANCE,
            PushMessageFactory::CHANNEL_DEPARTURE,
        ]);
    }

    public function testTheMessageCarriesEverythingTheAppShowsInData(): void
    {
        $notification = $this->notification(NotificationType::Proaction, 'J\'ai rangé ta semaine', 'Trois créneaux libérés.', '/api/events/01JABCDEF0123456789ABCDEFG');

        $message = (new PushMessageFactory())->build($notification, 'device-token');

        self::assertSame('device-token', $message['token']);
        self::assertArrayNotHasKey('notification', $message);
        self::assertSame([
            'notificationId' => (string) $notification->getId(),
            'type' => 'proaction',
            'title' => 'J\'ai rangé ta semaine',
            'body' => 'Trois créneaux libérés.',
            'channel' => 'chat_v2',
            'link' => 'maggie://event/01JABCDEF0123456789ABCDEFG',
            'actionLabel' => 'Voir l\'événement',
        ], $message['data']);
        self::assertSame(['priority' => 'high'], $message['android']);
    }

    public function testDataHoldsOnlyStringsAndLeavesOutWhatIsMissing(): void
    {
        $message = (new PushMessageFactory())->build($this->notification(NotificationType::Approval, 'Je peux payer ?'), 't');

        self::assertSame(['notificationId', 'type', 'title', 'channel'], array_keys($message['data']));
        self::assertContainsOnlyString($message['data']);
    }

    public function testAReminderSaysWhenRatherThanAMinuteCount(): void
    {
        $message = (new PushMessageFactory())->build($this->notification(NotificationType::Reminder, 'Dentiste', '15'), 't');

        self::assertSame('Dans 15 min', $message['data']['body']);
    }

    public function testALongMessageIsCutToFitFcmsSizeLimit(): void
    {
        // Two- and four-byte characters: FCM counts bytes, not characters.
        $message = (new PushMessageFactory())->build($this->notification(
            NotificationType::Proaction,
            str_repeat('é', 255),
            str_repeat('é😀', 2000),
            '/api/events/01JABCDEF0123456789ABCDEFG',
        ), str_repeat('t', 163));

        self::assertLessThan(4096, \strlen(json_encode($message, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)));
        self::assertStringEndsWith('…', $message['data']['body']);
        self::assertStringEndsWith('…', $message['data']['title']);
        self::assertTrue(mb_check_encoding($message['data']['body'], 'UTF-8'));
    }

    public function testATaskReminderCarriesItsReminderItsTaskAndItsButtonsInOrder(): void
    {
        $notification = $this->notification(NotificationType::TaskNudge, 'Suivante : « Appeler le garagiste ».', 'Premier pas : Trouver le numéro', '/api/tasks/01JABCDEF0123456789ABCDEFG')
            ->setReminderId('01JREMINDER0123456789ABCDE')
            ->setActions(['start', 'in10', 'tonight']);

        $message = (new PushMessageFactory())->build($notification, 't');

        self::assertSame('01JREMINDER0123456789ABCDE', $message['data']['reminderId']);
        self::assertSame('01JABCDEF0123456789ABCDEFG', $message['data']['taskId']);
        self::assertSame('start,in10,tonight', $message['data']['actions']);
        self::assertSame('task_nudge', $message['data']['type']);
        self::assertContainsOnlyString($message['data']);
        self::assertArrayNotHasKey('notification', $message);
        self::assertSame(['priority' => 'high'], $message['android']);
        self::assertSame('Suivante : « Appeler le garagiste ».', $message['data']['title']);
    }

    public function testAShortageAlertCarriesItsIdAndItsButtonInOrder(): void
    {
        $notification = $this->notification(NotificationType::Grocery, 'Il manque : Parmesan', 'Pour Gratin, ce soir.', '/api/shortage-alerts/01JSHORTAGE0123456789ABCD')
            ->setActions(['add']);

        $message = (new PushMessageFactory())->build($notification, 't');

        self::assertSame('01JSHORTAGE0123456789ABCD', $message['data']['shortageId']);
        self::assertSame('add', $message['data']['actions']);
        self::assertSame('grocery', $message['data']['type']);
        self::assertArrayNotHasKey('reminderId', $message['data']);
        self::assertArrayNotHasKey('taskId', $message['data']);
        self::assertArrayNotHasKey('notification', $message);
        self::assertContainsOnlyString($message['data']);
    }

    public function testAShortageIdIsReadFromTheAlertIriOnly(): void
    {
        $message = (new PushMessageFactory())->build($this->notification(NotificationType::Grocery, 'Stock bas', null, '/api/grocery_items/01JITEM'), 't');

        self::assertArrayNotHasKey('shortageId', $message['data']);
    }

    public function testARoutineReminderCarriesItsButtonsAndOpensTheTasksScreen(): void
    {
        $notification = $this->notification(NotificationType::Routine, 'Routines · soir', 'Brosser les dents, c\'est le moment.', '/tasks')
            ->setReminderId('01JREMINDER0123456789ABCDE')
            ->setActions(['done', 'in10', 'open']);

        $message = (new PushMessageFactory())->build($notification, 't');

        self::assertSame('routine', $message['data']['type']);
        self::assertSame('done,in10,open', $message['data']['actions']);
        self::assertSame('01JREMINDER0123456789ABCDE', $message['data']['reminderId']);
        self::assertSame('maggie://tasks', $message['data']['link']);
        self::assertArrayNotHasKey('taskId', $message['data']);
        self::assertArrayNotHasKey('notification', $message);
        self::assertContainsOnlyString($message['data']);
    }

    public function testANotificationWithoutButtonsCarriesNoReminderKeys(): void
    {
        $message = (new PushMessageFactory())->build($this->notification(NotificationType::TaskDue, 'Aujourd\'hui : « Payer »', null, '/api/tasks/01JABCDEF0123456789ABCDEFG'), 't');

        self::assertArrayNotHasKey('reminderId', $message['data']);
        self::assertArrayNotHasKey('taskId', $message['data']);
        self::assertArrayNotHasKey('actions', $message['data']);
        self::assertSame('reminders_v2', $message['data']['channel']);
        self::assertArrayNotHasKey('notification', $message);
    }

    public function testAShortMessageIsLeftWhole(): void
    {
        $message = (new PushMessageFactory())->build($this->notification(NotificationType::Proaction, 'Café prêt', 'À toi de jouer'), 't');

        self::assertSame('Café prêt', $message['data']['title']);
        self::assertSame('À toi de jouer', $message['data']['body']);
    }

    public function testTheLinkUsesTheSchemeOfTheBuild(): void
    {
        $message = (new PushMessageFactory('maggie-dev'))->build($this->notification(NotificationType::TaskDue, 'Rendre le dossier', null, '/api/tasks/01JTASK'), 't');

        self::assertSame('maggie-dev://task/01JTASK', $message['data']['link']);
    }

    /** @return iterable<string, array{string, ?string, ?string}> */
    public static function links(): iterable
    {
        yield 'event' => ['/api/events/01JEVENT', 'maggie://event/01JEVENT', 'Voir l\'événement'];
        yield 'task' => ['/api/tasks/01JTASK', 'maggie://task/01JTASK', 'Voir la tâche'];
        yield 'grocery item' => ['/api/grocery_items/01JITEM', 'maggie://grocery/01JITEM', 'Voir la liste de courses'];
        yield 'recipe' => ['/api/recipes/01JRECIPE', 'maggie://recipe/01JRECIPE', 'Voir la recette'];
        yield 'bank consent' => ['/finance/banks?connection=01JCONN', 'maggie://finance/banks', 'Reconnecter la banque'];
        yield 'envelope alert' => ['/finance/budgets', 'maggie://finance/budgets', 'Voir les enveloppes'];
        yield 'routines' => ['/tasks', 'maggie://tasks', 'Voir les routines'];
        yield 'finance home' => ['/finance', 'maggie://finance', 'Ouvrir les finances'];
        yield 'a screen the app does not have' => ['/finance/secrets', null, null];
        yield 'an entity the app cannot open' => ['/api/stores/01JSTORE', null, null];
        yield 'an id the app would refuse' => ['/api/events/../../x', null, null];
    }

    #[DataProvider('links')]
    public function testTheRelatedEntityBecomesTheLinkToOpen(string $iri, ?string $link, ?string $label): void
    {
        $message = (new PushMessageFactory())->build($this->notification(NotificationType::Proaction, 'Titre', null, $iri), 't');

        self::assertSame($link, $message['data']['link'] ?? null);
        self::assertSame($label, $message['data']['actionLabel'] ?? null);
    }

    /** @return iterable<string, array{NotificationType, string}> */
    public static function channels(): iterable
    {
        yield 'reminder' => [NotificationType::Reminder, 'reminders_v2'];
        yield 'task due' => [NotificationType::TaskDue, 'reminders_v2'];
        yield 'approval' => [NotificationType::Approval, 'approvals_v2'];
        yield 'consent' => [NotificationType::ConsentExpiring, 'finance_v2'];
        yield 'finance' => [NotificationType::Finance, 'finance_v2'];
        yield 'proaction' => [NotificationType::Proaction, 'chat_v2'];
        yield 'grocery' => [NotificationType::Grocery, 'chat_v2'];
        yield 'cooking timer' => [NotificationType::CookingTimer, 'reminders_v2'];
    }

    public function testACookingTimersEndIsTaggedOncePerTimerAndCarriesItsId(): void
    {
        $timerId = '01JCOOKINGTIMER0000000000A';
        $first = (new PushMessageFactory())->build($this->notification(NotificationType::CookingTimer, 'Pâtes : c\'est prêt', 'minuteur de l\'étape 3 · Pâtes aux légumes', '/api/cooking/timers/'.$timerId), 't');
        $again = (new PushMessageFactory())->build($this->notification(NotificationType::CookingTimer, 'Pâtes : c\'est prêt', null, '/api/cooking/timers/'.$timerId), 't');

        self::assertSame($timerId, $first['data']['cookingTimerId']);
        self::assertSame($first['data']['cookingTimerId'], $again['data']['cookingTimerId'], 'Two notifications of one timer replace each other on the phone.');
        self::assertSame('cooking-timer-'.$timerId, (new PushMessageFactory())->buildWithdrawal($this->notification(NotificationType::CookingTimer, 'x', null, '/api/cooking/timers/'.$timerId), 't')['data']['tag']);
        self::assertSame($timerId, $first['data']['cookingTimerId']);
        self::assertSame('cooking_timer', $first['data']['type']);
        self::assertSame('minuteur de l\'étape 3 · Pâtes aux légumes', $first['data']['body']);
    }

    public function testAWithdrawalIsDataOnlyAndNamesTheTagToTakeDown(): void
    {
        $timerId = '01JCOOKINGTIMER0000000000A';
        $notification = $this->notification(NotificationType::CookingTimer, 'Pâtes : c\'est prêt', null, '/api/cooking/timers/'.$timerId);

        $message = (new PushMessageFactory())->buildWithdrawal($notification, 't');

        self::assertArrayNotHasKey('notification', $message);
        self::assertSame(['type' => 'withdraw', 'notificationId' => (string) $notification->getId(), 'tag' => 'cooking-timer-'.$timerId], $message['data']);
        self::assertSame('high', $message['android']['priority']);
    }

    /** Asking the phone where it is (MAG-650): nothing shown, delivered at once, useless after ten minutes. */
    public function testALocationRequestIsDataOnlyAndUrgent(): void
    {
        $message = (new PushMessageFactory())->buildLocationRequest('t');

        self::assertSame('t', $message['token']);
        self::assertArrayNotHasKey('notification', $message);
        self::assertSame(['type' => 'location_request'], $message['data']);
        self::assertSame(['priority' => 'high', 'ttl' => '600s', 'collapse_key' => 'location_request'], $message['android']);
    }

    public function testAnotherNotificationKeepsItsOwnTagAndNoTimerId(): void
    {
        $notification = $this->notification(NotificationType::Reminder, 'Rendez-vous', null, '/api/events/01JEVENT');
        $message = (new PushMessageFactory())->build($notification, 't');

        self::assertArrayNotHasKey('cookingTimerId', $message['data']);
    }

    #[DataProvider('channels')]
    public function testEachTypeGoesToItsAndroidChannel(NotificationType $type, string $channel): void
    {
        $message = (new PushMessageFactory())->build($this->notification($type, 'Titre'), 't');

        self::assertSame($channel, $message['data']['channel']);
    }

    /** Time to leave (MAG-655): « Y aller » opens the trip's navigation URL, at high priority, in the departure channel. */
    public function testADepartureOpensTheNavigationUrlOfItsTrip(): void
    {
        $factory = new PushMessageFactory('maggie', [$this->navigationLinks(['/api/trips/01JTRIP' => 'https://waze.com/ul?ll=48.119,-1.6672&navigate=yes'])]);

        $message = $factory->build($this->notification(NotificationType::Departure, 'Pars maintenant pour Dentiste', null, '/api/trips/01JTRIP'), 't');

        self::assertSame('departure', $message['data']['type']);
        self::assertSame('https://waze.com/ul?ll=48.119,-1.6672&navigate=yes', $message['data']['link']);
        self::assertSame('Y aller', $message['data']['actionLabel']);
        self::assertSame('high', $message['android']['priority']);
        self::assertSame('departure', $message['data']['channel']);
        self::assertArrayNotHasKey('notification', $message);
        self::assertArrayNotHasKey('notification', $message['android']);
        self::assertSame('Pars maintenant pour Dentiste', $message['data']['title']);
    }

    /** A phone offline when the alert goes out must not be told « Pars maintenant » once the event has started (MAG-679). */
    public function testADepartureCarriesItsExpiryAndFcmDropsItThen(): void
    {
        $factory = new PushMessageFactory('maggie', [$this->navigationLinks(['/api/trips/01JTRIP' => 'https://waze.com/ul?navigate=yes'])]);
        $notification = $this->notification(NotificationType::Departure, 'Pars maintenant pour Dentiste', null, '/api/trips/01JTRIP')
            ->setExpiresAt(new \DateTimeImmutable('+20 minutes'));

        $message = $factory->build($notification, 't');

        self::assertSame($notification->getExpiresAt()?->format('c'), $message['data']['expiresAt']);
        self::assertEqualsWithDelta(1200, (int) rtrim($message['android']['ttl'], 's'), 5);
    }

    public function testADepartureWhoseTripNobodyKnowsAnymoreCarriesNoLink(): void
    {
        $factory = new PushMessageFactory('maggie', [$this->navigationLinks([])]);

        $message = $factory->build($this->notification(NotificationType::Departure, 'Pars maintenant pour Dentiste', null, '/api/trips/01JTRIP'), 't');

        self::assertArrayNotHasKey('link', $message['data']);
        self::assertArrayNotHasKey('actionLabel', $message['data']);
    }

    /** @param array<string, string> $urls related IRI → URL */
    private function navigationLinks(array $urls): NavigationLinkProvider
    {
        return new class($urls) implements NavigationLinkProvider {
            /** @param array<string, string> $urls */
            public function __construct(private readonly array $urls)
            {
            }

            public function navigationUrl(string $relatedEntityIri): ?string
            {
                return $this->urls[$relatedEntityIri] ?? null;
            }
        };
    }

    private function notification(NotificationType $type, string $title, ?string $body = null, ?string $iri = null): Notification
    {
        return (new Notification())
            ->setUser(new User())
            ->setType($type)
            ->setTitle($title)
            ->setBody($body)
            ->setRelatedEntityIri($iri);
    }
}
