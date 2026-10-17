<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\TreasurySettings;
use Maggie\Finance\UseCase\GetFinanceDashboard;
use Maggie\Finance\UseCase\GetFinanceScores;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The dashboard composes, so the reference salary reaches it twice (MAG-274):
 * in the month's score and in the saving capacity.
 *
 * The world is `reference_judgement.yaml`.
 */
class GetFinanceDashboardTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ReferenceJudgementWorld;

    private GetFinanceDashboard $useCase;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->useCase = self::getContainer()->get(GetFinanceDashboard::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('reference_judgement.yaml');
    }

    public function testTheMeasuredMarkReachesTheScoreAndTheSavingCapacity(): void
    {
        $this->measureTheSalary();
        /** @var User $user */
        $user = $this->getFixture('measured_user');

        $data = $this->useCase->execute($user, 2026, 9);

        self::assertSame(['monthlyCents' => 495000, 'source' => 'measured'], $data['score']['referenceSalary']);
        self::assertSame(495000, $data['savingCapacity']['monthlyNetIncomeCents']);
        self::assertTrue($data['savingCapacity']['isIncomeKnown']);
    }

    public function testAnUnknownMarkIsNotAnIncome(): void
    {
        /** @var User $user */
        $user = $this->getFixture('empty_user');

        $data = $this->useCase->execute($user, 2026, 9);

        self::assertNull($data['score']['referenceSalary']['monthlyCents']);
        self::assertSame(0, $data['savingCapacity']['monthlyNetIncomeCents']);
        self::assertFalse($data['savingCapacity']['isIncomeKnown']);
    }

    public function testItNamesTheBankOfEachAccountOfTheBalance(): void
    {
        $this->getFixture('measured_checking')->setBank('Crédit Agricole');
        $this->em->flush();
        /** @var User $user */
        $user = $this->getFixture('measured_user');

        $accounts = $this->useCase->execute($user, 2026, 9)['balance']['accounts'];
        $banks = array_column($accounts, 'bank', 'name');

        self::assertSame('Crédit Agricole', $banks['Courant measured']);
        self::assertNull($banks['Livret measured']);
    }

    private function expectTheSalaryOnThe25th(User $user): void
    {
        $this->em->persist((new TreasurySettings())->setUser($user)->setSalaryExpectedDay(25));
        $this->em->flush();
    }

    public function testTheTilesGatherTheAccountsTheBudgetAndThePlanning(): void
    {
        $this->measureTheSalary();
        /** @var User $user */
        $user = $this->getFixture('measured_user');
        $this->expectTheSalaryOnThe25th($user);

        $data = $this->useCase->execute($user, 2026, 9);
        $tiles = $data['tiles'];

        self::assertSame($data['balance']['totalCents'], $tiles['accounts']['totalCents']);
        self::assertSame($data['balance']['cushionCents'], $tiles['accounts']['cushionCents']);
        self::assertSame($data['balance']['availableCents'], $tiles['accounts']['availableCents']);
        self::assertCount(\count($data['balance']['accounts']), $tiles['accounts']['accounts']);
        self::assertSame(
            ['id', 'name', 'bank', 'balanceCents', 'currency', 'isCushion', 'rejectionsToSettleCount'],
            array_keys($tiles['accounts']['accounts'][0]),
        );

        self::assertSame(495000, $tiles['budget']['expectedSalary']['monthlyCents']);
        self::assertSame('measured', $tiles['budget']['expectedSalary']['source']);
        self::assertNotNull($tiles['budget']['expectedSalary']['expectedOn']);
        self::assertIsArray($tiles['budget']['balanceBeforeSalary']);
        self::assertIsInt($tiles['budget']['balanceBeforeSalary']['balanceCents']);
        self::assertSame(
            ['state', 'targetCents', 'currentCents', 'coveragePercent', 'monthsCovered', 'isConfigured'],
            array_keys($tiles['budget']['cushion']),
        );

        self::assertSame($data['savingCapacity'], $tiles['planning']['savingCapacity']);
        self::assertSame($data['independence'], $tiles['planning']['independence']);
    }

    public function testTheBudgetTileListsTheRootEnvelopesOnly(): void
    {
        /** @var User $user */
        $user = $this->getFixture('measured_user');

        $data = $this->useCase->execute($user, 2026, 9);
        $roots = array_values(array_filter($data['budgets'], static fn (array $line): bool => null === $line['parentEnvelopeId']));
        $envelopes = $data['tiles']['budget']['envelopes'];

        self::assertNotSame([], $envelopes);
        self::assertCount(\count($roots), $envelopes);
        self::assertSame(
            ['envelopeId', 'name', 'budgetCents', 'consumedCents', 'isOverspent', 'isOvercommitted'],
            array_keys($envelopes[0]),
        );
        self::assertSame($roots[0]['id'], $envelopes[0]['envelopeId']);
        self::assertSame($roots[0]['amountCents'], $envelopes[0]['budgetCents']);
    }

    public function testWithoutAReferenceSalaryTheExpectedSalaryAndTheBalanceBeforeItAreNull(): void
    {
        /** @var User $user */
        $user = $this->getFixture('empty_user');

        $budget = $this->useCase->execute($user, 2026, 9)['tiles']['budget'];

        self::assertNull($budget['expectedSalary']);
        self::assertNull($budget['balanceBeforeSalary']);
    }

    public function testAKnownSalaryWithoutAnExpectedDayLeavesTheBalanceBeforeItUnknown(): void
    {
        $this->measureTheSalary();
        /** @var User $user */
        $user = $this->getFixture('measured_user');

        $budget = $this->useCase->execute($user, 2026, 9)['tiles']['budget'];

        self::assertSame(495000, $budget['expectedSalary']['monthlyCents']);
        self::assertNull($budget['expectedSalary']['expectedOn']);
        self::assertNull($budget['balanceBeforeSalary']);
    }

    public function testTheScoresAreThoseOfTheMonthShown(): void
    {
        /** @var User $user */
        $user = $this->getFixture('empty_user');

        self::assertSame('2026-09-30', $this->useCase->execute($user, 2026, 9)['scores']['date'], 'a past month is scored on its last day');
        self::assertSame('2099-01-01', $this->useCase->execute($user, 2099, 1)['scores']['date'], 'a month to come is scored on its first day');

        $today = self::getContainer()->get(GetFinanceScores::class)->resolveDate($user, null);
        $current = $this->useCase->execute($user, (int) $today->format('Y'), (int) $today->format('n'));

        self::assertSame($today->format('Y-m-d'), $current['scores']['date'], 'the current month is scored today');
        self::assertArrayHasKey('week', $current['scores']);
        self::assertArrayHasKey('month', $current['scores']);
        self::assertArrayHasKey('global', $current['scores']);
    }
}
