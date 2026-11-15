<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Period;
use Maggie\Calendar\Message\CreatePeriodCommand;
use Maggie\Calendar\Service\PeriodRules;
use Maggie\Calendar\UseCase\SavePeriod;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreatePeriodHandler
{
    public function __construct(
        private readonly SavePeriod $savePeriod,
        private readonly PeriodRules $rules,
    ) {
    }

    public function __invoke(CreatePeriodCommand $command): Period
    {
        $period = new Period();
        $period->setName($command->name);
        $period->setAgenda($this->rules->agendaOf($command->userId, $command->agendaId));
        $period->setTimeZone($command->timeZone);
        $period->setRrule($command->rrule);
        if ($command->allDay) {
            if (null === $command->startDate) {
                throw new \DomainException('An all-day period needs a startDate.');
            }
            $period->scheduleAllDay($command->startDate, $command->endDate);
        } else {
            if (null === $command->startAt || null === $command->endAt) {
                throw new \DomainException('A timed period needs a startAt and an endAt.');
            }
            $period->scheduleTimed($command->startAt, $command->endAt);
        }

        return $this->savePeriod->execute($period);
    }
}
