<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Reminder;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Reminder\FastingReminderSource;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * « La fenêtre se ferme » (MAG-770): fasting-test fasts 16:8 from 12:00, so
 * the window closes at 20:00 Paris (19:00 UTC in November); fasting-none
 * stopped fasting.
 */
final class FastingReminderSourceTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-11-10 18:00:00 UTC'));
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/fasting.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testBeforeTheWindowsEndNobodyIsTold(): void
    {
        $report = $this->source()->remind(new \DateTimeImmutable('2026-11-10 18:59:59 UTC'));

        self::assertSame(0, $report->created);
        self::assertSame([], $this->notifications());
    }

    public function testAtTheWindowsEndOneEncouragingNotificationOpensNutrition(): void
    {
        $report = $this->source()->remind(new \DateTimeImmutable('2026-11-10 19:00:00 UTC'));

        self::assertSame(1, $report->created);
        self::assertSame([], $report->warnings);
        $notifications = $this->notifications();
        self::assertCount(1, $notifications);
        $notification = $notifications[0];
        self::assertSame('fasting-test@example.com', $notification->getUser()->getEmail());
        self::assertSame('La fenêtre se ferme.', $notification->getTitle());
        self::assertSame('Bon jeûne jusqu\'à demain 12 h.', $notification->getBody());
        self::assertSame('/health/nutrition', $notification->getRelatedEntityIri());
        self::assertEquals(new \DateTimeImmutable('2026-11-10 19:00:00 UTC'), $notification->getOccurrenceStartAt());
        $this->assertMercureUpdatePublished('/api/notifications/');
    }

    public function testTheSameWindowIsNeverToldTwice(): void
    {
        $this->source()->remind(new \DateTimeImmutable('2026-11-10 19:00:00 UTC'));
        $report = $this->source()->remind(new \DateTimeImmutable('2026-11-10 19:01:00 UTC'));

        self::assertSame(0, $report->created);
        self::assertCount(1, $this->notifications());
    }

    public function testAWindowClosedLongAgoIsNoLongerNews(): void
    {
        $report = $this->source()->remind(new \DateTimeImmutable('2026-11-10 19:30:00 UTC'));

        self::assertSame(0, $report->created);
    }

    public function testTheNextDaysWindowIsToldAgain(): void
    {
        $this->source()->remind(new \DateTimeImmutable('2026-11-10 19:00:00 UTC'));
        $report = $this->source()->remind(new \DateTimeImmutable('2026-11-11 19:00:00 UTC'));

        self::assertSame(1, $report->created);
        self::assertCount(2, $this->notifications());
    }

    /** @return list<Notification> */
    private function notifications(): array
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em->getRepository(Notification::class)->findBy(['type' => NotificationType::Health], ['createdAt' => 'ASC']);
    }

    private function source(): FastingReminderSource
    {
        $source = self::getContainer()->get(FastingReminderSource::class);
        \assert($source instanceof FastingReminderSource);

        return $source;
    }
}
