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
use Maggie\Notification\Message\ExpireNotificationsCommand;
use Maggie\Notification\Message\WithdrawPushNotificationCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/** What a notification is about is gone: it stops being worth saying, stays in the bell, and leaves the tray (MAG-679). */
final class ExpireNotificationsHandlerTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private const TRIP = '/api/trips/01JTRIP';

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('notifications_preference.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testAnUnreadAlertExpiresNowAndItsPushIsWithdrawn(): void
    {
        $this->alert('Pars maintenant pour Dentiste', self::TRIP, new \DateTimeImmutable('+1 hour'));
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->expire(self::TRIP);

        $notification = $this->only('Pars maintenant pour Dentiste');
        self::assertLessThanOrEqual(new \DateTimeImmutable(), $notification->getExpiresAt());
        self::assertNull($notification->getReadAt(), 'It stays unread in the bell: history, not deleted.');
        $payloads = $this->mercurePayloadsOn('/api/notifications/'.$notification->getId());
        self::assertArrayHasKey('expiresAt', end($payloads), 'The screens learn it through Mercure.');
        self::assertSame([(string) $notification->getId()], $this->withdrawnIds());
    }

    public function testAReadAlertExpiresWithoutAWithdrawPush(): void
    {
        $this->alert('Déjà vue', self::TRIP, new \DateTimeImmutable('+1 hour'));
        $notification = $this->only('Déjà vue');
        $notification->setReadAt(new \DateTimeImmutable());
        $this->em()->flush();
        $this->resetAsyncTransport();

        $this->expire(self::TRIP);

        self::assertLessThanOrEqual(new \DateTimeImmutable(), $this->only('Déjà vue')->getExpiresAt());
        self::assertSame([], $this->withdrawnIds());
    }

    public function testAnAlertThatAlreadyExpiredIsLeftAlone(): void
    {
        $startedAt = new \DateTimeImmutable('@'.(time() - 3 * 3600));
        $this->alert('Périmée', self::TRIP, $startedAt);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->expire(self::TRIP);

        self::assertEquals($startedAt, $this->only('Périmée')->getExpiresAt(), 'Its own expiry is not rewritten.');
        $this->assertMercureUpdateCount(0);
        self::assertSame([], $this->withdrawnIds());
    }

    public function testAnAlertThatNeverExpiresAndAnotherEntitysAreLeftAlone(): void
    {
        $this->alert('Sans fin', self::TRIP, null);
        $this->alert('Autre trajet', '/api/trips/01JOTHER', new \DateTimeImmutable('+1 hour'));

        $this->expire(self::TRIP);

        self::assertLessThanOrEqual(new \DateTimeImmutable(), $this->only('Sans fin')->getExpiresAt(), 'Gone is gone, even for an alert with no expiry yet.');
        self::assertGreaterThan(new \DateTimeImmutable(), $this->only('Autre trajet')->getExpiresAt());
    }

    private function alert(string $title, string $iri, ?\DateTimeImmutable $expiresAt): void
    {
        $user = $this->em()->getRepository(User::class)->findOneBy(['email' => 'enabled@example.com']);
        self::assertInstanceOf(User::class, $user);

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateNotificationCommand(
            type: 'departure',
            title: $title,
            relatedEntityIri: $iri,
            userId: (string) $user->getId(),
            expiresAt: $expiresAt,
        ));
        $this->em()->clear();
    }

    private function expire(string $iri): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new ExpireNotificationsCommand($iri));
        $this->em()->clear();
    }

    private function only(string $title): Notification
    {
        $found = $this->em()->getRepository(Notification::class)->findBy(['title' => $title]);
        self::assertCount(1, $found);

        return $found[0];
    }

    /** @return list<string> */
    private function withdrawnIds(): array
    {
        $ids = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof WithdrawPushNotificationCommand) {
                $ids[] = $message->notificationId;
            }
        }

        return $ids;
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
