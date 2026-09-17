<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Message\DeleteCategorizationRuleCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<CategorizationRule, void> */
class DeleteCategorizationRuleProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteCategorizationRuleCommand(
            categorizationRuleId: (string) $data->getId(),
        ));
    }
}
