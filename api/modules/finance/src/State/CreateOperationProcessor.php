<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Message\CreateOperationCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * One door for the three views. `/api/categorization_rules` knew no
 * recurrence: whatever its body says of one is not read.
 *
 * @implements ProcessorInterface<RecurringOperation, RecurringOperation>
 */
class CreateOperationProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): RecurringOperation
    {
        /** @var User $user */
        $user = $this->security->getUser();
        $recurrence = RecurringOperation::VIEW_RULE !== ($operation->getExtraProperties()[RecurringOperation::VIEW] ?? null);

        return $this->dispatch(new CreateOperationCommand(
            userId: (string) $user->getId(),
            categoryId: (string) $data->getCategory()->getId(),
            label: $data->getLabel(),
            counterpartyName: $data->getCounterpartyName(),
            labelPattern: $data->getLabelPattern(),
            matchType: $data->getMatchType()->value,
            direction: $data->getDirection()->value,
            minAmountCents: $data->getMinAmountCents(),
            maxAmountCents: $data->getMaxAmountCents(),
            priority: $data->getPriority(),
            isActive: $data->isActive(),
            period: $recurrence ? $data->getPeriod()?->value : null,
            anchorOn: $recurrence ? $data->getAnchorOn()?->format('Y-m-d') : null,
            referenceAmountCents: $recurrence ? $data->getReferenceAmountCents() : null,
            accountId: $recurrence && null !== $data->getAccount() ? (string) $data->getAccount()->getId() : null,
            dayRule: $data->getDayRule()->value,
            referenceSource: $data->getReferenceSource()->value,
            amountTolerancePercent: $data->getAmountTolerancePercent(),
            dateToleranceDays: $data->getDateToleranceDays(),
            endsOn: $recurrence ? $data->getEndsOn()?->format('Y-m-d') : null,
            applyToExisting: $data->shouldApplyToExisting(),
            // Left at its default, the body named none: the category's applies.
            qualification: $data->getQualification()->toArray() === Qualification::none()->toArray() ? null : $data->getQualification()->toArray(),
        ))->last(HandledStamp::class)->getResult();
    }

    /** What the handler refuses — a reference not found, a recurrence changed — is the request's fault. */
    private function dispatch(object $command): Envelope
    {
        try {
            return $this->bus->dispatch($command);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof \DomainException) {
                throw new BadRequestHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }
    }
}
