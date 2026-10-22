<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Service\IndependenceProjector;
use Maggie\Finance\UseCase\GetIndependenceCounter;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * What the projection reads of the user's world (MAG-549): the measured rentes
 * and lifestyle, and what the planning leaves of the capacity. The projector's
 * arithmetic is IndependenceProjectorTest's.
 *
 * The world: rentes 340 €, train de vie 1 860 €, capacity 2 000 € a month, a
 * complete cushion and one project of 10 000 € — five months of that capacity.
 *
 * Exemption: N/A — DB state, Mercure and Elasticsearch, it is a pure read.
 */
class GetIndependenceProjectionTest extends KernelTestCase
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
    private function projection(string $as = 'test_user'): array
    {
        return $this->counter->execute($this->getFixture($as))['projection'];
    }

    private static function inMonths(int $months): string
    {
        return (new \DateTimeImmutable('first day of this month'))->modify(sprintf('+%d months', $months))->format('Y-m');
    }

    public function testItDatesNFromTheMeasuredRentesAndWhatThePlanningLeavesOfTheCapacity(): void
    {
        $this->em->remove($this->getFixture('car'));
        $this->em->flush();

        $projection = $this->projection();

        // 2 000 € a month at 4 %: 6,67 € more rente each month, 1 520 € to find.
        self::assertSame(self::inMonths(0), $projection['startMonth']);
        self::assertSame(self::inMonths(228), $projection['nDate']);
        self::assertSame(
            ['25' => self::inMonths(19), '50' => self::inMonths(89), '75' => self::inMonths(159), '100' => self::inMonths(228)],
            $projection['milestones'],
        );
        self::assertSame(['month' => self::inMonths(0), 'coverage' => 0.1827], $projection['curve'][0]);
        self::assertSame(['month' => self::inMonths(228), 'coverage' => 1.0], $projection['curve'][228]);
        self::assertCount(229, $projection['curve']);
    }

    public function testAProjectTakingFiveMonthsOfCapacityMovesNByFiveMonths(): void
    {
        $withCar = $this->projection();
        $this->em->remove($this->getFixture('car'));
        $this->em->flush();
        $without = $this->projection();

        self::assertSame(5, self::getContainer()->get(IndependenceProjector::class)->nImpactMonths($without, $withCar));
        self::assertSame(self::inMonths(233), $withCar['nDate']);
    }

    public function testWithoutAnyIncomeNIsNotReachedAndThereIsNoError(): void
    {
        $this->em->remove($this->getFixture('cushion'));
        $this->em->flush();

        $projection = $this->projection();

        self::assertNull($projection['nDate']);
        self::assertSame(['25' => null, '50' => null, '75' => null, '100' => null], $projection['milestones']);
        self::assertCount(IndependenceProjector::HORIZON_MONTHS, $projection['curve']);
    }

    public function testAUserNeverReadsTheRentesOfAnother(): void
    {
        $mine = $this->projection();
        $theirs = $this->projection('other_user');

        self::assertNotSame($mine['curve'][0], $theirs['curve'][0]);
        self::assertSame(0.1827, $mine['curve'][0]['coverage']);
    }
}
