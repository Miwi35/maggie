<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Enum\Equipment;
use Maggie\Health\Enum\ExerciseKind;
use Maggie\Health\Enum\ExerciseUnit;
use Maggie\Health\Enum\LadderBranch;
use Maggie\Health\Enum\MovementPattern;
use Maggie\Health\Enum\MuscleGroup;
use Maggie\Health\Enum\NervousCost;
use Symfony\Component\Yaml\Yaml;

/**
 * The exercise reference data (MAG-774): exercises and progression ladders read
 * from `config/reference/*.yaml`, validated once on load. No table, no migration.
 *
 * @phpstan-type Exercise array{slug: string, name: string, pattern: string, primaryMuscles: list<string>, secondaryMuscles: list<string>, kind: string, equipment: list<string>, nervousCost: string, eccentric: bool, cue: string, unit: string, perSide: bool, loadable: bool}
 * @phpstan-type Step array{position: int, exercise: string, targetSets: int, targetMin: int, targetMax: int, advanceAt: int, doubleProgression: bool, softer: string|null}
 * @phpstan-type Ladder array{slug: string, pattern: string, name: string, branch: string, steps: list<Step>}
 */
final class ExerciseCatalog
{
    public const CUE_MAX_LENGTH = 60;

    /** @var array<string, Exercise>|null */
    private ?array $exercises = null;

    /** @var array<string, Ladder>|null */
    private ?array $ladders = null;

    public function __construct(private readonly string $referenceDir)
    {
    }

    /**
     * @param list<string>|null $equipment keep only what can be done with this gear (nothing needed always qualifies)
     *
     * @return list<array<string, mixed>>
     */
    public function exercises(?string $pattern = null, ?array $equipment = null, ?string $ladder = null): array
    {
        $this->load();
        $inLadder = null;
        if (null !== $ladder) {
            $inLadder = [];
            foreach ($this->ladders[$ladder]['steps'] ?? [] as $step) {
                $inLadder[$step['exercise']] = true;
            }
        }

        $result = [];
        foreach ($this->exercises ?? [] as $slug => $exercise) {
            if (null !== $pattern && $exercise['pattern'] !== $pattern) {
                continue;
            }
            if (null !== $equipment && [] !== array_diff($exercise['equipment'], $equipment)) {
                continue;
            }
            if (null !== $inLadder && !isset($inLadder[$slug])) {
                continue;
            }
            $result[] = $this->withLadders($exercise);
        }

        return $result;
    }

    /** @return array<string, mixed>|null */
    public function exercise(string $slug): ?array
    {
        $this->load();

        return isset($this->exercises[$slug]) ? $this->withLadders($this->exercises[$slug]) : null;
    }

    /** @return list<Ladder> */
    public function ladders(): array
    {
        $this->load();

        return array_values($this->ladders ?? []);
    }

    /** @return Ladder|null */
    public function ladder(string $slug): ?array
    {
        $this->load();

        return $this->ladders[$slug] ?? null;
    }

    /**
     * @param Exercise $exercise
     *
     * @return array<string, mixed>
     */
    private function withLadders(array $exercise): array
    {
        $positions = [];
        foreach ($this->ladders ?? [] as $ladder) {
            foreach ($ladder['steps'] as $step) {
                if ($step['exercise'] === $exercise['slug']) {
                    $positions[] = ['ladder' => $ladder['slug'], 'branch' => $ladder['branch'], 'position' => $step['position']];
                }
            }
        }

        return $exercise + ['ladders' => $positions];
    }

    private function load(): void
    {
        if (null !== $this->exercises) {
            return;
        }

        $exercises = [];
        foreach ($this->read('exercises.yaml', 'exercises') as $row) {
            $exercise = $this->exerciseRow($row);
            $exercises[$exercise['slug']] = $exercise;
        }

        $ladders = [];
        foreach ($this->read('ladders.yaml', 'ladders') as $row) {
            $ladder = $this->ladderRow($row, $exercises);
            $ladders[$ladder['slug']] = $ladder;
        }

        $this->exercises = $exercises;
        $this->ladders = $ladders;
    }

