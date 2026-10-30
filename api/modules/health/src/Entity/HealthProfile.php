<?php

declare(strict_types=1);

namespace Maggie\Health\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Attribute\MercureCollection;
use Maggie\Health\Enum\ActivityLevel;
use Maggie\Health\Enum\NeedMode;
use Maggie\Health\Enum\Sex;
use Maggie\Health\Enum\StepSource;
use Maggie\Health\Repository\HealthProfileRepository;
use Maggie\Health\Service\HealthProfileCalculator;
use Symfony\Component\Uid\Ulid;

/**
 * The health profile (MAG-707), one row per user, created on first write:
 * what the day's need is computed from, or the need typed, and the settings of
 * the physical activity.
 *
 * Read and written through `/api/health/profile`, never through an API
 * Platform operation on an identifier-less path, which would build a fresh
 * entity on write (MeOperationContractTest). Not searched, so not indexed.
 *
 * The weight is not stored here: it is the latest weighing not set aside
 * (one series of weights, spec part 16). {@see self::observe()} gives the
 * profile that weight and the current year before it is read or published;
 * the basal metabolism and the day's need follow from them.
 */
#[ORM\Entity(repositoryClass: HealthProfileRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_health_profile_user', columns: ['user_id'])]
#[MercureCollection(self::COLLECTION)]
class HealthProfile implements MercurePublishable, OwnedByUserInterface
{
    public const COLLECTION = '/api/health-profile';

    public const MIN_HEIGHT_CM = 100;

    public const MAX_HEIGHT_CM = 250;

    public const MIN_ENTERED_NEED_KCAL = 1000;

    public const MAX_ENTERED_NEED_KCAL = 5000;

    public const ENTERED_NEED_STEP_KCAL = 50;

    public const DEFAULT_STEP_GOAL = 8000;

    public const STEP_GOAL_STEP = 500;

    public const MAX_STEP_GOAL = 50000;

    public const DEFAULT_SUGGESTION_WINDOW_START = '08:00';

    public const DEFAULT_SUGGESTION_WINDOW_END = '20:00';

    public const SESSION_MINUTES = [15, 20, 30];

    public const DEFAULT_SESSION_MINUTES = 20;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    #[ORM\Column(length: 8, nullable: true, enumType: Sex::class)]
    private ?Sex $sex = null;

    #[ORM\Column(type: Types::SMALLINT, nullable: true)]
    private ?int $birthYear = null;

    #[ORM\Column(type: Types::SMALLINT, nullable: true)]
    private ?int $heightCm = null;

    #[ORM\Column(length: 16, enumType: ActivityLevel::class, options: ['default' => 'sedentary'])]
    private ActivityLevel $activityLevel = ActivityLevel::Sedentary;

    #[ORM\Column(length: 16, enumType: NeedMode::class, options: ['default' => 'computed'])]
    private NeedMode $mode = NeedMode::Computed;

    #[ORM\Column(type: Types::SMALLINT, nullable: true)]
    private ?int $enteredNeedKcal = null;

    #[ORM\Column(options: ['default' => self::DEFAULT_STEP_GOAL])]
    private int $stepGoal = self::DEFAULT_STEP_GOAL;

    /** « HH:MM », local time. */
    #[ORM\Column(length: 5, options: ['default' => self::DEFAULT_SUGGESTION_WINDOW_START])]
    private string $suggestionWindowStart = self::DEFAULT_SUGGESTION_WINDOW_START;

    /** « HH:MM », local time, after the start. */
    #[ORM\Column(length: 5, options: ['default' => self::DEFAULT_SUGGESTION_WINDOW_END])]
    private string $suggestionWindowEnd = self::DEFAULT_SUGGESTION_WINDOW_END;

    #[ORM\Column(type: Types::SMALLINT, options: ['default' => self::DEFAULT_SESSION_MINUTES])]
    private int $sessionMinutes = self::DEFAULT_SESSION_MINUTES;

    #[ORM\Column(length: 16, enumType: StepSource::class, options: ['default' => 'none'])]
    private StepSource $stepSource = StepSource::None;

    /** The latest weighing not set aside, given by {@see self::observe()}: never stored. */
    private ?float $weightKg = null;

    private ?int $currentYear = null;

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

    public function getSex(): ?Sex
    {
        return $this->sex;
    }

    public function setSex(?Sex $sex): static
    {
        $this->sex = $sex;

        return $this;
    }

