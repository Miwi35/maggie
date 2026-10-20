<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Repository\RewardRepository;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Symfony\Component\Uid\Ulid;

/**
 * The « bravo » of a thing done (MAG-508), one per subject: laid down when a
 * task is ticked ({@see \Maggie\Calendar\EventSubscriber\GrantRewardOnTaskCompleted}),
 * never taken back when it is reopened. A reward with `star` is a star: the
 * constellation counts them, with no other table.
 *
 * Its message comes from a written bank ({@see \Maggie\Calendar\Service\RewardMessages}),
 * never from the model. Read through `/api/rewards/{subjectType}/{subjectId}`.
 * Not searched, so not indexed.
 */
#[ORM\Entity(repositoryClass: RewardRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_reward_subject', columns: ['user_id', 'subject_type', 'subject_id'])]
class Reward implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    public const SUBJECT_TASK = 'task';
    /** Declared for the Sagas lot. */
    public const SUBJECT_SAGA_STEP = 'sagaStep';
    public const SUBJECT_SAGA_CHAPTER = 'sagaChapter';
    public const SUBJECTS = [self::SUBJECT_TASK, self::SUBJECT_SAGA_STEP, self::SUBJECT_SAGA_CHAPTER];

    public const LEVEL_STANDARD = 'standard';
    public const LEVEL_SURPRISE = 'surprise';
    /** A big moment — a saga chapter finished (MAG-509): gold, no star. */
    public const LEVEL_PALIER = 'palier';
    public const LEVELS = [self::LEVEL_STANDARD, self::LEVEL_SURPRISE, self::LEVEL_PALIER];

    public const SURPRISE_FULL_DAY = 'fullDay';
    public const SURPRISE_MONTH_RECORD = 'monthRecord';
    public const SURPRISE_WAITED = 'waited';
    /** In order of priority: the first that holds wins. */
    public const SURPRISES = [self::SURPRISE_FULL_DAY, self::SURPRISE_MONTH_RECORD, self::SURPRISE_WAITED];

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    #[ORM\Column(length: 20)]
    private string $subjectType = self::SUBJECT_TASK;

    /** The id of the task (or saga step, chapter): no foreign key, a deleted task keeps its star. */
    #[ORM\Column(length: 36)]
    private string $subjectId;

    #[ORM\Column(length: 20)]
    private string $level = self::LEVEL_STANDARD;

    #[ORM\Column(length: 20, nullable: true)]
    private ?string $surprise = null;

    /** Which message of the bank, `direct.4`, `fullDay.2`: the next one is never the same. */
    #[ORM\Column(length: 32)]
    private string $messageKey;

    /** The message as shown, its placeholders filled. */
    #[ORM\Column(type: Types::TEXT)]
    private string $message;

    /** What a palier celebrates (the chapter's title); null for a task, whose title the screens already know. */
    #[ORM\Column(length: 255, nullable: true)]
    private ?string $title = null;

    #[ORM\Column(options: ['default' => true])]
    private bool $star = true;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $earnedAt;

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

    public function getSubjectType(): string
    {
        return $this->subjectType;
    }

    /** @throws \InvalidArgumentException outside {@see self::SUBJECTS} */
    public function setSubjectType(string $subjectType): static
    {
        if (!\in_array($subjectType, self::SUBJECTS, true)) {
            throw new \InvalidArgumentException(\sprintf('subjectType must be one of %s, not "%s".', implode(', ', self::SUBJECTS), $subjectType));
        }
        $this->subjectType = $subjectType;

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

    public function getLevel(): string
    {
        return $this->level;
    }

    /** @throws \InvalidArgumentException outside {@see self::LEVELS} */
    public function setLevel(string $level): static
    {
        if (!\in_array($level, self::LEVELS, true)) {
            throw new \InvalidArgumentException(\sprintf('level must be one of %s, not "%s".', implode(', ', self::LEVELS), $level));
        }
        $this->level = $level;

        return $this;
    }

    public function getSurprise(): ?string
    {
        return $this->surprise;
    }

    /** @throws \InvalidArgumentException outside {@see self::SURPRISES} */
    public function setSurprise(?string $surprise): static
    {
        if (null !== $surprise && !\in_array($surprise, self::SURPRISES, true)) {
            throw new \InvalidArgumentException(\sprintf('surprise must be one of %s or null, not "%s".', implode(', ', self::SURPRISES), $surprise));
        }
        $this->surprise = $surprise;

        return $this;
    }

    public function getMessageKey(): string
    {
        return $this->messageKey;
    }

    public function setMessageKey(string $messageKey): static
    {
        $this->messageKey = $messageKey;

        return $this;
    }

    public function getMessage(): string
    {
        return $this->message;
    }

    public function setMessage(string $message): static
    {
        $this->message = $message;

        return $this;
    }

    public function getTitle(): ?string
    {
        return $this->title;
    }

    public function setTitle(?string $title): static
    {
        $this->title = $title;

        return $this;
    }

    public function isStar(): bool
    {
        return $this->star;
    }

    public function setStar(bool $star): static
    {
        $this->star = $star;

        return $this;
    }

    public function getEarnedAt(): \DateTimeImmutable
    {
        return $this->earnedAt;
    }

    public function setEarnedAt(\DateTimeImmutable $earnedAt): static
    {
        $this->earnedAt = $earnedAt;

        return $this;
    }

    /**
     * The reward as the screens read it, over REST and Mercure alike.
     *
     * @return array{id: string, subjectType: string, subjectId: string, level: string, surprise: string|null, messageKey: string, message: string, title: string|null, star: bool, earnedAt: string}
     */
    public function describe(): array
    {
        return [
            'id' => (string) $this->id,
            'subjectType' => $this->subjectType,
            'subjectId' => $this->subjectId,
            'level' => $this->level,
            'surprise' => $this->surprise,
            'messageKey' => $this->messageKey,
            'message' => $this->message,
            'title' => $this->title,
            'star' => $this->star,
            'earnedAt' => $this->earnedAt->format(\DATE_ATOM),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        $payload = $this->describe();
        unset($payload['id']);

        return self::filterPayload($payload, $changedProperties);
    }
}
