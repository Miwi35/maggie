<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\UpdateProjectCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The merge-patch has already been applied to $data: it is the whole state
 * wanted, so every field is sent, and a null one is an explicit clear.
 *
 * @implements ProcessorInterface<Project, Project>
 */
class UpdateProjectProcessor implements ProcessorInterface
{
    use DispatchesProjectCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Project
    {
        $targetOn = $data->getTargetOn()?->format('Y-m-d');
        $dependsOn = $data->dependencyIds();

        return $this->dispatchProjectCommand($this->bus, new UpdateProjectCommand(
            userId: (string) $data->getUser()->getId(),
            projectId: (string) $data->getId(),
            name: $data->getName(),
            amountCents: $data->getAmountCents(),
            currency: $data->getCurrency(),
            wellbeing: $data->getWellbeing()->value,
            dateMode: $data->getDateMode()->value,
            targetOn: $targetOn,
            dependsOn: $dependsOn,
            note: $data->getNote(),
            clearFields: array_values(array_filter([
                null === $targetOn ? 'targetOn' : null,
                null === $data->getNote() ? 'note' : null,
            ])),
        ))->last(HandledStamp::class)->getResult();
    }
}