    public function getBirthYear(): ?int
    {
        return $this->birthYear;
    }

    public function setBirthYear(?int $birthYear): static
    {
        $this->birthYear = $birthYear;

        return $this;
    }

    public function getHeightCm(): ?int
    {
        return $this->heightCm;
    }

    public function setHeightCm(?int $heightCm): static
    {
        $this->heightCm = $heightCm;

        return $this;
    }

    public function getActivityLevel(): ActivityLevel
    {
        return $this->activityLevel;
    }

    public function setActivityLevel(ActivityLevel $activityLevel): static
    {
        $this->activityLevel = $activityLevel;

        return $this;
    }

    public function getMode(): NeedMode
    {
        return $this->mode;
    }

    public function setMode(NeedMode $mode): static
    {
        $this->mode = $mode;

        return $this;
    }

    public function getEnteredNeedKcal(): ?int
    {
        return $this->enteredNeedKcal;
    }

    public function setEnteredNeedKcal(?int $enteredNeedKcal): static
    {
        $this->enteredNeedKcal = $enteredNeedKcal;

        return $this;
    }

    public function getStepGoal(): int
    {
        return $this->stepGoal;
    }

    public function setStepGoal(int $stepGoal): static
    {
        $this->stepGoal = $stepGoal;

        return $this;
    }

    public function getSuggestionWindowStart(): string
    {
        return $this->suggestionWindowStart;
    }

    public function setSuggestionWindowStart(string $suggestionWindowStart): static
    {
        $this->suggestionWindowStart = $suggestionWindowStart;

        return $this;
    }

    public function getSuggestionWindowEnd(): string
    {
        return $this->suggestionWindowEnd;
    }

    public function setSuggestionWindowEnd(string $suggestionWindowEnd): static
    {
        $this->suggestionWindowEnd = $suggestionWindowEnd;

        return $this;
    }

    public function getSessionMinutes(): int
    {
        return $this->sessionMinutes;
    }

    public function setSessionMinutes(int $sessionMinutes): static
    {
        $this->sessionMinutes = $sessionMinutes;

        return $this;
    }

    public function getStepSource(): StepSource
    {
        return $this->stepSource;
    }

    public function setStepSource(StepSource $stepSource): static
    {
        $this->stepSource = $stepSource;

        return $this;
    }

    /** Gives the profile what it does not store: the latest weight and the current year. */
    public function observe(?float $weightKg, int $currentYear): static
    {
        $this->weightKg = $weightKg;
        $this->currentYear = $currentYear;

        return $this;
    }

    public function getWeightKg(): ?float
    {
        return $this->weightKg;
    }

    public function getAge(): ?int
    {
        return null === $this->currentYear ? null : HealthProfileCalculator::age($this->birthYear, $this->currentYear);
    }

    public function getBmrKcal(): ?int
    {
        return HealthProfileCalculator::bmrKcal($this->sex, $this->getAge(), $this->heightCm, $this->weightKg);
    }

    public function getNeedKcal(): ?int
    {
        return HealthProfileCalculator::needKcal($this->mode, $this->getBmrKcal(), $this->activityLevel, $this->enteredNeedKcal);
    }

    /**
     * The profile as the screens and Maggie read it, figures included.
     *
     * @return array<string, mixed>
     */
    public function describe(): array
    {
        $bmr = $this->getBmrKcal();
        $need = $this->getNeedKcal();

        return [
            'sex' => $this->sex?->value,
            'birthYear' => $this->birthYear,
            'age' => $this->getAge(),
            'heightCm' => $this->heightCm,
            'weightKg' => $this->weightKg,
            'activityLevel' => $this->activityLevel->value,
            'mode' => $this->mode->value,
            'enteredNeedKcal' => $this->enteredNeedKcal,
            'bmrKcal' => $bmr,
            'needKcal' => $need,
            'factor' => HealthProfileCalculator::factor($this->mode, $bmr, $need, $this->activityLevel),
            'stepGoal' => $this->stepGoal,
            'suggestionWindowStart' => $this->suggestionWindowStart,
            'suggestionWindowEnd' => $this->suggestionWindowEnd,
            'sessionMinutes' => $this->sessionMinutes,
            'stepSource' => $this->stepSource->value,
        ];
    }

    /**
     * Always the whole profile: the figures move with any field (and with a
     * weighing), so a client replaces what it holds rather than merging.
     *
     * @return array<string, mixed>
     */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return ['id' => (string) $this->id] + $this->describe();
    }
}