    /** @return list<array<string, mixed>> */
    private function read(string $file, string $key): array
    {
        $data = Yaml::parseFile($this->referenceDir.'/'.$file);
        if (!\is_array($data) || !\is_array($data[$key] ?? null)) {
            throw new \LogicException(\sprintf('%s: missing "%s" list.', $file, $key));
        }

        /* @var list<array<string, mixed>> */
        return $data[$key];
    }

    /**
     * @param array<string, mixed> $row
     *
     * @return Exercise
     */
    private function exerciseRow(array $row): array
    {
        $slug = $row['slug'] ?? null;
        if (!\is_string($slug) || 1 !== preg_match('/^[a-z0-9]+(-[a-z0-9]+)*$/', $slug)) {
            throw new \LogicException('exercises.yaml: invalid slug '.json_encode($slug).'.');
        }
        $cue = (string) ($row['cue'] ?? '');
        if ('' === $cue || mb_strlen($cue) > self::CUE_MAX_LENGTH) {
            throw new \LogicException(\sprintf('exercises.yaml: %s: cue must be 1 to %d characters.', $slug, self::CUE_MAX_LENGTH));
        }

        $exercise = [
            'slug' => $slug,
            'name' => (string) ($row['name'] ?? ''),
            'pattern' => MovementPattern::from((string) ($row['pattern'] ?? ''))->value,
            'primaryMuscles' => $this->enumList(MuscleGroup::class, $row['primaryMuscles'] ?? []),
            'secondaryMuscles' => $this->enumList(MuscleGroup::class, $row['secondaryMuscles'] ?? []),
            'kind' => ExerciseKind::from((string) ($row['kind'] ?? ''))->value,
            'equipment' => $this->enumList(Equipment::class, $row['equipment'] ?? []),
            'nervousCost' => NervousCost::from((string) ($row['nervousCost'] ?? ''))->value,
            'eccentric' => (bool) ($row['eccentric'] ?? false),
            'cue' => $cue,
            'unit' => ExerciseUnit::from((string) ($row['unit'] ?? ''))->value,
            'perSide' => (bool) ($row['perSide'] ?? false),
            'loadable' => (bool) ($row['loadable'] ?? false),
        ];
        if ('' === $exercise['name'] || [] === $exercise['primaryMuscles']) {
            throw new \LogicException(\sprintf('exercises.yaml: %s: name and primaryMuscles are required.', $slug));
        }

        return $exercise;
    }

    /**
     * @param array<string, mixed>    $row
     * @param array<string, Exercise> $exercises
     *
     * @return Ladder
     */
    private function ladderRow(array $row, array $exercises): array
    {
        $slug = (string) ($row['slug'] ?? '');
        $pattern = MovementPattern::from((string) ($row['pattern'] ?? ''))->value;
        $steps = [];
        foreach (array_values((array) ($row['steps'] ?? [])) as $index => $step) {
            $exercise = (string) ($step['exercise'] ?? '');
            $softer = $step['softer'] ?? null;
            foreach ([$exercise, $softer] as $reference) {
                if (null !== $reference && !isset($exercises[$reference])) {
                    throw new \LogicException(\sprintf('ladders.yaml: %s: unknown exercise "%s".', $slug, $reference));
                }
            }
            $steps[] = [
                'position' => $index + 1,
                'exercise' => $exercise,
                'targetSets' => (int) $step['targetSets'],
                'targetMin' => (int) $step['targetMin'],
                'targetMax' => (int) $step['targetMax'],
                'advanceAt' => (int) $step['advanceAt'],
                'doubleProgression' => (bool) ($step['doubleProgression'] ?? false),
                'softer' => null === $softer ? null : (string) $softer,
            ];
        }
        if ([] === $steps) {
            throw new \LogicException(\sprintf('ladders.yaml: %s has no step.', $slug));
        }

        return [
            'slug' => $slug,
            'pattern' => $pattern,
            'name' => (string) ($row['name'] ?? ''),
            'branch' => LadderBranch::from((string) ($row['branch'] ?? ''))->value,
            'steps' => $steps,
        ];
    }

    /**
     * @param class-string<\BackedEnum> $enum
     *
     * @return list<string>
     */
    private function enumList(string $enum, mixed $values): array
    {
        $list = [];
        foreach ((array) $values as $value) {
            $list[] = $enum::from((string) $value)->value;
        }

        return $list;
    }
}
