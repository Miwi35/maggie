<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Finance\Enum\ReviewStep;
use Maggie\Finance\Repository\ReviewProgressRepository;
use Symfony\Component\Uid\Ulid;

/**
 * Where the user stands in the review of a month (MAG-456): the step reached
 * and the transactions already left with « Suivant » or « Valider », so the
 * activity resumes there on any device. One row per user and month, laid down
 * by the first write; a read of a month never written answers the defaults.
 *
 * Read and written through `/api/finance/review/progress`. Not searched, so not indexed.
 */
#[ORM\Entity(repositoryClass: ReviewProgressRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_review_progress_user_month', columns: ['user_id', 'month'])]
class ReviewProgress implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    /** The first day of the month reviewed. */
    #[ORM\Column(type: Types::DATE_IMMUTABLE)]
    private \DateTimeImmutable $month;

    #[ORM\Column(length: 20, enumType: ReviewStep::class, options: ['default' => 'review'])]
    private ReviewStep $step = ReviewStep::Review;

    /** @var list<string> transaction ULIDs, in the order they were handled */
    #[ORM\Column(type: Types::JSON, options: ['default' => '[]'])]
    private array $handledTransactionIds = [];

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $updatedAt = null;

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

    public function getMonth(): \DateTimeImmutable
    {
        return $this->month;
    }

    public function setMonth(\DateTimeImmutable $month): static
    {
        $this->month = new \DateTimeImmutable($month->format('Y-m-01'), new \DateTimeZone('UTC'));

        return $this;
    }

    public function getStep(): ReviewStep
    {
        return $this->step;
    }

    public function setStep(ReviewStep $step): static
    {
        $this->step = $step;

        return $this;
    }

    /** @return list<string> */
    public function getHandledTransactionIds(): array
    {
        return $this->handledTransactionIds;
    }

    /**
     * Adds these to the ones already handled: each once, in the order they came.
     *
     * @param list<string> $ids
     */
    public function addHandledTransactionIds(array $ids): static
    {
        $this->handledTransactionIds = array_values(array_unique([...$this->handledTransactionIds, ...$ids]));

        return $this;
    }

    public function getUpdatedAt(): ?\DateTimeImmutable
    {
        return $this->updatedAt;
    }

    public function setUpdatedAt(?\DateTimeImmutable $updatedAt): static
    {
        $this->updatedAt = $updatedAt;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'month' => $this->month->format('Y-m'),
            'step' => $this->step->value,
            'handledTransactionIds' => $this->handledTransactionIds,
            'updatedAt' => $this->updatedAt?->format(\DATE_ATOM),
        ], $changedProperties);
    }
}
