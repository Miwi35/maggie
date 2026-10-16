<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetFinanceDashboard;
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
}
