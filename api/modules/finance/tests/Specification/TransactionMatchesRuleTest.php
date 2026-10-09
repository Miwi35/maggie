<?php

namespace Maggie\Finance\Tests\Specification;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Specification\TransactionMatchesRule;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * One definition of "this rule recognises this line", whoever asks.
 */
class TransactionMatchesRuleTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private TransactionMatchesRule $spec;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->spec = self::getContainer()->get(TransactionMatchesRule::class);
        $this->loadFixtures(dirname(__DIR__).'/Controller/fixtures/rule_preview.yaml');
    }

    public function testARuleRecognisesALineByItsLabel(): void
    {
        $rule = $this->getFixture('rule_premium');

        self::assertTrue($this->spec->isSatisfiedBy($rule, $this->getFixture('netflix_premium')));
        self::assertFalse($this->spec->isSatisfiedBy($rule, $this->getFixture('netflix_july')));
    }

    public function testARuleRecognisesALineWhateverItsCurrentCategory(): void
    {
        $rule = $this->getFixture('rule_premium');
        $premium = $this->getFixture('netflix_premium');
        $premium->setCategory($this->getFixture('subscriptions'));

        self::assertTrue($this->spec->isSatisfiedBy($rule, $premium));
    }

    public function testADisabledRuleRecognisesNothing(): void
    {
        $rule = $this->getFixture('rule_premium');
        $rule->setIsActive(false);

        self::assertFalse($this->spec->isSatisfiedBy($rule, $this->getFixture('netflix_premium')));
    }

    public function testADebitRuleDoesNotRecogniseACredit(): void
    {
        $rule = $this->getFixture('rule_premium');
        $credit = $this->getFixture('netflix_refund');
        $credit->setLabel('NETFLIX PREMIUM REFUND');

        self::assertFalse($this->spec->isSatisfiedBy($rule, $credit));
    }

    public function testACreditCannotBeFiledUnderAnExpenseCategory(): void
    {
        $rule = $this->getFixture('rule_premium');
        $rule->setDirection(AmountDirection::Any);
        $credit = $this->getFixture('netflix_refund');
        $credit->setLabel('NETFLIX PREMIUM REFUND');

        self::assertFalse($this->spec->isSatisfiedBy($rule, $credit));
    }

    public function testACriteriaWithoutACategoryOnlyLooksAtTheLine(): void
    {
        $criteria = $this->getFixture('rule_premium')->matchCriteria();

        self::assertTrue($this->spec->matches($criteria, null, $this->getFixture('netflix_premium')));
        self::assertFalse($this->spec->matches($criteria, null, $this->getFixture('netflix_july')));
    }
}
