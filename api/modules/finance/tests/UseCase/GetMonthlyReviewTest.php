<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetMonthlyReview;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The month read against the reference salary (MAG-274): the mark and where it
 * comes from, and the share of it the month consumed — `null`, never 0, when
 * the mark is unknown.
 *
 * The world is `reference_judgement.yaml`: September 2026, 1 000 € consumed.
 */
class GetMonthlyReviewTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ReferenceJudgementWorld;

    private GetMonthlyReview $useCase;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->useCase = self::getContainer()->get(GetMonthlyReview::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('reference_judgement.yaml');
    }

    /** @return array<string, mixed> */
    private function review(string $as): array
    {
        /** @var User $user */
        $user = $this->getFixture($as.'_user');

        return $this->useCase->execute($user, 2026, 9);
    }

    public function testADeclaredMarkIsReadAgainstTheMonth(): void
    {
        $data = $this->review('declared');

        self::assertSame(['monthlyCents' => 520000, 'source' => 'declared'], $data['referenceSalary']);
        self::assertSame(
            ['consumedCents' => 100000, 'differenceCents' => -420000, 'percentOfReference' => 19],
            $data['consumedVsReference'],
        );
    }

    public function testAMeasuredMarkIsReadAgainstTheMonth(): void
    {
        $this->measureTheSalary();

        $data = $this->review('measured');

        self::assertSame(['monthlyCents' => 495000, 'source' => 'measured'], $data['referenceSalary']);
        self::assertSame(
            ['consumedCents' => 100000, 'differenceCents' => -395000, 'percentOfReference' => 20],
            $data['consumedVsReference'],
        );
    }

    public function testAMonthAboveTheMarkHasAPositiveDifferenceAndOverOneHundredPercent(): void
    {
        $this->spendInSeptember('declared', 500000);

        $data = $this->review('declared');

        self::assertSame(
            ['consumedCents' => 600000, 'differenceCents' => 80000, 'percentOfReference' => 115],
            $data['consumedVsReference'],
        );
    }

    public function testAnUnknownMarkHasNoPercentageNotZero(): void
    {
        $data = $this->review('empty');

        self::assertSame(['monthlyCents' => null, 'source' => 'unknown'], $data['referenceSalary']);
        self::assertSame(
            ['consumedCents' => 100000, 'differenceCents' => null, 'percentOfReference' => null],
            $data['consumedVsReference'],
        );
    }

    public function testTheComparisonIsStillThere(): void
    {
        self::assertSame(100000, $this->review('declared')['comparison']['thisMonthCents']);
    }
}
