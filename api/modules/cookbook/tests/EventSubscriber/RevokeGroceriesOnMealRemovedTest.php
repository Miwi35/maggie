<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\EventSubscriber;

use Maggie\Cookbook\Event\MealRemoved;
use Maggie\Cookbook\EventSubscriber\RevokeGroceriesOnMealRemoved;
use Maggie\Cookbook\Message\RevokeMealGroceriesCommand;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class RevokeGroceriesOnMealRemovedTest extends TestCase
{
    public function testTheFactBecomesARevokeCommandCarryingWhatTheMealHadPutOnTheList(): void
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
        $contributions = [['groceryItemId' => '01J0ITEM', 'quantity' => 400.0]];

        (new RevokeGroceriesOnMealRemoved($bus))(new MealRemoved('01J0MEAL', '01J0USER', $contributions));

        self::assertEquals([new RevokeMealGroceriesCommand('01J0MEAL', '01J0USER', $contributions)], $bus->sent);
    }
}
