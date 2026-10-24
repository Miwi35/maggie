<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\ShortageAlert;
use Maggie\Cookbook\Service\ShortageAlerts;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Notification\Entity\Notification;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * « Ajouter à la liste » (MAG-570): the button of an « Il manque » notification.
 *
 * The world is the one of {@see \Maggie\Cookbook\Tests\Service\ShortageAlertsTest}:
 * the parmesan of tonight's gratin is neither in stock nor on the list.
 */
final class ShortageAlertApiTest extends WebTestCase
{
    use AuthenticatedTestTrait;
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        Clock::set(new MockClock('2026-10-14 08:00:00 UTC'));
        $this->loadFixtures(\dirname(__DIR__).'/Service/fixtures/ShortageAlertsTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->em()->clear();
        $user = $this->em()->find(User::class, $this->getFixture('test_user')->getId());
        \assert($user instanceof User);
        self::assertSame(3, self::getContainer()->get(ShortageAlerts::class)->run($user));
        $this->em()->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testAddingPutsTheProductOnTheListAndTheNotificationGoes(): void
    {
        $alert = $this->tonightsParmesan();
        $notificationId = (string) $this->notificationOf($alert)->getId();

        $body = $this->act($alert, ['action' => 'add']);

        self::assertResponseIsSuccessful();
        self::assertTrue($body['alert']['resolved']);
        self::assertSame('Parmesan', $body['alert']['name']);
        self::assertNull($body['notification']);

        $this->em()->clear();
        $labels = array_map(static fn (GroceryItem $i): string => $i->getLabel(), $this->em()->getRepository(GroceryItem::class)->findAll());
        self::assertSame(1, \count(array_filter($labels, static fn (string $l): bool => 'Parmesan' === $l)));
        self::assertNull($this->em()->find(Notification::class, $notificationId));
        self::assertNotNull($this->em()->find(ShortageAlert::class, $alert->getId())?->getResolvedAt());

        $this->assertMercureDeletePublished('/api/notifications/'.$notificationId);
        $this->assertMercureUpdatePublished('/api/grocery_lists/');
        $this->assertElasticsearchDeleteDispatched();
    }

    public function testAnAnsweredAlertIsRefused(): void
    {
        $alert = $this->tonightsParmesan();
        $this->act($alert, ['action' => 'add']);
        self::assertResponseIsSuccessful();

        $this->act($alert, ['action' => 'add']);

        self::assertResponseStatusCodeSame(409);
    }

    public function testAnUnknownOrMissingActionIsRefused(): void
    {
        $alert = $this->tonightsParmesan();

        $this->act($alert, ['action' => 'later']);
        self::assertResponseStatusCodeSame(400);
        $this->act($alert, []);
        self::assertResponseStatusCodeSame(400);

        $this->em()->clear();
        self::assertNull($this->em()->find(ShortageAlert::class, $alert->getId())?->getResolvedAt());
        self::assertCount(3, $this->em()->getRepository(Notification::class)->findAll());
    }

    public function testAnotherUsersAlertIsNotFound(): void
    {
        $alert = $this->tonightsParmesan();
        $other = $this->em()->getRepository(User::class)->findOneBy(['email' => 'shortage-other@example.com']);
        \assert($other instanceof User);
        $this->authenticateAsUser($other);

        $this->act($alert, ['action' => 'add']);
        self::assertResponseStatusCodeSame(404);
        $this->client->request('POST', '/api/shortage-alerts/not-an-id/action', [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], '{"action":"add"}');
        self::assertResponseStatusCodeSame(404);

        $this->em()->clear();
        self::assertNull($this->em()->find(ShortageAlert::class, $alert->getId())?->getResolvedAt());
    }

    public function testAnActionNeedsAUser(): void
    {
        $alert = $this->tonightsParmesan();

        $this->client->request('POST', '/api/shortage-alerts/'.$alert->getId().'/action', [], [], ['CONTENT_TYPE' => 'application/json'], '{"action":"add"}');

        self::assertResponseStatusCodeSame(401);
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array<string, mixed>
     */
    private function act(ShortageAlert $alert, array $body): array
    {
        $this->client->request('POST', '/api/shortage-alerts/'.$alert->getId().'/action', [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json', 'HTTP_ACCEPT' => 'application/json'], json_encode($body, JSON_THROW_ON_ERROR));

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR) ?? [];
    }

    private function tonightsParmesan(): ShortageAlert
    {
        foreach ($this->em()->getRepository(ShortageAlert::class)->findAll() as $alert) {
            if ('Parmesan' === $alert->getProduct()->getName() && '2026-10-14' === $alert->getMeal()->getDate()->format('Y-m-d')) {
                return $alert;
            }
        }

        throw new \LogicException('No alert for tonight\'s parmesan.');
    }

    private function notificationOf(ShortageAlert $alert): Notification
    {
        $notification = $this->em()->getRepository(Notification::class)->findOneBy(['relatedEntityIri' => '/api/shortage-alerts/'.$alert->getId()]);
        self::assertInstanceOf(Notification::class, $notification);

        return $notification;
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
