<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Entity\ProjectFundingSource;
use Maggie\Finance\Enum\FundingSourceKind;
use Maggie\Finance\UseCase\GetProjectPlanning;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * What the planning reads of the user's world (MAG-526): the net capacity, the
 * cushion, the savings outside the cushion. The month-by-month rules are
 * ProjectPlannerTest's. Read on 2026-10-21, M0 is October 2026.
 *
 * Exemption: N/A — DB state, Mercure and Elasticsearch, it is a pure read.
 */
class GetProjectPlanningTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private GetProjectPlanning $planning;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-10-21 12:00:00 UTC'));

        $this->planning = self::getContainer()->get(GetProjectPlanning::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures(__DIR__.'/../Api/fixtures/project_funding.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function read(int $overspentCents = 0, string $as = 'test_user'): array
    {
        return $this->planning->execute($this->getFixture($as), $overspentCents);
    }

    /** @return array<string, mixed> */
    private function car(array $plan): array
    {
        self::assertSame('Voiture', $plan['projects'][0]['name']);

        return $plan['projects'][0];
    }

    public function testItReadsTheCapacityTheCushionAndTheSavingsOutsideTheCushion(): void
    {
        $plan = $this->read();

        self::assertSame('2026-10', $plan['startMonth']);
        self::assertSame(36, $plan['horizonMonths']);
        self::assertSame('EUR', $plan['currency']);
        self::assertSame(50000, $plan['netCapacityCents']);
        self::assertTrue($plan['isIncomeKnown']);
        self::assertSame(['state' => 'complete', 'completeMonth' => null], $plan['cushion']);

        // The Livret jeune only: not the cushion's Livret A, not the PEA, not another user's account.
        $car = $this->car($plan);
        self::assertSame(150000, $car['gatheredCents']);
        self::assertSame(150000, $plan['months'][0]['savingsCents']);
        // 8 500 € left at 500 € a month: the 17th month.
        self::assertSame('2028-02', $car['purchaseMonth']);
        self::assertFalse($car['isBeyondHorizon']);
    }

    public function testASavingsSourceClaimsItsAccountAndIsCountedWhenBought(): void
    {
        $car = $this->em->find(Project::class, (string) $this->getFixture('car')->getId());
        $account = $this->getFixture('livret_jeune');
        \assert($car instanceof Project && $account instanceof Account);
        $this->em->persist((new ProjectFundingSource())
            ->setProject($car)
            ->setKind(FundingSourceKind::Savings)
            ->setAmountCents(100000)
            ->setAccount($account));
        $this->em->flush();

        $plan = $this->read();

        // 1 500 € on the account, 1 000 € claimed by the source: 500 € set aside, 1 000 € arrive at the purchase.
        self::assertSame(50000, $this->car($plan)['gatheredCents']);
        self::assertSame('2028-02', $this->car($plan)['purchaseMonth']);
        self::assertSame(50000, $plan['months'][0]['savingsCents']);
        self::assertSame(100000, $plan['months'][16]['savingsCents']);
    }

    public function testWhatWasOverspentLowersThisMonthOnly(): void
    {
        $plan = $this->read(10000);

        self::assertSame([40000, 50000], [$plan['months'][0]['capacityCents'], $plan['months'][1]['capacityCents']]);
        self::assertSame('2028-03', $this->car($plan)['purchaseMonth']);
        self::assertSame($this->car($this->read())['gatheredCents'], $this->car($plan)['gatheredCents']);
    }

    public function testANegativeOverspendIsIgnored(): void
    {
        self::assertSame($this->read(), $this->read(-5000));
    }

    public function testAUserOnlyPlansTheirOwnProjectsAndSavings(): void
    {
        $plan = $this->read(0, 'other_user');

        self::assertSame(['Projet secret'], array_column($plan['projects'], 'name'));
        // 999 € set aside from their own 1 000 € livret: bought right away.
        self::assertSame(99900, $plan['projects'][0]['gatheredCents']);
        self::assertSame('2026-10', $plan['projects'][0]['purchaseMonth']);
    }

    public function testWithoutProjectsTheMonthsAreStillThere(): void
    {
        foreach ($this->em->getRepository(Project::class)->findAll() as $project) {
            $this->em->remove($project);
        }
        $this->em->flush();

        $plan = $this->read();

        self::assertSame([], $plan['projects']);
        self::assertCount(36, $plan['months']);
        self::assertSame('EUR', $plan['currency']);
    }
}
