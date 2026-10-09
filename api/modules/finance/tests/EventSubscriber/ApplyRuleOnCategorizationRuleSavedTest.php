<?php

namespace Maggie\Finance\Tests\EventSubscriber;

use Maggie\Finance\Event\CategorizationRuleSaved;
use Maggie\Finance\EventSubscriber\ApplyRuleOnCategorizationRuleSaved;
use Maggie\Finance\Message\ApplyCategorizationRuleCommand;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class ApplyRuleOnCategorizationRuleSavedTest extends TestCase
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

    public function testARuleSavedToBeAppliedBecomesAnApplyCommandForTheSameRule(): void
    {
        $bus = $this->recordingBus();

        (new ApplyRuleOnCategorizationRuleSaved($bus))(new CategorizationRuleSaved('01J0RULE', true));

        self::assertEquals([new ApplyCategorizationRuleCommand('01J0RULE')], $bus->sent);
    }

    public function testARuleSavedWithoutTheTickLeavesTheHistoryAlone(): void
    {
        $bus = $this->recordingBus();

        (new ApplyRuleOnCategorizationRuleSaved($bus))(new CategorizationRuleSaved('01J0RULE', false));

        self::assertSame([], $bus->sent);
    }
}
