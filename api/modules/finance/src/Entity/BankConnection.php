<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\HoldsConnectedServiceTokens;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Core\Security\ConnectedServiceTokenUnavailable;
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
class BankConnection implements MercurePublishable, OwnedByUserInterface, HoldsConnectedServiceTokens
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

    private const SESSION_ID = 'session_id';

    /**
     * The provider's session, once the consent journey has succeeded — stored
     * sealed (MAG-889): the plain value lives in memory only, sealed at flush
     * and opened at load by ConnectedServiceTokensListener.
     */
    #[ORM\Column(type: Types::TEXT, nullable: true)]
    private ?string $sessionId = null;

    private ?string $sessionIdPlain = null;

    private bool $sessionIdChanged = false;

    private bool $sessionIdUnreadable = false;

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
        if ($this->sessionIdUnreadable) {
            throw new ConnectedServiceTokenUnavailable('The bank session of this connection cannot be read: TOKEN_ENCRYPTION_KEY is missing or not the one it was sealed with. Reconnect the bank once the key is fixed.');
        }

        return $this->sessionIdPlain;
    }

    public function setSessionId(?string $sessionId): static
    {
        $this->sessionIdPlain = $sessionId;
        $this->sessionIdChanged = true;
        $this->sessionIdUnreadable = false;

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

    /**
     * Sends the link back through consent: a fresh state, nothing of the old
     * session kept. The accounts stay attached, so a renewed consent resumes
     * where the previous one stopped instead of creating them again.
     */
    public function reopen(): static
    {
        $this->state = bin2hex(random_bytes(24));
        $this->status = BankConnectionStatus::Pending;
        $this->setSessionId(null);
        $this->consentExpiresAt = null;

        return $this;
    }

    /** Marks the link as live, with the date the bank's consent runs out. */
    public function activate(string $sessionId, ?\DateTimeImmutable $consentExpiresAt): static
    {
        $this->setSessionId($sessionId);
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
        if (BankConnectionStatus::Active !== $this->status) {
            return false;
        }

        return null === $this->consentExpiresAt || $this->consentExpiresAt > $now;
    }

    /** Days before the consent runs out; negative once it has. */
    public function daysBeforeExpiry(\DateTimeImmutable $now = new \DateTimeImmutable()): ?int
    {
        if (null === $this->consentExpiresAt) {
            return null;
        }

        return (int) $now->diff($this->consentExpiresAt)->format('%r%a');
    }

    public function tokenContext(string $usage): string
    {
        return sprintf('bank_connection:%s:%s:%s', $this->id, $this->user->getId(), $usage);
    }

    /** @return array<string, ?string> */
    public function tokensToSeal(): array
    {
        return $this->sessionIdChanged ? [self::SESSION_ID => $this->sessionIdPlain] : [];
    }

    public function storeSealedToken(string $usage, ?string $sealed): void
    {
        $this->sessionId = $sealed;
        $this->sessionIdChanged = false;
    }

    /** @return array<string, ?string> */
    public function sealedTokens(): array
    {
        return [self::SESSION_ID => $this->sessionId];
    }

    /**
     * @param array<string, ?string> $plain
     * @param list<string>           $unreadable
     */
    public function openTokens(array $plain, array $unreadable): void
    {
        $this->sessionIdPlain = $plain[self::SESSION_ID] ?? null;
        $this->sessionIdChanged = false;
        $this->sessionIdUnreadable = in_array(self::SESSION_ID, $unreadable, true);
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
