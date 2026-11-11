<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\EventSubscriber;

use Maggie\Health\Event\PlannedWorkoutKept;
use Maggie\Health\EventSubscriber\KeepPlannedWorkoutOnKept;
use Maggie\Health\Message\KeepPlannedWorkoutCommand;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

final class KeepPlannedWorkoutOnKeptTest extends TestCase
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

        (new KeepPlannedWorkoutOnKept($bus))(new PlannedWorkoutKept('01JDONE', '01JPLANNED'));

        self::assertEquals([new KeepPlannedWorkoutCommand('01JDONE', '01JPLANNED')], $bus->sent);
    }
}
