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
            if (null === $value) {
                $clearFields[] = $field;
            }
        }

        // Only a change of the qualification is sent: re-sending the stored
        // one with a new category would keep it from following the category.
        $previous = $context['previous_data'] ?? null;
        $qualification = $data->getQualification()->toArray();
        $qualificationChanged = $previous instanceof CategorizationRule && $qualification !== $previous->getQualification()->toArray();

        $stamped = $this->bus->dispatch(new UpdateCategorizationRuleCommand(
            userId: (string) $data->getUser()->getId(),
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
            applyToExisting: $data->shouldApplyToExisting(),
            qualification: $qualificationChanged ? $qualification : null,
        ));

        return $stamped->last(HandledStamp::class)->getResult();
    }
}
