<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Repository\DayPlanRepository;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Symfony\Component\Uid\Ulid;

/**
 * The day (MAG-497): at most five slots, one row per user and local date,
 * created empty on first read. What is not in a slot waits in the reserve,
 * which is not stored: it is every open task the slots leave out.
 *
 * Read and written through `/api/day-plans/{date}` ({@see \Maggie\Calendar\Controller\DayPlanController}),
 * never through an API Platform operation. Not searched, so not indexed.
 */
#[ORM\Entity(repositoryClass: DayPlanRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_day_plan_user_date', columns: ['user_id', 'date'])]
class DayPlan implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    public const MAX_SLOTS = 5;
    public const TYPE_TASK = 'task';
    /** Declared for the Sagas lot, refused until it lands. */
    public const TYPE_SAGA_STEP = 'sagaStep';
    public const TYPES = [self::TYPE_TASK, self::TYPE_SAGA_STEP];

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    /** The day in the user's timezone (midnight, a date carries no zone). */
    #[ORM\Column(type: Types::DATE_IMMUTABLE)]
    private \DateTimeImmutable $date;

    /**
     * In order, positions 1 to 5 with no gap.
     *
     * @var list<array{position: int, type: string, taskId: string}>
     */
    #[ORM\Column(type: Types::JSON, options: ['default' => '[]'])]
    private array $slots = [];

    /** When the day was settled: « C'est parti » or the first change by hand. Null for a day only proposed. */
    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $confirmedAt = null;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getUser(): User
    {
        return $this->user;
    }

    public function setUser(User $user): static
    {
        $this->user = $user;

        return $this;
    }

    public function getDate(): \DateTimeImmutable
    {
        return $this->date;
    }

    public function setDate(\DateTimeImmutable $date): static
    {
        $this->date = $date;

        return $this;
    }

    /** @return list<array{position: int, type: string, taskId: string}> */
    public function getSlots(): array
    {
        return $this->slots;
    }

    /**
     * Renumbers them 1 to n in the order given.
     *
     * @param list<array{type: string, taskId: string}> $slots
     */
    public function setSlots(array $slots): static
    {
        if (\count($slots) > self::MAX_SLOTS) {
            throw new \InvalidArgumentException(\sprintf('A day holds %d slots at most.', self::MAX_SLOTS));
        }

        $this->slots = array_map(
            static fn (array $slot, int $index): array => ['position' => $index + 1, 'type' => $slot['type'], 'taskId' => $slot['taskId']],
            $slots,
            array_keys($slots),
        );

        return $this;
    }

    /** @return list<string> */
    public function getTaskIds(): array
    {
        return array_values(array_map(
            static fn (array $slot): string => $slot['taskId'],
            array_filter($this->slots, static fn (array $slot): bool => self::TYPE_TASK === $slot['type']),
        ));
    }

    public function getConfirmedAt(): ?\DateTimeImmutable
    {
        return $this->confirmedAt;
    }

    public function setConfirmedAt(?\DateTimeImmutable $confirmedAt): static
    {
        $this->confirmedAt = $confirmedAt;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'date' => $this->date->format('Y-m-d'),
            'slots' => $this->slots,
            'confirmedAt' => $this->confirmedAt?->format(\DATE_ATOM),
        ], $changedProperties);
    }
}
