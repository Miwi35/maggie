<?php

namespace Maggie\Grocery\Tests\EventSubscriber;

use Maggie\Grocery\Event\RecurringGroceryItemDue;
use Maggie\Grocery\EventSubscriber\AddRecurringItemOnDue;
use Maggie\Grocery\Message\AddRecurringGroceryItemCommand;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class AddRecurringItemOnDueTest extends TestCase
{
    public function testTheFactBecomesAnAddCommandForTheSameItem(): void
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

        (new AddRecurringItemOnDue($bus))(new RecurringGroceryItemDue('01J0RECURRING'));

        self::assertEquals([new AddRecurringGroceryItemCommand('01J0RECURRING')], $bus->sent);
    }
}
