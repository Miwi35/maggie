<?php

namespace Maggie\Core\Entity;

use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Get;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Core\Repository\UserPreferenceRepository;
use Maggie\Core\State\UserPreferenceItemProvider;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
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

    /** Quiet hours (MAG-687), "HH:MM" in the user's timezone: chat pushes are silent in the window. Both set or both null. */
    #[ORM\Column(length: 5, nullable: true)]
    private ?string $quietHoursStart = null;

    #[ORM\Column(length: 5, nullable: true)]
    private ?string $quietHoursEnd = null;

    /** The two daily rendezvous (MAG-787), laid down by the agent's code: "HH:MM" in the user's timezone. */
    #[ORM\Column(options: ['default' => true])]
    private bool $morningDigestEnabled = true;

    #[ORM\Column(length: 5, options: ['default' => '07:00'])]
    private string $morningDigestTime = '07:00';

    #[ORM\Column(options: ['default' => true])]
    private bool $eveningReviewEnabled = true;

    #[ORM\Column(length: 5, options: ['default' => '21:30'])]
    private string $eveningReviewTime = '21:30';

    /** Working hours (MAG-790), "HH:MM" in the user's timezone: the agent's tone follows them. Start before end. */
    #[ORM\Column(length: 5, options: ['default' => '09:00'])]
    private string $workStartTime = '09:00';

    #[ORM\Column(length: 5, options: ['default' => '18:00'])]
    private string $workEndTime = '18:00';

    /** @var list<int> ISO days, 1 = Monday … 7 = Sunday */
    #[ORM\Column(type: Types::JSON, options: ['default' => '[1,2,3,4,5]'])]
    private array $workDays = [1, 2, 3, 4, 5];

    /** The day the user said they fell sick (MAG-810), in their timezone; null when they are not sick. Kept until they say it is over. */
    #[ORM\Column(type: Types::DATE_IMMUTABLE, nullable: true)]
    #[ApiProperty(openapiContext: ['type' => ['string', 'null'], 'format' => 'date', 'example' => '2037-01-01'])]
    #[Context(normalizationContext: [DateTimeNormalizer::FORMAT_KEY => 'Y-m-d'])]
    private ?\DateTimeImmutable $sickSince = null;

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

    public function getQuietHoursStart(): ?string
    {
        return $this->quietHoursStart;
    }

    public function getQuietHoursEnd(): ?string
    {
        return $this->quietHoursEnd;
    }

    public function setQuietHoursStart(?string $quietHoursStart): static
    {
        $this->quietHoursStart = $quietHoursStart;

        return $this;
    }

    public function setQuietHoursEnd(?string $quietHoursEnd): static
    {
        $this->quietHoursEnd = $quietHoursEnd;

        return $this;
    }

    public function getMorningDigestEnabled(): bool
    {
        return $this->morningDigestEnabled;
    }

    public function setMorningDigestEnabled(bool $morningDigestEnabled): static
    {
        $this->morningDigestEnabled = $morningDigestEnabled;

        return $this;
    }

    public function getMorningDigestTime(): string
    {
        return $this->morningDigestTime;
    }

    public function setMorningDigestTime(string $morningDigestTime): static
    {
        $this->morningDigestTime = $morningDigestTime;

        return $this;
    }

    public function getEveningReviewEnabled(): bool
    {
        return $this->eveningReviewEnabled;
    }

    public function setEveningReviewEnabled(bool $eveningReviewEnabled): static
    {
        $this->eveningReviewEnabled = $eveningReviewEnabled;

        return $this;
    }

    public function getEveningReviewTime(): string
    {
        return $this->eveningReviewTime;
    }

    public function setEveningReviewTime(string $eveningReviewTime): static
    {
        $this->eveningReviewTime = $eveningReviewTime;

        return $this;
    }

    public function getWorkStartTime(): string
    {
        return $this->workStartTime;
    }

    public function setWorkStartTime(string $workStartTime): static
    {
        $this->workStartTime = $workStartTime;

        return $this;
    }

    public function getWorkEndTime(): string
    {
        return $this->workEndTime;
    }

    public function setWorkEndTime(string $workEndTime): static
    {
        $this->workEndTime = $workEndTime;

        return $this;
    }

    /** @return list<int> */
    public function getWorkDays(): array
    {
        return $this->workDays;
    }

    /** @param list<int> $workDays */
    public function setWorkDays(array $workDays): static
    {
        $this->workDays = $workDays;

        return $this;
    }

    public function getSickSince(): ?\DateTimeImmutable
    {
        return $this->sickSince;
    }

    public function setSickSince(?\DateTimeImmutable $sickSince): static
    {
        $this->sickSince = $sickSince;

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
            'quietHoursStart' => $this->quietHoursStart,
            'quietHoursEnd' => $this->quietHoursEnd,
            'morningDigestEnabled' => $this->morningDigestEnabled,
            'morningDigestTime' => $this->morningDigestTime,
            'eveningReviewEnabled' => $this->eveningReviewEnabled,
            'eveningReviewTime' => $this->eveningReviewTime,
            'workStartTime' => $this->workStartTime,
            'workEndTime' => $this->workEndTime,
            'workDays' => $this->workDays,
            'sickSince' => $this->sickSince?->format('Y-m-d'),
        ], $changedProperties);
    }
}
