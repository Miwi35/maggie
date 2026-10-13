<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\UseCase\GetIndependenceCounter;
use Maggie\Finance\UseCase\MeasureMonthlyLifestyle;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The independence counter measures the real (decision 15, MAG-307): a
 * recurring series moves neither the measured lifestyle nor the measured
 * coverage. Next to it, `projectedCoveragePercent` reads the rentes the
 * confirmed series still expect over the three months ahead.
 *
 * Read on 2027-05-01: the sample is February to April, with 300 € spent and
 * 100 € of rente landed each month — a lifestyle of 300 €, 33 % covered.
 */
class GetIndependenceCounterProjectionTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use RecurringProjectionsWorld;
    use SavingsAdvancesWorld;

    private const string THIS_MONTH = '2027-05-01';

    private GetIndependenceCounter $counter;
    private MeasureMonthlyLifestyle $lifestyle;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->counter = self::getContainer()->get(GetIndependenceCounter::class);
        $this->lifestyle = self::getContainer()->get(MeasureMonthlyLifestyle::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('recurring_projections.yaml');

        foreach (['2027-02-10', '2027-03-10', '2027-04-10'] as $day) {
            $this->add('checking', -30000, $day, 'leisure', 'Sorties');
            $this->add('checking', 10000, $day, 'rentes', 'Loyer perçu');
        }
    }

    /** @return array<string, mixed> */
    private function read(): array
    {
        return $this->counter->execute($this->user(), new \DateTimeImmutable(self::THIS_MONTH));
    }

    public function testASeriesOf900EurosAMonthChangesNeitherTheMeasuredLifestyleNorTheCoverage(): void
    {
        $before = $this->read();
        $lifestyleBefore = $this->lifestyle->execute($this->user(), new \DateTimeImmutable(self::THIS_MONTH));
        self::assertSame(30000, $lifestyleBefore);
        self::assertSame(33, $before['coveragePercent']);

        $rent = $this->series('Loyer', -90000, '2027-01-05');
        $this->settle($rent, '2027-05-05', -90000, TransactionStatus::Spent);

        $after = $this->read();

        self::assertSame($lifestyleBefore, $this->lifestyle->execute($this->user(), new \DateTimeImmutable(self::THIS_MONTH)));
        self::assertSame($before, $after, 'an expense series is neither measured nor projected as a rente');
    }

    public function testExpectedRentesRaiseTheProjectionAndLeaveTheMeasureAlone(): void
    {
        $before = $this->read();
        self::assertSame(0, $before['projectedCoveragePercent']);

        $this->series('Loyer perçu', 15000, '2027-01-05', categoryRef: 'rentes');

        $after = $this->read();

        // May to July: three occurrences of 150 €, a monthly 150 € against 300 €.
        self::assertSame(15000, $after['projectedPassiveIncomeCents']);
        self::assertSame(50, $after['projectedCoveragePercent']);
        self::assertSame($before['coveragePercent'], $after['coveragePercent']);
        self::assertSame($before['passiveIncomeCents'], $after['passiveIncomeCents']);
        self::assertSame($before['lifestyleCents'], $after['lifestyleCents']);
    }

    public function testARenteAlreadyLandedIsNotCountedTwice(): void
    {
        $rente = $this->series('Loyer perçu', 15000, '2027-01-05', categoryRef: 'rentes');
        $this->settle($rente, '2027-05-05', 15000, TransactionStatus::Spent);

        // May landed, June and July expected: 3 × 150 € over three months.
        self::assertSame(15000, $this->read()['projectedPassiveIncomeCents']);
    }

    public function testASeriesOutsideTheRenteCategoriesIsNotARente(): void
    {
        $this->series('Remboursement', 15000, '2027-01-05', categoryRef: 'leisure');

        self::assertSame(0, $this->read()['projectedCoveragePercent']);
    }
}
