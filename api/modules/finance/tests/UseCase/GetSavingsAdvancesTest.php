<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\UseCase\GetSavingsAdvances;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * What is left to give back to each source: nothing stored, the advance minus
 * the repayments pointing at it.
 */
class GetSavingsAdvancesTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SavingsAdvancesWorld;

    private GetSavingsAdvances $advances;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->advances = self::getContainer()->get(GetSavingsAdvances::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('savings_advances.yaml');
    }

    public function testNothingAdvancedIsNothingOwed(): void
    {
        self::assertSame(['advances' => [], 'bySource' => [], 'totalOutstandingCents' => 0], $this->advances->execute($this->user()));
    }

    public function testTheBalanceIsKeptPerSourceWithTwoSources(): void
    {
        [$livret] = $this->transfer('livret', 'checking', 150000, '2026-10-01', TransferKind::Advance);
        $this->transfer('pea', 'checking', 50000, '2026-10-03', TransferKind::Advance);
        $this->transfer('livret', 'checking', 20000, '2026-10-05', TransferKind::Advance);

        $data = $this->advances->execute($this->user());

        self::assertCount(3, $data['advances']);
        self::assertSame([
            'id' => (string) $livret->getId(),
            'sourceAccountId' => (string) $livret->getAccount()->getId(),
            'sourceAccountName' => 'Livret A',
            'amountCents' => 150000,
            'openedOn' => '2026-10-01',
            'repaidCents' => 0,
            'outstandingCents' => 150000,
            'isClosed' => false,
        ], $data['advances'][0]);
        self::assertSame(['Livret A', 'PEA'], array_column($data['bySource'], 'sourceAccountName'));
        self::assertSame([170000, 50000], array_column($data['bySource'], 'outstandingCents'));
        self::assertSame([2, 1], array_column($data['bySource'], 'openCount'));
        self::assertSame(220000, $data['totalOutstandingCents']);
    }

    public function testAnAdvanceRepaidInTwoGoesIsSettled(): void
    {
        [$advance] = $this->transfer('livret', 'checking', 150000, '2026-10-01', TransferKind::Advance);

        $this->repay($advance, 'livret', 100000, '2026-10-27');
        $half = $this->advances->execute($this->user());
        self::assertSame(100000, $half['advances'][0]['repaidCents']);
        self::assertSame(50000, $half['advances'][0]['outstandingCents']);
        self::assertSame(50000, $half['totalOutstandingCents']);

        $this->repay($advance, 'livret', 50000, '2026-11-27');
        $settled = $this->advances->execute($this->user());
        self::assertSame(150000, $settled['advances'][0]['repaidCents']);
        self::assertSame(0, $settled['advances'][0]['outstandingCents']);
        self::assertTrue($settled['advances'][0]['isClosed']);
    }

    public function testASettledAdvanceLeavesTheTotal(): void
    {
        [$settled] = $this->transfer('livret', 'checking', 30000, '2026-09-28', TransferKind::Advance);
        $this->repay($settled, 'livret', 30000, '2026-10-27');
        $this->transfer('pea', 'checking', 50000, '2026-10-03', TransferKind::Advance);

        $data = $this->advances->execute($this->user());

        self::assertCount(2, $data['advances']);
        self::assertSame(['PEA'], array_column($data['bySource'], 'sourceAccountName'));
        self::assertSame(50000, $data['totalOutstandingCents']);
    }

    public function testAnInternalTransferIsNotAnAdvance(): void
    {
        $this->transfer('livret', 'checking', 150000, '2026-10-01');

        self::assertSame([], $this->advances->execute($this->user())['advances']);
    }

    /** A single-legged line on the checking account, tied by hand to a livret no bank sync gives (MAG-806). */
    private function sourced(int $amountCents, string $bookedAt, TransferKind $kind, string $sourceRef = 'livret'): Transaction
    {
        $source = $this->getFixture($sourceRef);
        \assert($source instanceof Account);

        $line = $this->add('checking', $amountCents, $bookedAt, label: 'VIR LIVRET A');
        $line->setTransferKind($kind)->setAdvanceSource($source);
        $this->flushWithoutTransactionEffects($this->em);

        return $line;
    }

    public function testASourcedLivretGivesAdvancedMinusRepaid(): void
    {
        foreach ([50000, 60000, 70000, 50000, 60000, 60000, 60000] as $day => $amount) {
            $this->sourced($amount, sprintf('2026-06-%02d', $day + 1), TransferKind::Advance);
        }
        $this->sourced(-200000, '2026-07-23', TransferKind::Repayment);

        $data = $this->advances->execute($this->user());

        self::assertSame([[
            'sourceAccountId' => (string) $this->getFixture('livret')->getId(),
            'sourceAccountName' => 'Livret A',
            'advancedCents' => 410000,
            'repaidCents' => 200000,
            'outstandingCents' => 210000,
            'openCount' => 4,
        ]], $data['bySource']);
        self::assertSame(210000, $data['totalOutstandingCents']);
        // What was given back covers the oldest credits first.
        self::assertCount(7, $data['advances']);
        self::assertSame([true, true, true, false, false, false, false], array_column($data['advances'], 'isClosed'));
        self::assertSame([0, 0, 0, 30000, 60000, 60000, 60000], array_column($data['advances'], 'outstandingCents'));
        self::assertSame(210000, array_sum(array_column($data['advances'], 'outstandingCents')));
    }

    public function testASourcedLivretGivenBackInFullLeavesTheTotal(): void
    {
        $this->sourced(50000, '2026-06-01', TransferKind::Advance);
        $this->sourced(-50000, '2026-07-01', TransferKind::Repayment);

        $data = $this->advances->execute($this->user());

        self::assertSame([], $data['bySource']);
        self::assertSame(0, $data['totalOutstandingCents']);
        self::assertTrue($data['advances'][0]['isClosed']);
    }

    public function testClassicAdvancesAreUnchanged(): void
    {
        [$advance] = $this->transfer('livret', 'checking', 150000, '2026-10-01', TransferKind::Advance);
        $this->repay($advance, 'livret', 100000, '2026-10-27');
        $this->transfer('pea', 'checking', 50000, '2026-10-03', TransferKind::Advance);
        $this->sourced(30000, '2026-10-04', TransferKind::Advance);

        $data = $this->advances->execute($this->user());

        self::assertSame(['Livret A', 'PEA'], array_column($data['bySource'], 'sourceAccountName'));
        self::assertSame([180000, 50000], array_column($data['bySource'], 'advancedCents'));
        self::assertSame([100000, 0], array_column($data['bySource'], 'repaidCents'));
        self::assertSame([80000, 50000], array_column($data['bySource'], 'outstandingCents'));
        self::assertSame(130000, $data['totalOutstandingCents']);
        self::assertSame(50000, $data['advances'][0]['outstandingCents']);
        self::assertSame(100000, $data['advances'][0]['repaidCents']);
    }

    public function testAnotherUsersAdvancesAreNotCounted(): void
    {
        $this->transfer('other_livret', 'other_checking', 150000, '2026-10-01', TransferKind::Advance);

        self::assertSame(0, $this->advances->execute($this->user())['totalOutstandingCents']);
        self::assertSame(150000, $this->advances->execute($this->user('other_user'))['totalOutstandingCents']);
    }
}
