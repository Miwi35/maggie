<?php

declare(strict_types=1);

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Period;
use Maggie\Calendar\Message\CreatePeriodCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Period, Period> */
class CreatePeriodProcessor implements ProcessorInterface
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

        return $this->dispatchPeriodCommand($this->bus, new CreatePeriodCommand(
            userId: (string) $user->getId(),
            agendaId: (string) $data->getAgenda()->getId(),
            name: $data->getName(),
            allDay: $data->isAllDay(),
            startAt: $data->getStartAt(),
            endAt: $data->getEndAt(),
            startDate: $data->getStartDate(),
            endDate: $data->getEndDate(),
            timeZone: $data->getTimeZone(),
            rrule: $data->getRrule(),
            holiday: $data->getHoliday()?->value,
        ))->last(HandledStamp::class)->getResult();
    }
}
