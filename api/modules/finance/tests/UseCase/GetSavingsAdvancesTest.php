<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
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

    public function testAnotherUsersAdvancesAreNotCounted(): void
    {
        $this->transfer('other_livret', 'other_checking', 150000, '2026-10-01', TransferKind::Advance);

        self::assertSame(0, $this->advances->execute($this->user())['totalOutstandingCents']);
        self::assertSame(150000, $this->advances->execute($this->user('other_user'))['totalOutstandingCents']);
    }
}
