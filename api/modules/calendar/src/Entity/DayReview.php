<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Repository\DayReviewRepository;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Symfony\Component\Uid\Ulid;

/**
 * The evening's review (MAG-500), one row per user and local date, created
 * empty on first read: what helped that day, from a closed list. `reviewedAt`
 * says « Bonne nuit » was said, so the evening does not open again.
 *
 * Stored for a future preparation by Maggie; the morning rule
 * ({@see \Maggie\Calendar\Service\DayProposalRule}) does not read it.
 * Read and written through `/api/day-reviews/{date}`. Not searched, so not indexed.
 */
#[ORM\Entity(repositoryClass: DayReviewRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_day_review_user_date', columns: ['user_id', 'date'])]
class DayReview implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    public const HELPED_START_SMALL = 'start_small';
    public const HELPED_MORNING = 'morning';
    public const HELPED_TIMER = 'timer';
    /** Closed, in the order the screens show them; « Faire avec Maggie » comes with her. */
    public const HELPED = [self::HELPED_START_SMALL, self::HELPED_MORNING, self::HELPED_TIMER];

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    /** The day in the user's timezone (midnight, a date carries no zone). */
    #[ORM\Column(type: Types::DATE_IMMUTABLE)]
    private \DateTimeImmutable $date;

    /** @var list<string> among {@see self::HELPED}, in that order */
    #[ORM\Column(type: Types::JSON, options: ['default' => '[]'])]
    private array $helped = [];

    /** When « Bonne nuit » closed the review. Null while it is not done. */
    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $reviewedAt = null;

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

    /** @return list<string> */
    public function getHelped(): array
    {
        return $this->helped;
    }

    /**
     * Each once, in the order of {@see self::HELPED}.
     *
     * @param list<string> $helped
     *
     * @throws \InvalidArgumentException on a value outside the list
     */
    public function setHelped(array $helped): static
    {
        $unknown = array_diff($helped, self::HELPED);
        if ([] !== $unknown) {
            throw new \InvalidArgumentException(\sprintf('helped: unknown value(s) %s. Known values: %s.', implode(', ', $unknown), implode(', ', self::HELPED)));
        }
        $this->helped = array_values(array_intersect(self::HELPED, $helped));

        return $this;
    }

    public function getReviewedAt(): ?\DateTimeImmutable
    {
        return $this->reviewedAt;
    }

    public function setReviewedAt(?\DateTimeImmutable $reviewedAt): static
    {
        $this->reviewedAt = $reviewedAt;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'date' => $this->date->format('Y-m-d'),
            'helped' => $this->helped,
            'reviewedAt' => $this->reviewedAt?->format(\DATE_ATOM),
        ], $changedProperties);
    }
}
