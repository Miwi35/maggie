<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Period;
use Maggie\Calendar\Message\UpdatePeriodCommand;
use Maggie\Calendar\Repository\PeriodRepository;
use Maggie\Calendar\Service\PeriodRules;
use Maggie\Calendar\UseCase\SavePeriod;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/** Only the fields given change; everything is checked before the flush. */
#[AsMessageHandler]
class UpdatePeriodHandler
{
    public function __construct(
        private readonly SavePeriod $savePeriod,
        private readonly PeriodRepository $periodRepository,
        private readonly PeriodRules $rules,
    ) {
    }

    public function __invoke(UpdatePeriodCommand $command): Period
    {
        $period = $this->periodRepository->findOneOfUser($command->userId, $command->periodId)
            ?? throw new \DomainException("Period not found: {$command->periodId}");

        // Read before anything is set: a refusal leaves the period whole.
        $agenda = null === $command->agendaId ? null : $this->rules->agendaOf($command->userId, $command->agendaId);
        $holiday = null === $command->holiday ? null : $this->rules->holiday($command->holiday);

        if (null !== $command->name) {
            $period->setName($command->name);
        }
        if (null !== $agenda) {
            $period->setAgenda($agenda);
        }
        if (null !== $command->timeZone) {
            $period->setTimeZone($command->timeZone);
        }
        if (true === $command->allDay) {
            if (null === $command->startDate) {
                throw new \DomainException('An all-day period needs a startDate.');
            }
            $period->scheduleAllDay($command->startDate, $command->endDate);
        } elseif (false === $command->allDay) {
            if (null === $command->startAt || null === $command->endAt) {
                throw new \DomainException('A timed period needs a startAt and an endAt.');
            }
            $period->scheduleTimed($command->startAt, $command->endAt);
        }
        if (null !== $command->rrule) {
            $period->setRrule($command->rrule);
        } elseif ($command->clears('rrule')) {
            $period->setRrule(null);
        }
        if (null !== $holiday) {
            $period->setHoliday($holiday);
        } elseif ($command->clears('holiday')) {
            $period->setHoliday(null);
        }

        return $this->savePeriod->execute($period);
    }
}
