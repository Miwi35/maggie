<?php

declare(strict_types=1);

namespace Maggie\Finance\EventSubscriber;

use Maggie\Finance\Event\CategorizationRuleSaved;
use Maggie\Finance\Message\ApplyCategorizationRuleCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** Turns the fact into a command when the rule was saved to be applied; the rule lives in the use case. */
#[AsMessageHandler(bus: 'event.bus')]
final class ApplyRuleOnCategorizationRuleSaved
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(CategorizationRuleSaved $event): void
    {
        if (!$event->applyToExisting) {
            return;
        }

        $this->commandBus->dispatch(new ApplyCategorizationRuleCommand($event->ruleId));
    }
}
