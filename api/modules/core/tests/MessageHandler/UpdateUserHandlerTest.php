<?php

namespace Maggie\Core\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Entity\User;
use Maggie\Core\Message\UpdateUserCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateUserHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('UpdateUserHandlerTest.yaml');
        $this->loginFixtureUser();
    }

    private function dispatch(UpdateUserCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function reload(): User
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(User::class)->find($this->getFixture('test_user')->getId());
    }

    public function testClearingTheAvatarEmptiesItAndLeavesTheNameUntouched(): void
    {
        $this->dispatch(new UpdateUserCommand(
            userId: (string) $this->getFixture('test_user')->getId(),
            clearFields: ['avatar'],
        ));

        $user = $this->reload();
        self::assertNull($user->getAvatar());
        self::assertSame('Update User Test', $user->getName());
        $this->assertMercureUpdatePublished('/api/users/');
        $this->assertElasticsearchIndexDispatched(User::class);
    }

    public function testNullFieldsWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateUserCommand(
            userId: (string) $this->getFixture('test_user')->getId(),
            name: 'Renamed',
        ));

        $user = $this->reload();
        self::assertSame('Renamed', $user->getName());
        self::assertSame('https://example.com/avatar.png', $user->getAvatar());
    }

    public function testUnknownUserFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('User not found');

        $this->dispatch(new UpdateUserCommand(userId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clearFields: ['avatar']));
    }
}
