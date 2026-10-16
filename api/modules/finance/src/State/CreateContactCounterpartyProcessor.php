<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\ContactCounterparty;
use Maggie\Finance\Message\LinkContactCounterpartyCommand;
use Maggie\Finance\UseCase\MeasureCounterpartyUsage;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Links a label to a contact; a label already linked to someone else is a 400.
 *
 * @implements ProcessorInterface<ContactCounterparty, ContactCounterparty>
 */
class CreateContactCounterpartyProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
        private readonly MeasureCounterpartyUsage $usage,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): ContactCounterparty
    {
        /** @var User $user */
        $user = $this->security->getUser();

        try {
            $stamped = $this->bus->dispatch(new LinkContactCounterpartyCommand(
                userId: (string) $user->getId(),
                contactId: (string) $data->getContact()->getId(),
                label: $data->getLabel(),
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof \DomainException) {
                throw new BadRequestHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }

        /** @var ContactCounterparty $counterparty */
        $counterparty = $stamped->last(HandledStamp::class)->getResult();
        $this->usage->fill([$counterparty]);

        return $counterparty;
    }
}
