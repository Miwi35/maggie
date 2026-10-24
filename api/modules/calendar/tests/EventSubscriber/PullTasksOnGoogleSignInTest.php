<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\EventSubscriber;

use Maggie\Calendar\EventSubscriber\PullTasksOnGoogleSignIn;
use Maggie\Calendar\Message\PullTasksFromGoogleCommand;
use Maggie\Core\Event\UserSignedInWithGoogle;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class PullTasksOnGoogleSignInTest extends TestCase
{
    public function testASignInBecomesAPullOfTheOwnersTasks(): void
    {
        $bus = new class implements MessageBusInterface {
            /** @var list<object> */
            public array $sent = [];

            public function dispatch(object $message, array $stamps = []): Envelope
            {
                $this->sent[] = $message;

                return new Envelope($message);
            }
        };

        (new PullTasksOnGoogleSignIn($bus))(new UserSignedInWithGoogle('01J0USER'));

        self::assertEquals([new PullTasksFromGoogleCommand(userId: '01J0USER')], $bus->sent);
    }
}
