<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Calendar\Service\TaskAlertSchedule;
use Maggie\Calendar\Service\TaskOrderWeights;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Symfony\Component\Uid\Ulid;

/**
 * The task settings, one row per user, created on first read: the weights of
 * the order (MAG-406), the alert schedule and the morning time (MAG-407),
 * the evening time the review opens after (MAG-500).
 *
 * Read and written through `/api/task-settings/me`, never through an API
 * Platform operation on an identifier-less path, which would build a fresh
 * entity on write (MeOperationContractTest). Not searched, so not indexed.
 */
#[ORM\Entity(repositoryClass: TaskSettingsRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_task_settings_user', columns: ['user_id'])]
class TaskSettings implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    public const DEFAULT_MORNING_TIME = '08:00';
    public const DEFAULT_EVENING_TIME = '21:00';
    public const TIME_PATTERN = '/^([01]\d|2[0-3]):[0-5]\d$/';

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    /**
     * The weights of the order score: {@see TaskOrderWeights}.
     *
     * @var array<string, array<string, int>>
     */
    #[ORM\Column(type: Types::JSON)]
    private array $orderWeights = TaskOrderWeights::DEFAULTS;

    /**
     * The alerts of each urgency: {@see TaskAlertSchedule}.
     *
     * @var array<string, array<string, mixed>>
     */
    #[ORM\Column(type: Types::JSON)]
    private array $alertSchedule = TaskAlertSchedule::DEFAULTS;

    /** The « morning » the alerts go out, `HH:MM` in the user's timezone. */
    #[ORM\Column(length: 5, options: ['default' => self::DEFAULT_MORNING_TIME])]
    private string $morningTime = self::DEFAULT_MORNING_TIME;

    /** The « evening » the review opens after, `HH:MM` in the user's timezone. */
    #[ORM\Column(length: 5, options: ['default' => self::DEFAULT_EVENING_TIME])]
    private string $eveningTime = self::DEFAULT_EVENING_TIME;

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

    /** @return array<string, array<string, int>> every weight, the defaults filling what is not stored */
    public function getOrderWeights(): array
    {
        return TaskOrderWeights::complete($this->orderWeights);
    }

    /** @param array<string, array<string, int>> $orderWeights */
    public function setOrderWeights(array $orderWeights): static
    {
        $this->orderWeights = $orderWeights;

        return $this;
    }

    /** @return array<string, array{daysBefore: list<int>, dueDay: bool, overdueEveryDays: int|null}> every urgency, the defaults filling what is not stored */
    public function getAlertSchedule(): array
    {
        return TaskAlertSchedule::complete($this->alertSchedule);
    }

    /** @param array<string, array{daysBefore: list<int>, dueDay: bool, overdueEveryDays: int|null}> $alertSchedule */
    public function setAlertSchedule(array $alertSchedule): static
    {
        $this->alertSchedule = $alertSchedule;

        return $this;
    }

    public function getMorningTime(): string
    {
        return $this->morningTime;
    }

    /** @throws \InvalidArgumentException when it is not `HH:MM` */
    public function setMorningTime(string $morningTime): static
    {
        if (1 !== preg_match(self::TIME_PATTERN, $morningTime)) {
            throw new \InvalidArgumentException(\sprintf('morningTime must be a time "HH:MM" from 00:00 to 23:59, not "%s".', $morningTime));
        }
        $this->morningTime = $morningTime;

        return $this;
    }

    public function getEveningTime(): string
    {
        return $this->eveningTime;
    }

    /** @throws \InvalidArgumentException when it is not `HH:MM` */
    public function setEveningTime(string $eveningTime): static
    {
        if (1 !== preg_match(self::TIME_PATTERN, $eveningTime)) {
            throw new \InvalidArgumentException(\sprintf('eveningTime must be a time "HH:MM" from 00:00 to 23:59, not "%s".', $eveningTime));
        }
        $this->eveningTime = $eveningTime;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'orderWeights' => $this->getOrderWeights(),
            'alertSchedule' => $this->getAlertSchedule(),
            'morningTime' => $this->morningTime,
            'eveningTime' => $this->eveningTime,
        ], $changedProperties);
    }
}
