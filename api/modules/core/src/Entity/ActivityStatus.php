<?php

declare(strict_types=1);

namespace Maggie\Core\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercureItemKey;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Enum\Activity;
use Maggie\Core\Mercure\Attribute\MercureCollection;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Core\Repository\ActivityStatusRepository;
use Symfony\Component\Uid\Ulid;

/**
 * Where the user stands in an activity in progress (Cuisine, Comptes…), so it
 * resumes there on any device (MAG-613). One per user and activity, replaced
 * by each write, removed when the activity ends or starts over.
 *
 * A named exception to « the API exposes domain resources only »: this is a
 * screen's progress, kept server-side because the owner wants to leave an
 * activity on the phone and pick it up in the admin. Not a precedent.
 *
 * The state is the activity's own JSON object, opaque to the API: stored as
 * the client sent it, so an empty object stays an object.
 *
 * Read and written through `/api/activity-status/{activity}`
 * (ActivityStatusController); published on `/api/activity-status/{activity}`.
 * Nothing to search, so not indexed.
 */
#[ORM\Entity(repositoryClass: ActivityStatusRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_activity_status_user_activity', columns: ['user_id', 'activity'])]
#[MercureCollection('/api/activity-status')]
class ActivityStatus implements MercurePublishable, MercureItemKey, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    /** The longest state accepted, in bytes of JSON. */
    public const MAX_STATE_BYTES = 16384;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    #[ORM\Column(length: 20, enumType: Activity::class)]
    private Activity $activity;

    /** What launched it: `meal`, `recipe`, `review`… */
    #[ORM\Column(length: 30)]
    private string $originKind;

    #[ORM\Column(length: 64)]
    private string $originId;

    /** A JSON object, as the client sent it. */
    #[ORM\Column(type: Types::TEXT)]
    private string $state = '{}';

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $startedAt;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $updatedAt;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getMercureItemKey(): string
    {
        return $this->activity->value;
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

    public function getActivity(): Activity
    {
        return $this->activity;
    }

    public function setActivity(Activity $activity): static
    {
        $this->activity = $activity;

        return $this;
    }

    public function getOriginKind(): string
    {
        return $this->originKind;
    }

    public function getOriginId(): string
    {
        return $this->originId;
    }

    public function setOrigin(string $kind, string $id): static
    {
        $this->originKind = $kind;
        $this->originId = $id;

        return $this;
    }

    /** The state as JSON text. */
    public function getState(): string
    {
        return $this->state;
    }

    /** @param string $state a JSON object */
    public function setState(string $state): static
    {
        $this->state = $state;

        return $this;
    }

    public function getStartedAt(): \DateTimeImmutable
    {
        return $this->startedAt;
    }

    public function setStartedAt(\DateTimeImmutable $startedAt): static
    {
        $this->startedAt = $startedAt;

        return $this;
    }

    public function getUpdatedAt(): \DateTimeImmutable
    {
        return $this->updatedAt;
    }

    public function setUpdatedAt(\DateTimeImmutable $updatedAt): static
    {
        $this->updatedAt = $updatedAt;

        return $this;
    }

    /**
     * What the REST endpoints send. The state is decoded to objects, not
     * arrays, so `{}` goes back out as `{}`.
     *
     * @return array{activity: string, origin: array{kind: string, id: string}, state: object, startedAt: string, updatedAt: string}
     */
    public function describe(): array
    {
        return [
            'activity' => $this->activity->value,
            'origin' => ['kind' => $this->originKind, 'id' => $this->originId],
            'state' => $this->decodedState(),
            'startedAt' => $this->startedAt->format(\DATE_ATOM),
            'updatedAt' => $this->updatedAt->format(\DATE_ATOM),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        // The origin is one field for the clients, two columns here.
        return self::filterPayload($this->describe(), $changedProperties, ['originKind' => 'origin', 'originId' => 'origin']);
    }

    private function decodedState(): object
    {
        $state = json_decode($this->state, false, 512, \JSON_THROW_ON_ERROR);

        return \is_object($state) ? $state : new \stdClass();
    }
}
