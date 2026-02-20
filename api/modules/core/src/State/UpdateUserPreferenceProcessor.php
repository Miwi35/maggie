<?php

namespace Maggie\Core\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\UserPreference;
use Maggie\Core\Message\UpdateUserPreferenceCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<UserPreference, UserPreference> */
class UpdateUserPreferenceProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): UserPreference
    {
        $envelope = $this->bus->dispatch(new UpdateUserPreferenceCommand(
            userPreferenceId: (string) $data->getId(),
            theme: $data->getTheme(),
            locale: $data->getLocale(),
            timezone: $data->getTimezone(),
            defaultCalendarView: $data->getDefaultCalendarView(),
            enabledAgendaIds: $data->getEnabledAgendaIds(),
            notificationsEnabled: $data->isNotificationsEnabled(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
