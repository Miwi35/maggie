<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\EventSubscriber;

use Maggie\Health\Event\PlannedWorkoutMissed;
use Maggie\Health\EventSubscriber\ProposeWorkoutMakeupOnMissed;
use Maggie\Health\Message\ProposeWorkoutMakeupCommand;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

final class ProposeWorkoutMakeupOnMissedTest extends TestCase
{
    public function testTheFactBecomesItsCommandForTheSameWorkouts(): void
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

        (new ProposeWorkoutMakeupOnMissed($bus))(new PlannedWorkoutMissed('01JPLANNED'));

        self::assertEquals([new ProposeWorkoutMakeupCommand('01JPLANNED')], $bus->sent);
    }
}
