<?php

namespace Maggie\Core\Trait;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Security\ConnectedServiceTokenUnavailable;

/**
 * The Google tokens are stored sealed (MAG-889): the mapped columns hold the
 * sealed values, the plain ones live in memory only — sealed at flush and
 * opened at load by ConnectedServiceTokensListener.
 */
trait HasGoogleOAuthTokensTrait
{
    #[ORM\Column(type: Types::TEXT, nullable: true)]
    private ?string $googleAccessToken = null;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    private ?string $googleRefreshToken = null;

    private ?string $googleAccessTokenPlain = null;

    private ?string $googleRefreshTokenPlain = null;

    /** @var array<string, true> usages set since the last load or seal */
    private array $googleTokensChanged = [];

    /** @var array<string, true> usages stored but not opened */
    private array $googleTokensUnreadable = [];

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $googleTokenExpiresAt = null;

    #[ORM\Column(length: 255, nullable: true)]
    private ?string $googleTaskListId = null;

    public function getGoogleAccessToken(): ?string
    {
        return $this->readGoogleToken('google_access_token');
    }

    public function setGoogleAccessToken(?string $googleAccessToken): static
    {
        $this->googleAccessTokenPlain = $googleAccessToken;
        $this->googleTokensChanged['google_access_token'] = true;
        unset($this->googleTokensUnreadable['google_access_token']);

        return $this;
    }

    public function getGoogleRefreshToken(): ?string
    {
        return $this->readGoogleToken('google_refresh_token');
    }

    public function setGoogleRefreshToken(?string $googleRefreshToken): static
    {
        $this->googleRefreshTokenPlain = $googleRefreshToken;
        $this->googleTokensChanged['google_refresh_token'] = true;
        unset($this->googleTokensUnreadable['google_refresh_token']);

        return $this;
    }

    public function getGoogleTokenExpiresAt(): ?\DateTimeImmutable
    {
        return $this->googleTokenExpiresAt;
    }

    public function setGoogleTokenExpiresAt(?\DateTimeImmutable $googleTokenExpiresAt): static
    {
        $this->googleTokenExpiresAt = $googleTokenExpiresAt;

        return $this;
    }

    public function getGoogleTaskListId(): ?string
    {
        return $this->googleTaskListId;
    }

    public function setGoogleTaskListId(?string $googleTaskListId): static
    {
        $this->googleTaskListId = $googleTaskListId;

        return $this;
    }

    public function hasGoogleCalendarTokens(): bool
    {
        return $this->holdsGoogleRefreshToken();
    }

    /** A refresh token set since the load counts, even before the flush seals it. */
    private function holdsGoogleRefreshToken(): bool
    {
        return isset($this->googleTokensChanged['google_refresh_token'])
            ? null !== $this->googleRefreshTokenPlain
            : null !== $this->googleRefreshToken;
    }

    /** @return array<string, ?string> */
    public function tokensToSeal(): array
    {
        return array_intersect_key($this->googleTokensPlain(), $this->googleTokensChanged);
    }

    public function storeSealedToken(string $usage, ?string $sealed): void
    {
        match ($usage) {
            'google_access_token' => $this->googleAccessToken = $sealed,
            'google_refresh_token' => $this->googleRefreshToken = $sealed,
            default => throw new \InvalidArgumentException(sprintf('No Google token is used for "%s".', $usage)),
        };
        unset($this->googleTokensChanged[$usage]);
    }

    /** @return array<string, ?string> */
    public function sealedTokens(): array
    {
        return [
            'google_access_token' => $this->googleAccessToken,
            'google_refresh_token' => $this->googleRefreshToken,
        ];
    }

    /**
     * @param array<string, ?string> $plain
     * @param list<string>           $unreadable
     */
    public function openTokens(array $plain, array $unreadable): void
    {
        $this->googleAccessTokenPlain = $plain['google_access_token'] ?? null;
        $this->googleRefreshTokenPlain = $plain['google_refresh_token'] ?? null;
        $this->googleTokensChanged = [];
        $this->googleTokensUnreadable = array_fill_keys($unreadable, true);
    }

    /** @return array<string, ?string> */
    private function googleTokensPlain(): array
    {
        return [
            'google_access_token' => $this->googleAccessTokenPlain,
            'google_refresh_token' => $this->googleRefreshTokenPlain,
        ];
    }

    private function readGoogleToken(string $usage): ?string
    {
        if (isset($this->googleTokensUnreadable[$usage])) {
            throw new ConnectedServiceTokenUnavailable('Google token for this user cannot be read: TOKEN_ENCRYPTION_KEY is missing or not the one it was sealed with. Reconnect Google once the key is fixed.');
        }

        return $this->googleTokensPlain()[$usage];
    }
}
