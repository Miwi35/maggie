<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Finance\Enum\BankConnectionStatus;
use Maggie\Finance\Repository\BankConnectionRepository;
use Symfony\Component\Uid\Ulid;

/**
 * A live link to one bank, held on the user's behalf by the data provider.
 *
 * The consent behind it expires — banks grant access for a limited time — so
 * the expiry is stored rather than inferred: the app has to be able to say
 * "this bank needs reconnecting" instead of quietly showing stale figures.
 */
#[ORM\Entity(repositoryClass: BankConnectionRepository::class)]
class BankConnection implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    /** The bank as the provider names it — what we send back to reconnect. */
    #[ORM\Column(length: 255)]
    private string $bankName;

    #[ORM\Column(length: 2)]
    private string $country = 'FR';

    #[ORM\Column(length: 20, enumType: BankConnectionStatus::class)]
    private BankConnectionStatus $status = BankConnectionStatus::Pending;

    /**
     * Ties the bank's answer back to the journey we started. It is unguessable
     * and single-use: without it, anyone could post a code to our callback.
     */
    #[ORM\Column(length: 64, unique: true)]
    private string $state;

    /** The provider's session, once the consent journey has succeeded. */
    #[ORM\Column(length: 255, nullable: true)]
    private ?string $sessionId = null;

    #[ORM\Column(type: 'datetime_immutable', nullable: true)]
    private ?\DateTimeImmutable $consentExpiresAt = null;

    #[ORM\Column(type: 'datetime_immutable', nullable: true)]
    private ?\DateTimeImmutable $lastSyncedAt = null;

    #[ORM\Column(type: 'datetime_immutable')]
    private \DateTimeImmutable $createdAt;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->createdAt = new \DateTimeImmutable();
        $this->state = bin2hex(random_bytes(24));
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getBankName(): string
    {
        return $this->bankName;
    }

    public function setBankName(string $bankName): static
    {
        $this->bankName = $bankName;

        return $this;
    }

    public function getCountry(): string
    {
        return $this->country;
    }

    public function setCountry(string $country): static
    {
        $this->country = strtoupper($country);

        return $this;
    }

    public function getStatus(): BankConnectionStatus
    {
        return $this->status;
    }

    public function setStatus(BankConnectionStatus $status): static
    {
        $this->status = $status;

        return $this;
    }

    public function getState(): string
    {
        return $this->state;
    }

    public function getSessionId(): ?string
    {
        return $this->sessionId;
    }

    public function setSessionId(?string $sessionId): static
    {
        $this->sessionId = $sessionId;

        return $this;
    }

    public function getConsentExpiresAt(): ?\DateTimeImmutable
    {
        return $this->consentExpiresAt;
    }

    public function setConsentExpiresAt(?\DateTimeImmutable $consentExpiresAt): static
    {
        $this->consentExpiresAt = $consentExpiresAt;

        return $this;
    }

    public function getLastSyncedAt(): ?\DateTimeImmutable
    {
        return $this->lastSyncedAt;
    }

    public function setLastSyncedAt(?\DateTimeImmutable $lastSyncedAt): static
    {
        $this->lastSyncedAt = $lastSyncedAt;

        return $this;
    }

    public function getCreatedAt(): \DateTimeImmutable
    {
        return $this->createdAt;
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

    /** Marks the link as live, with the date the bank's consent runs out. */
    public function activate(string $sessionId, ?\DateTimeImmutable $consentExpiresAt): static
    {
        $this->sessionId = $sessionId;
        $this->consentExpiresAt = $consentExpiresAt;
        $this->status = BankConnectionStatus::Active;

        return $this;
    }

    /**
     * Whether the bank will still answer. Expiry is checked at read time
     * rather than trusted from the stored status, which only a sync updates.
     */
    public function isUsable(\DateTimeImmutable $now = new \DateTimeImmutable()): bool
    {
        if ($this->status !== BankConnectionStatus::Active) {
            return false;
        }

        return $this->consentExpiresAt === null || $this->consentExpiresAt > $now;
    }

    /** Days before the consent runs out; negative once it has. */
    public function daysBeforeExpiry(\DateTimeImmutable $now = new \DateTimeImmutable()): ?int
    {
        if ($this->consentExpiresAt === null) {
            return null;
        }

        return (int) $now->diff($this->consentExpiresAt)->format('%r%a');
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'bankName' => $this->bankName,
            'country' => $this->country,
            'status' => $this->status->value,
            'consentExpiresAt' => $this->consentExpiresAt?->format(\DateTimeInterface::ATOM),
            'lastSyncedAt' => $this->lastSyncedAt?->format(\DateTimeInterface::ATOM),
        ], $changedProperties);
    }
}
