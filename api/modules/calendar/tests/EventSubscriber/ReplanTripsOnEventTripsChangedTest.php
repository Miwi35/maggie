<?php

namespace Maggie\Calendar\Tests\EventSubscriber;

use Maggie\Calendar\Event\EventTripsChangedEvent;
use Maggie\Calendar\EventSubscriber\ReplanTripsOnEventTripsChanged;
use Maggie\Calendar\Message\ReplanTripsCommand;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class ReplanTripsOnEventTripsChangedTest extends TestCase
{
    /** @return iterable<string, array{string, string}> */
    public static function changes(): iterable
    {
        yield 'created' => [EventTripsChangedEvent::CREATED, ReplanTripsCommand::EVENT_CREATED];
        yield 'updated' => [EventTripsChangedEvent::UPDATED, ReplanTripsCommand::EVENT_UPDATED];
        yield 'deleted' => [EventTripsChangedEvent::DELETED, ReplanTripsCommand::EVENT_DELETED];
    }

    #[DataProvider('changes')]
    public function testTheFactBecomesAReplanCommandForTheSameUserWithItsReason(string $change, string $reason): void
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

        (new ReplanTripsOnEventTripsChanged($bus))(new EventTripsChangedEvent('01J0EVENT', '01J0USER', $change));

        self::assertEquals([new ReplanTripsCommand('01J0USER', $reason)], $bus->sent);
    }
}
