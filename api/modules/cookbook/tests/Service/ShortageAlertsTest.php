<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Service;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\ShortageAlert;
use Maggie\Cookbook\Service\ShortageAlerts;
use Maggie\Cookbook\UseCase\DeleteMeal;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\SendPushNotificationCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * « Il manque » (MAG-570): what the meals of today and tomorrow miss raises one
 * interruption per product and meal, and goes when it is covered.
 *
 * The clock is pinned: 2026-10-14 08:00 UTC is 10:00 in Paris, outside the
 * quiet hours.
 */
final class ShortageAlertsTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private const MORNING = '2026-10-14 08:00:00 UTC';

    private MockClock $clock;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->at(self::MORNING);
        $this->loadFixtures('ShortageAlertsTest.yaml');
        $this->reset();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testMissesWhatIsNeitherInStockNorOnTheList(): void
    {
        $missing = $this->service()->missing($this->user());

        // Eggs are in stock, butter is on the list, milk is ticked on it; the meal in two days is out of the window.
        self::assertSame(
            ['Parmesan · dinner · 2026-10-14', 'Crème fraîche · lunch · 2026-10-15', 'Parmesan · dinner · 2026-10-15'],
            array_map(static fn (array $m): string => $m['name'].' · '.$m['slot'].' · '.$m['date'], $missing),
        );
    }

    public function testOneNotificationPerProductAndMeal(): void
    {
        $sent = $this->service()->run($this->user());
        $this->em()->clear();

        self::assertSame(3, $sent);
        self::assertSame(
            [
                'Il manque : Crème fraîche · Pour Tarte, demain midi.',
                'Il manque : Parmesan · Pour Gratin, ce soir.',
                'Il manque : Parmesan · Pour Gratin, demain soir.',
            ],
            $this->notificationTexts(),
        );

        $notification = $this->notificationFor('Pour Gratin, ce soir.');
        self::assertSame(NotificationType::Grocery, $notification->getType());
        self::assertSame(['add'], $notification->getActions());
        self::assertNull($notification->getReminderId());
        self::assertNull($notification->getReadAt());

        $alert = $this->alertFor('Parmesan', 'dinner', '2026-10-14');
        self::assertSame('/api/shortage-alerts/'.$alert->getId(), $notification->getRelatedEntityIri());
        self::assertSame('Parmesan', $alert->getProduct()->getName());
        self::assertSame('dinner', $alert->getMeal()->getSlot()->value);
        self::assertFalse($alert->isResolved());

        $this->assertMercureUpdatePublished('/api/notifications/');
        $this->assertElasticsearchIndexDispatched(Notification::class);
        self::assertSame(3, $this->pushesRequested(), 'one push per notification');
    }

    public function testNothingIsSaidTwice(): void
    {
        $this->service()->run($this->user());
        $this->reset();

        self::assertSame(0, $this->service()->run($this->user()));
        self::assertCount(3, $this->em()->getRepository(Notification::class)->findAll());
        self::assertSame(0, $this->pushesRequested());
    }

    public function testADeletedNotificationDoesNotComeBack(): void
    {
        $this->service()->run($this->user());
        $this->em()->clear();
        $this->em()->remove($this->notificationFor('Pour Tarte, demain midi.'));
        $this->em()->flush();
        $this->reset();

        self::assertSame(0, $this->service()->run($this->user()));
        self::assertCount(2, $this->em()->getRepository(Notification::class)->findAll());
    }

    public function testNothingIsRaisedInTheQuietHours(): void
    {
        // 21:30 in Paris, past the default 21:00 start of the quiet hours.
        $this->at('2026-10-14 19:30:00 UTC');

        self::assertSame(0, $this->service()->run($this->user()));
        self::assertSame([], $this->em()->getRepository(Notification::class)->findAll());
    }

    public function testQuietHoursComeFromTheUsersSettings(): void
    {
        $this->em()->persist((new TaskSettings())->setUser($this->user())->setQuietStart('09:00')->setQuietEnd('11:00'));
        $this->em()->flush();

        self::assertSame(0, $this->service()->run($this->user()));
    }

    public function testTodaysLunchIsOverInTheAfternoon(): void
    {
        foreach ($this->em()->getRepository(GroceryItem::class)->findAll() as $item) {
            $this->em()->remove($item);
        }
        $this->em()->flush();

        $lunchToday = fn (): array => array_filter(
            $this->service()->missing($this->user()),
            static fn (array $m): bool => 'lunch' === $m['slot'] && '2026-10-14' === $m['date'],
        );
        self::assertCount(1, $lunchToday(), 'at 10:00, lunch is still ahead');

        $this->at('2026-10-14 13:00:00 UTC');
        self::assertCount(0, $lunchToday(), 'at 15:00 it is over');
    }

    public function testBuyingTheProductSettlesItsAlertsAndDeletesTheNotifications(): void
    {
        $this->service()->run($this->user());
        $this->em()->clear();
        $parmesan = $this->em()->getRepository(Product::class)->findOneBy(['name' => 'Parmesan']);
        $parmesan->setStockState(ProductStockState::InStock);
        $this->em()->flush();
        $notificationId = (string) $this->notificationFor('Pour Gratin, ce soir.')->getId();
        $this->reset();

        $sent = $this->service()->run($this->user());
        $this->em()->clear();

        self::assertSame(0, $sent);
        self::assertSame(['Il manque : Crème fraîche · Pour Tarte, demain midi.'], $this->notificationTexts());
        $this->assertMercureDeletePublished('/api/notifications/'.$notificationId);

        $alerts = $this->em()->getRepository(ShortageAlert::class)->findBy([]);
        self::assertCount(3, $alerts, 'the rows stay: they are what keeps it from coming back');
        self::assertSame(2, \count(array_filter($alerts, static fn (ShortageAlert $a): bool => $a->isResolved())));
    }

    public function testAProductAddedToTheListByOtherMeansSettlesItToo(): void
    {
        $this->service()->run($this->user());
        $this->reset();

        $this->em()->clear();
        $list = $this->em()->getRepository(GroceryItem::class)->findOneBy([])->getGroceryList();
        $cream = $this->em()->getRepository(Product::class)->findOneBy(['name' => 'Crème fraîche']);
        $list->addItem((new GroceryItem())->setProduct($cream)->setSource(GroceryItemSource::Manual)->setPosition(9));
        $this->em()->flush();

        $this->service()->run($this->user());
        $this->em()->clear();

        self::assertNotContains('Il manque : Crème fraîche · Pour Tarte, demain midi.', $this->notificationTexts());
        self::assertCount(2, $this->em()->getRepository(Notification::class)->findAll());
    }

    public function testAMealMovedOutOfTheWindowSettlesItsAlert(): void
    {
        $this->service()->run($this->user());
        $this->em()->clear();
        $meal = $this->em()->getRepository(Meal::class)->findOneBy(['summary' => 'Déjeuner : Tarte']);
        $meal->setDate(new \DateTimeImmutable('2026-10-20'));
        $this->em()->flush();
        $this->reset();

        $this->service()->run($this->user());
        $this->em()->clear();

        self::assertNotContains('Il manque : Crème fraîche · Pour Tarte, demain midi.', $this->notificationTexts());
    }

    public function testAddToTheListPutsTheProductOnItAndSettlesTheAlert(): void
    {
        $this->service()->run($this->user());
        $this->em()->clear();
        $alert = $this->alertFor('Parmesan', 'dinner', '2026-10-14');
        $notificationId = (string) $this->em()->getRepository(Notification::class)->findOneBy(['relatedEntityIri' => '/api/shortage-alerts/'.$alert->getId()])->getId();
        $this->reset();

        $answer = $this->service()->act($alert, 'add');
        $this->em()->clear();

        self::assertSame(['id' => (string) $alert->getId(), 'productId' => (string) $alert->getProduct()->getId(), 'name' => 'Parmesan', 'mealId' => (string) $alert->getMeal()->getId(), 'resolved' => true], $answer['alert']);
        self::assertNull($answer['notification']);

        $labels = array_map(static fn (GroceryItem $i): string => $i->getLabel(), $this->em()->getRepository(GroceryItem::class)->findAll());
        self::assertContains('Parmesan', $labels);
        self::assertSame(1, \count(array_filter($labels, static fn (string $l): bool => 'Parmesan' === $l)));

        $this->assertMercureDeletePublished('/api/notifications/'.$notificationId);
        $this->assertMercureUpdatePublished('/api/grocery_lists/');

        // The other dinner's alert for the same product finds it on the list: settled at the next check.
        $this->reset();
        $this->service()->run($this->user());
        $this->em()->clear();
        self::assertSame(['Il manque : Crème fraîche · Pour Tarte, demain midi.'], $this->notificationTexts());
    }

    public function testAnAnsweredAlertIsRefused(): void
    {
        $this->service()->run($this->user());
        $this->em()->clear();
        $alert = $this->alertFor('Parmesan', 'dinner', '2026-10-14');
        $this->service()->act($alert, 'add');

        $this->expectException(\DomainException::class);
        $this->service()->act($alert, 'add');
    }

    public function testAnUnknownActionIsRefused(): void
    {
        $this->service()->run($this->user());
        $this->em()->clear();

        $this->expectException(\InvalidArgumentException::class);
        $this->service()->act($this->alertFor('Parmesan', 'dinner', '2026-10-14'), 'later');
    }

    public function testDeletingAMealTakesItsAlertsAndNotificationsWithIt(): void
    {
        $this->service()->run($this->user());
        $this->em()->clear();
        $notificationId = (string) $this->notificationFor('Pour Gratin, ce soir.')->getId();
        $this->reset();
        $meal = $this->em()->getRepository(Meal::class)->findOneBy(['summary' => 'Dîner : Gratin', 'date' => new \DateTimeImmutable('2026-10-14')]);

        self::getContainer()->get(DeleteMeal::class)->execute($meal);
        $this->em()->clear();

        self::assertCount(2, $this->em()->getRepository(ShortageAlert::class)->findAll());
        self::assertNotContains('Il manque : Parmesan · Pour Gratin, ce soir.', $this->notificationTexts());
        $this->assertMercureDeletePublished('/api/notifications/'.$notificationId);
    }

    public function testAnotherUsersMealsAreNotMine(): void
    {
        $other = $this->em()->getRepository(User::class)->findOneBy(['email' => 'shortage-other@example.com']);

        self::assertSame([], $this->service()->missing($other));
        self::assertSame(0, $this->service()->run($other));
    }

    private function service(): ShortageAlerts
    {
        return self::getContainer()->get(ShortageAlerts::class);
    }

    private function user(): User
    {
        return $this->em()->getRepository(User::class)->findOneBy(['email' => 'shortage@example.com']);
    }

    private function alertFor(string $product, string $slot, string $date): ShortageAlert
    {
        foreach ($this->em()->getRepository(ShortageAlert::class)->findAll() as $alert) {
            if ($alert->getProduct()->getName() === $product && $alert->getMeal()->getSlot()->value === $slot && $alert->getMeal()->getDate()?->format('Y-m-d') === $date) {
                return $alert;
            }
        }

        throw new \LogicException('No such alert.');
    }

    /** @return list<string> « title · body », sorted */
    private function notificationTexts(): array
    {
        $texts = array_map(
            static fn (Notification $n): string => $n->getTitle().' · '.$n->getBody(),
            $this->em()->getRepository(Notification::class)->findAll(),
        );
        sort($texts);

        return $texts;
    }

    private function notificationFor(string $body): Notification
    {
        $notification = $this->em()->getRepository(Notification::class)->findOneBy(['body' => $body]);
        self::assertInstanceOf(Notification::class, $notification);

        return $notification;
    }

    private function at(string $instant): void
    {
        $this->clock = new MockClock($instant);
        Clock::set($this->clock);
    }

    private function reset(): void
    {
        $this->em()->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function pushesRequested(): int
    {
        return \count(array_filter(
            $this->getAsyncTransport()->getSent(),
            static fn ($envelope): bool => $envelope->getMessage() instanceof SendPushNotificationCommand,
        ));
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
