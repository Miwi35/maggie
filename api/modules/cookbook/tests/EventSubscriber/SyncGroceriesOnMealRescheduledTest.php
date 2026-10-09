<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\EventSubscriber;

use Maggie\Cookbook\Event\MealRescheduled;
use Maggie\Cookbook\EventSubscriber\SyncGroceriesOnMealRescheduled;
use Maggie\Cookbook\Message\SyncMealGroceriesCommand;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class SyncGroceriesOnMealRescheduledTest extends TestCase
{
    public function testTheFactBecomesASyncCommandForTheSameMeal(): void
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

        (new SyncGroceriesOnMealRescheduled($bus))(new MealRescheduled('01J0MEAL'));

        self::assertEquals([new SyncMealGroceriesCommand('01J0MEAL')], $bus->sent);
    }
}
