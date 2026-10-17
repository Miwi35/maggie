<?php

namespace Maggie\Finance\Tests\EventSubscriber;

use Maggie\Finance\Event\OperationSaved;
use Maggie\Finance\EventSubscriber\ApplyOperationOnOperationSaved;
use Maggie\Finance\Message\ApplyOperationCommand;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class ApplyOperationOnOperationSavedTest extends TestCase
{
    private function recordingBus(): MessageBusInterface
    {
        return new class implements MessageBusInterface {
            /** @var list<object> */
            public array $sent = [];

            public function dispatch(object $message, array $stamps = []): Envelope
            {
                $this->sent[] = $message;

                return new Envelope($message);
            }
        };
    }

    public function testAnOperationSavedToBeAppliedBecomesAnApplyCommandForTheSameOperation(): void
    {
        $bus = $this->recordingBus();

        (new ApplyOperationOnOperationSaved($bus))(new OperationSaved('01J0RULE', true));

        self::assertEquals([new ApplyOperationCommand('01J0RULE')], $bus->sent);
    }

    public function testAnOperationSavedWithoutTheTickLeavesTheHistoryAlone(): void
    {
        $bus = $this->recordingBus();

        (new ApplyOperationOnOperationSaved($bus))(new OperationSaved('01J0RULE', false));

        self::assertSame([], $bus->sent);
    }
}
