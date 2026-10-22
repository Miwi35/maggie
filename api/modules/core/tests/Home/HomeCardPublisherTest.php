<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Home;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Home\HomeCardPublisher;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class HomeCardPublisherTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ElasticsearchAssertionTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('HomeControllerTest.yaml');
    }

    public function testPublishesAPrivateRefreshOnTheUsersHomeTopic(): void
    {
        $user = $this->getFixture('test_user');

        (new HomeCardPublisher($this->getMercureHub()))->publish('tasks', $user);

        $topic = '/users/'.$user->getId().'/home/tasks';
        $this->assertMercureUpdatePublished($topic);
        $this->assertMercureUpdateCount(1);
        $update = $this->getMercureHub()->getUpdates()[0];
        self::assertSame([$topic], $update->getTopics());
        self::assertTrue($update->isPrivate());
        self::assertSame(['action' => 'refresh', 'module' => 'tasks'], json_decode($update->getData(), true));
    }
}
