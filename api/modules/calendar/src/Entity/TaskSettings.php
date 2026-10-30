<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Enum\DayMoment;
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
 * the evening time the review opens after (MAG-500), how a task done is
 * cheered and the tone of the words (MAG-508), the reminders' daily cap, quiet
 * hours and moments of the day (MAG-510).
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

    public const TONE_DOUX = 'doux';
    public const TONE_DIRECT = 'direct';
    public const TONE_DROLE = 'drole';
    /** The tone of the rewards, and of the reminders to come. */
    public const TONES = [self::TONE_DOUX, self::TONE_DIRECT, self::TONE_DROLE];
    public const DEFAULT_TONE = self::TONE_DOUX;

    public const DEFAULT_REMINDER_DAILY_CAP = 6;
    public const MIN_REMINDER_DAILY_CAP = 1;
    public const MAX_REMINDER_DAILY_CAP = 12;
    public const DEFAULT_QUIET_START = '21:00';
    public const DEFAULT_QUIET_END = '08:00';

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
     * Réglages › Rappels, per urgency — « À programmer » and « Pas faite »,
     * read by the morning digest: {@see TaskAlertSchedule}.
     *
     * @var array<string, array<string, mixed>>
     */
    #[ORM\Column(type: Types::JSON)]
    private array $alertSchedule = TaskAlertSchedule::DEFAULTS;

    /** The « morning » of the tasks, `HH:MM` in the user's timezone: the morning proposal waits for it. No alert goes out at it anymore (MAG-718). */
    #[ORM\Column(length: 5, options: ['default' => self::DEFAULT_MORNING_TIME])]
    private string $morningTime = self::DEFAULT_MORNING_TIME;

    /** The « evening » the review opens after, `HH:MM` in the user's timezone. */
    #[ORM\Column(length: 5, options: ['default' => self::DEFAULT_EVENING_TIME])]
    private string $eveningTime = self::DEFAULT_EVENING_TIME;

    /** The « bravo » of a task done: its animation, its short vibration, its little sound. */
    #[ORM\Column(options: ['default' => true])]
    private bool $rewardAnimation = true;

    #[ORM\Column(options: ['default' => true])]
    private bool $rewardVibration = true;

    #[ORM\Column(options: ['default' => true])]
    private bool $rewardSound = true;

    /** Among {@see self::TONES}. */
    #[ORM\Column(length: 10, options: ['default' => self::DEFAULT_TONE])]
    private string $tone = self::DEFAULT_TONE;

    /**
     * The hour of each moment of the day, `HH:MM`: {@see DayMoment}.
     *
     * @var array<string, string>
     */
    #[ORM\Column(type: Types::JSON, options: ['default' => '{"wake":"07:30","afterBreakfast":"08:30","afterLunch":"13:30","afterDinner":"20:00"}'])]
    private array $dayMoments = DayMoment::DEFAULTS;

    /** At most this many reminders a local day, those put off by the user aside. */
    #[ORM\Column(options: ['default' => self::DEFAULT_REMINDER_DAILY_CAP])]
    private int $reminderDailyCap = self::DEFAULT_REMINDER_DAILY_CAP;

    /** No reminder goes out from `quietStart` to `quietEnd`, `HH:MM` in the user's timezone. */
    #[ORM\Column(length: 5, options: ['default' => self::DEFAULT_QUIET_START])]
    private string $quietStart = self::DEFAULT_QUIET_START;

    #[ORM\Column(length: 5, options: ['default' => self::DEFAULT_QUIET_END])]
    private string $quietEnd = self::DEFAULT_QUIET_END;

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

    public function isRewardAnimation(): bool
    {
        return $this->rewardAnimation;
    }

    public function setRewardAnimation(bool $rewardAnimation): static
    {
        $this->rewardAnimation = $rewardAnimation;

        return $this;
    }

    public function isRewardVibration(): bool
    {
        return $this->rewardVibration;
    }

    public function setRewardVibration(bool $rewardVibration): static
    {
        $this->rewardVibration = $rewardVibration;

        return $this;
    }

    public function isRewardSound(): bool
    {
        return $this->rewardSound;
    }

    public function setRewardSound(bool $rewardSound): static
    {
        $this->rewardSound = $rewardSound;

        return $this;
    }

    public function getTone(): string
    {
        return $this->tone;
    }

    /** @throws \InvalidArgumentException outside {@see self::TONES} */
    public function setTone(string $tone): static
    {
        if (!\in_array($tone, self::TONES, true)) {
            throw new \InvalidArgumentException(\sprintf('tone must be one of %s, not "%s".', implode(', ', self::TONES), $tone));
        }
        $this->tone = $tone;

        return $this;
    }

    /** @return array<string, string> every moment, the defaults filling what is not stored */
    public function getDayMoments(): array
    {
        return self::completeMoments($this->dayMoments);
    }

    /** The hour of one moment, `HH:MM`. */
    public function getDayMoment(DayMoment $moment): string
    {
        return $this->getDayMoments()[$moment->value];
    }

    /**
     * @param array<string, string> $dayMoments
     *
     * @throws \InvalidArgumentException on an unknown moment or a value that is not `HH:MM`
     */
    public function setDayMoments(array $dayMoments): static
    {
        $this->dayMoments = self::mergeMoments($this->getDayMoments(), $dayMoments);

        return $this;
    }

    /**
     * $changes over $current, every key checked: a refused one changes none.
     *
     * @param array<string, string> $current
     * @param array<mixed>          $changes
     *
     * @return array<string, string>
     *
     * @throws \InvalidArgumentException
     */
    public static function mergeMoments(array $current, array $changes): array
    {
        foreach ($changes as $moment => $time) {
            if (null === DayMoment::tryFrom((string) $moment)) {
                throw new \InvalidArgumentException(\sprintf('dayMoments: unknown moment "%s". Known moments: %s.', $moment, implode(', ', array_keys(DayMoment::DEFAULTS))));
            }
            if (!\is_string($time) || 1 !== preg_match(self::TIME_PATTERN, $time)) {
                throw new \InvalidArgumentException(\sprintf('dayMoments.%s must be a time "HH:MM" from 00:00 to 23:59.', $moment));
            }
            $current[(string) $moment] = $time;
        }

        return self::completeMoments($current);
    }

    /**
     * @param array<string, string> $moments
     *
     * @return array<string, string>
     */
    private static function completeMoments(array $moments): array
    {
        $complete = [];
        foreach (DayMoment::DEFAULTS as $moment => $default) {
            $complete[$moment] = $moments[$moment] ?? $default;
        }

        return $complete;
    }

    public function getReminderDailyCap(): int
    {
        return $this->reminderDailyCap;
    }

    /** @throws \InvalidArgumentException outside 1 to 12 */
    public function setReminderDailyCap(int $reminderDailyCap): static
    {
        if ($reminderDailyCap < self::MIN_REMINDER_DAILY_CAP || $reminderDailyCap > self::MAX_REMINDER_DAILY_CAP) {
            throw new \InvalidArgumentException(\sprintf('reminderDailyCap must be a whole number from %d to %d, not %d.', self::MIN_REMINDER_DAILY_CAP, self::MAX_REMINDER_DAILY_CAP, $reminderDailyCap));
        }
        $this->reminderDailyCap = $reminderDailyCap;

        return $this;
    }

    public function getQuietStart(): string
    {
        return $this->quietStart;
    }

    /** @throws \InvalidArgumentException when it is not `HH:MM` */
    public function setQuietStart(string $quietStart): static
    {
        if (1 !== preg_match(self::TIME_PATTERN, $quietStart)) {
            throw new \InvalidArgumentException(\sprintf('quietStart must be a time "HH:MM" from 00:00 to 23:59, not "%s".', $quietStart));
        }
        $this->quietStart = $quietStart;

        return $this;
    }

    public function getQuietEnd(): string
    {
        return $this->quietEnd;
    }

    /** @throws \InvalidArgumentException when it is not `HH:MM` */
    public function setQuietEnd(string $quietEnd): static
    {
        if (1 !== preg_match(self::TIME_PATTERN, $quietEnd)) {
            throw new \InvalidArgumentException(\sprintf('quietEnd must be a time "HH:MM" from 00:00 to 23:59, not "%s".', $quietEnd));
        }
        $this->quietEnd = $quietEnd;

        return $this;
    }

    /**
     * Whether $time, `HH:MM` on the user's clock, falls in the quiet hours. They
     * run over midnight when they start after they end; the same start and end
     * leave no quiet hours.
     */
    public function isQuiet(string $time): bool
    {
        if ($this->quietStart === $this->quietEnd) {
            return false;
        }

        return $this->quietStart < $this->quietEnd
            ? $time >= $this->quietStart && $time < $this->quietEnd
            : $time >= $this->quietStart || $time < $this->quietEnd;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'orderWeights' => $this->getOrderWeights(),
            'alertSchedule' => $this->getAlertSchedule(),
            'morningTime' => $this->morningTime,
            'eveningTime' => $this->eveningTime,
            'rewardAnimation' => $this->rewardAnimation,
            'rewardVibration' => $this->rewardVibration,
            'rewardSound' => $this->rewardSound,
            'tone' => $this->tone,
            'dayMoments' => $this->getDayMoments(),
            'reminderDailyCap' => $this->reminderDailyCap,
            'quietStart' => $this->quietStart,
            'quietEnd' => $this->quietEnd,
        ], $changedProperties);
    }
}
