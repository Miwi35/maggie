<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\CreateProjectCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Project, Project> */
class CreateProjectProcessor implements ProcessorInterface
{
    use DispatchesProjectCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Project
    {
        /** @var User $user */
        $user = $this->security->getUser();

        return $this->dispatchProjectCommand($this->bus, new CreateProjectCommand(
            userId: (string) $user->getId(),
            name: $data->getName(),
            amountCents: $data->getAmountCents(),
            currency: $data->getCurrency(),
            wellbeing: $data->getWellbeing()->value,
            dateMode: $data->getDateMode()->value,
            targetOn: $data->getTargetOn()?->format('Y-m-d'),
            dependsOn: $data->dependencyIds(),
            note: $data->getNote(),
        ))->last(HandledStamp::class)->getResult();
    }
}
