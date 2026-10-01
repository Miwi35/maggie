<?php

declare(strict_types=1);

namespace Maggie\Notification\Tests\Enum;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\MessageHandler\CreateNotificationHandler;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

final class NotificationTypeTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testEveryTypeTheProducersNeedExists(): void
    {
        self::assertEqualsCanonicalizing(
            ['reminder', 'consent_expiring', 'proaction', 'task_due', 'grocery', 'approval', 'finance'],
            array_map(static fn (NotificationType $type) => $type->value, NotificationType::cases()),
        );
    }

    public function testEveryTypeFitsTheColumn(): void
    {
        $length = self::getContainer()->get('doctrine.orm.entity_manager')
            ->getClassMetadata(Notification::class)
            ->getFieldMapping('type')->length;

        foreach (NotificationType::cases() as $type) {
            self::assertLessThanOrEqual($length, strlen($type->value), $type->value.' does not fit the type column');
        }
    }

    /**
     * @return iterable<string, array{string}>
     */
    public static function newTypes(): iterable
    {
        foreach (['proaction', 'task_due', 'grocery', 'approval', 'finance'] as $type) {
            yield $type => [$type];
        }
    }

    #[DataProvider('newTypes')]
    public function testANotificationOfTheTypeIsStoredPublishedAndIndexed(string $type): void
    {
        $this->loadFixtures('user.yaml');
        $user = $this->entityManager()->getRepository(User::class)->findOneBy(['email' => 'type-test@example.com']);
        self::assertInstanceOf(User::class, $user);

        // Through the bus, like every producer: the Mercure update comes from its middleware.
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateNotificationCommand(
            type: $type,
            title: 'Titre '.$type,
            userId: (string) $user->getId(),
        ));
        $this->entityManager()->clear();

        $stored = $this->entityManager()->getRepository(Notification::class)->findOneBy(['title' => 'Titre '.$type]);
        self::assertInstanceOf(Notification::class, $stored);
        self::assertSame($type, $stored->getType()->value);
        self::assertSame('type-test@example.com', $stored->getUser()->getEmail());
        self::assertNull($stored->getReadAt());

        $this->assertMercureUpdatePublished('/api/notifications/');
        $this->assertElasticsearchIndexDispatched(Notification::class);
    }

    public function testAnUnknownTypeIsRefused(): void
    {
        $this->loadFixtures('user.yaml');
        $user = $this->entityManager()->getRepository(User::class)->findOneBy(['email' => 'type-test@example.com']);

        $this->expectException(\ValueError::class);

        (self::getContainer()->get(CreateNotificationHandler::class))(new CreateNotificationCommand(
            type: 'spam',
            title: 'Inconnu',
            userId: (string) $user->getId(),
        ));
    }

    private function entityManager(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
