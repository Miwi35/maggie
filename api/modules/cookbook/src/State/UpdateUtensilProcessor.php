<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\Exception\UtensilNameTakenException;
use Maggie\Cookbook\Message\RenameUtensilCommand;
use Symfony\Component\HttpKernel\Exception\ConflictHttpException;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Utensil, Utensil> */
class UpdateUtensilProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Utensil
    {
        try {
            $envelope = $this->bus->dispatch(new RenameUtensilCommand(
                utensilId: (string) $data->getId(),
                name: $data->getName(),
            ));
        } catch (HandlerFailedException $e) {
            if ($e->getPrevious() instanceof UtensilNameTakenException) {
                throw new ConflictHttpException($e->getPrevious()->getMessage(), $e);
            }

            throw $e;
        }

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
