<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetCushionStatus;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The safety net's target follows the reference salary (MAG-274): the salary
 * declared on the cushion when there is one, the recurring « Salaire »
 * otherwise, and « not configured » only when neither is known.
 *
 * The world is `reference_judgement.yaml`: a full 20 000 € livret for the
 * declared and the measured users.
 */
class GetCushionStatusTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ReferenceJudgementWorld;

    private GetCushionStatus $useCase;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->useCase = self::getContainer()->get(GetCushionStatus::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('reference_judgement.yaml');
    }

    /** @return array<string, mixed> */
    private function cushionOf(string $as): array
    {
        /** @var User $user */
        $user = $this->getFixture($as.'_user');

        return $this->useCase->execute($user);
    }

    /** A user who declared a salary keeps the figure they declared, whatever the series says. */
    public function testADeclaredSalaryStillWinsOverTheMeasure(): void
    {
        $data = $this->cushionOf('declared');

        self::assertSame(520000, $data['monthlyNetIncomeCents']);
        self::assertSame(1560000, $data['targetCents']);
        self::assertSame('declared', $data['referenceSalarySource']);
        self::assertTrue($data['isConfigured']);
        self::assertSame('complete', $data['state']);
    }

    public function testTheTargetFollowsTheMeasuredSalary(): void
    {
        $this->measureTheSalary();

        $data = $this->cushionOf('measured');

        self::assertSame(495000, $data['monthlyNetIncomeCents']);
        self::assertSame(3, $data['targetMonths']);
        self::assertSame(1485000, $data['targetCents']);
        self::assertSame('measured', $data['referenceSalarySource']);
        self::assertTrue($data['isConfigured']);
        self::assertSame(4.0, $data['monthsCovered']);
        self::assertSame(100, $data['coveragePercent']);
    }

    /** Nothing declared and nothing measured yet: the expected amount of the series is already a mark. */
    public function testAnExpectedSalaryConfiguresTheCushion(): void
    {
        $data = $this->cushionOf('measured');

        self::assertSame(500000, $data['monthlyNetIncomeCents']);
        self::assertSame(1500000, $data['targetCents']);
        self::assertSame('expected', $data['referenceSalarySource']);
        self::assertTrue($data['isConfigured']);
    }

    public function testNoMarkLeavesTheCushionUnconfiguredAtZero(): void
    {
        $data = $this->cushionOf('empty');

        self::assertSame(0, $data['monthlyNetIncomeCents']);
        self::assertSame(0, $data['targetCents']);
        self::assertSame('unknown', $data['referenceSalarySource']);
        self::assertFalse($data['isConfigured']);
        self::assertSame(0, $data['coveragePercent']);
        self::assertSame(0.0, $data['monthsCovered']);
        self::assertTrue($data['blocksGreenScore']);
    }

    public function testTheCushionEntityKeepsItsOwnFigure(): void
    {
        $this->measureTheSalary();
        $this->cushionOf('measured');

        $cushion = $this->em->getRepository(\Maggie\Finance\Entity\SafetyCushion::class)->findOneBy(['user' => $this->getFixture('measured_user')]);

        self::assertSame(0, $cushion->getMonthlyNetIncomeCents());
        self::assertSame(0, $cushion->getTargetCents());
    }

    public function testItNamesTheBankOfEachCushionAccount(): void
    {
        $this->getFixture('measured_livret')->setBank('Boursorama');
        $this->em->flush();

        $accounts = $this->cushionOf('measured')['accounts'];

        self::assertSame(['Boursorama'], array_column($accounts, 'bank'));
    }
}
