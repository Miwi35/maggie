<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Message\UpdateCategorizationRuleCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<CategorizationRule, CategorizationRule> */
class UpdateCategorizationRuleProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): CategorizationRule
    {
        // A nullable field that is null after the merge-patch is an explicit clear
        $clearFields = [];
        foreach ([
            'minAmountCents' => $data->getMinAmountCents(),
            'maxAmountCents' => $data->getMaxAmountCents(),
        ] as $field => $value) {
            if ($value === null) {
                $clearFields[] = $field;
            }
        }

        $stamped = $this->bus->dispatch(new UpdateCategorizationRuleCommand(
            categorizationRuleId: (string) $data->getId(),
            labelPattern: $data->getLabelPattern(),
            categoryId: (string) $data->getCategory()->getId(),
            matchType: $data->getMatchType()->value,
            direction: $data->getDirection()->value,
            minAmountCents: $data->getMinAmountCents(),
            maxAmountCents: $data->getMaxAmountCents(),
            priority: $data->getPriority(),
            isActive: $data->isActive(),
            clearFields: $clearFields,
        ));

        return $stamped->last(HandledStamp::class)->getResult();
    }
}
