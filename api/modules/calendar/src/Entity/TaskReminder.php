<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Enum\DayMoment;
use Maggie\Calendar\Repository\TaskReminderRepository;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Notification\Entity\Notification;
use Symfony\Component\Uid\Ulid;

/**
 * One reminder about a task (MAG-510): planned for a moment, then sent — or
 * dropped, when it falls in the quiet hours, over the daily cap, or its task
 * is done meanwhile. {@see \Maggie\Calendar\Service\TaskReminders} plans,
 * sends and answers them; a new kind plugs in there without touching its
 * rules.
 *
 * Bookkeeping: what the user sees is the notification it sent. Answered
 * through `/api/task-reminders/{id}/action`; not published, not indexed.
 */
#[ORM\Entity(repositoryClass: TaskReminderRepository::class)]
#[ORM\Index(columns: ['user_id', 'scheduled_for'], name: 'idx_task_reminder_user_scheduled')]
class TaskReminder implements OwnedByUserInterface
{
    /** A morning alert on a task near or past its due day (MAG-410). */
    public const KIND_DUE = 'due';
    /** A nudge on the task « now » of the day, at a moment. */
    public const KIND_NUDGE = 'nudge';
    /** Declared for the Routines lot. */
    public const KIND_ROUTINE = 'routine';
    public const KINDS = [self::KIND_DUE, self::KIND_NUDGE, self::KIND_ROUTINE];

    public const ACTION_START = 'start';
    public const ACTION_IN10 = 'in10';
    public const ACTION_TONIGHT = 'tonight';
    public const ACTION_DONE = 'done';
    public const ACTION_TOMORROW = 'tomorrow';
    public const ACTIONS = [self::ACTION_START, self::ACTION_IN10, self::ACTION_TONIGHT, self::ACTION_DONE, self::ACTION_TOMORROW];

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    #[ORM\Column(length: 20)]
    private string $kind;

    /** The task — for `routine`, what the Routines lot sets. No foreign key: a deleted task drops its reminder. */
    #[ORM\Column(length: 36)]
    private string $subjectId;

    #[ORM\Column(length: 20, nullable: true, enumType: DayMoment::class)]
    private ?DayMoment $moment = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $scheduledFor;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $sentAt = null;

    /** When it was let go without being sent: quiet hours, cap, a task done meanwhile. */
    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $droppedAt = null;

    /** Put off by the user (« Dans 10 min », « Ce soir »): outside the daily cap. */
    #[ORM\Column(options: ['default' => false])]
    private bool $snoozed = false;

    /** Which message of the bank, once sent: the next one of the same kind is never the same. */
    #[ORM\Column(length: 40, nullable: true)]
    private ?string $messageKey = null;

    #[ORM\ManyToOne(targetEntity: Notification::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?Notification $notification = null;

    /** The button the user pressed, once: a reminder is answered once. */
    #[ORM\Column(length: 20, nullable: true)]
    private ?string $action = null;

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

    public function getKind(): string
    {
        return $this->kind;
    }

    /** @throws \InvalidArgumentException outside {@see self::KINDS} */
    public function setKind(string $kind): static
    {
        if (!\in_array($kind, self::KINDS, true)) {
            throw new \InvalidArgumentException(\sprintf('kind must be one of %s, not "%s".', implode(', ', self::KINDS), $kind));
        }
        $this->kind = $kind;

        return $this;
    }

    public function getSubjectId(): string
    {
        return $this->subjectId;
    }

    public function setSubjectId(string $subjectId): static
    {
        $this->subjectId = $subjectId;

        return $this;
    }

    public function getMoment(): ?DayMoment
    {
        return $this->moment;
    }

    public function setMoment(?DayMoment $moment): static
    {
        $this->moment = $moment;

        return $this;
    }

    public function getScheduledFor(): \DateTimeImmutable
    {
        return $this->scheduledFor;
    }

    public function setScheduledFor(\DateTimeImmutable $scheduledFor): static
    {
        $this->scheduledFor = $scheduledFor;

        return $this;
    }

    public function getSentAt(): ?\DateTimeImmutable
    {
        return $this->sentAt;
    }

    public function setSentAt(?\DateTimeImmutable $sentAt): static
    {
        $this->sentAt = $sentAt;

        return $this;
    }

    public function getDroppedAt(): ?\DateTimeImmutable
    {
        return $this->droppedAt;
    }

    public function setDroppedAt(?\DateTimeImmutable $droppedAt): static
    {
        $this->droppedAt = $droppedAt;

        return $this;
    }

    public function isSnoozed(): bool
    {
        return $this->snoozed;
    }

    public function setSnoozed(bool $snoozed): static
    {
        $this->snoozed = $snoozed;

        return $this;
    }

    public function getMessageKey(): ?string
    {
        return $this->messageKey;
    }

    public function setMessageKey(?string $messageKey): static
    {
        $this->messageKey = $messageKey;

        return $this;
    }

    public function getNotification(): ?Notification
    {
        return $this->notification;
    }

    /** The notification sent learns which reminder its buttons answer. */
    public function setNotification(?Notification $notification): static
    {
        $notification?->setReminderId((string) $this->id);
        $this->notification = $notification;

        return $this;
    }

    public function getAction(): ?string
    {
        return $this->action;
    }

    /** @throws \InvalidArgumentException outside {@see self::ACTIONS} */
    public function setAction(?string $action): static
    {
        if (null !== $action && !\in_array($action, self::ACTIONS, true)) {
            throw new \InvalidArgumentException(\sprintf('action must be one of %s, not "%s".', implode(', ', self::ACTIONS), $action));
        }
        $this->action = $action;

        return $this;
    }

    public function isPending(): bool
    {
        return null === $this->sentAt && null === $this->droppedAt;
    }

    /**
     * The reminder as its action answers it.
     *
     * @return array<string, mixed>
     */
    public function describe(): array
    {
        return [
            'id' => (string) $this->id,
            'kind' => $this->kind,
            'subjectId' => $this->subjectId,
            'moment' => $this->moment?->value,
            'scheduledFor' => $this->scheduledFor->format(\DATE_ATOM),
            'sentAt' => $this->sentAt?->format(\DATE_ATOM),
            'droppedAt' => $this->droppedAt?->format(\DATE_ATOM),
            'snoozed' => $this->snoozed,
            'messageKey' => $this->messageKey,
            'action' => $this->action,
        ];
    }
}
