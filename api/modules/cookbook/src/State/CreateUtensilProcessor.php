<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\Message\CreateUtensilCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * A name already in the catalogue, whatever its case, answers the utensil
 * that has it: the catalogue never holds the same utensil twice.
 *
 * @implements ProcessorInterface<Utensil, Utensil>
 */
class CreateUtensilProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Utensil
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $envelope = $this->bus->dispatch(new CreateUtensilCommand(
            userId: (string) $user->getId(),
            name: $data->getName(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
