<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Finance\Repository\EnvelopeAlertRepository;
use Symfony\Component\Uid\Ulid;

/**
 * Maggie said it once: this envelope crossed this threshold in this period.
 *
 * The unique key is the rule « once per threshold and per period » — a second
 * sync of the same month finds the row and stays silent.
 */
#[ORM\Entity(repositoryClass: EnvelopeAlertRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_envelope_alert', columns: ['envelope_id', 'period', 'threshold_percent'])]
class EnvelopeAlert
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(type: 'datetime_immutable')]
    private \DateTimeImmutable $sentAt;

    public function __construct(
        #[ORM\ManyToOne(targetEntity: Envelope::class)]
        #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
        private Envelope $envelope,
        /** « 2026-10 » for a monthly envelope, « 2026 » for an annual one. */
        #[ORM\Column(length: 7)]
        private string $period,
        #[ORM\Column(type: 'integer')]
        private int $thresholdPercent,
    ) {
        $this->id = new Ulid();
        $this->sentAt = new \DateTimeImmutable();
    }

    /** The period an envelope budgets, as an alert keys it. */
    public static function periodOf(Envelope $envelope): string
    {
        return null === $envelope->getMonth()
            ? sprintf('%04d', $envelope->getYear())
            : sprintf('%04d-%02d', $envelope->getYear(), $envelope->getMonth());
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getEnvelope(): Envelope
    {
        return $this->envelope;
    }

    public function getPeriod(): string
    {
        return $this->period;
    }

    public function getThresholdPercent(): int
    {
        return $this->thresholdPercent;
    }

    public function getSentAt(): \DateTimeImmutable
    {
        return $this->sentAt;
    }
}
