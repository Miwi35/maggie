<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\HealthDay;
use Maggie\Health\Enum\HealthDayAuthor;
use Maggie\Health\Repository\HealthDayRepository;
use Maggie\Health\Service\HealthDayCalculator;
use Psr\Clock\ClockInterface;

/**
 * Writes a health journal day's typed values (MAG-750), and who wrote them
 * when. A day not in the journal yet is laid down with its computed columns,
 * so that it shows the day's figures at once.
 */
class UpdateHealthDay
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly HealthDayRepository $days,
        private readonly HealthDayCalculator $calculator,
        private readonly ClockInterface $clock,
    ) {
    }

    /** @param array{mood?: ?int, fatigue?: ?int, note?: ?string} $values the fields to write, null empties one */
    public function execute(User $user, \DateTimeImmutable $date, HealthDayAuthor $author, array $values): HealthDay
    {
        $day = $this->days->findOneByDate($user, $date);
        if (null === $day) {
            $day = (new HealthDay())->setUser($user)->setDate($date);
            $day->record($this->calculator->compute($user, $date));
        }

        if (\array_key_exists('mood', $values)) {
            $day->setMood($values['mood']);
        }
        if (\array_key_exists('fatigue', $values)) {
            $day->setFatigue($values['fatigue']);
        }
        if (\array_key_exists('note', $values)) {
            $day->setNote($values['note']);
        }
        $day->noted($author, $this->clock->now());

        $this->em->persist($day);
        $this->em->flush();

        return $day;
    }
}
