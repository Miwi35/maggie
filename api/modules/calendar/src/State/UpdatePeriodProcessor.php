<?php

declare(strict_types=1);

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Period;
use Maggie\Calendar\Message\UpdatePeriodCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The merge-patch has already been applied to $data: it is the whole state
 * wanted, so every field is sent, and a nullable one that is null is cleared.
 *
 * @implements ProcessorInterface<Period, Period>
 */
class UpdatePeriodProcessor implements ProcessorInterface
{
    use DispatchesPeriodCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Period
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $clearFields = [];
        if (null === $data->getRrule()) {
            $clearFields[] = 'rrule';
        }

        return $this->dispatchPeriodCommand($this->bus, new UpdatePeriodCommand(
            userId: (string) $user->getId(),
            periodId: (string) $data->getId(),
            name: $data->getName(),
            agendaId: (string) $data->getAgenda()->getId(),
            allDay: $data->isAllDay(),
            startAt: $data->getStartAt(),
            endAt: $data->getEndAt(),
            startDate: $data->getStartDate(),
            endDate: $data->getEndDate(),
            timeZone: $data->getTimeZone(),
            rrule: $data->getRrule(),
            clearFields: $clearFields,
        ))->last(HandledStamp::class)->getResult();
    }
}
