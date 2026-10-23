<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Entity\ProjectInvestment;
use Maggie\Finance\Enum\PlacementFrequency;
use Maggie\Finance\UseCase\GetIndependenceCounter;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The rent of an investment and the capital of a placement count as rentes in
 * the independence projection (MAG-558), never in the measured coverage.
 *
 * The world is MAG-549's: rentes 340 €, train de vie 1 860 €, capacity 2 000 €
 * a month, one project (the car, 10 000 €) that five months of capacity buy.
 *
 * Exemption: N/A — DB state, Mercure and Elasticsearch, it is a pure read.
 */
class GetIndependenceRentesTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private GetIndependenceCounter $counter;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->counter = self::getContainer()->get(GetIndependenceCounter::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures(__DIR__.'/../Api/fixtures/independence_projection.yaml');
    }

    /** @return array<string, mixed> */
    private function read(string $as = 'test_user'): array
    {
        return $this->counter->execute($this->getFixture($as));
    }

    private static function inMonths(int $months): string
    {
        return (new \DateTimeImmutable('first day of this month'))->modify(sprintf('+%d months', $months))->format('Y-m');
    }

    private function rentTheCar(int $rentCents): void
    {
        $car = $this->getFixture('car');
        $investment = (new ProjectInvestment())->setProject($car)->setMonthlyRentCents($rentCents);
        $car->setInvestment($investment);
        $this->em->persist($investment);
        $this->em->flush();
    }

    private function place(string $name, int $initialCents, int $years, string $as = 'test_user'): Placement
    {
        $placement = (new Placement())
            ->setName($name)
            ->setInitial($initialCents)
            ->setDeposit(0)
            ->setFrequency(PlacementFrequency::Once)
            ->setAnnualRate(0)
            ->setYears($years)
            ->setUser($this->getFixture($as));
        $this->em->persist($placement);
        $this->em->flush();

        return $placement;
    }

    public function testRentingTheBoxAt160EurosBringsNAndTheFirstMilestoneForward(): void
    {
        $before = $this->read();
        $this->rentTheCar(16000);
        $after = $this->read();

        // The car is bought in the 5th month (index 4), the rent counts from the next.
        self::assertSame(self::inMonths(233), $before['projection']['nDate']);
        self::assertLessThan($before['projection']['nDate'], $after['projection']['nDate']);
        self::assertLessThan($before['projection']['milestones'][25], $after['projection']['milestones'][25]);
        self::assertSame(self::inMonths(5), $after['projection']['milestones'][25]);
        // The curve is untouched until the rent starts.
        self::assertSame($before['projection']['curve'][4], $after['projection']['curve'][4]);
        self::assertGreaterThan($before['projection']['curve'][5]['coverage'], $after['projection']['curve'][5]['coverage']);
    }

    public function testTheRentIsNotCountedInTheMeasuredCoverage(): void
    {
        $before = $this->read();
        $this->rentTheCar(16000);
        $after = $this->read();

        foreach (['coveragePercent', 'passiveIncomeCents', 'projectedPassiveIncomeCents', 'milestones', 'nextMilestonePercent'] as $key) {
            self::assertSame($before[$key], $after[$key], $key);
        }
    }

    public function testAPlacementOf71420EurosCounts238EurosAMonthAtItsTerm(): void
    {
        $before = $this->read();
        $placement = $this->place('PEA', 7142000, 10);
        $after = $this->read();

        $placements = $after['renteSources']['placements'];
        self::assertSame(1, $placements['count']);
        self::assertSame(23807, $placements['monthlyRentCents']);
        self::assertSame(
            [['placementId' => (string) $placement->getId(), 'name' => 'PEA', 'monthlyRentCents' => 23807, 'fromMonth' => self::inMonths(120), 'coveragePoints' => 12]],
            $placements['items'],
        );
        self::assertSame(400, $after['renteSources']['withdrawalRateBasisPoints']);

        // Nothing changes before the term, then the rente is 238 € higher.
        self::assertSame($before['projection']['curve'][119], $after['projection']['curve'][119]);
        self::assertGreaterThan($before['projection']['curve'][120]['coverage'], $after['projection']['curve'][120]['coverage']);
        self::assertLessThan($before['projection']['nDate'], $after['projection']['nDate']);
    }

    public function testTheRenteSourcesListEachInvestmentWithWhatItAddsInPoints(): void
    {
        $this->rentTheCar(16000);

        $investments = $this->read()['renteSources']['investments'];

        self::assertSame(1, $investments['count']);
        self::assertSame(16000, $investments['monthlyRentCents']);
        self::assertSame('Voiture', $investments['items'][0]['name']);
        self::assertSame(self::inMonths(5), $investments['items'][0]['fromMonth']);
        self::assertNull($investments['items'][0]['untilMonth']);
        // 160 € against a train de vie of 1 860 €: 8 points.
        self::assertSame(8, $investments['items'][0]['coveragePoints']);
    }

    public function testWithoutPlacementNorInvestmentThereIsNothingToList(): void
    {
        $sources = $this->read()['renteSources'];

        self::assertSame(['count' => 0, 'monthlyRentCents' => 0, 'items' => []], $sources['placements']);
        self::assertSame(['count' => 0, 'monthlyRentCents' => 0, 'items' => []], $sources['investments']);
    }

    public function testAUserNeverCountsThePlacementOfAnother(): void
    {
        $this->place('PEA du voisin', 7142000, 10, 'other_user');

        self::assertSame(0, $this->read()['renteSources']['placements']['count']);
        self::assertSame(1, $this->read('other_user')['renteSources']['placements']['count']);
    }
}
