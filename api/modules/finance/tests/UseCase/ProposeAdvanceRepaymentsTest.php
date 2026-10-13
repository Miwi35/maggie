<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\UseCase\ProposeAdvanceRepayments;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * What to give back, to whom and for how much: one exact counter-transfer per
 * open advance, from the checking account the money landed on, never more
 * than that account holds.
 */
class ProposeAdvanceRepaymentsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SavingsAdvancesWorld;

    private ProposeAdvanceRepayments $propose;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->propose = self::getContainer()->get(ProposeAdvanceRepayments::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('savings_advances.yaml');
    }

    private function balance(string $accountRef, int $balanceCents): void
    {
        $account = $this->getFixture($accountRef);
        \assert($account instanceof Account);
        $account->setBalanceCents($balanceCents);
        $this->flushWithoutTransactionEffects($this->em);
    }

    public function testNoOpenAdvanceProposesNothingAndReportsNoProblem(): void
    {
        self::assertSame(
            ['proposals' => [], 'totalCents' => 0, 'unproposed' => [], 'unproposedCents' => 0],
            $this->propose->execute($this->user()),
        );
    }

    public function testASettledAdvanceProposesNothing(): void
    {
        [$advance] = $this->transfer('livret', 'checking', 30000, '2026-10-01', TransferKind::Advance);
        $this->repay($advance, 'livret', 30000, '2026-10-27');

        self::assertSame([], $this->propose->execute($this->user())['proposals']);
    }

    public function testOneAdvanceGivesOneCounterTransferOfTheExactAmountWithALabel(): void
    {
        $this->balance('checking', 400000);
        [$advance] = $this->transfer('livret', 'checking', 150000, '2026-10-01', TransferKind::Advance);

        $result = $this->propose->execute($this->user());

        self::assertSame([[
            'fromAccountId' => (string) $this->getFixture('checking')->getId(),
            'toAccountId' => (string) $this->getFixture('livret')->getId(),
            'amountCents' => 150000,
            'advanceId' => (string) $advance->getId(),
            'label' => 'Remise avance Livret A',
            'currency' => 'EUR',
            'isPartial' => false,
        ]], $result['proposals']);
        self::assertSame(150000, $result['totalCents']);
        self::assertSame([], $result['unproposed']);
        self::assertSame(0, $result['unproposedCents']);
    }

    public function testAPartlyRepaidAdvanceIsProposedForWhatIsLeft(): void
    {
        $this->balance('checking', 400000);
        [$advance] = $this->transfer('livret', 'checking', 150000, '2026-10-01', TransferKind::Advance);
        $this->repay($advance, 'livret', 100000, '2026-10-27');

        $result = $this->propose->execute($this->user());

        self::assertSame([50000], array_column($result['proposals'], 'amountCents'));
    }

    public function testTwoSourcesGiveTwoProposalsOneEach(): void
    {
        $this->balance('checking', 400000);
        $this->transfer('livret', 'checking', 150000, '2026-10-01', TransferKind::Advance);
        $this->transfer('pea', 'checking', 50000, '2026-10-03', TransferKind::Advance);

        $result = $this->propose->execute($this->user());

        self::assertSame(
            [(string) $this->getFixture('livret')->getId(), (string) $this->getFixture('pea')->getId()],
            array_column($result['proposals'], 'toAccountId'),
        );
        self::assertSame([150000, 50000], array_column($result['proposals'], 'amountCents'));
        self::assertSame(200000, $result['totalCents']);
    }

    public function testTwoAdvancesOnTheSameSourceAreTwoProposalsOfTheirOwnAmounts(): void
    {
        $this->balance('checking', 400000);
        $this->transfer('livret', 'checking', 150000, '2026-10-01', TransferKind::Advance);
        $this->transfer('livret', 'checking', 20000, '2026-10-05', TransferKind::Advance);

        self::assertSame([150000, 20000], array_column($this->propose->execute($this->user())['proposals'], 'amountCents'));
    }

    public function testTheProposalIsReducedToTheBalanceAndTheRestIsAnnounced(): void
    {
        $this->balance('checking', 100000);
        [$advance] = $this->transfer('livret', 'checking', 150000, '2026-10-01', TransferKind::Advance);

        $result = $this->propose->execute($this->user());

        self::assertSame([100000], array_column($result['proposals'], 'amountCents'));
        self::assertTrue($result['proposals'][0]['isPartial']);
        self::assertSame(100000, $result['totalCents']);
        self::assertSame([[
            'advanceId' => (string) $advance->getId(),
            'sourceAccountId' => (string) $this->getFixture('livret')->getId(),
            'sourceAccountName' => 'Livret A',
            'amountCents' => 50000,
            'reason' => 'insufficient_balance',
        ]], $result['unproposed']);
        self::assertSame(50000, $result['unproposedCents']);
    }

    public function testTheBalanceIsSharedBetweenTheAdvancesOfOneCheckingAccountOldestFirst(): void
    {
        $this->balance('checking', 180000);
        $this->transfer('livret', 'checking', 150000, '2026-10-01', TransferKind::Advance);
        $this->transfer('pea', 'checking', 50000, '2026-10-03', TransferKind::Advance);

        $result = $this->propose->execute($this->user());

        self::assertSame([150000, 30000], array_column($result['proposals'], 'amountCents'));
        self::assertSame(180000, $result['totalCents']);
        self::assertSame([20000], array_column($result['unproposed'], 'amountCents'));
        self::assertSame(['PEA'], array_column($result['unproposed'], 'sourceAccountName'));
    }

    public function testAnEmptyOrOverdrawnCheckingAccountProposesNothingAndSaysSo(): void
    {
        $this->balance('checking', -2000);
        $this->transfer('livret', 'checking', 150000, '2026-10-01', TransferKind::Advance);

        $result = $this->propose->execute($this->user());

        self::assertSame([], $result['proposals']);
        self::assertSame(0, $result['totalCents']);
        self::assertSame(150000, $result['unproposedCents']);
    }

    public function testTheTransferLeavesTheCheckingAccountTheAdvanceLandedOn(): void
    {
        $this->balance('checking', 400000);
        $this->balance('joint', 400000);
        $this->transfer('livret', 'joint', 150000, '2026-10-01', TransferKind::Advance);

        $proposal = $this->propose->execute($this->user())['proposals'][0];

        self::assertSame((string) $this->getFixture('joint')->getId(), $proposal['fromAccountId']);
    }

    public function testAnotherUsersAdvancesAreNeverProposed(): void
    {
        $this->balance('other_checking', 400000);
        $this->transfer('other_livret', 'other_checking', 150000, '2026-10-01', TransferKind::Advance);

        self::assertSame([], $this->propose->execute($this->user())['proposals']);
        self::assertCount(1, $this->propose->execute($this->user('other_user'))['proposals']);
    }
}
