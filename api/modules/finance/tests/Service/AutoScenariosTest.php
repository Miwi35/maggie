<?php

namespace Maggie\Finance\Tests\Service;

use Maggie\Finance\Entity\Project;
use Maggie\Finance\Enum\ProjectWellbeing;
use Maggie\Finance\Service\AutoScenarios;
use Maggie\Finance\Service\IndependenceBase;
use Maggie\Finance\Service\PlanningInputs;
use Maggie\Finance\Service\ScenarioCalculator;
use Maggie\Finance\Service\ScenarioSpec;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The three scenarios laid out on their own (MAG-550, spec rule 5), on a fixed
 * world: 1 000 € a month of capacity, a complete cushion, a train de vie of
 * 300 € a month and no rente yet, M0 = 2026-10.
 *
 * What each project weighs on N (months it pushes N back when it comes last):
 * Dentiste 3, Cinéma 3, Cuisine 5, Japon 6, Voiture 10.
 *
 * Exemption: N/A — pure computation, nothing is written.
 */
class AutoScenariosTest extends KernelTestCase
{
    private ScenarioCalculator $calculator;
    private AutoScenarios $auto;
    private IndependenceBase $base;

    /** @var array<string, Project> by name */
    private array $projects = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->calculator = self::getContainer()->get(ScenarioCalculator::class);
        $this->auto = new AutoScenarios($this->calculator);
        $this->base = new IndependenceBase(0, 30000);
    }

    /** @param list<array{string, int, ProjectWellbeing}> $definitions in the user's order */
    private function inputs(array $definitions): PlanningInputs
    {
        $this->projects = [];
        foreach ($definitions as $position => [$name, $cents, $wellbeing]) {
            $this->projects[$name] = (new Project())->setName($name)->setAmountCents($cents)->setWellbeing($wellbeing)->setPosition($position);
        }

        return new PlanningInputs(
            new \DateTimeImmutable('2026-10-21 12:00:00'),
            100000,
            true,
            ['state' => 'complete', 'deficitCents' => 0, 'monthlyRechargeCents' => 0],
            0,
            array_values($this->projects),
        );
    }

    private function standard(): PlanningInputs
    {
        return $this->inputs([
            ['Dentiste', 200000, ProjectWellbeing::Essential],
            ['Voiture', 900000, ProjectWellbeing::High],
            ['Cuisine', 400000, ProjectWellbeing::Medium],
            ['Japon', 500000, ProjectWellbeing::Comfort],
            ['Cinéma', 150000, ProjectWellbeing::Comfort],
        ]);
    }

    /** @return list<string> the project names, in the spec's order */
    private function names(ScenarioSpec $spec): array
    {
        $byId = [];
        foreach ($this->projects as $name => $project) {
            $byId[(string) $project->getId()] = $name;
        }

        return array_map(static fn (string $id): string => $byId[$id], $spec->projectIds);
    }

    private function nDate(PlanningInputs $inputs, ScenarioSpec $spec): ?string
    {
        return $this->calculator->evaluate($inputs, $this->base, $spec)['nDate'];
    }

    public function testRapidPutsOffTheComfortProjectsThatDelayNByMoreThanThreeMonths(): void
    {
        $inputs = $this->standard();

        $specs = $this->auto->specs($inputs, $this->base);

        // Lightest first; Japon is Comfort and weighs 6 months: last. Cinéma is
        // Comfort too but weighs 3, which is not more than 3: it keeps its place.
        self::assertSame(['Dentiste', 'Cinéma', 'Cuisine', 'Voiture', 'Japon'], $this->names($specs[AutoScenarios::RAPID]));
    }

    public function testBalancedKeepsTheEssentialAndHighProjectsFirstInTheUsersOrder(): void
    {
        $inputs = $this->standard();

        $specs = $this->auto->specs($inputs, $this->base);

        self::assertSame(['Dentiste', 'Voiture', 'Cinéma', 'Cuisine', 'Japon'], $this->names($specs[AutoScenarios::BALANCED]));
    }

    public function testQualityOfLifePutsTheProjectsThatCountMostFirst(): void
    {
        $inputs = $this->standard();

        $specs = $this->auto->specs($inputs, $this->base);

        // Scores 4, 3, 2, then the two Comfort (1) in the user's order.
        self::assertSame(['Dentiste', 'Voiture', 'Cuisine', 'Japon', 'Cinéma'], $this->names($specs[AutoScenarios::QUALITY]));
    }

    public function testEveryAutoScenarioKeepsAllTheProjectsAndNoneIsWorseOnNThanTheUsersOrder(): void
    {
        $inputs = $this->standard();
        $own = $this->nDate($inputs, ScenarioSpec::ofProjects($inputs->projects));

        foreach ($this->auto->specs($inputs, $this->base) as $key => $spec) {
            self::assertCount(5, array_unique($spec->projectIds), $key);
            self::assertLessThanOrEqual($own, $this->nDate($inputs, $spec), $key);
        }
    }

    public function testRapidHasTheEarliestNOfTheThreeAndTheQualityTheBestWellbeing(): void
    {
        $inputs = $this->standard();
        $metrics = [];
        foreach ($this->auto->specs($inputs, $this->base) as $key => $spec) {
            $metrics[$key] = $this->calculator->evaluate($inputs, $this->base, $spec)['metrics'];
        }

        foreach ([AutoScenarios::BALANCED, AutoScenarios::QUALITY] as $other) {
            self::assertLessThanOrEqual($metrics[$other]['nDate'], $metrics[AutoScenarios::RAPID]['nDate']);
        }
        // Everything is bought within the planning: the same 11 points of wellbeing, 4 + 3 + 2 + 1 + 1.
        self::assertSame(11, $metrics[AutoScenarios::QUALITY]['wellbeing']);
        foreach ($metrics as $row) {
            self::assertGreaterThanOrEqual(11, $row['wellbeing']);
        }
    }

    public function testRapidIsTheUsersOrderWhenNothingWeighsMoreThanThreeMonths(): void
    {
        $inputs = $this->inputs([
            ['Livre', 100000, ProjectWellbeing::Comfort],
            ['Plantes', 100000, ProjectWellbeing::Comfort],
            ['Casque', 100000, ProjectWellbeing::Comfort],
        ]);

        $specs = $this->auto->specs($inputs, $this->base);

        self::assertSame(['Livre', 'Plantes', 'Casque'], $this->names($specs[AutoScenarios::RAPID]));
        self::assertSame(['Livre', 'Plantes', 'Casque'], $this->names($specs[AutoScenarios::BALANCED]));
        self::assertSame(['Livre', 'Plantes', 'Casque'], $this->names($specs[AutoScenarios::QUALITY]));
    }

    public function testBalancedNeverMovesAnEssentialOrHighProjectBehindAnotherKind(): void
    {
        $inputs = $this->inputs([
            ['Japon', 500000, ProjectWellbeing::Comfort],
            ['Toit', 800000, ProjectWellbeing::Essential],
            ['Cinéma', 100000, ProjectWellbeing::Comfort],
            ['Dentiste', 200000, ProjectWellbeing::High],
        ]);

        $names = $this->names($this->auto->specs($inputs, $this->base)[AutoScenarios::BALANCED]);

        self::assertSame(['Toit', 'Dentiste'], \array_slice($names, 0, 2));
    }

    public function testWithoutProjectsThereIsNothingToOrder(): void
    {
        $specs = $this->auto->specs($this->inputs([]), $this->base);

        self::assertSame(['rapid', 'balanced', 'quality'], array_keys($specs));
        foreach ($specs as $spec) {
            self::assertSame([], $spec->projectIds);
        }
    }
}
