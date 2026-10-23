<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Scenario;
use Maggie\Finance\Enum\ScenarioKind;
use Maggie\Finance\Enum\ScenarioStatus;

/**
 * A scenario as the catalog serves it: a stored one, or an automatic one not
 * stored yet (its id is `auto-<key>`; it becomes a stored one the moment the
 * user makes it current).
 */
final readonly class ScenarioItem
{
    public const AUTO_PREFIX = 'auto-';

    /**
     * @param list<string>               $projectIds
     * @param list<array<string, mixed>> $repayments
     * @param list<string>               $placementIds
     */
    public function __construct(
        public string $id,
        public string $name,
        public ScenarioKind $kind,
        public ScenarioStatus $status,
        public bool $current,
        public array $projectIds,
        public array $repayments,
        public array $placementIds,
        public ?int $monthlySaving,
        public ?Scenario $stored,
        public ?string $autoKey = null,
    ) {
    }

    public static function stored(Scenario $scenario): self
    {
        $spec = ScenarioSpec::fromScenario($scenario);

        return new self(
            (string) $scenario->getId(),
            $scenario->getName(),
            $scenario->getKind(),
            $scenario->getStatus(),
            $scenario->isCurrent(),
            $spec->projectIds,
            $spec->repayments,
            $spec->placementIds,
            $spec->monthlySaving,
            $scenario,
            $scenario->getAutoKey(),
        );
    }

    public static function automatic(string $key, ScenarioSpec $spec): self
    {
        return new self(
            self::AUTO_PREFIX.$key,
            AutoScenarios::NAMES[$key],
            ScenarioKind::Auto,
            ScenarioStatus::Draft,
            false,
            $spec->projectIds,
            [],
            [],
            null,
            null,
            $key,
        );
    }

    public function isVirtual(): bool
    {
        return null === $this->stored;
    }

    public function spec(): ScenarioSpec
    {
        return new ScenarioSpec($this->projectIds, $this->monthlySaving, $this->repayments, $this->placementIds);
    }
}
