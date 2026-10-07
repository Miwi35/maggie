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
        self::assertSame('chat', $message['android']['notification']['channel_id']);
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

    public function testALongTextIsCutToFitFcmsSizeLimit(): void
    {
        $message = (new PushMessageFactory())->build($this->notification(NotificationType::Proaction, 'Long', str_repeat('é', 5000)), 't');

        self::assertSame(1000, mb_strlen($message['notification']['body']));
        self::assertStringEndsWith('…', $message['data']['body']);
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
        yield 'reminder' => [NotificationType::Reminder, 'reminders'];
        yield 'task due' => [NotificationType::TaskDue, 'reminders'];
        yield 'approval' => [NotificationType::Approval, 'approvals'];
        yield 'consent' => [NotificationType::ConsentExpiring, 'finance'];
        yield 'finance' => [NotificationType::Finance, 'finance'];
        yield 'proaction' => [NotificationType::Proaction, 'chat'];
        yield 'grocery' => [NotificationType::Grocery, 'chat'];
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
