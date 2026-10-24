<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Tests\EventSubscriber;

use Maggie\Cookbook\Event\MealRemoved;
use Maggie\MealGrocery\Doctrine\MealRemovalLedger;
use Maggie\MealGrocery\EventSubscriber\RevokeGroceriesOnMealRemoved;
use Maggie\MealGrocery\Message\RevokeMealGroceriesCommand;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class RevokeGroceriesOnMealRemovedTest extends TestCase
{
    public function testAMealThatGaveNothingRevokesNothing(): void
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

        (new RevokeGroceriesOnMealRemoved($bus, new MealRemovalLedger()))(new MealRemoved('01J0MEAL', '01J0USER'));

        self::assertEquals([new RevokeMealGroceriesCommand('01J0MEAL', '01J0USER', [])], $bus->sent);
    }
}
