<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetDailyScore;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The month's colour against the reference salary (MAG-274). Spending above
 * the mark is told as a reason and holds green back — it never makes the
 * score orange or red: those speak of the budget alone.
 *
 * The world is `reference_judgement.yaml`: September 2026, 1 000 € consumed
 * in a 10 000 € envelope, against 9 000 € a year earlier, with a cushion
 * that is full for the declared and the measured users. Without a mark to
 * mind, that month is green.
 */
class GetDailyScoreTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ReferenceJudgementWorld;

    private GetDailyScore $useCase;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->useCase = self::getContainer()->get(GetDailyScore::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('reference_judgement.yaml');
    }

    /** @return array<string, mixed> */
    private function score(string $as): array
    {
        /** @var User $user */
        $user = $this->getFixture($as.'_user');

        return $this->useCase->execute($user, 2026, 9);
    }

    /** @return list<string> */
    private static function codes(array $data): array
    {
        return array_column($data['reasons'], 'code');
    }

    public function testBelowADeclaredMarkTheMonthStaysGreen(): void
    {
        $data = $this->score('declared');

        self::assertSame('green', $data['score']);
        self::assertNotContains('spending_above_reference_salary', self::codes($data));
        self::assertSame(['monthlyCents' => 520000, 'source' => 'declared'], $data['referenceSalary']);
    }

    public function testAboveADeclaredMarkTheMonthTellsWhyAndLosesItsGreenOnly(): void
    {
        $this->spendInSeptember('declared', 500000);

        $data = $this->score('declared');

        self::assertSame('neutral', $data['score']);
        self::assertContains('spending_above_reference_salary', self::codes($data));
        $reason = $data['reasons'][array_search('spending_above_reference_salary', self::codes($data), true)];
        self::assertSame(80000, $reason['amountCents']);
    }

    public function testAboveAMeasuredMarkTheMonthTellsWhy(): void
    {
        $this->measureTheSalary();
        $this->spendInSeptember('measured', 450000);

        $data = $this->score('measured');

        self::assertSame('neutral', $data['score']);
        self::assertSame(['monthlyCents' => 495000, 'source' => 'measured'], $data['referenceSalary']);
        $reason = $data['reasons'][array_search('spending_above_reference_salary', self::codes($data), true)];
        self::assertSame(55000, $reason['amountCents']);
    }

    /** The mark can hold green back; it cannot lift an envelope fault off the colour. */
    public function testAboveTheMarkAnOverspentEnvelopeIsStillOrange(): void
    {
        $this->spendInSeptember('declared', 1000000, inGroceries: true);

        $data = $this->score('declared');

        self::assertSame('orange', $data['score']);
        self::assertContains('spending_above_reference_salary', self::codes($data));
    }

    public function testAnUnknownMarkAddsNoReasonWhateverIsSpent(): void
    {
        $this->spendInSeptember('empty', 5000000);

        $data = $this->score('empty');

        self::assertNotContains('spending_above_reference_salary', self::codes($data));
        self::assertSame(['monthlyCents' => null, 'source' => 'unknown'], $data['referenceSalary']);
    }

    public function testBeyondTheMarkOnlyTheGreenIsAtStake(): void
    {
        $this->spendInSeptember('declared', 500000);

        // The same month, the mark cleared: green is back, so the mark was the only thing holding it.
        $declared = $this->em->getRepository(\Maggie\Finance\Entity\SafetyCushion::class)->findOneBy(['user' => $this->getFixture('declared_user')]);
        $declared->setMonthlyNetIncomeCents(600000);
        $this->em->flush();

        $data = $this->score('declared');

        self::assertSame('green', $data['score']);
        self::assertNotContains('spending_above_reference_salary', self::codes($data));
    }
}
