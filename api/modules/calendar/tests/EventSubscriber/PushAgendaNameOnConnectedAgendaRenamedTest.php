<?php

namespace Maggie\Calendar\Tests\EventSubscriber;

use Maggie\Calendar\Event\ConnectedAgendaRenamed;
use Maggie\Calendar\EventSubscriber\PushAgendaNameOnConnectedAgendaRenamed;
use Maggie\Calendar\Message\PushAgendaNameToGoogleCommand;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class PushAgendaNameOnConnectedAgendaRenamedTest extends TestCase
{
    public function testTheFactBecomesAPushCommandForTheSameAgenda(): void
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

        (new PushAgendaNameOnConnectedAgendaRenamed($bus))(new ConnectedAgendaRenamed('01J0AGENDA', 'Garde Ael'));

        self::assertEquals([new PushAgendaNameToGoogleCommand('01J0AGENDA', 'Garde Ael')], $bus->sent);
    }
}
