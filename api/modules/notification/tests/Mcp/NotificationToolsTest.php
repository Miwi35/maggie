<?php

namespace Maggie\Notification\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Mcp\Tool\ManageNotificationsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class NotificationToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function tool(): ManageNotificationsTool
    {
        return self::getContainer()->get(ManageNotificationsTool::class);
    }

    public function testListWithoutUserIsRefused(): void
    {
        $this->loadFixtures('notification.yaml');

        $data = json_decode(($this->tool())('list'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('No user bound', $data['error']);
    }

    public function testUnknownActionIsRejected(): void
    {
        $this->loadFixtures('notification.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('archive'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('Unknown action', $data['error']);
    }

    public function testMarkReadRequiresANotificationId(): void
    {
        $this->loadFixtures('notification.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('mark_read'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testListReturnsOnlyTheCurrentUserNotifications(): void
    {
        $this->loadFixtures('notification.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('list'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(2, $data['count']);
        $titles = array_column($data['notifications'], 'title');
        self::assertContains('Rendez-vous dentiste', $titles);
        self::assertNotContains('Pas pour nous', $titles);
    }

    public function testListUnreadOnly(): void
    {
        $this->loadFixtures('notification.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('list', unreadOnly: true), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(1, $data['count']);
        self::assertSame('Rendez-vous dentiste', $data['notifications'][0]['title']);
        self::assertNull($data['notifications'][0]['readAt']);
    }

    public function testMarkReadPersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('notification.yaml');
        $this->loginFixtureUser();

        /** @var Notification $notification */
        $notification = $this->getFixture('unread_reminder');

        $data = json_decode(($this->tool())('mark_read', notificationId: (string) $notification->getId()), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->getRepository(Notification::class)->find($notification->getId());
        self::assertNotNull($stored->getReadAt());

        $this->assertMercureUpdatePublished('/notifications/');
        $this->assertElasticsearchIndexDispatched(Notification::class);
    }

    public function testDeleteRemovesAndPublishes(): void
    {
        $this->loadFixtures('notification.yaml');
        $this->loginFixtureUser();

        /** @var Notification $notification */
        $notification = $this->getFixture('read_reminder');

        $data = json_decode(($this->tool())('delete', notificationId: (string) $notification->getId()), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->getRepository(Notification::class)->find($notification->getId()));

        $this->assertMercureUpdatePublished('/notifications/');
    }
}
