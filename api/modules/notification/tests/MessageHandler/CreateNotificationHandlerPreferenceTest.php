<?php

declare(strict_types=1);

namespace Maggie\Notification\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Message\CreateNotificationCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * The « notifications activées » preference decides whether a notification
 * exists at all: every producer goes through `CreateNotificationCommand`.
 */
final class CreateNotificationHandlerPreferenceTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('notifications_preference.yaml');
    }

    public function testNothingIsCreatedWhenTheUserTurnedNotificationsOff(): void
    {
        $this->notify('silent@example.com', 'Rappel muet');

        self::assertCount(0, $this->notificationsTitled('Rappel muet'));
        $this->assertMercureUpdateCount(0);
    }

    public function testANotificationIsCreatedWhenTheUserKeptThemOn(): void
    {
        $this->notify('enabled@example.com', 'Rappel actif');

        $stored = $this->notificationsTitled('Rappel actif');
        self::assertCount(1, $stored);
        self::assertSame('enabled@example.com', $stored[0]->getUser()->getEmail());
        $this->assertMercureUpdatePublished('/api/notifications/');
        $this->assertElasticsearchIndexDispatched(Notification::class);
    }

    public function testANotificationIsCreatedWhenTheUserNeverSavedPreferences(): void
    {
        // Notifications are on by default: no preference row must not mean off.
        $this->notify('default@example.com', 'Rappel par défaut');

        self::assertCount(1, $this->notificationsTitled('Rappel par défaut'));
    }

    public function testTurningThemOffOnlySilencesThatUser(): void
    {
        $this->notify('silent@example.com', 'Pour le muet');
        $this->notify('enabled@example.com', 'Pour l\'actif');

        self::assertCount(0, $this->notificationsTitled('Pour le muet'));
        self::assertCount(1, $this->notificationsTitled('Pour l\'actif'));
    }

    private function notify(string $email, string $title): void
    {
        $user = $this->em()->getRepository(User::class)->findOneBy(['email' => $email]);
        self::assertInstanceOf(User::class, $user);

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateNotificationCommand(
            type: 'reminder',
            title: $title,
            userId: (string) $user->getId(),
        ));
        $this->em()->clear();
    }

    /** @return list<Notification> */
    private function notificationsTitled(string $title): array
    {
        return array_values($this->em()->getRepository(Notification::class)->findBy(['title' => $title]));
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
