<?php

declare(strict_types=1);

namespace Maggie\Grocery\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Message\UpdateAisleCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * A rename and a move both go through here: the admin drops an aisle at its
 * new place with one PATCH of its `position`, the others follow.
 *
 * @implements ProcessorInterface<Aisle, Aisle>
 */
class UpdateAisleProcessor implements ProcessorInterface
{
    use DispatchesAisleCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Aisle
    {
        /** @var User $user */
        $user = $this->security->getUser();

        /** @var Aisle|null $previous */
        $previous = $context['previous_data'] ?? null;

        if (null !== $previous && $previous->getStore() !== $data->getStore()) {
            throw new BadRequestHttpException('An aisle stays in its store: create one in the other store instead.');
        }

        $envelope = $this->dispatchAisleCommand(new UpdateAisleCommand(
            userId: (string) $user->getId(),
            aisleId: (string) $data->getId(),
            name: null === $previous || $previous->getName() !== $data->getName() ? $data->getName() : null,
            position: null === $previous || $previous->getPosition() !== $data->getPosition() ? $data->getPosition() : null,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
