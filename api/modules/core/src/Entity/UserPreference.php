<?php

namespace Maggie\Core\Entity;

use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Get;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Core\Repository\UserPreferenceRepository;
use Maggie\Core\State\UserPreferenceItemProvider;
use Symfony\Component\Uid\Ulid;

#[ORM\Entity(repositoryClass: UserPreferenceRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_user_preference_user', columns: ['user_id'])]
// Writes go through UpdateUserPreferenceController: an API Platform PATCH on
// an identifier-less operation builds a fresh object instead of populating the
// provider's, which used to make every update fail.
#[ApiResource(operations: [
    new Get(uriTemplate: '/user_preferences/me', provider: UserPreferenceItemProvider::class),
])]
class UserPreference implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    #[ORM\Column(length: 10, options: ['default' => 'system'])]
    private string $theme = 'system';

    #[ORM\Column(length: 10, options: ['default' => 'fr'])]
    private string $locale = 'fr';

    #[ORM\Column(length: 50, options: ['default' => 'Europe/Paris'])]
    private string $timezone = 'Europe/Paris';

    #[ORM\Column(length: 10, options: ['default' => 'month'])]
    private string $defaultCalendarView = 'month';

    #[ORM\Column(length: 100, nullable: true)]
    private ?string $defaultCity = null;

    /** @var list<string> */
    #[ORM\Column(type: Types::JSON)]
    private array $enabledAgendaIds = [];

    #[ORM\Column(options: ['default' => true])]
    private bool $notificationsEnabled = true;

    #[ORM\Column]
    private \DateTimeImmutable $createdAt;

    #[ORM\Column]
    private \DateTimeImmutable $updatedAt;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->createdAt = new \DateTimeImmutable();
        $this->updatedAt = new \DateTimeImmutable();
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

    public function getTheme(): string
    {
        return $this->theme;
    }

    public function setTheme(string $theme): static
    {
        $this->theme = $theme;

        return $this;
    }

    public function getLocale(): string
    {
        return $this->locale;
    }

    public function setLocale(string $locale): static
    {
        $this->locale = $locale;

        return $this;
    }

    public function getTimezone(): string
    {
        return $this->timezone;
    }

    public function setTimezone(string $timezone): static
    {
        $this->timezone = $timezone;

        return $this;
    }

    public function getDefaultCalendarView(): string
    {
        return $this->defaultCalendarView;
    }

    public function setDefaultCalendarView(string $defaultCalendarView): static
    {
        $this->defaultCalendarView = $defaultCalendarView;

        return $this;
    }

    public function getDefaultCity(): ?string
    {
        return $this->defaultCity;
    }

    public function setDefaultCity(?string $defaultCity): static
    {
        $this->defaultCity = $defaultCity;

        return $this;
    }

    /** @return list<string> */
    public function getEnabledAgendaIds(): array
    {
        return $this->enabledAgendaIds;
    }

    /** @param list<string> $enabledAgendaIds */
    public function setEnabledAgendaIds(array $enabledAgendaIds): static
    {
        $this->enabledAgendaIds = $enabledAgendaIds;

        return $this;
    }

    public function isNotificationsEnabled(): bool
    {
        return $this->notificationsEnabled;
    }

    public function setNotificationsEnabled(bool $notificationsEnabled): static
    {
        $this->notificationsEnabled = $notificationsEnabled;

        return $this;
    }

    public function getCreatedAt(): \DateTimeImmutable
    {
        return $this->createdAt;
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

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'theme' => $this->theme,
            'locale' => $this->locale,
            'timezone' => $this->timezone,
            'defaultCalendarView' => $this->defaultCalendarView,
            'defaultCity' => $this->defaultCity,
            'enabledAgendaIds' => $this->enabledAgendaIds,
            'notificationsEnabled' => $this->notificationsEnabled,
        ], $changedProperties);
    }
}
