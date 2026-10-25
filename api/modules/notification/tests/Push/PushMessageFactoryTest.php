<?php

declare(strict_types=1);

namespace Maggie\Notification\Tests\Push;

use Maggie\Core\Entity\User;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Push\PushMessageFactory;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/**
 * The FCM message carries a `notification` block — Android shows it when the
 * app is not in front — and a `data` block for the app's interruption (MAG-314).
 */
final class PushMessageFactoryTest extends TestCase
{
    public function testTheMessageCarriesBothBlocks(): void
    {
        $notification = $this->notification(NotificationType::Proaction, 'J\'ai rangé ta semaine', 'Trois créneaux libérés.', '/api/events/01JABCDEF0123456789ABCDEFG');

        $message = (new PushMessageFactory())->build($notification, 'device-token');

        self::assertSame('device-token', $message['token']);
        self::assertSame(['title' => 'J\'ai rangé ta semaine', 'body' => 'Trois créneaux libérés.'], $message['notification']);
        self::assertSame([
            'notificationId' => (string) $notification->getId(),
            'type' => 'proaction',
            'title' => 'J\'ai rangé ta semaine',
            'body' => 'Trois créneaux libérés.',
            'link' => 'maggie://event/01JABCDEF0123456789ABCDEFG',
            'actionLabel' => 'Voir l\'événement',
        ], $message['data']);
        self::assertSame('high', $message['android']['priority']);
        self::assertSame('chat_v2', $message['android']['notification']['channel_id']);
        self::assertSame((string) $notification->getId(), $message['android']['notification']['tag']);
    }

    public function testDataHoldsOnlyStringsAndLeavesOutWhatIsMissing(): void
    {
        $message = (new PushMessageFactory())->build($this->notification(NotificationType::Approval, 'Je peux payer ?'), 't');

        self::assertSame(['title' => 'Je peux payer ?'], $message['notification']);
        self::assertSame(['notificationId', 'type', 'title'], array_keys($message['data']));
        self::assertContainsOnlyString($message['data']);
    }

    public function testAReminderSaysWhenRatherThanAMinuteCount(): void
    {
        $message = (new PushMessageFactory())->build($this->notification(NotificationType::Reminder, 'Dentiste', '15'), 't');

        self::assertSame('Dans 15 min', $message['notification']['body']);
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
        self::assertStringEndsWith('…', $message['notification']['title']);
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
        // Data alone: Android would show a `notification` block itself, without the buttons.
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
        // Data alone, like the reminders: a `notification` block would hide the buttons.
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
        self::assertSame('reminders_v2', $message['android']['notification']['channel_id']);
        self::assertArrayHasKey('notification', $message, 'without buttons, both blocks as always');
    }

    public function testAShortMessageIsLeftWhole(): void
    {
        $message = (new PushMessageFactory())->build($this->notification(NotificationType::Proaction, 'Café prêt', 'À toi de jouer'), 't');

        self::assertSame(['title' => 'Café prêt', 'body' => 'À toi de jouer'], $message['notification']);
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

        self::assertSame('cooking-timer-'.$timerId, $first['android']['notification']['tag']);
        self::assertSame($first['android']['notification']['tag'], $again['android']['notification']['tag'], 'Two notifications of one timer replace each other on the phone.');
        self::assertSame($timerId, $first['data']['cookingTimerId']);
        self::assertSame('cooking_timer', $first['data']['type']);
        self::assertSame('minuteur de l\'étape 3 · Pâtes aux légumes', $first['notification']['body']);
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

    public function testAnotherNotificationKeepsItsOwnTagAndNoTimerId(): void
    {
        $notification = $this->notification(NotificationType::Reminder, 'Rendez-vous', null, '/api/events/01JEVENT');
        $message = (new PushMessageFactory())->build($notification, 't');

        self::assertSame((string) $notification->getId(), $message['android']['notification']['tag']);
        self::assertArrayNotHasKey('cookingTimerId', $message['data']);
    }

    #[DataProvider('channels')]
    public function testEachTypeGoesToItsAndroidChannel(NotificationType $type, string $channel): void
    {
        $message = (new PushMessageFactory())->build($this->notification($type, 'Titre'), 't');

        self::assertSame($channel, $message['android']['notification']['channel_id']);
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
