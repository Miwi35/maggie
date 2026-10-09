<?php

namespace Maggie\Grocery\Tests\EventSubscriber;

use Maggie\Grocery\Event\ProductOutOfStockEvent;
use Maggie\Grocery\EventSubscriber\RestockOnProductOutOfStock;
use Maggie\Grocery\Message\RestockProductCommand;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class RestockOnProductOutOfStockTest extends TestCase
{
    public function testTheFactBecomesARestockCommandForTheSameProduct(): void
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

        (new RestockOnProductOutOfStock($bus))(new ProductOutOfStockEvent('01J0PRODUCT'));

        self::assertEquals([new RestockProductCommand('01J0PRODUCT')], $bus->sent);
    }
}
