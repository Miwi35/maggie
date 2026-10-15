<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\GetBudgetStatus;
use Maggie\Finance\UseCase\GetDailyScore;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The Finance calculations read the transaction's qualification, not the flag
 * of its category (MAG-477). Every test works on one category — « Essence »,
 * flagged optional — and tells two of its lines apart by their qualification.
 *
 * The world is `qualified_calculations.yaml`.
 */
class QualifiedCalculationsTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private User $user;
    private GetDailyScore $score;
    private GetBudgetStatus $budget;
    private TransactionRepository $transactions;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->score = self::getContainer()->get(GetDailyScore::class);
        $this->budget = self::getContainer()->get(GetBudgetStatus::class);
        $this->transactions = self::getContainer()->get(TransactionRepository::class);

        $this->loadFixtures('qualified_calculations.yaml');

        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->user = $user;
    }

    /** @return array<string, mixed> */
    private function fuelLine(int $month): array
    {
        foreach ($this->budget->execute($this->user, 2026, $month)['budgets'] as $line) {
            if ('Essence' === $line['categoryName']) {
                return $line;
            }
        }

        self::fail('The Essence envelope is missing.');
    }

    /** @return list<string> */
    private function reasonCodes(int $month): array
    {
        return array_column($this->score->execute($this->user, 2026, $month)['reasons'], 'code');
    }

    public function testAMandatoryFillUpMakesTheOverrunMandatoryWhateverTheCategoryFlag(): void
    {
        $result = $this->score->execute($this->user, 2026, 9);

        self::assertSame('red', $result['score']);
        self::assertContains('mandatory_category_exceeded', $this->reasonCodes(9));
        self::assertSame(12000, $this->fuelLine(9)['mandatoryConsumedCents']);
    }

    public function testAnOptionalFillUpInTheSameCategoryMakesTheOverrunOptional(): void
    {
        $result = $this->score->execute($this->user, 2026, 8);

        self::assertSame('orange', $result['score']);
        self::assertContains('optional_category_exceeded', $this->reasonCodes(8));
        self::assertNotContains('mandatory_category_exceeded', $this->reasonCodes(8));
        self::assertSame(0, $this->fuelLine(8)['mandatoryConsumedCents']);
    }

    public function testAMandatoryShareDoesNotExcuseAnOptionalPartThatOverrunsAlone(): void
    {
        $result = $this->score->execute($this->user, 2026, 7);

        self::assertSame('orange', $result['score']);
        self::assertContains('optional_category_exceeded', $this->reasonCodes(7));
        self::assertSame(3000, $this->fuelLine(7)['mandatoryConsumedCents']);
        self::assertSame(15000, $this->fuelLine(7)['consumedCents']);
    }

    public function testTheReviewKeepsTheSpendingNoQualificationExcuses(): void
    {
        $labels = array_map(
            static fn ($transaction) => $transaction->getLabel(),
            $this->transactions->findReviewableBetween(
                $this->user,
                new \DateTimeImmutable('2026-06-01'),
                new \DateTimeImmutable('2026-07-01'),
            ),
        );

        self::assertSame(['Plein ordinaire'], $labels, 'mandatory, repayment and loan lines of the same category are not up for judgement');
    }

    public function testTheLifestyleDropsWhatIsPaidBackOrPutAsideButKeepsTheMandatory(): void
    {
        $consumed = $this->transactions->sumConsumedBetween(
            $this->user,
            new \DateTimeImmutable('2026-03-01'),
            new \DateTimeImmutable('2026-04-01'),
        );

        self::assertSame(10000, $consumed, 'plain 60 € + mandatory 40 €; repayment, saving and investment lines are left out');
    }
}
