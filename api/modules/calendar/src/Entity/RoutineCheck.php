<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Repository\RoutineCheckRepository;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedThroughInterface;
use Symfony\Component\Uid\Ulid;

/**
 * A routine done on a local date (MAG-511), at most once a day. A day it was
 * not due counts too: done another day, it was done.
 */
#[ORM\Entity(repositoryClass: RoutineCheckRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_routine_check_routine_date', columns: ['routine_id', 'date'])]
class RoutineCheck implements MercurePublishable, OwnedThroughInterface
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Routine::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Routine $routine;

    /** The day in the user's timezone (midnight, a date carries no zone). */
    #[ORM\Column(type: Types::DATE_IMMUTABLE)]
    private \DateTimeImmutable $date;

    public function __construct(Routine $routine, \DateTimeImmutable $date)
    {
        $this->id = new Ulid();
        $this->routine = $routine;
        $this->date = $date;
    }

    public static function getOwnerRelation(): string
    {
        return 'routine';
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getRoutine(): Routine
    {
        return $this->routine;
    }

    public function getDate(): \DateTimeImmutable
    {
        return $this->date;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return [
            'routine' => (string) $this->routine->getId(),
            'date' => $this->date->format('Y-m-d'),
        ];
    }
}
